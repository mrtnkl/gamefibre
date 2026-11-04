"""Fetch the latest Steam dataset; rebuild indexes with the fixed local HLTB data.

Python 3.11+; no third-party dependencies. Downloads and builds finish in staging
before repository assets are replaced. A source/build failure exits unsuccessfully.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
STEAM_API = "https://huggingface.co/api/datasets/FronkonGames/steam-games-dataset"
STEAM_FILES = "https://huggingface.co/datasets/FronkonGames/steam-games-dataset/resolve"
FIXED_HLTB = {
    "mode": "fixed",
    "release": "scrape-2026-06-28",
    "published_at": "2026-06-28T10:08:49Z",
    "url": "https://github.com/julianxhokaxhiu/hltb-scraper/releases/download/scrape-2026-06-28/howlongtobeat_games.csv",
    "files": {
        "hltb-min.csv": "3317090732f7b0f4f6ad322552f12b4a1406ae32218fc90cd665e544c8924c8f",
        "hltb-names.tsv": "48eaf8a4beebfea5837304f2f56e9c8ff6d2eac1518daa3e2415d6685bb0805f",
    },
    "coverage": {"time_records": 31520, "title_aliases": 48514},
}


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def request(url):
    return urllib.request.urlopen(urllib.request.Request(
        url, headers={"User-Agent": "GameFibre-bundled-data-updater"}), timeout=120)


def retry(action):
    for attempt in range(3):
        try:
            return action()
        except (OSError, urllib.error.URLError):
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def get_json(url):
    def fetch():
        with request(url) as response:
            return json.load(response)
    return retry(fetch)


def sha256_value(value):
    value = value.removeprefix("sha256:")
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        raise ValueError("Upstream did not supply a valid SHA-256 checksum")
    return value


def resolve_sources():
    revision = get_json(f"{STEAM_API}/revision/main")["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Invalid Steam dataset revision")
    files = {item["path"]: item for item in get_json(f"{STEAM_API}/tree/{revision}")}
    steam = {}
    for name in ("games.csv", "games.json"):
        item = files[name]
        steam[name] = dict(url=f"{STEAM_FILES}/{revision}/{name}",
                           sha256=sha256_value(item["lfs"]["oid"]), size=item["size"])
    return dict(steam=dict(revision=revision, files=steam))


def download(source, cache):
    expected = source["sha256"]
    path = cache / expected
    if path.exists() and path.stat().st_size == source["size"] and checksum(path) == expected:
        print(f"Using verified cached source: {source['url']}", flush=True)
        return path
    print(f"Downloading {source['url']} ({source['size']:,} bytes)", flush=True)
    def fetch():
        with request(source["url"]) as response, path.with_suffix(".part").open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
        partial = path.with_suffix(".part")
        if partial.stat().st_size != source["size"] or checksum(partial) != expected:
            partial.unlink()
            raise OSError(f"Source size/checksum mismatch: {source['url']}")
        partial.replace(path)
    retry(fetch)
    return path


def builder(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare_fixed_hltb(root, assets):
    for name, digest in FIXED_HLTB["files"].items():
        source = root / "app/src/main/assets" / name
        if not source.is_file() or checksum(source) != digest:
            raise ValueError(f"Fixed HLTB dataset is missing or modified: {name}. Restore it from Git.")
        shutil.copyfile(source, assets / name)
    return dict(FIXED_HLTB)


def update(root, cache):
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="gamefibre-data-build-") as directory:
        stage = Path(directory)
        assets = stage / "app/src/main/assets"
        assets.mkdir(parents=True)
        # Validate the checked-in inputs before making any network requests.
        hltb = prepare_fixed_hltb(root, assets)
        sources = resolve_sources()
        sources["hltb"] = hltb
        csv_source = download(sources["steam"]["files"]["games.csv"], cache)
        json_source = download(sources["steam"]["files"]["games.json"], cache)
        resource = stage / "app/src/main/res/values/steam_catalog_tags.xml"
        resource.parent.mkdir(parents=True)
        builder("build-hltb-index").build(assets)
        revision = sources["steam"]["revision"]
        builder("build-steam-title-index").build(
            csv_source, assets / "steam-titles-v2.bin.xz", revision=revision,
            expected_sha256=sources["steam"]["files"]["games.csv"]["sha256"],
            aliases=assets / "hltb-names.tsv")
        json_options = dict(revision=revision, expected_sha256=sources["steam"]["files"]["games.json"]["sha256"])
        builder("build-steam-tag-index").build(json_source, assets / "steam-tags-v1.bin.xz", **json_options)
        builder("build-steam-catalog").build(json_source, assets / "steam-catalog-v1.bin.xz",
                                             resource=resource, **json_options)
        # Reject empty/truncated upstream exports before touching working assets.
        for name, field in (("steam-titles-v2.json", "unique_titles"),
                            ("steam-tags-v1.json", "tagged_games"), ("steam-catalog-v1.json", "games")):
            if json.loads((assets / name).read_text())[field] <= 0:
                raise ValueError(f"No usable records in {name}")
        outputs = sorted(path for path in (stage / "app").rglob("*") if path.is_file())
        sources["outputs"] = {str(path.relative_to(stage)): checksum(path) for path in outputs}
        manifest = assets / "bundled-data-sources.json"
        manifest.write_text(json.dumps(sources, indent=2) + "\n", encoding="utf-8")
        outputs.append(manifest)
        # Each replacement is atomic; no repository output changes until every build succeeds.
        for source in outputs:
            destination = root / source.relative_to(stage)
            destination.parent.mkdir(parents=True, exist_ok=True)
            fd, temporary = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
            try:
                with os.fdopen(fd, "wb") as output, source.open("rb") as stream:
                    shutil.copyfileobj(stream, output)
                os.replace(temporary, destination)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
    print(f"Updated bundled indexes: Steam {revision}, fixed HLTB {sources['hltb']['release']}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, help="Reuse downloads after verifying their size and SHA-256")
    args = parser.parse_args()
    if args.cache_dir:
        update(ROOT, args.cache_dir.resolve())
    else:
        with tempfile.TemporaryDirectory(prefix="gamefibre-data-downloads-") as directory:
            update(ROOT, Path(directory))


if __name__ == "__main__":
    main()
