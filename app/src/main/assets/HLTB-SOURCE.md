# Offline completion-time data

Rebuild indexes with `python3 scripts/update-bundled-data.py`.
Completion estimates use the fixed `hltb-min.csv` and `hltb-names.tsv` checked into
Git, derived from the `scrape-2026-06-28` snapshot of
https://github.com/julianxhokaxhiu/hltb-scraper. There are 31,520 time records and
48,514 title aliases. The upstream MIT license is in `licenses/hltb-scraper-MIT.txt`.
The updater verifies these inputs against their recorded SHA-256 hashes and
rebuilds the binary index locally. It makes no HLTB requests or snapshot downloads.
Steam metadata is refreshed independently and merges these fixed title aliases.
`bundled-data-sources.json` records the fixed snapshot, source checksums,
coverage, and generated file hashes.

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
