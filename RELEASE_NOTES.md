# News 3.6.1

**3.6.0 crashed on every launch. This is the fix, plus three smaller ones that were sitting on `main` behind it.**

The 3.6.0 release moved reporting onto `light-common`. A same-day commit followed the install
call with `check(reporting)` — an assertion that the reporting chip had armed synchronously.
It usually hadn't: `LightReport.install` returns before its flag flips whenever there is no
network, the token is rejected, or the install is simply racing first composition. The
assertion threw, the home screen never composed, and the app died before the crash chip it
was asserting on could catch anything — which is why no report was ever filed for it.
Reporting is a best-effort side channel; it is now attempted inside `runCatching` and the
result ignored. News opens whether or not the chip armed.

Also in this release, all fixed on September 6 and never cut:

- **Refresh ran on every open.** The unforced refresh fired whenever any feed was 15 minutes
  stale, which is always. It is now a once-a-day 8 AM local check-in, tracked in a
  `last_auto_refresh_at` metadata row. Pull to refresh is unchanged.
- **Clocks stuck in the old timezone.** Every clock read `ZoneId.systemDefault()`, which the
  JVM caches and Android only invalidates through a manifest receiver a Light SDK tool
  cannot register. `currentZone()` clears the cache before each read; every call site uses it.
- **Pull-to-refresh was a hair trigger.** The briefing and timeline set `edgeNotches = 2`
  against the app-wide 3. Dropped the override.

No schema change. Installs over 3.6.0 and keeps everything.
