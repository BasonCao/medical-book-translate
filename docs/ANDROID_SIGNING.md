# Android stable signing

The debug APK is intentionally not used for upgrade continuity because GitHub-hosted runners can produce different debug certificates.

Configure these GitHub Actions repository secrets before building the stable release:

- `ANDROID_KEYSTORE_BASE64` — base64 of the permanent `.jks`/`.keystore` file.
- `ANDROID_KEYSTORE_PASSWORD` — keystore password.
- `ANDROID_KEY_ALIAS` — signing key alias.
- `ANDROID_KEY_PASSWORD` — signing key password.

After the first stable build, keep the same keystore permanently. Never commit the keystore or passwords to the repository.

Important: APKs already installed from previous GitHub Actions debug builds cannot be upgraded to the new stable-signing certificate. They require one uninstall/reinstall. After that, future stable releases with increasing `versionCode` will update normally.