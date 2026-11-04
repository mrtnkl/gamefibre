<p align="center">
  <a href="https://gamefibre.com">
    <img src="https://gamefibre.com/assets/logo.svg" width="96" height="96" alt="GameFibre logo" />
  </a>
</p>

<h1 align="center">GameFibre</h1>

<p align="center"><strong>Unified game library for Android</strong></p>

GameFibre brings your Steam, GOG, Epic and Xbox games into one Android app. Browse
covers, revisit games you haven't played in a while, and enjoy the collection you
already own. The focus is on browsing and discovery, with lists and progress
tracking there when you want them.

<p align="center">
  <a href="https://github.com/mrtnkl/gamefibre/releases/latest"><strong>Download the latest APK</strong></a>
  &nbsp;·&nbsp;
  <a href="https://gamefibre.com">Visit gamefibre.com</a>
</p>

<p align="center">
  <a href="https://gamefibre.com/assets/screenshot-01.jpg"><img src="https://gamefibre.com/assets/screenshot-01.jpg" width="30%" alt="GameFibre library with Steam, GOG, Epic and Xbox filters" /></a>
  &nbsp;
  <a href="https://gamefibre.com/assets/screenshot-02.jpg"><img src="https://gamefibre.com/assets/screenshot-02.jpg" width="30%" alt="Steam games displayed as covers" /></a>
  &nbsp;
  <a href="https://gamefibre.com/assets/screenshot-03.jpg"><img src="https://gamefibre.com/assets/screenshot-03.jpg" width="30%" alt="GameFibre list view with game titles and Metascores" /></a>
</p>

[Features](#features) · [Privacy and security](#privacy-and-security) · [Build from source](#build-from-source)

## Install and get started

GameFibre runs on **Android 10 or newer**. Download the APK from the latest release
and open it on your device to install. Android may ask you to allow installation
from your browser or file manager.

Connect your store accounts and let your library and artwork load. Once saved,
your collection, covers and game details remain available offline, including when
a store connection expires.

GameFibre doesn't save your passwords or sign-in codes. It securely stores the
token returned by each store on your device and uses it to keep your account
connected and retrieve your games.

The production app checks for updates once a day while in use. You can dismiss
the prompt and download the update later from the app menu. Install the new APK
over the existing app to keep your saved data.

## Features

- **Offline browsing.** Keep browsing your saved collection, covers, game details
  and lists without an internet connection.
- **Custom artwork.** Choose alternative covers from SteamGridDB using your own
  API key. Selected covers are saved on your device too.
- **Favorites, lists and progress.** Mark favorites, create custom lists and swipe
  between them. Track games as Unplayed, Play next, Playing, On hold or Finished.
- **Sorting and discovery.** Sort your collection by Metascore, playtime or
  HowLongToBeat estimates, or shuffle it to see a different selection.
- **Game file downloads.** Download games you own on Steam, GOG or Epic to Android
  as ZIP files or folders. Use the files with compatible ports and engines such
  as OpenMW for Morrowind or DevilutionX for Diablo, following the engine's setup
  instructions. Compatibility depends on the game and engine.

### Also included

- **Tag filtering.** Combine tags such as open world, first-person and RPG to
  find games in your collection.
- **HowLongToBeat.** See estimated completion times for the main story, extras
  and completionist runs.
- **Screenshots and videos.** Browse full-screen screenshots and watch YouTube
  videos for each game.
- **Steam game search.** Search the Steam catalog with community tags and sort
  results by user reviews.
- **Wishlist.** Save games you'd like to get or import your existing Steam wishlist.
- **Xbox Game Pass.** Browse games available through Game Pass.

An internet connection is needed to connect accounts, update your collection,
download game files, watch videos or load media that hasn't been saved yet. Xbox
games can be collected and browsed; game-file downloads support Steam, GOG and Epic.

## Privacy and security

GameFibre doesn't save your passwords or sign-in codes. To keep accounts connected,
it stores refresh tokens and account IDs encrypted using Android Keystore. Access
tokens stay in memory. Your collection and preferences are stored locally; signing
out removes that store's saved session. Your SteamGridDB API key, if provided, is
also stored encrypted on your device.

The app uses Google Analytics to measure app openings, with cookies to recognize
repeat visits. Account IDs, game lists, searches and sign-in details are not passed
to Analytics. Google signals and ad personalization signals are disabled.

---

## Build from source

Use JDK 21 and the Android SDK with platform 36 and build-tools 35.0.0. After
cloning the repository, generate the bundled game data and build a debug APK:

```sh
python3 scripts/update-bundled-data.py --cache-dir .cache/bundled-data
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The debug app
installs alongside the production app and uses separate local data.

Run the Android checks with:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

For the shared artwork preparation and display rules, see
[the asset architecture](docs/asset-architecture.md).

## Updating bundled game data

Run from the repository root with Python 3.11 or newer:

```sh
python3 scripts/update-bundled-data.py
```

This downloads the latest published Steam dataset, then rebuilds
Steam title/ID matching, community tags, Metascores, review counts, release dates,
and the compiled tag labels. Completion estimates and title aliases use the fixed
HLTB dataset checked into the repository; its index is rebuilt locally without
HLTB network requests. The updater uses Python's standard library; no npm setup
or API keys are needed. Steam downloads total about 1.4 GB and stay outside the
APK. To reuse verified Steam downloads between runs:

```sh
python3 scripts/update-bundled-data.py --cache-dir /path/to/gamefibre-data-cache
```

The updater pins the latest Steam revision and verifies upstream SHA-256 hashes.
It verifies the fixed HLTB inputs against their recorded SHA-256 hashes.
It prepares every output before replacing bundled files. A download or build failure
leaves the existing data in place and exits with an error.

Generated indexes, manifests, and tag resources are ignored by Git. The fixed
HLTB source CSV and title aliases remain tracked. Run the
updater after a fresh checkout, and again whenever you want current local data.
Android builds verify the generated files against their manifest and report the
update command if data is missing or inconsistent. Normal local builds reuse the
last successful generation. The Android check workflow generates its own data.

The signed release workflow runs the updater before testing and building, both for
`v*` tags and manual runs. An update failure stops the release. Generated data is
included in the APK without making an automatic commit or changing the tag. Each
release also includes `bundled-data-sources.json`, recording the Steam revision,
fixed HLTB snapshot, checksums, coverage, and output hashes. The same
manifest is bundled in the APK.

Validate builder changes with:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
./gradlew :app:testDebugUnitTest --tests '*SteamTitleIndexTest' --tests '*SteamTagIndexTest' --tests '*SteamCatalogTest' --tests '*BeatIndexTest' --tests '*StoreSearchStartupTest'
```
