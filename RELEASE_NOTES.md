# News 3.6.0

**Reporting is the shared chip now.** News files its issues through `light-common`, the same
library every other Bright* app uses, instead of its own copy of the shake screen and its own
crash catcher. Shake the phone and a small chip appears in the bottom-right corner — SEND
FEEDBACK? — a tap opens the sheet, and ignoring it lets it fade. A crash last run raises the
same chip once on the next launch with the trace attached. A failure the app notices itself
does too. Settings has a SEND FEEDBACK row for when there is nothing to shake about. Reports
carry the install id, the screen you were on, a screenshot taken at the shake, and go out from
disk, so one filed with no signal is sent on the next launch.

Behind it: the SDK tool policy now allows `com.gios:light-common` — Compose, OkHttp and the
lifecycle runtime, all already permitted — and the accelerometer and crash handler live inside
the library rather than in tool source.

No schema change. Installs over 3.5.0 and keeps everything.
