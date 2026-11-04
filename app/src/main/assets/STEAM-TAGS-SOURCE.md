# Offline Steam community tags

Refresh all bundled datasets together with `python3 scripts/update-bundled-data.py`.
Release builds run this automatically. Current revisions, checksums, sizes, and
coverage are in the adjacent JSON manifests and `bundled-data-sources.json`.
The measurements and pinned commands below describe the initial snapshot; they
are retained as format documentation, not current coverage guarantees.

Source: FronkonGames / Martin Bustos, Steam Games Dataset (MIT).
License: `licenses/fronkongames-steam-dataset-MIT.txt` (shared with the title index).

- Dataset: https://huggingface.co/datasets/FronkonGames/steam-games-dataset
- Pinned revision: `6a31fcb42082345acc817e176b28849b7c05f248`
- Input: **games.json**, 970,222,554 bytes; the CSV does not contain community tags.
- SHA-256: `6c5c8f7c119c4f3f38cf82944d79ae6f3ca9a433ce39685a9a98393108b50ef8`
- 144,499 source games; 83,385 with tags, 452 distinct names, 1,180,641 assignments.
- Source values are vote counts, not Valve tag IDs. Keep all tags in descending vote order; alphabetical tie breaks. Do not retain votes.

`steam-tags-v1.bin.xz` is 1,196,668 bytes compressed, 1,594,774 bytes decoded.
The current title index references 129,130 distinct Steam IDs, of which 80,996 have
community tag records (62.7%). The same resolved Steam ID supplies tags across all
stores and wishlist entries. The bundled table remains read-only. Runtime enrichment
stores ranked tag names independently in app-private `files/live-steam-tags/<AppID>.json`
files with fetch timestamps. Live records take precedence in memory; missing or failed
enrichment retains the static list or previous live list. Missing-table games are queued
after library updates; opening a game also queues enrichment. Cached live results last
seven days, with explicit refresh allowed sooner and a 15-minute request cooldown.

The format is big-endian `STTG`, version 1, followed by four int32 values: name
count, tagged-game count, block size (32), payload byte count. The dictionary
contains UTF-8 names prefixed by unsigned base-128 varint byte lengths. Then comes
one int32 payload offset per block. Rows store a positive AppID delta (absolute
AppID at each block start), varint tag count, and ranked varint dictionary IDs.
Dictionary IDs are dense local indices, **not Valve IDs**. The most frequent names
come first to minimize varint sizes. XZ uses preset 6 and CRC32.

The shared reader loads and validates the asset on a background thread, keeps
compact payload bytes plus block offsets, and caches at most 512 decoded tag lists.
UI binding and search perform memory-only lookups; live requests run on background
workers with at most two concurrent exchanges. Community tags remain separate
from store feature categories in `GameDetails.tags`.

Rebuild using Python's standard library:

```sh
python3 scripts/build-steam-tag-index.py --download
# Or reuse a downloaded pinned JSON (checksum is verified):
python3 scripts/build-steam-tag-index.py --json /path/to/games.json
```

The source stays outside the repository and APK. The generator verifies a full
binary round trip before publishing the asset and writes `steam-tags-v1.json`
with provenance and output checksum. Update revision and input checksum together
when changing snapshots. Run the SteamTagIndex and LibrarySearch unit tests after
rebuilding; the reader test checks every tagged game's lookup result.
