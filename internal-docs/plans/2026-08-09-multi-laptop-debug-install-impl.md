# Per-laptop Debug Installs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every debug build install with a machine-specific `applicationId` suffix and app label derived from the laptop's hostname, so debug builds from different laptops coexist on one shared test device without `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

**Architecture:** A new `buildSrc` module holds the pure, unit-tested hostname detection/sanitization logic (classes in `buildSrc` are importable from every project's `build.gradle.kts` at configuration time). `:app`'s `debug` build type uses it for `applicationIdSuffix` and a generated `app_name` resource; the deleted `app/src/debug/res/values/strings.xml` removes the duplicate-resource conflict. `:benchmark` computes the target debug package at configuration time (via the same `buildSrc` function) and injects it through `testInstrumentationRunnerArguments`, which the benchmark instrumentation reads at runtime — the segment is never re-detected on-device (a device's hostname is not the laptop's hostname).

**Tech Stack:** Gradle 9.6.1, Kotlin DSL, AGP 9.3.1, `kotlin-dsl` buildSrc, JUnit4 (`kotlin-test`), ktlint 14.2.0 (tool 1.5.0).

---

## Source plan and deviations

Operationalizes `internal-docs/plans/MULTI_LAPTOP_DEBUG_INSTALL_PLAN.md`. Two deliberate deviations, agreed with the user:

1. **Benchmarks ARE touched.** The source plan claims no benchmark changes, but `benchmark/src/main/kotlin/app/readylytics/health/benchmark/BenchmarkTestSupport.kt:11` hardcodes `MACROBENCHMARK_PACKAGE_NAME = "app.readylytics.health.local"` — the exact debug package this change makes machine-specific. `StartupBenchmark` and `ScrollBenchmark` use it as their `measureRepeated(packageName=...)` target, so it must become machine-aware (Tasks 5–6).
   - The segment is injected via instrumentation argument at configuration time because the benchmark tests execute **on the device**, where `detectHostname()` would return the device's hostname (or fail), not the building laptop's.
   - Pre-existing ambiguity (out of scope, not made worse): `benchmark/README.md` documents `connectedBenchmarkAndroidTest` against the `.macrobenchmark` variant while the constant targets the debug `.local` app. This plan keeps the constant pointing at the debug app, exactly as today, just machine-suffixed.
2. **Sanitizer is extracted + unit-tested.** Per user choice, the pure hostname→identifier logic lives in `buildSrc` with JVM unit tests (edge cases: digits, empty, `.local` suffix, length cap) instead of being inlined untested in `app/build.gradle.kts`.

## Migration note (informational, no code)

Because `applicationId` changes for *every* machine (not a pure suffix addition), any developer with an existing `app.readylytics.health.local` debug build on a device must uninstall it once before the next `installDebug`, or Gradle/adb reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. This is expected and not a bug.

## Docs obligations (none)

- No `internal-docs/DATA_FLOW.md` change: only local debug install identity is touched, not the data pipeline, Room schema, scoring engine, or documented architecture.
- No `ABOUT.md` / `docs/*` / in-app About strings change: the `app_name` launcher label is not score-explanation copy.
- No `.github/ISSUE_TEMPLATE` change.
- After the final task, run `codegraph sync` (file deletions) per `AGENTS.md` (see Task 7).

## File map

| Action | Path | Responsibility |
|---|---|---|
| Create | `buildSrc/build.gradle.kts` | `kotlin-dsl` module exposing `DebugInstallIdentity` to all build scripts; test deps |
| Create | `buildSrc/src/main/kotlin/readylytics/buildlogic/DebugInstallIdentity.kt` | Pure hostname detect/strip/sanitize (single source of truth) |
| Create | `buildSrc/src/test/kotlin/readylytics/buildlogic/DebugInstallIdentityTest.kt` | Edge-case unit tests (zero Android deps) |
| Modify | `app/build.gradle.kts` | Import `DebugInstallIdentity`; compute `rawHostname`/`machineIdSegment`; set `applicationIdSuffix` + `resValue` in `debug` block |
| Delete | `app/src/debug/res/values/strings.xml` | Superseded by generated `resValue` (only contained `app_name`) |
| Modify | `benchmark/build.gradle.kts` | Inject `readylytics.machineIdSegment` into `testInstrumentationRunnerArguments` |
| Modify | `benchmark/src/main/kotlin/app/readylytics/health/benchmark/BenchmarkTestSupport.kt` | Derive `MACROBENCHMARK_PACKAGE_NAME` from the injected argument |

---

### Task 1: Scaffold `buildSrc` with failing unit tests

**Files:**
- Create: `buildSrc/build.gradle.kts`
- Create: `buildSrc/src/test/kotlin/readylytics/buildlogic/DebugInstallIdentityTest.kt`

- [ ] **Step 1: Create `buildSrc/build.gradle.kts`**

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}
```

Note: no `settings.gradle.kts` needed — Gradle auto-includes a `buildSrc` directory at the repo root. It coexists with the existing `build-logic` included build.

- [ ] **Step 2: Create the failing test file**

```kotlin
package readylytics.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals

class DebugInstallIdentityTest {

    @Test
    fun `strips non-alphanumerics and lowercases`() {
        assertEquals("gregorsmacbookpro", DebugInstallIdentity.sanitizeMachineId("Gregors-MacBook-Pro"))
    }

    @Test
    fun `prefixes m when segment starts with a digit`() {
        assertEquals("m123", DebugInstallIdentity.sanitizeMachineId("123"))
    }

    @Test
    fun `prefixes m once when digit follows leading letters`() {
        assertEquals("m1laptop", DebugInstallIdentity.sanitizeMachineId("1-Laptop"))
    }

    @Test
    fun `does not double the m prefix`() {
        assertEquals("m1laptop", DebugInstallIdentity.sanitizeMachineId("m1laptop"))
    }

    @Test
    fun `empty hostname falls back to device`() {
        assertEquals("device", DebugInstallIdentity.sanitizeMachineId(""))
    }

    @Test
    fun `all-non-alphanumeric hostname falls back to device`() {
        assertEquals("device", DebugInstallIdentity.sanitizeMachineId("  !@#  "))
    }

    @Test
    fun `truncates to twenty characters`() {
        assertEquals("abcdefghijklmnopqrst", DebugInstallIdentity.sanitizeMachineId("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `strips mdns local suffix`() {
        assertEquals("Gregors-MacBook-Pro", DebugInstallIdentity.stripMdnsSuffix("Gregors-MacBook-Pro.local"))
    }

    @Test
    fun `keeps hostname without local suffix unchanged`() {
        assertEquals("myhost", DebugInstallIdentity.stripMdnsSuffix("myhost"))
    }

    @Test
    fun `sanitizes hostname after stripping mdns suffix`() {
        val raw = DebugInstallIdentity.stripMdnsSuffix("Gregors-MacBook-Pro.local")
        assertEquals("gregorsmacbookpro", DebugInstallIdentity.sanitizeMachineId(raw))
    }
}
```

- [ ] **Step 3: Run tests and confirm they fail to compile**

Run: `./gradlew :buildSrc:test --console=plain`

Expected: `FAILURE: Build failed with an exception` — `unresolved reference: DebugInstallIdentity` in the test source set (main source does not exist yet). This is the intended TDD red state.

---

### Task 2: Implement `DebugInstallIdentity` and get tests green

**Files:**
- Create: `buildSrc/src/main/kotlin/readylytics/buildlogic/DebugInstallIdentity.kt`

- [ ] **Step 1: Create the implementation**

```kotlin
package readylytics.buildlogic

import java.net.InetAddress

object DebugInstallIdentity {

    val rawHostname: String by lazy { stripMdnsSuffix(detectHostname()) }

    val machineIdSegment: String by lazy { sanitizeMachineId(rawHostname) }

    fun detectHostname(): String =
        (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"))?.takeIf { it.isNotBlank() }
            ?: runCatching {
                ProcessBuilder("hostname").start().inputStream.bufferedReader().use { it.readText() }.trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name")
            ?: "device"

    fun stripMdnsSuffix(hostname: String): String = hostname.removeSuffix(".local")

    fun sanitizeMachineId(rawHostname: String): String {
        val sanitized =
            rawHostname
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "")
                .take(20)
                .ifBlank { "device" }
        return if (sanitized.first().isDigit()) "m$sanitized" else sanitized
    }
}
```

The `lazy` properties ensure `detectHostname()` (which spawns a `hostname` subprocess) runs at most once per Gradle invocation. Behavior is identical to the source plan's inline logic.

- [ ] **Step 2: Run tests and confirm they pass**

Run: `./gradlew :buildSrc:test --console=plain`

Expected: `BUILD SUCCESSFUL`; test report shows 10 tests, 0 failures (verified working on Gradle 9.6.1).

- [ ] **Step 3: Commit**

```bash
git add buildSrc
git commit -m "feat(build): add unit-tested debug identity helpers in buildSrc"
```

---

### Task 3: Wire machine-specific identity into the `debug` build type

**Files:**
- Modify: `app/build.gradle.kts` (imports at top; `val`s after line 113; `debug` block at lines 173-177)

- [ ] **Step 1: Add the import to the top of `app/build.gradle.kts`**

At the top of the imports block (line 1-8), add:

```kotlin
import readylytics.buildlogic.DebugInstallIdentity
```

- [ ] **Step 2: Add the computed values near the other top-level helpers**

Immediately after `val computedVersionName = resolvedVersion.second` (currently line 113), add:

```kotlin
val rawHostname = DebugInstallIdentity.rawHostname
val machineIdSegment = DebugInstallIdentity.machineIdSegment
```

- [ ] **Step 3: Update the `debug` build type block**

Replace the current block (lines 173-177):

```kotlin
debug {
    applicationIdSuffix = ".local"
    versionNameSuffix = "-local"
    enableUnitTestCoverage = true
}
```

with:

```kotlin
debug {
    applicationIdSuffix = ".local.$machineIdSegment"
    versionNameSuffix = "-local"
    enableUnitTestCoverage = true
    resValue("string", "app_name", "Readylytics Local ($rawHostname)")
}
```

Do NOT touch `benchmark` or `nonMinifiedRelease` blocks — they `initWith(release)` and are intentionally unchanged.

- [ ] **Step 4: Verify the debug APK assembles and carries the new identity**

Run: `./gradlew :app:assembleDebug --console=plain`

Expected: `BUILD SUCCESSFUL`.

Then inspect the resolved package and label without a device:

```bash
unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | strings | rg -i readylytics
```

Expected: contains a line ending in `.local.<sanitized-hostname>` (e.g. `.local.gregorsmacbookpro`). If `aapt`/`aapt2` is on PATH, `aapt dump badging app/build/outputs/apk/debug/app-debug.apk | rg -E "package:|application-label"` gives the same result plus the label.

Then confirm the generated resource:

```bash
cat app/build/generated/res/resValues/debug/values/generated.xml
```

Expected: `<string name="app_name">Readylytics Local (<raw hostname>)</string>`.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "feat(build): machine-specific debug applicationId and app label"
```

---

### Task 4: Delete the conflicting debug `app_name` resource

**Files:**
- Delete: `app/src/debug/res/values/strings.xml`

- [ ] **Step 1: Delete the file**

```bash
git rm app/src/debug/res/values/strings.xml
```

It contains only `<string name="app_name">Readylytics Local</string>`; the `resValue(...)` added in Task 3 generates `app_name` for the same variant, so leaving both causes a duplicate-resource build error. Nothing else in `app/src/debug/` (the `AndroidManifest.xml` and `kotlin/` sources) is affected.

- [ ] **Step 2: Verify assembly still succeeds**

Run: `./gradlew :app:assembleDebug --console=plain`

Expected: `BUILD SUCCESSFUL` (no `Duplicate resources` failure).

- [ ] **Step 3: Re-confirm the generated resource is still the sole source**

Run: `cat app/build/generated/res/resValues/debug/values/generated.xml`

Expected: `app_name` still present with the hostname value.

- [ ] **Step 4: Commit**

```bash
git add -u app/src/debug
git commit -m "chore(build): remove debug app_name res superseded by resValue"
```

---

### Task 5: Make macrobenchmark target the machine-specific debug package (configuration side)

**Files:**
- Modify: `benchmark/build.gradle.kts`

- [ ] **Step 1: Add the import to the top of `benchmark/build.gradle.kts`**

At the top (after line 1), add:

```kotlin
import readylytics.buildlogic.DebugInstallIdentity
```

- [ ] **Step 2: Inject the machine segment into the instrumentation arguments**

In the `defaultConfig` block (currently lines 18-23), add the argument line:

```kotlin
defaultConfig {
    minSdk = 26
    targetSdk = 37
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "DEBUGGABLE"
    testInstrumentationRunnerArguments["readylytics.machineIdSegment"] = DebugInstallIdentity.machineIdSegment
}
```

This resolves at configuration time on the building laptop (same `buildSrc` function `:app` uses), so no detection logic runs on the device.

- [ ] **Step 3: Verify the benchmark test APK compiles**

Run: `./gradlew :benchmark:assembleBenchmark --console=plain`

Expected: `BUILD SUCCESSFUL` (compile only — no device needed). If the task name is not found, list valid ones with `./gradlew :benchmark:tasks --all | rg -i "benchmark"` and use the `assembleBenchmark`/compile equivalent.

- [ ] **Step 4: Commit**

```bash
git add benchmark/build.gradle.kts
git commit -m "feat(benchmark): inject machine-specific debug package segment"
```

---

### Task 6: Make `MACROBENCHMARK_PACKAGE_NAME` read the injected segment (runtime side)

**Files:**
- Modify: `benchmark/src/main/kotlin/app/readylytics/health/benchmark/BenchmarkTestSupport.kt:11`

- [ ] **Step 1: Replace the hardcoded constant**

Replace line 11:

```kotlin
internal const val MACROBENCHMARK_PACKAGE_NAME = "app.readylytics.health.local"
```

with:

```kotlin
private const val ARG_MACHINE_ID_SEGMENT = "readylytics.machineIdSegment"

internal val MACROBENCHMARK_PACKAGE_NAME: String =
    "app.readylytics.health.local." +
        requireNotNull(InstrumentationRegistry.getArguments().getString(ARG_MACHINE_ID_SEGMENT)) {
            "Instrumentation argument '$ARG_MACHINE_ID_SEGMENT' missing — " +
                "set it in :benchmark defaultConfig.testInstrumentationRunnerArguments"
        }
```

Leave `BASELINE_PROFILE_PACKAGE_NAME = "app.readylytics.health.baselineprofile"` (line 12) untouched — the `nonMinifiedRelease` variant's package is not changing. All usages in `StartupBenchmark`/`ScrollBenchmark` (`measureRepeated(packageName = ...)`, `grantHealthConnectPermissions(...)`, `appString(...)`) accept a runtime `val`.

- [ ] **Step 2: Verify the benchmark module still compiles**

Run: `./gradlew :benchmark:assembleBenchmark --console=plain`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add benchmark/src/main/kotlin/app/readylytics/health/benchmark/BenchmarkTestSupport.kt
git commit -m "fix(benchmark): target machine-specific debug package from instrumentation args"
```

---

### Task 7: Full pre-commit verification and repo bookkeeping

**Files:** none (verification only)

- [ ] **Step 1: Format and unit tests (project-mandated pre-commit)**

Run:

```bash
./gradlew ktlintFormat --console=plain
./gradlew :buildSrc:test testDebugUnitTest --console=plain
```

Expected: `BUILD SUCCESSFUL` for both. `ktlintFormat` may reformat the edited `.kts`/`.kt` files (task 1-6 code is authored already ktlint-clean); re-stage any reformatted files into their tasks' commits or a follow-up `git add -A` commit before continuing. `:buildSrc:test` covers the new sanitizer; `testDebugUnitTest` confirms no existing unit test depended on the old debug `app_name`/package.

- [ ] **Step 2: Final lint gate**

Run: `./gradlew lintRelease --console=plain`

Expected: `BUILD SUCCESSFUL` (`abortOnError = true`, `warningsAsErrors = true`).

- [ ] **Step 3: Device smoke test (if a device/emulator is attached)**

Run: `./gradlew installDebug`

Expected: app installs and launches; the app-drawer label shows `Readylytics Local (<raw hostname>)`. Run it once from a second laptop (or after changing `detectHostname`'s inputs) to confirm two machine-specific debug builds coexist — note the one-time uninstall of any pre-existing `app.readylytics.health.local` build first (Migration note above).

- [ ] **Step 4: Refresh codebase indexes**

Run: `codegraph sync` (new `buildSrc` sources, deleted `strings.xml`) per `AGENTS.md`.

- [ ] **Step 5: Commit any post-format changes**

```bash
git add -A
git status
git commit -m "chore(build): post-format from ktlintFormat"
```

(Only if Step 1 produced reformatting; otherwise skip.)

---

## Self-review notes

- **Spec coverage:** every element of the source plan is present — helpers (Task 2), `debug` block change (Task 3), resource deletion (Task 4), verification (Tasks 3/4/7), migration note (above). Added benchmark machine-awareness (Tasks 5-6) per user decision.
- **Type consistency:** `DebugInstallIdentity.rawHostname`/`machineIdSegment` are `lazy val` properties consumed identically in `app/build.gradle.kts` (Task 3) and `benchmark/build.gradle.kts` (Task 5); the instrumentation-arg key `readylytics.machineIdSegment` is written in Task 5 and read in Task 6 with the same string.
- **No placeholders:** all files contain complete code; all commands show expected output.
