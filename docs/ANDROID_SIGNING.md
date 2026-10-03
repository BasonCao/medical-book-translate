# Android stable signing

## Permanent signing identity

The project now has one permanent release signing identity. Every production APK must be signed with this exact certificate:

- Alias: `medbook-release`
- Certificate SHA-256: `BA:AC:5E:53:80:BA:5F:9B:5C:B1:E2:81:76:62:CC:D5:76:C5:F3:40:35:C1:C1:14:73:36:55:39:CC:0B:10:F4`
- Certificate validity: 2026-10-03 through 2126-09-09
- Keystore SHA-256: `48c72d2f12eb49041afb60cf5d64281a857109ef89ccc028883372ce94b7e74f`

The private keystore is intentionally NOT stored in this public repository.

## One-time GitHub Actions configuration

Configure these four repository secrets:

- `ANDROID_KEYSTORE_BASE64` — base64 contents of the permanent `.jks` file.
- `ANDROID_KEYSTORE_PASSWORD` — keystore password.
- `ANDROID_KEY_ALIAS` — must be `medbook-release`.
- `ANDROID_KEY_PASSWORD` — private key password.

The workflow verifies the certificate fingerprint before building. If the keystore is missing, incomplete, or replaced by another key, the release build fails instead of producing an incompatible APK.

## Upgrade policy

1. Keep `applicationId` exactly `com.aitiniubi.medicalbooktranslator`.
2. Keep the permanent keystore and alias unchanged forever.
3. Every new release must use a strictly higher `versionCode`.
4. Never build production releases with a GitHub runner's generated debug key.
5. Never commit the private keystore or passwords to Git.
6. The stable release workflow verifies the final APK certificate before uploading it.

The old v1.11.x/v1.12.0 debug APKs were signed by different transient debug certificates. Those already-installed builds require one uninstall/reinstall to move onto the permanent stable signing identity. Once the stable APK is installed, future stable releases with higher `versionCode` will update directly without uninstalling.

## Local files generated for the owner

The permanent keystore and its credentials were generated separately from the public repository. Keep them in secure offline backup in at least two locations. Loss of the permanent private key would prevent future in-place Android updates.
