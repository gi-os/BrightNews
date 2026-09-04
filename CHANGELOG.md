# Changelog

Notable changes to Light RSS are recorded here. This project follows [Semantic Versioning](https://semver.org/).

## Unreleased

## 3.3.0 - 2026-09-03

### Added

- `rss_only` metadata flag (Settings → HOME). `HomeViewModel.section` collapses to TIMELINE while
  it is set; the bar reads "RSS", the bottom bar is Saved / Refresh / Archive, and
  `refreshAll` skips `Source.KAGI` feeds.

## 3.2.0 - 2026-09-03

### Changed

- `Briefing.editionTime` / `clockLine`; the briefing's `StatusLine` shows the edition time and
  `REFRESHING…`. `refreshAll(force = false)` ignores the 15-minute window for a Kagi feed whose
  `lastFetchedAt` predates today's 12:00 UTC edition (`todaysEditionAt`).
- Story rows show `Briefing.topic(article)` (the `topic · location` prefix) before the source count.
- `Briefing.timeline` takes `opened`; buckets outside today/yesterday emit a `Header(count,
  folded)` with their stories withheld until tapped (`HomeViewModel.toggleBucket`, session
  state). `BucketHeader` is clickable when `count > 0`.
- New `ic_day_white` (a sun) for the briefing tab; `ic_kagi_white` stays in the repo unused.

## 3.1.0 - 2026-09-03

### Changed

- Briefing sections show their first three with a `MoreRow`; `HomeViewModel.expanded` (session
  only, cleared on `onAppPause`; `CALENDAR_SECTION = -1`). Past calendar entries lightened.
  Margins 1.5u both sides, weather flush under the date.
- `TimelineList` measures each headline with `rememberTextMeasurer` and gives every row its
  natural height (`STORY_PAD_UNITS` 0.9, `STORY_GAP_UNITS` 0.375) instead of a uniform two-line
  row.
- Reader: `turnWithSlide` animates a `graphicsLayer` translation out and in around
  `viewModel.turn`; `UpNext` landing zone (≥55% of the screen) after the actions; a
  `NestedScrollConnection` turns on 220 px of overscroll past either end; `WheelScroll` gains
  `edgeNotches` (reader passes 1).

## 3.0.0 - 2026-09-03

### Changed

- Home is two tabs, `HomeSection.BRIEFING` (default, persisted in `app_metadata`) and
  `HomeSection.TIMELINE`, replacing the RSS / Newsletters / Kagi tabs. `Briefing.kt` holds the
  pure shapes: `edition()` groups Kagi rows by feed, `timeline()` interleaves bucket headers on
  a 04:00 journal day, `sourceLine()`, `weatherLine()`, `entryTime()`. `BriefingUi.kt` renders
  both; `BriefingContent` is a plain scroll view, `TimelineList` a lazy list with two row
  heights.
- `sdk/ui` `LightLazyScrollView` gains an overload taking `itemCount`, `heightsKey` and a
  per-index `itemHeightGridUnits`, prefix-summed for the scroll bar; the uniform version is a
  wrapper over the same core.
- `NotebookBridge` reads `content://com.gios.lightnotebook.nextup/day` and `/weather`
  (BrightNotebook ≥ 1.61). The plugin gains `queryProviders` in `lighttool.toml`, emitted as
  `<provider android:authorities>` inside `<queries>`.
- `ArticleEntity.sourceCount` (schema 5 → 6) for Kagi stories; the reader's bar shows
  `Category · n of m` via `RssRepository.kagiPosition`. `FeedsScreen` gains a Mailbox button.

## 2.11.0 - 2026-09-03

### Fixed

- Wheel: the 150 ms first-notch hold is removed (every first turn felt late); `SMOOTHING` 0.28
  → 0.36. Row snap no longer runs when the list cannot scroll both ways, which was the
  reverse jump at the end of a list.
- Chrome: an up-scroll only counts while `canScrollForward`, so a list clamping its offset when
  the bars hide at the end no longer re-shows them.

### Changed

- `ChromeVisibility` shows the bars only after 500 ms of continuous up-scroll (runs broken by a
  250 ms pause) or near the top; hides after 40 dp down. Applied to Home, Feed, Feeds, Saved,
  Archive, Reader, ReaderPage, Kagi list and Kagi picker via `ChromeScrollEffect`.
- `sdk/ui` `LightLazyScrollView` drops the duplicated end padding in Outside mode and reserves
  the gutter with a spacer when the bar is hidden; both scroll views fade the bar out 900 ms
  after the last movement (`rememberScrollBarAlpha`). List rows end 0.75 units from the gutter
  (`ROW_END_MARGIN_UNITS`); `HairlineDivider` is edge to edge.

## 2.10.0 - 2026-09-03

### Changed

- Spacing refresh. Shared constants in `RssUi.kt` (`SIDE_MARGIN_UNITS` 1.5, `READER_MARGIN_UNITS`
  1.75, `ROW_PADDING_UNITS` 1.1, `PARAGRAPH_GAP_UNITS` 1.4, `SECTION_GAP_UNITS` 2.25). Article
  rows 6 units minimum with 0.9 padding and a right-edge 3.6×3.6 thumbnail; feed/label rows 5
  units with 1.0 padding; `HairlineDivider` under list rows, settings rows and picker parents.
  Reader title in Heading, byline in Fine, no trailing host line; `ReaderType` supplies a 1.5
  line-height paragraph/detail typography to article bodies; section headings carry a rule.
  End-of-article actions are one `ArticleActions` row. `EmptyState`/`LoadingScreen` are
  top-left content blocks; `StatusLine` in Detail.
- Wheel (`hw/Wheel.kt`): inter-notch acceleration (`gainFor`), row snap for `LazyListState`
  when `rowPx` is passed, a 150 ms hold that releases a lone notch instead of dropping it,
  frame-length-corrected smoothing, and `onEdge`/`WheelEdge` reporting three notches past an
  end. `ReaderViewModel` holds a mutable current id and turns to `nextInFeed`/`previousInFeed`
  in place; a Kagi category's list edge opens the next category.

## 2.9.0 - 2026-09-03

### Added

- Kagi News as a third section. A Kagi category (`Source.KAGI`) is a `FeedEntity` whose url is
  the category's public JSON file under `news.kagi.com`; each story ("cluster") is an
  `ArticleEntity` whose summary, highlights, quote, perspectives, timeline, did-you-know and
  source list travel as `ContentBlock`s. New block kinds `Heading` and `Link` (`H\t`, `L\t`
  records; older decoders skip them). The Kagi tab on home lists followed categories with
  unread counts; a category's stories sit in Kagi's ranking (edition timestamp stepped back
  one second per rank); NEXT in the category's bottom bar moves to the following category.
- A picker for Kagi's ~190 categories, shelved: the general categories, then places folded
  under a parent (`USA | Texas` under USA, bare countries under one row), then topics. The
  index is cached a day in `app_metadata`.
- Full-text RSS articles. `ArticleEntity.readerBlocks` (schema 4 → 5) holds the article's
  page as extracted by `ReaderExtractor`; the reader fetches it on first open and refreshes
  prefetch the newest twelve unread. A page that cannot be read stores a sentinel so it is
  not retried on refresh. `FULL ARTICLES` in Settings turns it off.

### Changed

- Kagi stories seen in another followed category within 36 hours are not stored again; Kagi
  editions older than a week are trimmed (saved and archived stories exempt).
- With images off, a body that has text blocks now shows the text blocks rather than the
  feed's plain summary.

## 2.8.0 - 2026-09-01

### Fixed

- A feed whose redirect target varies — mirror rotation, tracking parameters, an
  http-to-https bounce — reappeared unread on every refresh. `stableArticleId` hashed the
  fetch's post-redirect effective URL, and `updateFeedAfterRefresh` rewrote the stored feed
  URL to it each time, so every article regenerated its id and read/starred/archived state
  was orphaned. An article's identity is now built from the feed's database id — it belongs
  to the feed you subscribed to, not to whichever mirror answered. Adding a feed inserts the
  feed row first so first articles are keyed with the real id.
- Refreshes no longer overwrite the stored feed URL: the address you subscribed with is the
  one the app keeps and fetches from. Title, siteUrl, description, ETag/Last-Modified and
  the fetch time still update as before.
- Existing RSS articles are re-keyed once, in place, on the first launch after the update:
  a flagged one-transaction migration (`rekeyRssArticleIdsOnce`, no schema change, Room
  stays at version 4) recomputes each row's id from its own feedId/guid/link/title. Read,
  starred and archived travel with the row. Rows the old scheme had duplicated under
  different URLs collapse to one — the copy the reader acted on (starred, then archived,
  then read, then newest) survives. Gmail newsletters (`gmail:` ids, message-id identity)
  are excluded, which also leaves their `newsletter_bodies` foreign keys untouched.

## 2.7.0 - 2026-09-01

### Fixed

- The decode-tolerance rule 2.6.2 established now covers every server reply, not only
  `GetKeyboardOptions`. `GetToken`, `GetVersion`, `GetPermission` and
  `RequestPermissionComponent` responses all carry defaults; `lightJson` sets
  `coerceInputValues = true` so an enum member from a newer server coerces to
  `Result.Unknown` instead of throwing; and `ensureToken` wraps its decode in the same
  guard `callRemoteServiceMethod` already had, treating a blank token as a failed grant.
  A blank permission component is likewise an error at the call site, not a launch of
  nothing. `GetUserPreferences` joined `allMethods`, which the TODO above it predicted.
- Feeds that publish no dates reshuffled to the top on every refresh. The parse-time
  fallback stamp was rewritten by the unconditional content UPDATE in `storeArticles`, so
  every dateless item re-stamped to the moment of the sync. Parsed articles now carry
  `hasDate`, and a dateless item's refresh goes through an UPDATE that leaves
  `publishedAt` alone. First insert still assigns the fallback, so nothing changes about
  where a new item lands.
- The Subscriptions list still used a hand-tuned 3.6-unit row for the same
  paragraph-over-superfine stack that clipped in the article and Gmail label lists. It now
  uses the measured `stackedRowHeightGridUnits` helper, with the old constant as the floor.
- The version on the Settings screen said 2.4.1. It now reads
  `BuildConfig.VERSION_NAME`, generated from `lighttool.toml`, so it cannot fall behind a
  release again (`buildConfig` is now enabled for the tool module).
- Site sign-in cookies could be lost to a fast DONE or BACK: the persist ran on
  `viewModelScope`, which leaving the screen cancels. It now runs under `NonCancellable`.
- A scanned Google credential carrying its own `redirect_uri` never completed consent: the
  WebView matcher only knew the three built-in prefixes, and `GmailAuth.isRedirect` was
  dead code. The stored redirect is published into the sign-in UI state and matched
  alongside the static prefixes, and `isRedirect` is now the authoritative check before the
  code exchange.
- Google revoking a refresh token (`invalid_grant`) cleared the credentials without
  publishing it, so Settings kept saying SIGNED IN. The state refreshes with the clear.
- A newsletter charset with trailing parameters ("utf-8; boundary=…") fell back to UTF-8;
  the parameter list is now cut before the name is looked up.
- A feed body opening with a UTF-8 BOM or stray whitespace failed SAX with "content is not
  allowed in prolog". The prolog is trimmed before parsing.
- A feed URL with `user:pass@` userinfo authenticated with nothing, because OkHttp never
  sends userinfo. The credentials are split into a Basic `Authorization` header on that
  feed's requests.

## 2.6.2 - 2026-08-27

### Fixed

- Every screen with a text field crashed the tool on a newer LightOS. `GetKeyboardOptions` is
  fetched by all of them, and a newer SDK server omits a null field rather than encoding it,
  which to `kotlinx.serialization` is a missing *required* field on a nullable property with no
  default. The decode threw, out of `callRemoteServiceMethod`, out of the coroutine
  `rememberKeyboardOptions` launched, and into the uncaught handler. `lightJson` now sets
  `explicitNulls = false`, every response field carries a default, and `swipeEnabled` is
  declared for the servers that send it.
- A response that cannot be decoded is now returned as `LightResult.Error` instead of thrown.
  Callers already handle an error and none of them handle an exception, so protocol drift
  degrades a feature rather than ending the process. `CancellationException` still propagates.
- The keyboard-options refresh cannot take a screen down. If the lookup fails the screen keeps
  `defaultKeyboardOptions()`.
- Article rows clipped the source line whenever a title wrapped to two lines. The row height was
  a hand-written 4.75 grid units against a content height nearer 5.3, and it is now measured from
  the paragraph and superfine line heights, so it holds at any screen size. The Gmail label list
  had the same bug against a 3.6-unit row and shares the new helper.

## 2.6.1 - 2026-08-25

### Fixed

- **Clear read articles** deleted the archive. The query exempted starred articles and nothing
  else, while every other delete in the reader also exempted archived rows and reads Gmail had
  not accepted yet. It now carries both, so the screen v2.6.0 added is no longer emptied by a
  settings row that says it keeps your subscriptions.
- **Mark all read** marked archived articles read, which is what made them eligible for that
  delete. It now skips them, matching every list query in the DAO. Gmail's own read state still
  reconciles onto archived issues; it is only the bulk catch-up that leaves them alone. Marking
  a single feed read had the same problem and got the same clause.
- Newsletter iframes were only removed when images were switched off, so with images on — the
  default — an issue could pull an arbitrary remote document into the reader's WebView and
  confirm the open that the tracking-pixel strip is there to deny. `iframe`, `object` and
  `embed` are now removed in both modes.
- Signing out of Gmail left Google's session cookies behind, so the next sign-in silently
  reconnected the account that had just left. Sign-out and "forget client" clear them.

### Changed

- The confirmation copy for both settings rows names what actually survives.
- A `check` workflow compiles and tests every branch and pull request. `main` is the default
  branch again; the `images-and-qr` branch it had drifted to is gone, and with it the chance of
  a commit reaching the default branch with no CI at all.
- Release notes link the repository under its current name.

## 2.6.0 - 2026-08-25

### Added

- An **Archive** screen, reached from the Subscriptions and Mailbox bars, listing every archived
  article. Archiving hid an article from all four lists and nothing could see a hidden row again,
  so an accidental archive was indistinguishable from a delete.
- **RESTORE ALL** in the archive's bar, for undoing a sweep rather than one mis-tap.

### Changed

- The archive action in both readers is a toggle. Archiving still leaves the reader; opening an
  archived article shows **RESTORE** (RSS) or a restore icon (newsletters) in the same place the
  archive action was.
- Archived newsletters are exempt from the per-label trim and from the stale-issue cleanup, the
  way saved ones already were. Hiding an issue no longer puts it in line to be dropped.
- The "all caught up" empty state no longer calls the read pile "the archive", now that the
  archive is a real screen.

### Changed

- Prepared repository documentation, privacy and security disclosures, screenshots, and trusted CI for public release.

## 2.5.0 - 2026-08-05

### Changed

- Release builds use R8 full mode. Keep rules were added for Room's generated database and DAO
  implementations, which are loaded by name, and for jsoup's compile-only nullability
  annotations, which R8 treats as a fatal missing reference. Stack traces keep their line
  numbers.

### Not done

- The move onto `com.gios:light-common` was not possible. The Light SDK plugin's dependency
  allowlist does not include it, and the library's wheel bus expects an activity to feed it,
  which a tool does not have.
- The LightSync backup provider was not possible. A tool's manifest is generated from
  `lighttool.toml` and cannot declare a `ContentProvider`, and the sandbox blocks the imports
  one would need.

## 1.12.0 - 2026-07-29

### Added

- The brightness wheel scrolls. Article lists, the subscription list, the article, the reader page
  and Settings all move a notch at a time, and the sign-in WebView does too. Notches are paid out
  over the following frames rather than applied on arrival, so a fast spin reads as one sweep, and
  the first notch after a pause is held back until a second one confirms it, so a thumb resting on
  the wheel no longer moves the page.
- The version shown in Settings was still 1.10.0. It now matches the build.

### Changed

- `LightHardwareKeys` in the vendored SDK hands a tool first refusal on a hardware key, from
  `LightActivity.dispatchKeyEvent`. A tool owns no window and the build rules keep it away from the
  activity and `LocalView`, so there was no way for one to see the wheel at all.

## 1.10.0 - 2026-07-28

### Fixed

- Markup no longer leaks into article text. Feeds that escape their HTML twice only reveal their
  tags once entities are decoded, and the old order of operations decoded last, so `<p>` and stray
  attributes were being printed. Tags are now stripped again after decoding.
- Angle brackets that are not markup survive: `5 < 10` and `if (a < b)` read as written, because a
  tag has to open with a letter, a slash, or a markup declaration.
- Comments are dropped even when they contain a `>`, and a tag left unterminated by a truncated
  feed no longer prints as text.
- Posts that begin with an ellipsis — The Verge does this on every item — no longer start with
  three dots. A single leading period, as in `.38 calibre`, is left alone.
- The launcher icon dropped its adaptive layer. Tools that read an icon out of an installed
  package, Obtainium among them, can hand back nothing when the icon resolves to an
  `AdaptiveIconDrawable` instead of a bitmap; plain mipmaps avoid the question.

## 1.9.0 - 2026-07-28

### Added

- A launcher icon, in the same style as the other Light tools: a white letter on black, here an R
  for RSS. Shipped as legacy mipmaps for every density plus an adaptive icon, with a black
  background colour and the letter as the foreground layer.
- `scripts/generate_icon.py` draws it, so the letter or face can be changed in one command. It
  sizes the glyph by its own bounding box rather than its line height, which is what keeps it
  optically centred.

### Changed

- The generated manifest points `android:icon` and `android:roundIcon` at `@mipmap/ic_launcher`,
  so a tool that ships those resources gets its icon on the launcher.

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
