# Title matching audit

Audited the complete pinned Steam snapshot (125,855 rows) and all bundled
`hltb-names.tsv` aliases (46,762 rows), covering 132,809 distinct Steam IDs.
This covers the repository's catalog, not a connected user's store libraries.

## Findings

`Watch_Dogs® 2` and `Watch Dogs 2` already both normalize to `watch dogs 2`.
The same is true for the catalog's underscore/trademark variations of the first
Watch Dogs. A duplicate with exactly these spellings can also indicate multiple
products from one store: the library deliberately leaves those groups separate.

Library grouping and owned-copy resolution use the full normalized title.
Steam metadata lookup additionally tries a globally unique relaxed key, which
ignores articles, joins spaces, removes apostrophes, and equates `&` with `and`.
Consequently, finding the same cover does not mean two library cards will merge.

| Check | Groups |
| --- | ---: |
| Same Steam ID, differing full normalized title aliases | 3,726 |
| Above groups sharing one globally unique relaxed metadata key | 212 |
| Apostrophe/ampersand equivalences with just one Steam ID | 57 |
| Apostrophe/ampersand equivalences containing different Steam IDs | 11 |
| All ambiguous relaxed keys, excluded from metadata matching | 2,241 |

Examples that library grouping currently misses:

| Steam ID | Titles |
| --- | --- |
| 1843110 | `Maki's Adventure` / `Makis Adventure` |
| 1616280 | `Chronicles of Tal'Dun: The Remainder` / `Chronicles of Taldun: The Remainder` |
| 283640 | `Salt & Sanctuary` / `Salt and Sanctuary` |
| 329630 | `Command H.Q.` / `Command HQ` |
| 1464550 | `Raging Blasters` / `RagingBlasters` |

Examples of conflicting Steam IDs under the proposed apostrophe/ampersand rules:

| Titles | Steam IDs |
| --- | --- |
| `Heaven & Hell` / `heaven and hell` | 980490 / 2662200 |
| `No Mans Land` / `No Man's Land` | 666560 / 3863620 |
| `Beyond Good and Evil™` / `Beyond Good & Evil` | 15130 / 2556990 |
| `Farmers' Market` / `Farmer's Market` | 1735640 / 3949430 |

Different IDs may represent separate releases, editions, or unrelated products.
Conversely, sharing a source ID does not establish that edition words can always
be ignored: the inputs include `Shadow of the Tomb Raider` / `Shadow of the Tomb
Raider: Definitive Edition`, as well as renamed and translated titles. These are
review candidates, not an approved equivalence list.

## Changes and recommendation

Full normalization now also removes written-out `(TM)`, `(R)`, and `(C)` markers before
Unicode decomposition. `Watch Dogs 2 (TM)` and `Watch Dogs(R) 2` therefore join
the existing Watch Dogs 2 group across stores. Markers followed immediately by a
letter or number stay part of the title (`(R)Evolution`), as does `(c)` inside
`Drak(c)ula`. Sequels, subtitles, editions, and remaster labels remain intact.

Automatic SteamGridDB artwork selection uses this same full normalized title
comparison. Formatting differences are equivalent, but multiple matching SGDB
IDs remain ambiguous and no cover is applied for those results or empty keys.

Both Python asset builders now import one normalization implementation. Shared
fixtures test Python and Kotlin against the same expected exact/relaxed keys.
Rebuilding both assets produced identical packaged bytes: no source title was
affected by the new marker rule, but incoming store titles can now use it.

Keep broad fuzzy matching out of automatic duplicate merging. Further coverage
should use reviewed title aliases, with explicit conflicts excluded, or verified
cross-store product mappings that preserve editions. Applying metadata's relaxed
key directly to library identities would discard its global ambiguity check.

## Reproduce the full report

Use the CSV pinned in `app/src/main/assets/STEAM-TITLES-SOURCE.md`. Its checksum
is verified before auditing. The JSON includes every same-ID alias candidate,
every proposed apostrophe/ampersand group, and every ambiguous relaxed group:

```sh
python3 scripts/audit-title-matching.py --csv /path/to/games.csv --output /tmp/title-audit.json
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew testDebugUnitTest assembleDebug lintDebug
```

There are no network title queries, data migrations, or changes to store IDs.
