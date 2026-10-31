"""Reduce a pinned FronkonGames CSV to a deterministic, offline Steam title index.

Run with --download, or --csv /path/to/games.csv to reuse a downloaded snapshot.
Only the compact index and its provenance manifest are packaged in the app.
"""
import argparse
import csv
import gzip
import hashlib
import json
import lzma
import struct
import tempfile
import urllib.request
from pathlib import Path

from title_normalization import normalize, relaxed_key

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
REVISION = "6a31fcb42082345acc817e176b28849b7c05f248"
CSV_SHA256 = "1b48008b01a799d82385d6e66ba0cb65dd477b6aa4b6c2ec8c443025ab6880ad"
SOURCE = f"https://huggingface.co/datasets/FronkonGames/steam-games-dataset/resolve/{REVISION}/games.csv"
BLOCK_SIZE = 32


def varint(value):
    assert 0 <= value <= 0x7fffffff
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return result


def encode(records, block_size=BLOCK_SIZE):
    data = bytearray()
    offsets = []
    previous = b""
    for number, (name, app_id) in enumerate(records):
        title = name.encode("utf-8")
        assert 0 < len(title) <= 16384
        if number % block_size == 0:
            offsets.append(len(data))
            previous = b""
        prefix = 0
        while prefix < min(len(previous), len(title)) and previous[prefix] == title[prefix]:
            prefix += 1
        suffix = title[prefix:]
        data += varint(prefix) + varint(len(suffix)) + suffix + varint(app_id)
        previous = title
    header = struct.pack(">4siiii", b"STIX", 1, len(records), block_size, len(data))
    return header + b"".join(struct.pack(">i", offset) for offset in offsets) + data


def build(source, output):
    with source.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != CSV_SHA256:
        raise ValueError("CSV differs from the pinned snapshot; update revision and checksum together")
    candidates = {}
    relaxed_candidates = {}

    def add(app_id, title):
        if app_id <= 0 or app_id > 0x7fffffff:
            return
        name = normalize(title)
        if name:
            candidates.setdefault(name, set()).add(app_id)
        key = relaxed_key(title)
        if key:
            relaxed_candidates.setdefault(key, set()).add(app_id)

    csv.field_size_limit(16 * 1024 * 1024)
    source_rows = 0
    with source.open(encoding="utf-8-sig", newline="") as stream:
        for row in csv.DictReader(stream):
            source_rows += 1
            add(int(row["AppID"]), row["Name"])
    aliases = ASSETS / "hltb-names.tsv"
    with aliases.open(encoding="utf-8") as stream:
        for line in stream:
            app_id, title = line.rstrip("\n").split("\t", 1)
            add(int(app_id), title)
    # Exclude every ambiguous title, even when the old index had only one of its IDs.
    # UTF-16 sort order matches Kotlin String.compareTo, including non-BMP titles.
    records = sorted(((name, next(iter(ids))) for name, ids in candidates.items() if len(ids) == 1),
                     key=lambda item: item[0].encode("utf-16-be"))
    relaxed_records = sorted(((name, next(iter(ids))) for name, ids in relaxed_candidates.items() if len(ids) == 1),
                             key=lambda item: item[0].encode("utf-16-be"))
    # Both tables share one compression dictionary; each remains independently searchable.
    binary = b"STIX" + struct.pack(">i", 2) + encode(records)[8:] + encode(relaxed_records)[8:]
    packed = lzma.compress(binary, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32, preset=6)
    minified = json.dumps({"exact": records, "relaxed": relaxed_records}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    # Compare against a simpler sorted, full-title table with fixed-width integers.
    plain = b"".join(struct.pack(">ii", len(name.encode("utf-8")), app_id) + name.encode("utf-8")
                     for name, app_id in records + relaxed_records)
    manifest = {
        "format_version": 2, "source": SOURCE, "source_revision": REVISION, "source_sha256": digest,
        "source_license": "MIT", "source_rows": source_rows, "source_bytes": source.stat().st_size,
        "aliases_sha256": hashlib.sha256(aliases.read_bytes()).hexdigest(),
        "unique_titles": len(records), "ambiguous_titles_excluded": sum(len(ids) > 1 for ids in candidates.values()),
        "relaxed_keys": len(relaxed_records),
        "ambiguous_relaxed_keys_excluded": sum(len(ids) > 1 for ids in relaxed_candidates.values()),
        "block_size": BLOCK_SIZE, "uncompressed_index_bytes": len(binary), "compressed_index_bytes": len(packed),
        "minified_id_title_json_bytes": len(minified),
        "gzip_minified_json_bytes": len(gzip.compress(minified, compresslevel=9, mtime=0)),
        "gzip_full_title_binary_bytes": len(gzip.compress(plain, compresslevel=9, mtime=0)),
        "gzip_prefix_index_bytes": len(gzip.compress(binary, compresslevel=9, mtime=0)),
        "xz_full_title_binary_bytes": len(lzma.compress(plain, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32, preset=6)),
        "index_sha256": hashlib.sha256(packed).hexdigest(),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(packed)
    output.with_name("steam-titles-v2.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--csv", type=Path)
    group.add_argument("--download", action="store_true")
    parser.add_argument("--output", type=Path, default=ASSETS / "steam-titles-v2.bin.xz")
    args = parser.parse_args()
    if args.csv:
        build(args.csv, args.output)
    else:
        with tempfile.TemporaryDirectory(prefix="steam-title-index-") as directory:
            source = Path(directory) / "games.csv"
            urllib.request.urlretrieve(SOURCE, source)
            build(source, args.output)


if __name__ == "__main__":
    main()
