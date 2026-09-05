<img src="docs/icon.png" alt="" width="72" align="left" />

# News

A daily briefing for the Light Phone III: today from BrightNotebook, then Kagi News a category
at a time; and a timeline of every RSS feed and Gmail newsletter you follow, read in one reader. Built on the
Light SDK; tool id `com.lightrss.reader`. Feeds, labels, read state, saved items, images and
search stay on the phone. Current release: **v3.5.0** (`tool/lighttool.toml`: versionCode 41).

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightNews.png" alt="Scan to open BrightNews in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightNews there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[brightmarket.gzl.dev](https://brightmarket.gzl.dev)**.

Formerly **LightRSS**, and before that a fork of
**[zachattack323/LightRSS](https://github.com/zachattack323/LightRSS)** on
`lightphone/light-sdk`'s tool scaffold. This fork added article/list images, subscribing by
QR code instead of typing a URL on the phone keyboard, an in-app reader for the page an
article links to (with session-carrying sign-in for sites that gate content), and
hardware-wheel scrolling. **v2.0.0 absorbed [LightNews](https://github.com/gi-os/LightNews)**,
which was a separate newsletter app; that repo is retired. Work happens on the
`images-and-qr` branch, **which is this repo's default branch** — see
[Branches and CI](#branches-and-ci-read-this-before-you-push), because the trigger layout
here does not match the rest of the portfolio.

> ### The merge, in one paragraph
>
> **A Gmail label is a feed.** A subscribed label is a `FeedEntity` with `sourceType =
> "GMAIL"`, and each of its messages is an `ArticleEntity` like any other. That is the whole
> design: the home list, search, saved items, read state, the article cache and the trim
> policy are the ones that were already there, and the second source cost a sync
> (`NewsletterSync`) and a renderer (`NewsletterHtml`) rather than a second app. Only
> `RssRepository.refreshFeedInternal` branches on where an article came from.
>
> Three things changed shape on the way in, and each is worth knowing about:
>
> - **Sign-in moved into the app.** LightNews opened Google's consent page in the system
>   browser and waited for a custom-scheme redirect through an intent filter on its
>   `MainActivity`. A Light SDK tool ships no manifest and owns no activity, so there is
>   nothing for the OS to hand a redirect back to. `GmailSignInScreen` loads the consent page
>   in a WebView the tool owns and catches the redirect in `shouldOverrideUrlLoading` before
>   it is ever fetched — so it needs no browser, no intent filter and no resolvable address,
>   and the authorization code never leaves the process. This removes the single largest
>   unknown in LightNews's release notes.
> - **Newsletter bodies became a table.** LightNews wrote them to `filesDir`, which cost it a
>   staging-write dance, an orphan sweep and a `hasBody` check on every sync.
>   `newsletter_bodies` is a child table with `ON DELETE CASCADE`: the body cannot outlive its
>   article and cannot go missing while the row survives, and all three mechanisms are gone.
> - **Background sync went away.** LightNews polled hourly through WorkManager. Newsletters
>   now refresh with everything else, when you open the app or pull the refresh button, which
>   is what the RSS side always did.
>
> Read state still round-trips to Gmail. Opening an issue clears `UNREAD` on the server, and
> `ArticleEntity.pendingRead` is what makes that honest offline — the flag flips locally
> first, the push happens on the next sync, and a row carrying an unpushed read is excluded
> from both directions of reconciliation so a stale server answer cannot erase it.

## Newsletters: setting it up

Nothing outside a label you explicitly follow is ever read. The app asks for `gmail.modify`,
which is a restricted scope, because clearing `UNREAD` is a write — point it at an account
you don't mind it touching first.

1. In Gmail, filter your newsletters into a label. `LightNewsletter` is a reasonable name.
2. In Google Cloud, create a **Desktop app** OAuth client and enable the Gmail API. Leave the
   consent screen in **Testing** and add your address under *Test users*. Publishing it to
   production is the wrong move here and worth understanding: `gmail.modify` is a *restricted*
   scope, and an unverified app in production is blocked from requesting one outright —
   Google answers the consent screen with a flat `Access blocked … invalid_request`. Testing
   works, at the price of a grant that expires after seven days. The app treats the resulting
   `invalid_grant` as "ask for consent again" rather than retrying forever, so the cost is
   re-running step 4 weekly until you submit the app for verification.
3. On the phone: **News → Newsletters → list button → Add client ID.** Type it, paste it, or
   scan it as a QR code off another screen.
4. **Sign in**, then **+** to pick the label.

Or skip the phone entirely: run `scripts/authorize.py` on a computer, and scan the QR code it
prints. That carries a refresh token straight in and never opens a consent screen on the
device.

Reading an issue, the top and bottom bars slide away once you are scrolling forwards — seven
grid units of a twelve-unit panel, given back to the document. They return near the top of the
issue and on any deliberate scroll up, which matters because the back button lives up there.

Two render modes, one keypress apart in the reader. **DARK** forces white-on-black and
unwraps the layout tables so the copy reflows to the panel — the better read, but it hides
dark logos drawn on transparent backgrounds. **PAPER** keeps the newsletter's own design and
only fixes the width, which is what brand-heavy issues want; a panel showing black on white is
the closest thing to the paper these were designed for. Sponsor blocks are cut by default
and a marker is left where each one was, so a wrong guess is visible rather than silent.

## Kagi News

[Kagi News](https://news.kagi.com) publishes its edition as public JSON — `kite.json` lists
the categories, and each category file holds a dozen stories synthesised from dozens of
sources. No key, no account. **News → Kagi News tab → list button → +** to follow a category;
the picker shelves the ~190 of them as Kagi's general categories, places folded under a parent,
and topics. Each category is read on its own, in Kagi's ranking, and NEXT moves to the next
one. A story's sources open in the reader. Kagi refreshes about once a day; the app asks
conditionally, so nothing is downloaded when nothing changed. See `Kagi.kt`.

## Full articles

A feed usually sends a paragraph. On first open the reader fetches the linked page, reduces it
to its body copy with `ReaderExtractor`, and stores the result on the article, so it reads whole
now and offline later; refreshes fetch the newest unread articles ahead of time. Paywalls and
bot checks fall back to the feed's text. **Settings → FULL ARTICLES** turns it off.

## Colour

The Light Phone III's panel is a full-colour AMOLED. Its black-and-white look is Android's
accessibility daltonizer pinned to simulate-monochromacy — a SurfaceFlinger colour matrix over
everything on screen — and LightOS lifts it itself for photos and video. News does the same while
a picture is on screen, and puts it straight back when you leave the article or the app.

It needs one grant, once per install, because `WRITE_SECURE_SETTINGS` is
`signature|privileged|development`:

```sh
adb shell pm grant com.lightrss.reader android.permission.WRITE_SECURE_SETTINGS
```

Without it every call quietly does nothing and pictures stay grey — the `SecurityException` is
swallowed, so the app degrades rather than breaks. **Settings → COLOUR** turns it off if you would
rather the phone never changed. Colour is only held where there is something to see in it: an
article with images, an extracted page with images, an HTML newsletter. A text-only article never
touches the setting.

The mechanism is a straight port of `ColorMode` from [LightCamera](https://github.com/gi-os/Roll),
itself a port of LightChat's — the reference counting and foreground handling are load-bearing and
were arrived at the hard way. Two things differ because this is an SDK tool rather than an app: the
`android.content.Context` import and the `contentResolver` access are blocked by the SDK build
policy and are marked exempt line by line rather than by deleting the rules (`grep -rn
'light-sdk-allow' tool/src` finds all four), and there is no `Application` to hook, so the
foreground handling comes off the screen's own lifecycle instead.

## Quick start

```sh
git clone https://github.com/gi-os/BrightNews.git
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
adb install -r LightRSS-<version>.apk   # from github.com/gi-os/BrightNews/releases
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
  [gi-os.github.io/BrightNews](https://gi-os.github.io/BrightNews) generates a matching code
  from any feed or site address, client-side.
- **Images** (this fork's main addition). A thumbnail per article row and full-width
  images in the reader, downsampled to the width the panel needs and shown **in colour** — see
  [Colour](#colour). Pulled from
  `enclosure`, `media:content`, `media:thumbnail`, `itunes:image` and inline `<img>`
  markup; 1x1 tracking beacons, `data:` URIs and known tracking hosts are dropped.
  Lazy, on-screen-row-only downloads into an 8 MB memory cache / 24 MB disk cache. A
  Settings switch disables the whole feature.
- **Archiving is not deleting.** **ARCHIVE** takes an article out of every list; the
  **Archive** button in the Subscriptions and Mailbox bars still shows it. Open one and the
  reader's own row reads **RESTORE**, or use **RESTORE ALL** to undo a sweep. Archived items
  are also exempt from the newsletter trim, the way saved ones are, so hiding an issue is never
  what loses it.
- **Shake to report.** Rattle the phone — there and back, twice — and a screen asks what went
  wrong: closed, froze, looks off, slow, other, plus an optional note. It files to the private
  `gi-os/light-reports` tracker with the build details, the screen you were on and the last crash
  attached, queued on disk first so a report made offline goes out on the next launch. The
  accelerometer is on only while News is showing. The gesture arithmetic is `report/ShakeGesture`,
  free of Android imports so `ShakeGestureTest` can pin it on the JVM.
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
| v3.5.0  | —         | Shake to report a bug, filed to light-reports; a folder for the archive instead of the delete glyph; Add moved into the Subscriptions bottom bar |
| v3.4.0  | —         | Crash catcher that files to light-reports; no header, everything in the bottom bar, pull to refresh; today's dozen only, most-read categories first, position kept across the reader |
| v3.3.1  | —         | Launch crash with the timeline open: a future-dated article produced a duplicate bucket header |
| v3.3.0  | —         | Settings → HOME switches between the daily briefing and a plain RSS reader |
| v3.2.0  | —         | Edition time under the bar and a morning refresh; topics on story rows; older timeline buckets fold to a count; a sun for the briefing tab |
| v3.1.0  | —         | Briefing sections fold to three with SHOW MORE; natural-height timeline rows; the reader slides between articles and runs into an UP NEXT landing zone |
| v3.0.0  | —         | Daily Briefing (notebook day + Kagi edition) and Timeline (RSS + newsletters in time buckets) replace the three source tabs; `n of m` in the Kagi reader; Notebook provider bridge |
| v2.11.0 | —         | Wheel scrolls on the first notch and stops jumping back at the end; rows use the full width; the scroll bar fades when still; bars hide on down and return only after half a second of up |
| v2.10.0 | —         | Room to read: taller rows on hairlines, wider margins, 1.5 leading in articles, one line of actions. Wheel: acceleration, row snap, lone clicks kept, turn past the end for the next article or category |
| v2.9.0  | —         | Kagi News as a third section — categories are feeds, stories carry their highlights, perspectives, timeline and sources; a shelved picker for the 190 categories. RSS articles fetch their whole page on open and ahead of time |
| v2.8.0  | —         | An article's identity belongs to the feed, not to whichever mirror answered |
| v2.7.0  | —         | Every server reply tolerates drift, not just the keyboard's |
| v2.6.2  | —         | Opening anything with a text field no longer crashes the tool on a newer LightOS, and a two-line headline stops clipping the feed name under it |
| v2.6.0  | —         | The archive is a place you can look. Archived articles are listed, restorable one at a time or all at once, and no longer trimmed away |
| v2.3.0  | —         | Pictures show in colour on a phone that stays grey, via the daltonizer and a one-time adb grant |
| v2.2.0  | —         | Images keep their colour. The app no longer flattens them; the phone's own daltonizer does that, and can be switched off |
| v2.1.0  | —         | The newsletter reader's bars get out of the way while you read forwards, and come back on any scroll up |
| v2.0.1  | —         | A stored OAuth client can be seen, replaced and removed; signing out now takes the cached issues with it |
| v2.0.0  | `29e3685` | Renamed to News. Absorbed LightNews: Gmail labels are feeds, newsletters read in the app's own reader, sign-in moved into an in-app WebView, bodies moved from files to a cascading table, hourly WorkManager polling dropped |
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

<!-- bright-footer:begin -->
---

## Bright\*

**It's not Light, it's Bright.**

26 open-source apps for the **Light Phone III** — camera, music, maps, messages,
reading, transit, games. The phone has no app store, so they install by sideload: scan one
code from **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)** and BrightMarket keeps them updated.

[Roll](https://github.com/gi-os/Roll) · [BrightNotebook](https://github.com/gi-os/BrightNotebook) · [BrightControl](https://github.com/gi-os/BrightControl) · [BrightWay](https://github.com/gi-os/BrightWay) · [BrightChat](https://github.com/gi-os/BrightChat) · [browse all 26 →](https://brightmarket.gzl.dev)

The Light Phone does not sponsor or endorse any of these. Built by
[Giovanni Lupo](https://github.com/gi-os) — if this one is useful to you, a ⭐ helps the next
person find it.
<!-- bright-footer:end -->
