# Offline completion-time data

Refresh all datasets with `python3 scripts/update-bundled-data.py`.
The updater downloads `howlongtobeat_games.csv` from the latest stable release of
https://github.com/julianxhokaxhiu/hltb-scraper and generates `hltb-min.csv` and
`hltb-names.tsv` from its Steam IDs, game names, and completion-time columns.
The upstream MIT license is in `licenses/hltb-scraper-MIT.txt`.
`bundled-data-sources.json` records the exact release, publication date, source
checksum, coverage, and generated file hashes.

The publisher was archived in June 2026; its final snapshot is June 28, 2026.
Refreshing fetches the newest published snapshot, not live HowLongToBeat data.

The completion values are seconds. Estimates are a static snapshot and are not
refreshed over the network. The first nonzero row for a Steam ID is retained.
Other stores resolve known Steam IDs, exact normalized titles, or globally
unambiguous relaxed title keys. These names are
also merged into the broader Steam catalog described in `STEAM-TITLES-SOURCE.md`,
which resolves Steam artwork IDs even without completion estimates. Editions
and sequels are not stripped from titles.

The app loads hltb-index-v1.bin, a prebuilt binary index, instead of parsing and
normalizing the source files at startup. Regenerate it after updating either
source with `python3 scripts/build-hltb-index.py` from the repository root.
Unit tests compare every bundled ID and title against the source parser.
After changing `hltb-names.tsv`, also regenerate the broader Steam title index
using `scripts/build-steam-title-index.py`; its manifest records the alias hash.
