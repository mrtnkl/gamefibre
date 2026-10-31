The bundled hltb-min.csv is copied from GameFibre's scripts/hltb/hltb-min.csv.
hltb-names.tsv is generated from the steam_id and game_name columns in that
project's scripts/hltb/howlongtobeat_games.csv, excluding missing/zero Steam IDs.

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
