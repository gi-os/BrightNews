# Changelog

Notable changes to Light RSS are recorded here. This project follows [Semantic Versioning](https://semver.org/).

## Unreleased

### Changed

- Prepared repository documentation, privacy and security disclosures, screenshots, and trusted CI for public release.

## 1.8.0 - 2026-07-27

### Changed

- SIGN IN no longer hands the link to Chrome. Signing in there was useless: the cookie that gets
  past the wall lands in Chrome, and the reader keeps being turned away. The page now opens in the
  app, the cookies it collects are kept per host, and the user agent that earned them is recorded
  and reused, so the next reader fetch arrives looking like the same client. Coming back from that
  screen re-fetches the article automatically.
- SIGN IN and RELOAD sit at the end of the article now instead of in a fixed bottom bar.
- The sign-in view is the one place in the app that runs a page's own scripts, and the only place
  a WebView exists. Reader mode itself still renders extracted text.

## 1.7.0 - 2026-07-27

### Added

- SIGN IN at the bottom of reader mode hands the article's address to whatever app on the phone
  opens web links, so a bot check or subscriber wall can be cleared where it can actually be
  cleared. It opens the resolved address, not the redirector the feed linked to. If nothing on the
  phone handles web links, the screen says so rather than doing nothing.
- Bot checks and sign-in walls are now recognised instead of being reported as an empty page:
  "This site is checking your browser or asking you to sign in." Detection looks for challenge
  scripts from the usual vendors, and for wall phrasing — but the phrasing only counts when
  nothing readable came out, since plenty of complete articles carry a subscribe promo.
- The address is shown on the failure screen, so it can be read off or copied even without a
  browser installed.

### Changed

- The vendored light-sdk plugin permits `android.content.Intent` and `startActivity` for this one
  purpose. All in-app navigation still goes through `LightScreen.navigateTo()`.

## 1.6.1 - 2026-07-27

### Fixed

- OPEN now lands on the real article rather than whatever the feed linked to. HTTP redirects are
  followed, then the page's own `<link rel="canonical">`, `og:url`, or `<meta http-equiv=refresh>`
  is chased one hop, which is what gets you off a tracking or redirector URL.
- When a page yields nothing readable and declares an AMP copy, that copy is read instead.
- Sites that turn away the Light RSS user agent — the New York Times among them — are retried once
  as a desktop browser. Feed requests still always identify as Light RSS; only reader-mode page
  fetches do this.
- A refusal now says so: "403: this site refuses outside readers. It may need a subscription."

## 1.6.0 - 2026-07-27

### Added

- OPEN at the bottom of an article fetches the page it links to and shows a reader-mode version
  of it: body copy and its images, in Light typography, with no browser, scripts, or page
  furniture. Pages are kept for the session, so going back and forth does not re-fetch.
- `ReaderExtractor` finds the article by locating the longest run of substantial paragraphs after
  navigation, promos, comment widgets, and footers have been stripped, then reuses the feed
  parser's block conversion. Five tests cover extraction, junk removal, headline de-duplication,
  pages with no `<article>` element, and pages with nothing to read.

### Changed

- List thumbnails moved to the left of the title.

## 1.5.0 - 2026-07-27

### Added

- A PASTE button on feed entry and search. Feed addresses are long and usually already on a
  clipboard, and the Light keyboard is the slowest way to enter one.
- Feed entry and search now use the phone's own keyboard by default, with a URL keyboard layout
  and a Go key that submits. A KEYS button hands the same field to the Light keyboard for anyone
  who prefers it, or for a device with no system IME.

### Fixed

- When LightOS declines to answer the permission check, the scanner now raises Android's own
  permission prompt instead of leaving the camera unreachable. The LightOS dialog is still
  preferred whenever the server is willing to answer.

## 1.4.0 - 2026-07-27

### Changed

- Scanning no longer goes through the SDK scanner composable. `RssScanner.kt` owns the CameraX
  `LifecycleCameraController`, binds it to the lifecycle, and renders it in a `PreviewView` — the
  same way the LightPass camera screens do — with an ML Kit analyzer restricted to QR codes.
- The permission gate reads Android directly through `Context.checkSelfPermission` instead of
  asking LightOS whether the camera is granted. LightOS is still asked to show its permission
  dialog, but its answer is no longer what decides whether the camera starts, so a server that
  will not reply cannot block the preview.
- The state is re-read on every `RESUMED`, so accepting the permission dialog brings the preview
  up without leaving the screen.
- Whatever went wrong is printed at the bottom of the scan screen: the LightOS reply, or the
  CameraX bind failure, plus the adb command to grant the permission by hand.
- The vendored light-sdk plugin allows `LocalContext` and `LocalLifecycleOwner`, which a tool
  needs to bind CameraX itself. LightPass uses both.

## 1.3.0 - 2026-07-27

### Changed

- Camera permission now follows the LightPass pattern instead of the SDK scanner's built-in
  handling: ask LightOS, request the permission when it says no, and re-check every time the
  screen returns to the front — which covers coming back from the LightOS permission dialog,
  since `LightActivity.onResume()` calls `onScreenShow()`. Granting the camera the first time
  now works from inside the app, with no adb needed.
- The permission check is retried four times, 600 ms apart, before its silence is believed. The
  service binding can lose a race with the first frame, and a single failed check was being
  treated as a permanent refusal.
- A server that never answers no longer blocks the screen: the camera is started anyway, because
  an unanswered check says nothing about whether the permission is actually held.
- When LightOS does report the permission missing, the screen offers ASK AGAIN and TYPE INSTEAD
  rather than dead-ending.

## 1.2.1 - 2026-07-27

### Fixed

- The scanner dead-ended on "unable to request camera permission". The SDK's client wrapper treats
  a failed LightOS permission call as fatal, and LightOS refuses that call for a sideloaded tool.
  The screen now drives the UI-level scanner directly: when the server does not answer, the camera
  is started anyway and the screen explains how to grant the permission over adb. When the server
  does answer, the normal LightOS permission flow is unchanged.
- The scan screen shows the underlying server response, so a failure says what actually happened.

### Changed

- Article rows and thumbnails are back to their 1.1.0 size.

## 1.2.0 - 2026-07-27

### Added

- A QR code generator at [gi-os.github.io/LightRSS](https://gi-os.github.io/LightRSS), so a feed
  address can be turned into a scannable code on a laptop instead of typed on the phone. It runs
  entirely in the browser and loads nothing from a third party.
- The generator address is shown on the add-feed chooser and in Settings.

### Fixed

- The QR scanner failed in release builds: R8 stripped the ML Kit component registrars that
  `BarcodeScanning.getClient()` looks up reflectively. The camera and barcode stack is now declared
  directly by the tool and kept by `tool/proguard-rules.pro`.
- List thumbnails were too small to read. Article rows and thumbnails are larger, thumbnails decode
  at 360 px instead of 220 px, and titles get a third line.

### Changed

- The inbox filter button now names the view you are in — `UNREAD` while showing unread, `ALL`
  while showing everything — instead of the view it would switch to.
- The vendored light-sdk plugin allows `androidx.camera` and `com.google.mlkit` as tool
  dependencies. Official Light distribution may not; the scanner still works without them
  declared, but release builds need the keep rules either way.

## 1.1.0 - 2026-07-27

### Added

- Feed images: thumbnails in every article list and full-width images inside the reader, downsampled and converted to greyscale for the Light Phone display.
- Image extraction from `enclosure`, `media:content`, `media:thumbnail`, `itunes:image`, XHTML bodies, and inline `<img>` markup, with 1 × 1 beacons, `data:` URIs, and known tracking hosts filtered out.
- On-device image cache: lazy per-row downloads, an 8 MB in-memory cache, a 24 MB disk cache, and **Settings → CLEAR IMAGE CACHE**.
- **Settings → IMAGES ON / IMAGES OFF** to disable image downloads entirely and return to the text-only reader.
- **Add feed → Scan QR code**: adds a subscription from a QR code using the SDK scanner, accepting bare URLs, `feed://` and `rss://` schemes, and codes that wrap an address in text.
- Article body blocks, so feed images render in the position the publisher put them rather than being stripped.

### Changed

- The + button on Subscriptions now opens a chooser for scanning or typing an address.
- `tool/lighttool.toml` declares `android.permission.CAMERA` and moves to version 1.1.0 (versionCode 2).
- The SDK `buildDatabase` helper accepts Room migrations; the article table migrates from schema 1 to 2 without losing subscriptions or saved articles.
- User agent is now `LightRSS/1.1 (Light Phone III)`.

## 1.0.0 - 2026-07-22

### Added

- RSS 2.0, Atom, and RDF/RSS parsing with safe XML settings.
- Website feed discovery and conditional refresh support.
- Persistent subscriptions, articles, unread state, saved state, archive state, and local search.
- Inbox, subscriptions, feed, search, reader, confirmation, message, and settings screens.
- LightOS-native theme, navigation, keyboard, icons, scrollbars, action bars, and 27 × 31 grid layout.
- Unit coverage for parsing, discovery, dates, stable IDs, and URL normalization.
