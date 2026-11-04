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

- `index.html`: hero, five main features with app screenshots, a compact “Also included”
  panel, download section, and footer. The secondary panel covers tag filtering,
  HowLongToBeat, screenshots/videos, Steam game search, wishlist, and Xbox Game Pass.
- `styles.css`: the app's DM Sans typography, white surfaces, and blue palette.
  The secondary features share one bordered panel with two columns on desktop
  and one on mobile, using smaller headings and icons than the main features.
- Icons: outline SVGs based on Lucide, with the license in `assets/lucide-LICENSE.txt`.
- `app.js`: `<gamefibre-icon>`, screenshot carousels, and the latest-release APK download action.
- `assets/logo.svg`: the current app mark from `docs/design/app-icon.svg`, with complete
  white-filled rear cards for natural overlap,
  with a tighter viewBox for the web.
  The custom element uses those same three filled cards, with a staggered six-second
  fan opening based on the old website. Reduced-motion preferences disable it.
- `assets/screenshot-01.jpg` through `screenshot-12.jpg`: app screenshots in carousel order,
  resized to 640 × 1422 pixels and encoded as JPEG at quality 95.
  The portrait carousel starts after its first loaded screenshot is at least half in view
  for three seconds, then slides right to left every three seconds. It pauses offscreen
  and in background tabs. Previous/next and pause/play controls remain available;
  reduced-motion preferences disable autoplay and animated transitions.
- `assets/dm-sans.ttf`: the app's font; its license is included alongside it.

Every regular APK-button click fetches GitHub's latest stable release and opens
its uploaded `gamefibre*.apk` asset. No release version is hardcoded. If GitHub's
API is unavailable or rate-limited, a message links to the latest release for a
manual download. Without JavaScript, buttons open that release directly.


## Search and social previews

The canonical production URL is `https://gamefibre.com/`. The page includes a
search title/description, Open Graph and large-image social cards, and static
Schema.org `WebSite` / `SoftwareApplication` data. Metadata describes the visible
app features; there are no invented ratings or reviews. Keep canonical, social,
structured-data, robots and sitemap URLs aligned if the production domain changes.

`robots.txt` allows crawling and points to `sitemap.xml`. The GitHub Pages workflow
publishes both alongside the page and social assets. After deployment, submit
`https://gamefibre.com/sitemap.xml` in Google Search Console and use URL Inspection
to request indexing. Account verification is configured separately; no verification
token is included in this repository.

`assets/social-card.png` is a 1200 × 630 sharing image made from the current logo,
font and first screenshot. PNG favicons and an Apple touch icon use the same logo.
To regenerate these assets after a branding or screenshot change, use ImageMagick:

```sh
python3 scripts/build-website-social-assets.py
```

The website itself still needs no build step. Search engines and social platforms
may cache earlier metadata and images until they crawl the deployed page again.
