"""Compile the bundled CSV/title files; the app never normalizes the full catalog at startup."""
import csv
import struct
from pathlib import Path

from title_normalization import normalize

assets = Path(__file__).resolve().parents[1] / "app/src/main/assets"

times = {}
with (assets / "hltb-min.csv").open() as source:
    for row in csv.DictReader(source):
        app_id = int(row["steam_id"])
        values = tuple(max(0, int(row.get(key) or 0)) for key in ("comp_main", "comp_plus", "comp_100"))
        if app_id > 0 and any(values):
            times.setdefault(app_id, values)

names = {}
with (assets / "hltb-names.tsv").open() as source:
    for line in source:
        app_id, name = line.rstrip("\n").split("\t", 1)
        names.setdefault(normalize(name), set()).add(int(app_id))
names = {name: next(iter(ids)) for name, ids in names.items() if len(ids) == 1}

with (assets / "hltb-index-v1.bin").open("wb") as output:
    output.write(b"HLTB")
    output.write(struct.pack(">ii", 1, len(times)))
    for app_id, values in sorted(times.items()):
        output.write(struct.pack(">iqqq", app_id, *values))
    output.write(struct.pack(">i", len(names)))
    for name, app_id in sorted(names.items()):
        encoded = name.encode("utf-8")
        output.write(struct.pack(">i", len(encoded)))
        output.write(encoded)
        output.write(struct.pack(">i", app_id))
print(f"Compiled {len(times)} time records and {len(names)} unique titles")
