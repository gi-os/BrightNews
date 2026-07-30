# LightRSS

RSS and Atom reader for the Light Phone III, built on the Light SDK. Tool id
`com.lightrss.reader`. Feeds, read state, saved articles, images and search stay on the
phone. Current release: **v1.12.0** (`tool/lighttool.toml`: versionCode 16).

This is a fork of **[zachattack323/LightRSS](https://github.com/zachattack323/LightRSS)**,
itself built on `lightphone/light-sdk`'s tool scaffold. This fork adds article/list
images, subscribing by QR code instead of typing a URL on the phone keyboard, an
in-app reader for the page an article links to (with session-carrying sign-in for sites
that gate content), and hardware-wheel scrolling. Work happens on the
`images-and-qr` branch, **which is this repo's default branch** — see
[Branches and CI](#branches-and-ci-read-this-before-you-push), because the trigger
layout here does not match the rest of the portfolio.

## Quick start

```sh
git clone https://github.com/gi-os/LightRSS.git
cd LightRSS
export JAVA_HOME="<path-to-jdk-17>"
export ANDROID_HOME="<path-to-android-sdk>"
export GH_PACKAGES_USER="<github-username>"
export GH_PACKAGES_TOKEN="<token-with-read:packages>"
./gradlew :tool:testDebugUnitTest :tool:lintDebug :tool:assembleDebug
```

The GitHub token is required even for a local build — the Light SDK keyboard dependency
is hosted on GitHub Packages, and an unauthenticated `maven.pkg.github.com` request fails
with 401. `gpr.user`/`gpr.key` in `local.properties` work instead of the env vars; never
commit either. The debug APK lands in `tool/build/outputs/apk/debug/`.

To sideload immediately without building, grab a release APK instead:

```sh
adb install -r LightRSS-<version>.apk   # from github.com/gi-os/LightRSS/releases
```

or track the repo in [Obtainium](https://github.com/ImranR98/Obtainium).
[INSTALL.md](INSTALL.md) has the signing fingerprint to verify against.

## Branches and CI — read this before you push

Unlike every other repo in this portfolio, **a push to LightRSS's default branch does
not build or release anything.** Verified directly from the workflow files:

- `.github/workflows/build.yml` (`Trusted build`: test, lint, `assembleDebug`) triggers
  only `on: push: branches: [main]`.
- `.github/workflows/release.yml` (`Release APK`, the one that publishes a signed
  GitHub Release) triggers only `on: push: tags: ['v*']`, and fails on purpose if the
  tag doesn't match `versionName` in `tool/lighttool.toml`.
- The repo's default branch — confirmed via `git branch --show-current` /
  `remote show origin` on a fresh clone — is **`images-and-qr`**, which sits one commit
  ahead of `main` on unreleased work and is *not* a target of either workflow.

So: pushing to `images-and-qr` alone runs no CI at all. To actually test a change, push
the same commit to `main` as well:

```sh
git push origin images-and-qr:images-and-qr   # keeps the default branch current
git push origin images-and-qr:main            # runs build.yml (test + lint + assembleDebug)
```

To cut a real release, bump both `versionName` and `versionCode` in
`tool/lighttool.toml`, then tag:

```sh
git tag v1.13.0
git push origin v1.13.0                        # runs release.yml, publishes the signed APK
```

## Configuration and usage

- **Subscribing.** Subscriptions → **+** → paste a URL, use the on-screen keyboard, or
  **Scan QR code**. The scanner accepts a bare URL, a `feed://`/`rss://` scheme, or a
  code that wraps an address inside other text.
  [gi-os.github.io/LightRSS](https://gi-os.github.io/LightRSS) generates a matching code
  from any feed or site address, client-side.
- **Images** (this fork's main addition). A thumbnail per article row and full-width
  images in the reader, downsampled and rendered grey for the panel. Pulled from
  `enclosure`, `media:content`, `media:thumbnail`, `itunes:image` and inline `<img>`
  markup; 1x1 tracking beacons, `data:` URIs and known tracking hosts are dropped.
  Lazy, on-screen-row-only downloads into an 8 MB memory cache / 24 MB disk cache. A
  Settings switch disables the whole feature.
- **Reader mode.** **OPEN** fetches the linked article and renders it with no WebView —
  no script, ad or tracking pixel loads. If a site answers with a bot check or a
  paywall, **SIGN IN** opens that one page in the app's only WebView, keeps the cookies
  it hands back per host, and records the user agent that earned them (the two travel
  together); subsequent reader fetches for that host send both, feed refreshes never do.
- **The wheel** scrolls the list, the article, the reader page and the settings menu.
  It needed one addition to the vendored SDK: a tool has no window of its own
  (`LightActivity` owns it, and build rules block tool code from the window/`LocalView`),
  so `LightHardwareKeys` in `sdk/client` is a seam called from
  `LightActivity.dispatchKeyEvent` before the event reaches the view hierarchy — the
  only place a key can be claimed ahead of a focused WebView or text field. Both halves
  of a notch are consumed there; an unclaimed key in an SDK tool is otherwise forwarded
  to the LightOS server, which reads a turn as a brightness change.
  [LightControl](https://github.com/gi-os/LightControl) (optional, separate app) owns
  the wheel click, the camera button and brightness phone-wide, and passes bare turns
  through to `com.lightrss.reader` (alongside `com.gios.*`, LightFastread and
  LightPhono) so per-notch scrolling inside the app is unaffected by installing it.

## New articles don't hide above the scroll position

`ArticleList` keys its `LightLazyScrollView` rows by article id
(`items(articles, key = { it.article.id })`), so a sync inserting newer articles at the
top of the feed doesn't silently anchor the viewport to what used to be the first row —
without a stable key, `LazyColumn`/`LightLazyScrollView` can throw on reordering, or
just leave new rows sitting above where the user is looking.

`HomeViewModel` pairs that with a `jumpToNewest` counter: a `jumpPending` flag set once
at construction and again in `onAppPause()` is consumed in `onScreenShow()` by bumping
`jumpToNewest`, which the screen observes to call `listState.scrollToItem(0)`. The net
effect: opening the app, or coming back to it after it was backgrounded, lands on the
newest article; returning from the in-app reader does not re-trigger the jump, because
`onAppPause()` only fires for the screen actually on top, so a half-read list keeps its
place instead of snapping back to the top underneath you.

## What it inherited from upstream

RSS 2.0/Atom/RDF-RSS parsing with namespaced content, feed/site-URL discovery,
`ETag`/`Last-Modified` conditional refresh, a local Room database for subscriptions and
articles, unread/all/per-feed/saved/archive views, local search, and defensive XML
parsing (DTD processing and external entities off). Removable NASA/BBC World/Hacker News
subscriptions ship by default.

## Build and test

```sh
./gradlew :tool:testDebugUnitTest :tool:lintDebug :tool:assembleDebug
```

To run in the SDK emulator: set `serverPackage = "com.thelightphone.sdk.emulator"` in
`tool/lighttool.toml`, follow `docs/system_app/README.md`, then set it back to
`com.lightos` before a device build. `lighttool.toml` requests `INTERNET` and `CAMERA`
(the QR scan screen only). Local debug/release builds use the SDK's public development
keystore — not production artifacts; the release workflow supplies the real one from
`LIGHTRSS_KEYSTORE_BASE64`.

## Privacy

No account, no analytics, no server of LightRSS's own. Feed hosts see an ordinary
request with the `LightRSS/1.1 (Light Phone III)` user agent; with images on, image
hosts see requests for on-screen articles only. Unfollowing removes local articles;
uninstalling removes the database. Full disclosure in [PRIVACY.md](PRIVACY.md).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening an issue or PR, and
[SECURITY.md](SECURITY.md) for reporting a vulnerability privately rather than in a
public issue. The tool id `com.lightrss.reader` is treated as permanent — confirm
ownership before any first official Tool Library submission, since community-tool
distribution guidance from Light keeps changing (see the upstream SDK docs before
relying on anything stated here). [CHANGELOG.md](CHANGELOG.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) track detail this README doesn't.

## Version history

Tag → commit, from `git tag --sort=-creatordate` / `git log`. Several releases fold in
one or more untagged commits that shipped as part of them; those are noted.

| Version | Commit | Change |
| --- | --- | --- |
| v1.12.0 | `c472d9e` | Scroll with the brightness wheel |
| v1.11.1 | `b3c3ec3` | Put the article actions at the end of the text, not in a fixed bar |
| v1.11.0 | `76ecc8a` | Release 1.11.0 — includes `ab2a0fc`, open on the newest article and let home follow only favourite feeds (the keyed-list fix above) |
| v1.10.0 | `c86ca3c` | Keep markup out of article text, and drop the adaptive icon layer |
| v1.9.0  | `a757f60` | Add a launcher icon in the sibling tools' style |
| v1.8.0  | `94f3548` | Use the screen's url in the sign-in WebView, not `WebView.getUrl()` |
| v1.7.0  | `db996fe` | Recognise bot checks and offer to open the link for signing in — includes `b1719c9`, sign in inside the app and reuse the session for reader fetches |
| v1.6.1  | `7f4b42e` | Restore `initialize()` and `addFeed()`, lost in the reader-page edit |
| v1.6.0  | `040d108` | Add a reader view for the page an article links to — includes `1c170af`, open the real article and get past sites that refuse the reader |
| v1.5.0  | `b14ca45` | Paste and the phone keyboard for feed entry, plus an Android camera prompt |
| v1.4.0  | `10a3693` | Run the camera the way LightPass runs it |
| v1.3.0  | `55c2840` | Handle the camera permission the way LightPass does |
| v1.2.1  | `149278e` | Stop the scanner dead-ending when LightOS refuses the permission check |
| v1.2.0  | `0751349` | Fix the QR scanner in release builds, enlarge images, add a code generator |
| v1.1.0  | `d3cdc0a` | Pick a single apksigner when several build-tools versions are installed |

Earlier, untagged commits (`83a35e6` "Ship signed sideload APKs on tag", `f5ee3c5` "Add
feed images and QR-code feed subscribing", `3a9745a` "Document Light RSS workflows and
harden repository hygiene") are the initial fork work, predating the current tag/release
scheme; the commits below that point are unmodified upstream `zachattack323/LightRSS`
and `lightphone/light-sdk` history.

## Origin and credits

- **[zachattack323](https://github.com/zachattack323)** wrote the upstream
  [LightRSS](https://github.com/zachattack323/LightRSS): the parser, Room schema,
  repository layer, conditional refresh, and the original LightOS screens.
- **[lightphone/light-sdk](https://github.com/lightphone/light-sdk)** and
  **[lightphone/light-keyboard](https://github.com/lightphone/light-keyboard)** supply
  the client library, UI kit, keyboard and builder (MIT).
- **[gi-os/LightPass](https://github.com/gi-os/LightPass)** supplied the camera code —
  the SDK scanner wouldn't start reliably in a release build, so `RssScanner.kt` now
  owns a CameraX `LifecycleCameraController` and reads the camera permission with
  `Context.checkSelfPermission`, the same way LightPass does.
- **[gi-os/LightQR](https://github.com/gi-os/LightQR)** supplied the browser-side QR
  generator behind `docs/index.html`.
- **[Obtainium](https://github.com/ImranR98/Obtainium)** handles update checks.

## License

MIT, the same as upstream. See [LICENSE](LICENSE).
