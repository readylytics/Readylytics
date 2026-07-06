# Readylytics Release Signing

## Purpose

Readylytics release artifacts must be signed with Play upload key material that stays outside repository. Release builds fail closed if required signing inputs are missing. Debug builds remain unsigned or debug-signed as usual.

## Required GitHub Environments

`.github/workflows/release.yml` selects an environment from the release ref:

- `beta-release`: restrict deployments to `main`. Every successful `main` release publishes to the Play `beta` track.
- `production-release`: restrict deployments to tags matching `v*` and require manual approval. Valid `vMAJOR.MINOR.PATCH` tags publish to the Play `production` track.

Keep signing secrets scoped to these environments. Configure the same upload-key secrets in both because Play requires beta and production bundles to use the upload certificate.

## Required Secrets

Configure these environment secrets in GitHub:

- `READYLYTICS_UPLOAD_STORE_BASE64`: base64-encoded upload keystore bytes.
- `READYLYTICS_UPLOAD_STORE_PASSWORD`: keystore password.
- `READYLYTICS_UPLOAD_KEY_ALIAS`: upload key alias.
- `READYLYTICS_UPLOAD_KEY_PASSWORD`: upload key password.
- `READYLYTICS_UPLOAD_CERT_SHA256`: expected SHA-256 certificate fingerprint for signed artifact verification.

## Workload Identity Federation

Play publishing uses Google Workload Identity Federation (WIF), not a service-account JSON key. Configure one workload identity pool and GitHub OIDC provider. Restrict the provider to repository `readylytics/Readylytics`, then grant each service account `roles/iam.workloadIdentityUser` only to its environment subject:

- Beta: `repo:readylytics/Readylytics:environment:beta-release`
- Production: `repo:readylytics/Readylytics:environment:production-release`

Create two service accounts without user-managed keys:

- Beta account: grant app access and testing-track release permission in Play Console. Do not grant production release permission.
- Production account: grant app access and production release permission in Play Console.

Enable the Google Play Android Developer API in the Google Cloud project. Neither service account needs broad project roles such as Owner or Editor.

Configure GitHub Actions variables:

- Repository variable `GCP_WORKLOAD_IDENTITY_PROVIDER`: full provider resource name, for example `projects/123456789/locations/global/workloadIdentityPools/readylytics-github/providers/github`.
- `beta-release` environment variable `GCP_SERVICE_ACCOUNT`: beta service-account email.
- `production-release` environment variable `GCP_SERVICE_ACCOUNT`: production service-account email.

The workflow grants `id-token: write`, exchanges GitHub's short-lived OIDC token through `google-github-actions/auth`, and exposes Application Default Credentials to Gradle Play Publisher. The generated `gha-creds-*.json` file is ephemeral, ignored by Git, and deleted by the action's post-step cleanup.

`release.yml` decodes the keystore into the runner temp directory, exports `READYLYTICS_UPLOAD_STORE_FILE`, runs the release build, verifies `jarsigner`, compares the artifact certificate fingerprint, uploads the signed AAB, authenticates through WIF, and publishes to the ref-selected Play track. It deletes the temporary keystore in `always()` cleanup.

## Release Routing And Versioning

- A `main` build uses the latest merged stable tag as its base and publishes `BASE_VERSION-RC<GITHUB_RUN_NUMBER>` to `beta`. Before the first tag, `baseVersionName` from `gradle.properties` is the base.
- A valid `vMAJOR.MINOR.PATCH` tag publishes `MAJOR.MINOR.PATCH` to `production`.
- All CI releases use `versionCode = 100000 + GITHUB_RUN_NUMBER`, keeping beta-to-production upgrades monotonic.
- Manual workflow runs publish only when targeting `main` or a valid release tag.
- Both tracks use completed releases. Play edit transactions are serialized by workflow concurrency.

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

## Beta-Track Dry Run

Before production submission:

1. Trigger `.github/workflows/release.yml` on `main`.
2. Confirm `verifyReleaseSigningInputs`, `testDebugUnitTest`, `lint`, `lintRelease`, `bundleRelease`, `jarsigner`, and fingerprint verification all pass.
3. Confirm WIF authentication uses the beta service account and no JSON key secret.
4. Confirm Play accepts the bundle on `beta` with expected RC version metadata.
5. Download the uploaded AAB artifact and archive the workflow URL with release notes.

After beta succeeds, push a protected release tag and confirm the production environment approval, production service account, and production track before deleting the legacy service-account key.

## Credential Migration And Recovery

1. Configure and test WIF for both environments before revoking the legacy key.
2. Delete `PLAY_SERVICE_ACCOUNT_JSON` from GitHub after both paths pass.
3. Disable and then delete the old Google service-account key.
4. If token exchange fails, verify the selected GitHub environment, exact OIDC subject binding, provider resource variable, service-account variable, and `id-token: write` permission.
5. If Play rejects authorization, verify the service-account invitation and app-level track permissions in Play Console. Do not restore a long-lived JSON key as a permanent workaround.

## Failure Expectations

- Missing any required release-signing environment variable causes `verifyReleaseSigningInputs` to fail before artifact packaging.
- Release build never falls back to debug key.
- Fingerprint mismatch fails workflow even if bundle is otherwise signed.
- A malformed release tag or manual run from a non-`main` branch fails before publishing.
- WIF or Play authorization failure leaves the verified AAB available as a GitHub artifact but does not publish it.
