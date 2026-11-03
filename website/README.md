# GameFibre website

Buildless HTML, CSS, and JavaScript. No npm install or build step is needed.

## Preview

From the repository root:

```sh
python3 -m http.server 4173 --directory website
```

Open <http://localhost:4173>.

## Publish on GitHub Pages

In the repository's **Settings → Pages → Build and deployment**, select
**GitHub Actions** as the source. Push the website and
`.github/workflows/pages.yml` to `main`; subsequent website changes deploy
automatically. The workflow can also be run manually from Actions.

Only `website/` is uploaded. No `gh-pages` branch is needed. Relative asset URLs
work both at `https://mrtnkl.github.io/gamefibre/` and on a custom domain.

To move `gamefibre.com` here, configure that custom domain in GitHub Pages and
update its DNS as directed by GitHub. Domain and DNS settings are not changed by
this source update.

## Content and assets

- `index.html`: single-page content, based on the current root README.
- `styles.css`: the app's DM Sans typography, white surfaces, and blue palette.
- `app.js`: `<gamefibre-icon>`, screenshot carousels, and the latest-release APK download action.
- `assets/logo.svg`: the current app mark from `docs/design/app-icon.svg`, with complete
  white-filled rear cards for natural overlap,
  with a tighter viewBox for the web.
  The custom element uses those same three filled cards, with a staggered six-second
  fan opening based on the old website. Reduced-motion preferences disable it.
- `assets/portrait-1.jpg` through `portrait-7.jpg`: actual app screenshots.
  The portrait carousel starts after its first loaded screenshot is at least half in view
  for three seconds, then slides right to left every three seconds. It pauses offscreen
  and in background tabs. Previous/next and pause/play controls remain available;
  reduced-motion preferences disable autoplay and animated transitions.
- `assets/dm-sans.ttf`: the app's font; its license is included alongside it.

Every regular APK-button click fetches GitHub's latest stable release and opens
its uploaded `gamefibre*.apk` asset. No release version is hardcoded. If GitHub's
API is unavailable or rate-limited, a message links to the latest release for a
manual download. Without JavaScript, buttons open that release directly.
