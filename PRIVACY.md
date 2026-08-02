# Privacy

Light RSS is designed to read feeds without an account, advertising, or an application-operated backend.

## Data stored on the phone

The local Room database contains:

- feed URLs, titles, descriptions, refresh metadata, and errors;
- article titles, authors, links, publication dates, summaries, feed-provided text, and the image addresses a feed supplied;
- read, saved, and archived state;
- a flag recording whether the starter subscriptions were created, and whether images are switched on.

Downloaded images are stored as JPEG files in the app's private `images` folder, capped at 24 MB, and are never uploaded anywhere.

This data stays in the app's private Android storage. Light RSS does not upload or synchronize it to a Light RSS service.

## Network requests

Light RSS connects directly to the websites and feed URLs you follow. Those third parties can receive your IP address, request time, requested URL, and the `LightRSS/1.1 (Light Phone III)` user agent. Their own privacy policies apply.

On first launch, the app creates subscriptions for NASA, BBC World, and Hacker News and attempts to refresh them. You can unfollow any of them.

When you enter a normal website address, the app downloads that page once to look for an RSS or Atom discovery link, then requests the discovered feed. Later refreshes may send standard `If-None-Match` and `If-Modified-Since` headers.

## Content handling

Feed-provided HTML is converted to text and to an ordered list of text and image blocks. The app does not render a WebView, scripts, or advertisements, and it renders pages fetched by OPEN as text rather than executing them. A feed host still sees the direct feed request itself.

### Opening a linked page

**OPEN** on an article downloads that article's page directly from its publisher, which is an
ordinary HTTP request carrying your IP address, request time, requested URL, and the app user
agent. Redirects are followed, and the page's canonical address is fetched once more when it
differs, so the publisher named in the link is not always the only host contacted. A site that
rejects the `LightRSS/1.1` user agent with 401, 403, 406, 429, or 451 is retried once with a
desktop browser user agent; feed refreshes never do this. It happens only when you press OPEN, never in the background. The page is parsed into text
and images on the phone; scripts, trackers, and page furniture are discarded rather than executed,
and nothing is stored beyond a per-session cache that is dropped when the app closes.

### Signing in

**SIGN IN** in reader mode opens that one page in a WebView inside the app. This is the only
WebView in Light RSS and the only place a page's own scripts, cookies, and local storage are
allowed to run, which is unavoidable: a bot check cannot be cleared without executing it, and a
login cannot be completed without one.

What is kept when you leave that screen:

- the cookies the site set, stored in the local database under the site's host;
- the user agent string the WebView used, because the cookie is usually only honoured alongside it.

Both are then sent on later reader-mode fetches of that host, and on nothing else. Feed refreshes
never send them. Uninstalling the app removes them along with the rest of its data.

### Images

When images are on, Light RSS downloads the pictures a feed references. This is a direct request to whichever host serves the image, which is often a CDN rather than the feed publisher, and that host receives your IP address, request time, requested URL, and the app user agent.

- Images are requested only for articles that are actually on screen: a thumbnail is fetched when a row scrolls into the list, and full-size images are fetched when you open the article. Articles you never scroll past cause no image requests.
- Images with 1 × 1 dimensions, `data:` URIs, and addresses matching known tracking-beacon patterns are dropped during parsing and never requested.
- **Settings → IMAGES OFF** stops all image downloads and deletes the cache. **CLEAR IMAGE CACHE** deletes the downloaded files while leaving images enabled.
- With images off, the app behaves exactly as version 1.0.0 did: text only, no image hosts contacted.

## Controls and deletion

- Unfollowing a feed deletes that subscription and its locally stored articles.
- **Clear read articles** deletes read articles that are not saved.
- **Clear image cache** deletes every downloaded image; article text is untouched.
- Uninstalling Light RSS deletes the app database through Android's normal app-data removal.

Saved articles are intentionally retained by the cleanup action until you unsave them, unfollow their feed, or uninstall the app.

## Permissions

Light RSS requests `android.permission.INTERNET` and `android.permission.CAMERA` in `tool/lighttool.toml`, and its RSS code does not access contacts, location, the microphone, files, or notifications.

The camera is used for one purpose: the **Add feed → Scan QR code** screen, which reads a feed address from a QR code so you do not have to type it. The camera runs only while that screen is open, the preview is never recorded, and no image from the camera is stored or transmitted. Runtime permission is requested by the SDK scanner the first time you open that screen; declining it leaves every other part of the app working.

The final Android manifest is a merge of Light RSS and the bundled Light SDK dependency graph. In SDK version 0.0.12, that merged manifest also contains `CAMERA`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, and `VIBRATE`, plus an app-specific signature permission for safe dynamic receivers. Light RSS calls the SDK camera/QR helper on the scan screen only, and does not enable remote push notifications, schedule background jobs, or vibrate the phone. The inherited declarations and components remain in the APK for SDK compatibility.

The Light SDK UI module brings Google ML Kit barcode-scanning and data-transport components into the APK for its QR scanner, which Light RSS invokes on the scan screen. Google states that, when ML Kit APIs are used, ML Kit can contact Google for updates and sends device, app, performance, and utilization metrics for diagnostics and usage analytics. Release maintainers should therefore treat Google diagnostic collection as possible and make any required store or jurisdiction-specific disclosures. See Google's [ML Kit terms and privacy information](https://developers.google.com/ml-kit/terms) and [Android data disclosure guide](https://developers.google.com/ml-kit/android-data-disclosure).

The generated manifest is the source of truth for a particular build. Review `tool/build/intermediates/merged_manifests/` again whenever the SDK version changes.

## Changes

Privacy-impacting changes should update this document and the changelog in the same pull request.
