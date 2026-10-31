# Offline Steam title catalog

Source: FronkonGames / Martin Bustos, **Steam Games Dataset** (MIT).

- Dataset: https://huggingface.co/datasets/FronkonGames/steam-games-dataset
- Pinned revision: `6a31fcb42082345acc817e176b28849b7c05f248`
- Input: `games.csv`, 125,855 rows, 401,140,503 bytes.
- Upstream CSV SHA-256: `1b48008b01a799d82385d6e66ba0cb65dd477b6aa4b6c2ec8c443025ab6880ad`
- Attribution/license notice: `licenses/fronkongames-steam-dataset-MIT.txt`.

Only `AppID` and `Name` are retained. The generator merges the existing
`hltb-names.tsv` aliases, normalizes full titles, and excludes all 1,775 normalized
titles that identify multiple Steam IDs. Editions, sequels, and remasters are
never stripped. The resulting index has 132,613 unambiguous title entries;
different aliases can identify the same Steam ID. This is a snapshot and does not
guarantee complete coverage or that each matching game has portrait artwork.

## Compact format

`steam-titles-v2.bin.xz` is a deterministic XZ stream (LZMA2 preset 6, CRC32).
The app already depends on the pure-Java XZ decoder for game downloads. No new
dependency is needed. The index is loaded once on the existing background index
loader, retaining about 4.19 MB of encoded bytes and block-offset tables.
It does not allocate a permanent String/HashMap entry for every game. A bounded
2,048-entry cache per table speeds repeated lookups for a user's library.

The uncompressed stream contains big-endian 32-bit `STIX`, version 2, followed by
an exact table and a relaxed table. Each table starts with its record count,
block size (32), payload length, then one 32-bit payload offset per block. Titles
are sorted in UTF-16 order to match Kotlin String comparisons. Each record stores
unsigned base-128 integers for shared UTF-8 prefix length, suffix length, and
Steam ID, with suffix bytes between the suffix length and Steam ID. Each block
restarts with a full title. Lookup binary-searches block starts and decodes at
most 32 titles. A prefix may split a UTF-8 character; bytes are reconstructed
before decoding.

Measured sizes for both exact and relaxed tables:

| Representation | Bytes |
| --- | ---: |
| Minified ID/title JSON | 7,992,992 |
| Gzip minified JSON | 2,897,685 |
| XZ full-title binary | 2,271,456 |
| Prefix-compressed binary before XZ | 4,189,575 |
| Packaged prefix-compressed binary + XZ | 1,855,136 |

The app performs no catalog downloads or online title searches. Successful
matches allow the existing on-demand, persistent artwork cache to retrieve a
cover. Existing completion estimates remain unchanged and are still keyed by ID.
Known owned Steam IDs take priority for matching full titles. The catalog is then
searched for an exact normalized title, followed by a unique relaxed key. The
same resolution is used for artwork, page details/screenshots, and completion
estimates. Excluded ambiguous titles never fall back to the narrower old map.

## Title equivalence

The exact pass uses Unicode NFKD, lowercasing, accent removal, trademark/copyright
removal, and punctuation/whitespace normalization. The relaxed pass additionally
joins punctuation-separated words/letters, removes apostrophes, treats `&` as
`and`, and ignores one leading English `the`, `a`, or `an` or a trailing article
such as `Witcher, The`. Unicode letters/numbers are retained, including non-Latin
names. Keys shorter than three code points are not used for relaxed matching.
Numbers, subtitles, editions, and remaster labels are retained; no edit-distance
or partial-title guessing is performed.

Written-out `(TM)` and `(R)` suffix markers are also removed, except when
immediately followed by a letter or number, as in `(R)Evolution`. `(c)` is
retained because it can be part of a word, as in `Drak(c)ula`. The Python asset
builders share `scripts/title_normalization.py`; Python and Kotlin tests use
the same exact/relaxed fixtures. See `docs/title-matching-audit.md` for the
catalog-wide duplicate matching findings and reproduction instructions.

The generator checks relaxed keys against **all** source IDs, including titles
excluded by the exact table. It includes 131,007 unique relaxed keys and excludes
2,241 keys shared by different IDs. Exact title matches win when, for example,
`The Guild` and `Guild` identify different games; `G.U.I.L.D.` stays unmatched
when its relaxed key is ambiguous. The original store ID and download ownership
are preserved; the resolved Steam ID only enriches metadata and artwork.

## Rebuilding

From the repository root, download and compile the pinned snapshot:

```sh
python3 scripts/build-steam-title-index.py --download
```

Or reuse an already downloaded copy (the checksum is verified):

```sh
python3 scripts/build-steam-title-index.py --csv /path/to/games.csv
```

The 401 MB source stays outside the repository and APK. The script uses Python's
standard library, writes the compact asset and `steam-titles-v2.json` provenance
manifest, and verifies the pinned input hash before writing. To update the
snapshot, update `REVISION` and `CSV_SHA256` together, rebuild, update these
counts, and run `./gradlew testDebugUnitTest assembleDebug lintDebug`. Changes to
`hltb-names.tsv` require rebuilding both indexes. The manifest includes the alias
input hash and output hash for reproducibility.
