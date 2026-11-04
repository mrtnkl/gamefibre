# Offline Steam discovery catalog

Refresh all bundled datasets together with `python3 scripts/update-bundled-data.py`.
Release builds run this automatically. Current revisions, checksums, sizes, and
coverage are in the adjacent JSON manifests and `bundled-data-sources.json`.
The measurements and pinned commands below describe the initial snapshot; they
are retained as format documentation, not current coverage guarantees.

Source: FronkonGames / Martin Bustos, Steam Games Dataset (MIT), sharing the existing
`licenses/fronkongames-steam-dataset-MIT.txt` attribution. Uses the same pinned
`games.json` revision and SHA-256 as `STEAM-TAGS-SOURCE.md`.

`steam-catalog-v1.bin.xz` contains 144,498 named AppIDs (one nameless source record is
excluded). Original names, including duplicate names with different IDs, are retained.
It is a snapshot, not a guarantee of current Steam availability or game/product type.

Retained metadata: Metacritic score (4,282 records), full release date (144,498),
positive and total Steam user reviews (82,988 nonzero totals). User score is derived
as positive / total × 100. This is the raw positive percentage, not a proprietary
Steam ranking or a review-count-adjusted score. Zero scores and zero review totals
are unknown; unknown values sort last in both directions. Partial/unparseable dates
are unknown; the generator never invents a day for them. All values describe the
pinned snapshot, not current review totals. Prices and discounts are not retained.

Format: XZ preset 6 + CRC32; big-endian `STCA`, int32 version 1, int32 row count.
Rows are ascending AppID: unsigned varint AppID delta, varint UTF-8 name byte length,
name bytes, one-byte Metascore (0 = unknown), varint release day (days since
1900-01-01 + 1, 0 = unknown), varint positive count, varint total count. Counts
remain exact rather than rounded into buckets. No redundant user-score float,
repeated JSON keys, artwork URLs, descriptions, or per-row tag strings are stored.
Tags join the existing compact shared asset by AppID. Binary size: 3,869,691 bytes;
compressed size: 1,783,592 bytes (1.70 MiB). The manifest records hashes and coverage.

Rebuild using a downloaded pinned source (checksum checked before generation):

```sh
python3 scripts/build-steam-catalog.py --json /path/to/games.json
./gradlew testDebugUnitTest --tests '*SteamCatalogTest'
```

The generator independently decodes every generated row and checks all fields before
publishing. The runtime validates IDs, lengths, score/count bounds, and end of stream.
Discovery uses background local queries, cached ordering arrays and inverted tag
bitsets. Thirty logical results form a page; only those thirty request artwork. Store results and library list rows use `GameCardViews.kt`, with the active sort metric in the same right-hand badge. Search/count, list pills, overflow menu, and sort/order dialog are the shared library controls.
The tag bar is always visible, includes all catalog tags, and keeps global frequency
order without per-pill counts. Library filters retain their drill-down counts. Tag coverage is incomplete;
untagged entries remain searchable by name and in unfiltered results.

Artwork preparation lives in `assets/CatalogArtwork.kt`, shares `AssetTransport`,
its verified persistent blobs, `AssetStore` Steam URL cache, and `SteamArtworkUrls`.
It resolves actual Steam filenames (including hash directories), preferring landscape
capsules. Discovery never stages catalog games as owned inventory. Images are decoded
on background workers before rows enter RecyclerView. Missing artwork has a separate
preparation dialog with retry and artwork selection; prepared rows retain strong image
handles for the whole page. Publications wait for scroll idle, preserving native fling
and nested scrolling. Custom artwork replaces a displayed image only when ready.

The generator also writes `res/values/steam_catalog_tags.xml`: 452 tag names in
global frequency order as compiled Android resources. The tag strip paints from
these labels immediately, before catalog decoding, library indexes, or sorting.
Selection is available during loading. Store searches no longer calculate unused
per-tag drill-down counts. The full inverted tag index remains responsible for
filtering results; compiled labels do not replace the metadata index.

Opening a result resolves exact owned identities first, then unambiguous shared
Steam IDs or cross-store title matches, and uses the actual library record and
normal game pane. Another Steam AppID with the same name never grants ownership.
Store-origin game panes hide community tags; unowned results expose only Wishlist
among collection pills and omit library-management menu actions. Library-origin
game panes retain their existing controls and tags.
