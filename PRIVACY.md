# Privacy

Light RSS is designed to read feeds without an account, advertising, or an application-operated backend.

## Data stored on the phone

The local Room database contains:

- feed URLs, titles, descriptions, refresh metadata, and errors;
- article titles, authors, links, publication dates, summaries, and feed-provided text;
- read, saved, and archived state;
- a flag recording whether the starter subscriptions were created.

This data stays in the app's private Android storage. Light RSS does not upload or synchronize it to a Light RSS service.

## Network requests

Light RSS connects directly to the websites and feed URLs you follow. Those third parties can receive your IP address, request time, requested URL, and the `LightRSS/1.0 (Light Phone III)` user agent. Their own privacy policies apply.

On first launch, the app creates subscriptions for NASA, BBC World, and Hacker News and attempts to refresh them. You can unfollow any of them.

When you enter a normal website address, the app downloads that page once to look for an RSS or Atom discovery link, then requests the discovered feed. Later refreshes may send standard `If-None-Match` and `If-Modified-Since` headers.

## Content handling

Feed-provided HTML is converted to text. The app does not render a WebView, embedded images, scripts, advertisements, or tracking pixels, and it does not open external article pages. A feed host still sees the direct feed request itself.

## Controls and deletion

- Unfollowing a feed deletes that subscription and its locally stored articles.
- **Clear read articles** deletes read articles that are not saved.
- Uninstalling Light RSS deletes the app database through Android's normal app-data removal.

Saved articles are intentionally retained by the cleanup action until you unsave them, unfollow their feed, or uninstall the app.

## Permissions

Light RSS requests only `android.permission.INTERNET` in `tool/lighttool.toml`, and its RSS code does not access contacts, location, the microphone, files, or notifications.

The final Android manifest is a merge of Light RSS and the bundled Light SDK dependency graph. In SDK version 0.0.12, that merged manifest also contains `CAMERA`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, and `VIBRATE`, plus an app-specific signature permission for safe dynamic receivers. Light RSS does not call the SDK camera/QR helper, request runtime camera access, enable remote push notifications, schedule background jobs, or vibrate the phone. The inherited declarations and components remain in the APK for SDK compatibility.

The Light SDK UI module also brings Google ML Kit barcode-scanning and data-transport components into the APK for its optional QR scanner. Light RSS does not invoke that scanner. Google states that, when ML Kit APIs are used, ML Kit can contact Google for updates and sends device, app, performance, and utilization metrics for diagnostics and usage analytics. Release maintainers should therefore treat Google diagnostic collection as possible and make any required store or jurisdiction-specific disclosures. See Google's [ML Kit terms and privacy information](https://developers.google.com/ml-kit/terms) and [Android data disclosure guide](https://developers.google.com/ml-kit/android-data-disclosure).

The generated manifest is the source of truth for a particular build. Review `tool/build/intermediates/merged_manifests/` again whenever the SDK version changes.

## Changes

Privacy-impacting changes should update this document and the changelog in the same pull request.
