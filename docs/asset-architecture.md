# Offline library and artwork

The implementation lives in `app/src/main/java/com/gamefibre/assets/`.

## Ownership and publication

Store adapters retain account inventories. `AssetStore` separately owns the current candidate record and the published record for each store/game identity. Its SQLite transaction publishes the selected immutable image and metadata together. Account scope, source fingerprint, and all group members' fingerprints must still match when preparation finishes. A late result cannot overwrite a newer source or user cover selection.

Images are content-addressed JPEG files under `files/library-assets`; SQLite is `library-assets.sqlite`. A completed write uses fsync and atomic rename before publication. File existence and image bounds are verified at publication; decoding verifies the content checksum. Selected images are library data, not an evictable cache. Account changes remove active records for the previous account. Games missing from a newer inventory remain in the offline collection; separate inventory observations determine the access warning on their detail page.

Cross-store grouping preserves original store IDs. A shared group can reuse one selected asset, including a SteamGridDB override; ambiguous products remain separate. User cover preferences and original selected files remain in `CustomArtwork`. Ordinary refresh and temporary-cache cleanup preserve these choices. Choosing or resetting a cover goes through `AssetRepository.refreshArtwork`, which re-stages and publishes only the affected group from the already-local file; it never re-walks the whole library, and the render-time staging signature ignores cover preferences so one choice cannot trigger a second full synchronization.

## Preparation and transport

`AssetRepository` owns four workers, a durable queue with leases/backoff, Steam ID resolution, and selection policy. A synchronize pass stages the whole library inside one `AssetStore.transaction`; saved cover choices are published after that commit because `publish` can decline without failing the batch. Idle workers wait on the wake channel until the next lease or backoff expiry rather than polling. One worker is reserved for interactive demand; at most three background requests per host can occupy the four transport slots. Visible demand promotes existing jobs rather than starting duplicate jobs. Distinct viewport requests enter one conflated channel and one SQL update, so scroll frames cannot build an I/O backlog. Staging requests have an ordering generation; obsolete queued snapshots are skipped while refresh intent is carried forward. State publication is serialized. Work belongs to the application, never a card or Activity.

Selection order is explicit user cover, real native portrait, catalog-supplied Steam portrait, then a real landscape rendition. The bundled title/Steam ID index supplies exact IDs; Steam catalog lookups batch up to 50 IDs and retain both orientation URLs. Missing Steam results are briefly negatively cached. A catalog outage does not prevent native-image fallback. A temporary refresh failure does not downgrade a known portrait to landscape. Compact layouts may crop the selected real image instead of fetching another source solely for layout.

`AssetTransport` handles all public images: library preparation, Wishlist, related games, SGDB pickers, screenshots, and fullscreen media. It normalizes store image sizes, deduplicates requests by normalized URI and rendition, limits host concurrency, handles conditional responses and rate limits, validates redirects and payload bounds, and writes immutable objects. Steam hash-directory URLs can reuse existing content directly. Other unchanged image URLs have a six-hour revalidation interval; new URLs bypass that interval. Steam catalog refreshes use their own 30-second coalescing interval; normal positive catalog records last 24 hours and missing records five minutes. Epic catalog source records last 24 hours, with inventory title changes forcing a source update.

`AssetPreparationService` gives JobScheduler a persisted, network-constrained restart entry point into the same repository. It does not create a second executor. Backoff-only work releases the service and schedules a later wake. OS scheduling is opportunistic; correctness depends on durable queue/publications, not guaranteed immediate background execution.

## Rendering boundary

A game adapter accepts only `PublishedCard` records. `ReadyArtworkWindow` decodes local files into strong bitmap handles before committing a card window. Binding sets the prepared bitmap synchronously and starts no I/O. There are no colour previews, controller placeholders, or image fades.

`ArtworkGridLayoutManager` delegates actual distance consumption, fling velocity, and nested scrolling to Android's `GridLayoutManager`. It does not return artificial zero-consumption results, accumulate/replay scroll deltas, or defer a scroll to await a whole look-ahead window. A separate, coalesced local warming job stays ahead of the viewport and retains a bounded range of bitmap handles. Before layout, an exceptional local miss is completed from the shared bitmap cache or the verified local file; binding still starts no I/O and never exposes a placeholder. This can cost a frame on a cold local miss, but it does not terminate Android's fling. High-resolution user covers are downsampled during decoding rather than decoded at full size first.

`LibraryGestureHost` establishes the gesture axis after Android's touch slop. Once vertical, it disables horizontal pager interception through release/cancel while leaving native list, app-bar and refresh handling available. Horizontal gestures remain available for switching libraries. This follows the interception approach described in [Android's ViewPager2 guidance](https://developer.android.com/develop/ui/views/animations/vp2-migration).

Pager pages have explicit holder ownership. Rebinding transfers that ownership; a stale holder cannot release another holder's page. Disposal detaches the nested adapter before dropping its handles. The active page and its neighbours must be prepared before swipe input is enabled. Pending navigation owns the required page set; callbacks from the old page cannot prune its destination. Only an active attached scrolling page delays a metadata/card-model commit; hidden pages stop stale scrolling and prepare immediately. First exposure waits for local readiness; background preparation never clears an already visible publication. Games without any usable real image remain in an explicit preparation list with status, retry, and manual cover selection.

## Refresh and cleanup

Pull-to-refresh stages account data and source checks, keeps existing publication records and image handles, and replaces only validated results. The pull indicator is a brief acknowledgement, dismissed after 750 ms (or earlier if work finishes); its lifetime is independent of the background refresh. It is dismissed on detach/state restoration, and routine refresh does not show a persistent refreshing banner. Failed or stale jobs leave the previous publication usable. Visible ranges are prioritized; list changes wait until scrolling is idle before committing. Source updates replace old successful metadata records even when the new text is shorter.

Storage's temporary-details action clears game-page acceleration data only. Automatic image cleanup targets 128 MiB of unselected transport objects with a 24-hour grace period; it excludes every blob referenced by an active publication. Published offline assets and user choices are never removed by a routine refresh or cache cleanup. Download staging has an independent lifecycle.

The replacement does not read obsolete artwork caches, migrate their contents, or reset account/collection data. A device adopting this architecture prepares the new asset index once. Without an available real source, initial preparation cannot manufacture offline artwork.

## Verification

Debug builds install as **GameFibre Dev** (`com.gamefibre.debug`) alongside
the release app (`com.gamefibre`). Each app keeps its own accounts and local data;
sign in once in each app. The debug instrumentation targets only the dev app.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.gamefibre.debug.test/com.gamefibre.ArtworkAuditInstrumentation
# Native fling distance and diagonal upward touch regression:
adb shell am instrument -w -e mode scroll com.gamefibre.debug.test/com.gamefibre.ArtworkAuditInstrumentation
# Run with the phone disconnected from networks for cold offline validation:
adb shell am force-stop com.gamefibre.debug
adb shell am instrument -w -e mode offline com.gamefibre.debug.test/com.gamefibre.ArtworkAuditInstrumentation
```

Unlock the device before starting the instrumentation. Instrumentation uses isolated fixture databases for transaction, stale/account/group guards, failure retention, deduplication, restart, and cleanup checks. It then audits visible card images on every observed pre-draw while navigating actual account libraries, scrolling, jumping deep into lists, performing touch swipes, and refreshing. Its offline mode disables image transport before Activity launch and asserts no image HTTP. The test window stays awake during the run and releases that flag afterward. It reports frame counts, timing, heap usage, and unresolved titles. No app database or account is cleared by these tests.

A finite device run does not prove all devices or every OS/process-death interleaving; repeat these checks after changing publication, pager ownership, decoding, or queue policy.
