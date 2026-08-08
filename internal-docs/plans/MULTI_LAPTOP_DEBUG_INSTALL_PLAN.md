# Per-laptop debug installs (applicationId + app name)

## Context

Debug builds (`./gradlew installDebug`) currently all resolve to the same
identity — `applicationId = app.readylytics.health.local`, `app_name =
"Readylytics Local"` — signed with whatever `~/.android/debug.keystore`
happens to exist on the machine that built it (AGP auto-generates one per
machine; none is checked into the repo). If a shared physical test device
(or emulator image handed between machines) gets a debug build installed
from one laptop and then another laptop tries to `installDebug` the *same*
`applicationId` on top, Android rejects it with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch) unless it's
uninstalled first. Windows, Mac, and Ubuntu laptops all have distinct debug
keystores, so this hits any dev team sharing a test device across machines.

The repo already solves an analogous problem for the `benchmark` and
`nonMinifiedRelease` build types via `applicationIdSuffix`
(`app/build.gradle.kts:178-190`, documented in `benchmark/README.md:6-12`)
so they install side-by-side instead of colliding. This plan applies the
same pattern to `debug`, keyed off the laptop's hostname, so debug builds
from different machines coexist on one device and are visually
distinguishable in the app drawer.

Decisions made with the user: identity is auto-detected from the OS
hostname (no manual config step), this is always-on for every debug build
(matches the existing `.macrobenchmark`/`.baselineprofile` convention, no
opt-in flag), and the machine name shows in both the `applicationId`
suffix and the visible app name.

## Design

In `app/build.gradle.kts`, add a small hostname-detection helper near the
top of the file (alongside the existing `computedVersionCode`/
`computedVersionName`/`releaseUploadSigningReady` helpers) that is
cross-platform without branching on `os.name`, since `hostname` is a
built-in command on Windows (cmd/PowerShell), macOS, and Ubuntu alike:

```kotlin
fun detectHostname(): String =
    (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"))?.takeIf { it.isNotBlank() }
        ?: runCatching {
            ProcessBuilder("hostname").start().inputStream.bufferedReader().use { it.readText() }.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: System.getProperty("user.name")
        ?: "device"

val rawHostname = detectHostname().removeSuffix(".local") // macOS mDNS suffix, e.g. "Gregors-MacBook-Pro.local"

// applicationIdSuffix segments must be valid Java identifiers: lowercase
// alphanumeric only, must not start with a digit.
val machineIdSegment =
    rawHostname.lowercase().replace(Regex("[^a-z0-9]+"), "").take(20).ifBlank { "device" }.let {
        if (it.first().isDigit()) "m$it" else it
    }
```

Apply it only in the `debug` build type (`app/build.gradle.kts:173-177`):

```kotlin
debug {
    applicationIdSuffix = ".local.$machineIdSegment"
    versionNameSuffix = "-local"
    enableUnitTestCoverage = true
    resValue("string", "app_name", "Readylytics Local ($rawHostname)")
}
```

This changes the resolved debug identity from `app.readylytics.health.local`
/ `"Readylytics Local"` to e.g. `app.readylytics.health.local.gregorsmacbookpro`
/ `"Readylytics Local (Gregors-MacBook-Pro)"`.

`benchmark` and `nonMinifiedRelease` build types are untouched — they
already use `initWith(release)` and their own fixed suffixes, are
debug-*signed* but not meant for parallel per-laptop installs, and weren't
part of the ask.

### Resource conflict to resolve

`app/src/debug/res/values/strings.xml` currently hardcodes
`<string name="app_name">Readylytics Local</string>`. Since `resValue(...)`
in the `debug` build type generates an `app_name` resource for the same
variant, leaving both in place causes a duplicate-resource build error.
Delete `app/src/debug/res/values/strings.xml` (it contains only this one
string) so the `resValue` call is the sole source for debug's `app_name`.

### Migration note

Because `applicationId` itself changes for *every* machine (not just a
suffix addition), any developer with an existing `app.readylytics.health.local`
debug build on a device needs to uninstall it once before the next
`installDebug` — otherwise Gradle/adb will fail to update in place.

## Files to change

- `app/build.gradle.kts` — add `detectHostname()`/`rawHostname`/
  `machineIdSegment` helpers; update the `debug` block (lines ~173-177) to
  set `applicationIdSuffix` and add the `resValue` call.
- `app/src/debug/res/values/strings.xml` — delete (superseded by the
  generated `resValue`).

No changes needed to `benchmark/README.md`, `README.md`, CI workflows, or
`internal-docs/DATA_FLOW.md` — this only touches local debug install
identity, not the data pipeline, scoring, or documented architecture, and
CI's instrumented-test runs already do fresh installs per run regardless of
the resolved `applicationId`.

## Verification

1. `./gradlew ktlintFormat` then `./gradlew testDebugUnitTest` (mandatory
   pre-commit per project conventions).
2. `./gradlew :app:assembleDebug` and confirm it succeeds (validates the
   Kotlin DSL syntax and that the resource deletion doesn't orphan a
   reference).
3. Inspect the resolved package name and app label without a device:
   - `unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | strings | grep -i readylytics` or, if `aapt`/`aapt2` is available in the environment, `aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "package:|application-label"` — confirm `applicationId` ends in `.local.<sanitized-hostname>` and the label includes the raw hostname.
   - Also check the generated resource file at
     `app/build/generated/res/resValues/debug/values/generated.xml`
     (or equivalent path AGP writes for this variant) to confirm the
     `app_name` value contains the hostname.
4. If a connected device/emulator is available, `./gradlew installDebug`
   and confirm the app installs and launches, with the app-drawer label
   showing the machine name.
5. `./gradlew lintRelease` at the end (project's mandatory final check).
