"""Fetch the latest published Steam/HLTB snapshots and rebuild all bundled indexes.

Python 3.11+; no third-party dependencies. Downloads and builds finish in staging
before repository assets are replaced. A source/build failure exits unsuccessfully.
"""
import argparse
import csv
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
HLTB_REPO = "julianxhokaxhiu/hltb-scraper"
TIME_FIELDS = ("comp_main", "comp_plus", "comp_100")


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
    release = get_json(f"https://api.github.com/repos/{HLTB_REPO}/releases/latest")
    if release["draft"] or release["prerelease"]:
        raise ValueError("Expected a stable published HLTB snapshot")
    asset = next(a for a in release["assets"] if a["name"] == "howlongtobeat_games.csv")
    if not asset["browser_download_url"].startswith(f"https://github.com/{HLTB_REPO}/releases/download/"):
        raise ValueError("Unexpected HLTB asset URL")
    return dict(steam=dict(revision=revision, files=steam),
                hltb=dict(release=release["tag_name"], published_at=release["published_at"],
                          url=asset["browser_download_url"], sha256=sha256_value(asset.get("digest") or ""),
                          size=asset["size"]))


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


def prepare_hltb(source, assets):
    times, names = {}, set()
    with source.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not {"steam_id", "game_name", *TIME_FIELDS} <= set(reader.fieldnames or []):
            raise ValueError("HLTB CSV schema changed")
        for row in reader:
            app_id = int(row["steam_id"] or 0)
            if app_id <= 0:
                continue
            if app_id > 0x7fffffff:
                raise ValueError("HLTB Steam ID exceeds the index format")
            values = tuple(int(row[key] or 0) for key in TIME_FIELDS)
            if any(value < 0 or value > 0x7fffffffffffffff for value in values):
                raise ValueError(f"Invalid completion times for {app_id}")
            if any(values):
                times.setdefault(app_id, values)
            name = " ".join(row["game_name"].split())
            if name:
                names.add((app_id, name))
    if not times or not names:
        raise ValueError("HLTB snapshot contains no usable times or titles")
    with (assets / "hltb-min.csv").open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(("steam_id", *TIME_FIELDS))
        writer.writerows((app_id, *values) for app_id, values in sorted(times.items()))
    (assets / "hltb-names.tsv").write_text(
        "".join(f"{app_id}\t{name}\n" for app_id, name in sorted(names)), encoding="utf-8")
    return dict(time_records=len(times), title_aliases=len(names))


def update(root, cache):
    sources = resolve_sources()
    cache.mkdir(parents=True, exist_ok=True)
    # Resolve one immutable Steam revision and one HLTB release before downloading.
    csv_source = download(sources["steam"]["files"]["games.csv"], cache)
    json_source = download(sources["steam"]["files"]["games.json"], cache)
    hltb_source = download(sources["hltb"], cache)
    with tempfile.TemporaryDirectory(prefix="gamefibre-data-build-") as directory:
        stage = Path(directory)
        assets = stage / "app/src/main/assets"
        assets.mkdir(parents=True)
        resource = stage / "app/src/main/res/values/steam_catalog_tags.xml"
        resource.parent.mkdir(parents=True)
        sources["hltb"]["coverage"] = prepare_hltb(hltb_source, assets)
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
        outputs = sorted(path for path in stage.rglob("*") if path.is_file())
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
    print(f"Updated all bundled data: Steam {revision}, HLTB {sources['hltb']['release']}", flush=True)


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
