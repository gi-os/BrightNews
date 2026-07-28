# Changelog

Notable changes to Light RSS are recorded here. This project follows [Semantic Versioning](https://semver.org/).

## Unreleased

### Changed

- Prepared repository documentation, privacy and security disclosures, screenshots, and trusted CI for public release.

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
