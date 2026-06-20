# Readylytics Release Signing

## Purpose

Readylytics release artifacts must be signed with Play upload key material that stays outside repository. Release builds fail closed if required signing inputs are missing. Debug builds remain unsigned or debug-signed as usual.

## Required GitHub Environment

Use protected GitHub environment `production-release` for `.github/workflows/release.yml`.

- Require manual approvers before job starts.
- Restrict which branches or tags can target environment. Recommended: version tags matching `v*`.
- Keep signing secrets scoped only to this environment.

## Required Secrets

Configure these environment secrets in GitHub:

- `READYLYTICS_UPLOAD_STORE_BASE64`: base64-encoded upload keystore bytes.
- `READYLYTICS_UPLOAD_STORE_PASSWORD`: keystore password.
- `READYLYTICS_UPLOAD_KEY_ALIAS`: upload key alias.
- `READYLYTICS_UPLOAD_KEY_PASSWORD`: upload key password.
- `READYLYTICS_UPLOAD_CERT_SHA256`: expected SHA-256 certificate fingerprint for signed artifact verification.

`release.yml` decodes keystore into runner temp directory, exports `READYLYTICS_UPLOAD_STORE_FILE`, runs release build, verifies `jarsigner`, compares artifact certificate fingerprint, uploads signed AAB, then deletes temp keystore in `always()` cleanup.

## Local Release Verification

Use disposable key material outside repository for local verification only.

1. Create temporary keystore in temp directory, not in repo checkout.
2. Export required environment variables:
   - `READYLYTICS_UPLOAD_STORE_FILE`
   - `READYLYTICS_UPLOAD_STORE_PASSWORD`
   - `READYLYTICS_UPLOAD_KEY_ALIAS`
   - `READYLYTICS_UPLOAD_KEY_PASSWORD`
   - `READYLYTICS_UPLOAD_CERT_SHA256`
3. Run:

```powershell
.\gradlew verifyReleaseSigningInputs
.\gradlew bundleRelease
jarsigner -verify app\build\outputs\bundle\release\app-release.aab
```

4. Compare `keytool -printcert -jarfile app\build\outputs\bundle\release\app-release.aab` SHA-256 output to `READYLYTICS_UPLOAD_CERT_SHA256`.
5. Delete disposable keystore after verification.

## Play App Signing

- Enroll app in Play App Signing before first internal-track upload.
- Keep upload key custody separate from Play app-signing key custody.
- Store approved upload certificate fingerprint in secure team docs and in `READYLYTICS_UPLOAD_CERT_SHA256`.
- After any upload-key reset or rotation, update keystore secret, password/alias secrets if changed, and fingerprint secret together before next release run.

## Internal-Track Dry Run

Before production submission:

1. Trigger `.github/workflows/release.yml` manually against release candidate commit or push signed version tag.
2. Confirm `verifyReleaseSigningInputs`, `testDebugUnitTest`, `lint`, `lintRelease`, `bundleRelease`, `jarsigner`, and fingerprint verification all pass.
3. Download uploaded `readylytics-release-aab` artifact and archive workflow URL with release notes.
4. Upload same AAB to Play internal testing track.
5. Confirm Play accepts signing certificate and preserves expected package/version metadata.

## Failure Expectations

- Missing any required release-signing environment variable causes `verifyReleaseSigningInputs` to fail before artifact packaging.
- Release build never falls back to debug key.
- Fingerprint mismatch fails workflow even if bundle is otherwise signed.
