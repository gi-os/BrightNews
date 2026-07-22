# Contributing to Light RSS

Thanks for helping make Light RSS better. This repository is an unofficial community project built on the Light SDK.

## Before you start

- Search the repository's existing issues before opening a duplicate.
- Keep changes focused on the RSS reader. SDK framework changes usually belong in the upstream [Light SDK](https://github.com/lightphone/light-sdk).
- For a larger feature or architecture change, open an issue first so the behavior and LightOS fit can be discussed.
- Never put tokens, local property files, signing keys, databases, logs, or personal feed exports in an issue or pull request.
- Follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report vulnerabilities through [SECURITY.md](SECURITY.md).

## Local setup

Use JDK 17 and Android SDK Platform 36. The Light Keyboard package also requires GitHub package-read credentials through `GH_PACKAGES_USER` and `GH_PACKAGES_TOKEN`, or the ignored `local.properties` keys documented in the [README](README.md#build).

Run the app checks before submitting a change:

```bash
./gradlew :tool:testDebugUnitTest :tool:lintDebug :tool:assembleDebug
```

If you changed navigation, layout, input, theming, or list behavior, also run the app in the 1080 × 1240 LightOS emulator and exercise both the visible back button and the system back action.

## Pull requests

Keep pull requests small enough to review. Include:

- what changed and why;
- how it was tested;
- screenshots for visible UI changes;
- any privacy, permission, network, storage, or migration impact.

Do not commit generated APKs or build output. Do not modify `AndroidManifest.xml`; the Light SDK generates it from `tool/lighttool.toml`.

The repository's trusted build workflow runs only after code lands on `main` because Gradle needs a package-registry credential. Pull requests from forks must be tested locally and are never executed through a privileged pull-request trigger with maintainer secrets.

## Design expectations

Light RSS should remain quiet, compact, text-first, and usable without a touchscreen-first Android visual language. Prefer SDK primitives and tokens, keep primary actions in LightOS bars, avoid unnecessary animation, and preserve one-screen-at-a-time back navigation.
