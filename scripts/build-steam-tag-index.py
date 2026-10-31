"""Build the pinned offline Steam community tag asset, using only the standard library."""
import argparse
import collections
import hashlib
import json
import lzma
import tempfile
import urllib.request
from pathlib import Path

from steam_tag_table import entries, pack, verify

ROOT = Path(__file__).resolve().parents[1]
REVISION = "6a31fcb42082345acc817e176b28849b7c05f248"
SOURCE_SHA256 = "6c5c8f7c119c4f3f38cf82944d79ae6f3ca9a433ce39685a9a98393108b50ef8"
SOURCE = f"https://huggingface.co/datasets/FronkonGames/steam-games-dataset/resolve/{REVISION}/games.json"


def build(source, output):
    with source.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != SOURCE_SHA256:
        raise ValueError("JSON differs from pinned snapshot; update revision and checksum together")
    rows = []
    count = 0
    for app_id, game in entries(source):
        count += 1
        tags = game.get("tags", {})
        if not isinstance(tags, dict) and tags != []:
            raise ValueError(f"Invalid tags for {app_id}")
        if tags:
            if app_id <= 0 or any(not name.strip() or not isinstance(votes, int) or votes < 0 for name, votes in tags.items()):
                raise ValueError(f"Invalid tag record for {app_id}")
            rows.append((app_id, sorted(tags, key=lambda name: (-tags[name], name))))
    rows.sort()
    frequency = collections.Counter(name for _, tags in rows for name in tags)
    names = sorted(frequency, key=lambda name: (-frequency[name], name))
    binary = pack(rows, names)
    verify(binary, rows, names)
    packed = lzma.compress(binary, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32, preset=6)
    assert lzma.decompress(packed) == binary
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(packed)
    manifest = {
        "format_version": 1, "source": SOURCE, "source_revision": REVISION,
        "source_sha256": digest, "source_license": "MIT", "source_games": count,
        "tagged_games": len(rows), "unique_tags": len(names),
        "tag_assignments": sum(frequency.values()), "block_size": 32,
        "uncompressed_index_bytes": len(binary), "compressed_index_bytes": len(packed),
        "index_sha256": hashlib.sha256(packed).hexdigest(),
    }
    output.with_suffix("").with_suffix(".json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--json", type=Path)
    source.add_argument("--download", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/steam-tags-v1.bin.xz")
    args = parser.parse_args()
    if args.json:
        build(args.json, args.output)
    else:
        with tempfile.TemporaryDirectory(prefix="steam-tag-index-") as directory:
            path = Path(directory) / "games.json"
            urllib.request.urlretrieve(SOURCE + "?download=true", path)
            build(path, args.output)
