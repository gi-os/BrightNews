# Installing Light RSS

Light RSS is not in Light's Tool Library. It is a community build you install yourself, either
once over `adb` or continuously through [Obtainium](https://github.com/ImranR98/Obtainium).

Every release is an APK attached to a [GitHub Release](https://github.com/gi-os/LightRSS/releases),
built by CI from a tagged commit and signed with a personal sideload key.

## Before you start

- Developer options and USB debugging enabled on the Light Phone III.
- For Obtainium: Obtainium installed on the phone, and "install unknown apps" allowed for it.

## Option A — adb, once

```bash
adb devices                                # confirm the phone is listed
adb install -r LightRSS-1.10.0.apk         # -r replaces an existing install
```

Verify what you are installing first:

```bash
sha256sum -c LightRSS-1.10.0.apk.sha256
apksigner verify --print-certs LightRSS-1.10.0.apk
```

The signing certificate should report this SHA-256 fingerprint on every release:

```
C6:90:2A:A1:87:0B:4F:FA:2F:D0:CD:62:76:43:DC:8D:DE:E7:CC:0F:DB:D1:75:2F:EB:B3:D9:11:09:2D:8E:C5
```

If that fingerprint ever changes, the APK was not built from this repository's key — do not install it.

## If the scanner cannot open the camera

The scan screen asks for the camera the first time you open it and re-checks when you come back
from the permission dialog, so it should just work. If LightOS refuses to hand out the permission
on your device, grant it directly:

```bash
adb shell pm grant com.lightrss.reader android.permission.CAMERA
```

Every other screen works without the camera; only scan-to-subscribe needs it.

## Making a code to scan

[gi-os.github.io/LightRSS](https://gi-os.github.io/LightRSS) turns a feed or website address into
a QR code in the browser. Open it on a laptop, paste the address, and scan the screen with
**Subscriptions → + → Scan QR code**.

## Option B — Obtainium, with automatic updates

1. Open Obtainium and tap **Add App**.
2. Paste the repository URL:

   ```
   https://github.com/gi-os/LightRSS
   ```

3. Source is detected as GitHub. The defaults are fine; two settings worth setting:
   - **Filter APKs by regular expression**: `LightRSS-.*\.apk` — skips the `.sha256` file.
   - **Include prereleases**: off, unless you want test builds.
4. Tap **Add**, then **Install**.

Obtainium then checks the releases feed on its own schedule and offers the new APK whenever a
tag is published. Because the signing key never changes, updates install over the existing app
and your subscriptions, saved articles, and images are preserved.

Obtainium is the same route the Light Phone III community uses for other sideloaded tools such as
the [Luma launcher](https://github.com/vandamd/Luma).

## Cutting a release

CI builds on tags only. `versionCode` must increase every time or Android refuses the update.

1. Bump `versionCode` and `versionName` in `tool/lighttool.toml`.
2. Add the release to `CHANGELOG.md`.
3. Commit, then tag with the same version and push:

   ```bash
   git tag v1.2.1
   git push origin main v1.2.1
   ```

The **Release APK** workflow runs tests and lint, refuses to continue if the tag and
`versionName` disagree, builds `:tool:assembleRelease`, signs it, and publishes the APK plus its
checksum to a GitHub Release.

## Repository secrets the build needs

| Secret | Purpose |
| ------ | ------- |
| `GH_PACKAGES_USER` | GitHub username for the SDK's Light Keyboard package |
| `GH_PACKAGES_TOKEN` | Token with package read access for the same |
| `LIGHTRSS_KEYSTORE_BASE64` | Base64 of the release keystore |
| `LIGHTRSS_KEYSTORE_PASSWORD` | Keystore and key password |
| `LIGHTRSS_KEY_ALIAS` | Key alias inside the keystore (`lightrss`) |

Keep a copy of the keystore file and its password somewhere safe and offline. Losing it means no
future build can update an installed copy of the app — every user would have to uninstall and
reinstall, losing their subscriptions.

Local builds need neither the keystore nor the release secrets: without
`LIGHTRSS_KEYSTORE_FILE` in the environment, `./gradlew :tool:assembleRelease` falls back to the
SDK development key.

## Notes

- This APK is not signed or reviewed by Light. Official distribution goes through Light's own
  process, which signs a build produced by `builder/`.
- The app requests `INTERNET` and `CAMERA`. The camera is only used by the scan-to-subscribe
  screen. See [PRIVACY.md](PRIVACY.md).
