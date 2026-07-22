# Security Policy

## Supported version

Security fixes are made against the latest source on `main`. Older APKs and development builds are not supported release channels.

## Reporting a vulnerability

Do not disclose a vulnerability, token, private feed URL, database, or exploit details in a public issue.

Use GitHub Private Vulnerability Reporting for this repository when it is enabled. If no private reporting channel is visible, open a minimal public issue asking a maintainer to establish private contact; include no sensitive technical details in that issue.

Please provide the affected commit or version, impact, reproduction conditions, and a proposed fix if you have one. Maintainers should acknowledge a complete report promptly, keep the reporter informed, and coordinate disclosure after a fix is available.

## Signing and credentials

The tracked `sdk/keys/lightsdk-dev.jks` file is the Light SDK's deliberately public development key. APKs signed with it are development artifacts and must not be represented as production releases. The official builder removes local signing configuration and produces an unsigned artifact for the separate Light signing process.

Real credentials belong only in environment variables, GitHub encrypted secrets, or the ignored `local.properties` file. Never commit private signing material or force-add `sdk/emulator/keys/platform.jks`.
