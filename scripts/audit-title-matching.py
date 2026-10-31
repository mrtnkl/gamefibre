"""Audit every pinned Steam title and bundled alias. Does not change app data.

Run: python3 scripts/audit-title-matching.py --csv /path/to/games.csv --output /tmp/title-audit.json
The report contains candidates for review, not an approved list of equivalent games.
"""
import argparse
import csv
import hashlib
import importlib.util
import json
import re
from collections import defaultdict
from pathlib import Path

from title_normalization import normalize, relaxed_key


def proposed_library_key(title):
    return normalize(re.sub("['’‘ʼ]", "", title).replace("&", " and "))


def audit(source):
    spec = importlib.util.spec_from_file_location("builder", Path(__file__).with_name("build-steam-title-index.py"))
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    with source.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != builder.CSV_SHA256:
            raise ValueError("Expected the pinned Steam snapshot")
    csv.field_size_limit(16 * 1024 * 1024)
    with source.open(encoding="utf-8-sig", newline="") as stream:
        rows = [(int(row["AppID"]), row["Name"]) for row in csv.DictReader(stream)]
    source_rows = len(rows)
    aliases = builder.ASSETS / "hltb-names.tsv"
    rows += [(int(app_id), title) for app_id, title in
             (line.rstrip("\n").split("\t", 1) for line in aliases.read_text().splitlines())]
    by_id = defaultdict(set)
    relaxed = defaultdict(set)
    proposed = defaultdict(set)
    for app_id, title in rows:
        by_id[app_id].add(title)
        key = relaxed_key(title)
        if key:
            relaxed[key].add((app_id, title))
        proposed[proposed_library_key(title)].add((app_id, title))

    def variants(entries):
        return [{"steam_id": app_id, "title": title} for app_id, title in sorted(entries)]

    missed = []
    for app_id, titles in sorted(by_id.items()):
        if len({normalize(title) for title in titles}) <= 1:
            continue
        keys = {relaxed_key(title) for title in titles}
        unique_relaxed = len(keys) == 1 and bool(next(iter(keys))) and all(
            len({i for i, _ in relaxed[key]}) == 1 for key in keys)
        missed.append({"steam_id": app_id, "titles": sorted(titles),
                       "unique_shared_metadata_key": unique_relaxed})
    proposed_groups = [
        {"key": key, "variants": variants(entries), "same_steam_id": len({i for i, _ in entries}) == 1}
        for key, entries in sorted(proposed.items())
        if len({normalize(title) for _, title in entries}) > 1
    ]
    collisions = [
        {"key": key, "variants": variants(entries)} for key, entries in sorted(relaxed.items())
        if len({i for i, _ in entries}) > 1
    ]
    return {
        "source_revision": builder.REVISION,
        "aliases_sha256": hashlib.sha256(aliases.read_bytes()).hexdigest(),
        "summary": {
            "source_rows": source_rows, "alias_rows": len(rows) - source_rows,
            "steam_ids": len(by_id), "same_id_alias_groups_missed_by_library": len(missed),
            "already_supported_by_unique_relaxed_metadata_key": sum(g["unique_shared_metadata_key"] for g in missed),
            "apostrophe_ampersand_same_id_groups": sum(g["same_steam_id"] for g in proposed_groups),
            "apostrophe_ampersand_conflicting_id_groups": sum(not g["same_steam_id"] for g in proposed_groups),
            "ambiguous_relaxed_keys": len(collisions),
        },
        "same_id_alias_candidates": missed,
        "proposed_apostrophe_ampersand_groups": proposed_groups,
        "ambiguous_relaxed_groups": collisions,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.csv)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report["summary"], indent=2))


if __name__ == "__main__":
    main()
