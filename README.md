# GameFibre

**Your game collection, together on Android.**

GameFibre brings your **Steam, GOG, Epic Games, and Xbox** libraries into one place. It’s designed to make exploring the games you already own feel pleasant: browse covers, rediscover forgotten favorites, and find something you want to play next.

Connect your accounts and let your collection load, then take it offline. Your library and cover artwork are stored on your device, so you can keep browsing your collection, custom lists, wishlist, and game progress without an internet connection.

- **Make it your collection.** Create custom lists and swipe between them, whether you’re sorting cozy games, weekend adventures, or a backlog you’ll definitely finish someday.
- **Track your progress.** Mark games as Unplayed, Play next, Playing, On hold, or Finished.
- **Keep a wishlist.** Save games you’d like to get and import your Steam wishlist.
- **Choose your covers.** Pick your preferred custom cover art from SteamGridDB using your own API key. Selected covers are saved for offline browsing too.
- **Explore each game.** Browse screenshots and watch YouTube videos to get a feel for what you might play next.
- **Find the right fit.** Check HowLongToBeat completion-time estimates and Metascores where available, then sort by time to beat or score to find a quick adventure or a highly rated game.
- **Find something new.** Browse Xbox Game Pass titles or search the Steam catalog using community tags to find the kinds of games you enjoy.
- **Bring your game files to Android.** Download games you own on Steam, GOG, or Epic Games and export them as a ZIP file or game folder to your device’s **Downloads** folder.

Exported files can be used with compatible Android game engines and ports, such as **OpenMW for Morrowind**. You’ll need to own the game and follow your chosen engine’s setup instructions; compatibility depends on the game and engine.

You’ll need an internet connection to connect accounts, update your collection, download games, watch videos, or load media that hasn’t been saved yet. Xbox games can be collected and browsed; file exports are available for Steam, GOG, and Epic Games.

GameFibre runs on **Android 10 or newer**.

The production app checks GitHub for updates once a day while in use. You can
dismiss the update prompt and download it later from the hamburger menu. Open
the downloaded APK to install it over the existing app and keep your saved data.

**Privacy and security:** GameFibre doesn’t save your passwords or sign-in codes. To keep accounts connected, it saves refresh tokens—store-issued passes that renew your sign-in—and account IDs, encrypted using Android Keystore. Access tokens stay in memory. Your collection and preferences are stored locally on your device; signing out removes that store’s saved session. If you use SteamGridDB, your API key is also stored encrypted on your device.

[Visit gamefibre.com](https://gamefibre.com)


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
