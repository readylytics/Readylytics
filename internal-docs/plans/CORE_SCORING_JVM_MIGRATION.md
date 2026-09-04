# Plan: Convert `core:scoring` to a pure JVM module

> **Status:** DEFERRED — blocked on an AGP whose bundled built-in Kotlin compiler is
> confirmed compatible with the matching standalone Kotlin release. **AGP 9.4.0 was
> attempted and does NOT qualify** (see §1/§2 spike result, 2026-09-04). Do not start
> until the precondition in §2 passes. Written 2026-08-18.

This plan is the "migration we couldn't do" during the repo-wide architecture remediation that
landed on the `feat/code-review` branch: converting `:core:scoring` from an Android library to
a plain `kotlin("jvm")` module was attempted, hit an external blocker, and was deferred. It is
self-contained: everything needed to execute it is below, and no other document is required.

---

## 1. What this is and why it was deferred

**Goal.** Make `:core:scoring` a pure JVM module (`kotlin("jvm")`) instead of an
Android library (`readylytics.android-library-conventions`). Its source already
has zero `android.*`/`androidx.*` imports, so purity is currently enforced only by
a Konsist assertion. The JVM conversion makes it a compile-time guarantee and
drops the Android test runner from its test task.

**Why deferred.** On 2026-08-18 the conversion was attempted and the build broke
deterministically in `:core:scoring:compileKotlin`. The compiler crashed
*after* a successful compile, during the classpath-snapshot shrink
(`shrinkAndSaveClasspathSnapshot`), with `ArrayIndexOutOfBoundsException`
("Index 33 out of bounds for length 3") or `java.io.EOFException`.

Root cause:

- The project sets `android.builtInKotlin=true` (AGP 9.x). AGP therefore bundles
  its **own** Kotlin compiler, artifact `com.android.tools.external.com-intellij:kotlin-compiler:32.3.1`
  (a different build than the Maven-released compiler).
- The standalone `kotlin("jvm")` plugin uses the Maven `org.jetbrains.kotlin:kotlin-compiler-embeddable`
  (currently `2.3.21` in `gradle/libs.versions.toml`).
- These two compiler builds serialize the incremental-compilation classpath
  snapshot in mutually incompatible formats. When the standalone JVM compiler
  reads a sibling module's classes that were produced by the built-in compiler
  (specifically large-metadata classes such as `DailySummary`), the snapshot
  round-trips incorrectly and the compiler crashes.
- Android modules are unaffected because they are all compiled by the built-in
  compiler and therefore share one format.

What was tried and did **not** help:

- Bumping the standalone Kotlin to `2.4.10` (newest stable on Maven Central at
  the time) — same crash.
- Disabling the build cache and doing full `clean` + `--stop` rebuilds — the
  crash is deterministic, not a stale-cache artifact.
- The documented escape hatch `kotlin.incremental.useClasspathSnapshot=false`
  is **deprecated and removed** in Kotlin 2.3.x (the "history-based incremental
  compilation" it gated was replaced by ABI-snapshot-based incremental
  compilation), so the feature cannot be turned off.

**Why we wait for an AGP + standalone pairing that is actually compatible.** The
only clean fix is to make the built-in Kotlin compiler and the standalone compiler
use a common snapshot format. That requires a coordinated bump of AGP (which
bundles the built-in compiler) **and** the standalone `kotlin` version in the
catalog. As of 2026-08-18, `9.3.1` is the newest *stable* AGP; the only newer
releases are pre-releases (`9.4.0-rc01`, `9.5.0-alpha01`), which this project will
not adopt.

**2026-09-04 update — AGP 9.4.0 was tried and does NOT resolve this.** On
`feat/agp-9.4.0` (AGP `9.4.0` in the catalog), the spike was run with standalone
`kotlin = "2.2.10"` (AGP 9.4.0 has a runtime dependency on `kotlin-gradle-plugin
2.2.10` and bundles `com.android.tools.external.com-intellij:kotlin-compiler:32.3.2`,
the same compiler family as 9.3.x's `32.3.1`). Result: `:core:scoring:compileKotlin`
succeeded on the very first run (the classpath snapshot file happened to be absent),
then failed **deterministically on every clean rebuild** with the identical crash —
`ArrayIndexOutOfBoundsException: Index 33 out of bounds for length 3` in
`shrinkAndSaveClasspathSnapshot` / `DelegateDataExternalizer.read`, reading
`core/model/build/kotlin/compileDebugKotlin/classpath-snapshot/shrunk-classpath-snapshot.bin`
written by the built-in compiler. The `kotlin.incremental.useClasspathSnapshot=false`
escape hatch was also retried and still crashes (it is removed in 2.2.x as well, not
just 2.3.x). Conclusion: the "bump AGP to a newer bundled compiler that matches a
standalone Kotlin" hypothesis is falsified by AGP 9.4.0. The migration remains
blocked on a future AGP whose bundled compiler genuinely shares the snapshot format,
or on removing the Android-built-module-on-JVM-classpath boundary entirely (see the
§3.4 alternative: convert `core:model` to `kotlin("jvm")` too — bigger blast radius,
separate PR).

**What was already landed** (keep this): `org.gradle.caching=true` in
`gradle.properties` (commit `ab15774`).

---

## 2. Precondition + spike gate (must pass before §3)

1. Wait for an **AGP whose bundled built-in Kotlin compiler is confirmed compatible
   with a matching standalone Kotlin release**. **AGP 9.4.0 is explicitly known NOT
   to qualify** (attempted 2026-09-04, see §1 — it bundles KGP 2.2.10 / intellij
   compiler 32.3.2, and standalone 2.2.10 still crashes deterministically on clean
   rebuilds).
2. Determine the AGP's built-in Kotlin version. Inspect the Gradle cache after a
   sync:
   ```bash
   find ~/.gradle/caches -name "kotlin-compiler-*.jar" | grep -i com.android.tools
   # e.g. kotlin-compiler-32.4.0.jar
   ```
   Then set `kotlin = "<matching Maven version>"` in `gradle/libs.versions.toml`
   (the built-in compiler's snapshot format must match the standalone
   `kotlin("jvm")` compiler's). Note: matching the catalog `kotlin` version to the
   AGP's `kotlin-gradle-plugin` runtime dependency (`find ~/.gradle/caches
   .../com.android.tools.build/gradle/<ver>/*.pom` → `kotlin-gradle-plugin`) is a
   necessary but NOT sufficient check — the pairing must still pass the spike.
3. **Spike — do not proceed to §3 until this passes.** Reproduce the crash and
   confirm it is gone with the *smallest possible* change:
   - Temporarily convert only `core/scoring/build.gradle.kts` to `kotlin("jvm")`
     (see §3.5) and run:
     ```bash
     ./gradlew :core:scoring:compileKotlin --console=plain
     ```
   - If it compiles clean, **verify determinism**: delete the module build dir and
     recompile at least once (`rm -rf core/scoring/build && ./gradlew
     :core:scoring:compileKotlin`). A single green run is NOT sufficient — the
     2026-09-04 attempt passed once and then failed every clean rebuild. Only a
     repeatable clean compile counts as the mismatch being resolved.
   - If it still crashes with `ArrayIndexOutOfBoundsException` / `EOFException`
     in `shrinkAndSaveClasspathSnapshot`, the versions are still incompatible —
     stop and re-check the version pairing. Do **not** paper over it.

The spike must also cover the `core:database-schema` test dependency (see §3.7),
because that path exercises the same Android-module-on-JVM-classpath boundary.

---

## 3. Migration steps

Each step is a small, individually reviewable change. Commit after the batch.

### 3.1 Add missing version-catalog entries

In `gradle/libs.versions.toml`, add (next to the existing `kotlinx-coroutines-android`):

```toml
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
javax-inject = { module = "javax.inject:javax.inject", version = "1" }
```

(`coroutines` is already `1.11.0`.)

### 3.2 Replace `BuildConfig.DEBUG` with a compile-time constant

Create `core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ScoringDebug.kt`:

```kotlin
package app.readylytics.health.domain.scoring

/**
 * Compile-time debug flag for scoring diagnostics.
 * Replaces the former BuildConfig.DEBUG dependency that prevented
 * core:scoring from being a pure JVM module.
 */
internal const val SCORING_DEBUG = false
```

In `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/SleepScoringDebugLogger.kt`
(note: the file is `SleepScoringDebugLogger.kt`, not `ComputeSleepMetricsUseCase.kt` —
verified 2026-09-04):

- Remove the import `import app.readylytics.health.core.scoring.BuildConfig`
- Change `if (!BuildConfig.DEBUG) return` to `if (!SCORING_DEBUG) return`

### 3.3 Move the Hilt modules out of `core:scoring`

A `kotlin("jvm")` module cannot contain Hilt `@InstallIn` modules (they depend on
`dagger.hilt.android`, an Android artifact). `core:scoring` currently has exactly
two files that import `dagger.*`/`dagger.hilt.*`:

- `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/di/ScoringBindsModule.kt`
- `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/di/ScoringModule.kt`

(Actual package is `app.readylytics.health.core.scoring.di`, not
`app.readylytics.health.di` — verified 2026-09-04.)

Every other `core:scoring` file uses only `javax.inject.Inject` /
`javax.inject.Singleton` (verified: 45 files import `javax.inject`, and only
the two module files import `dagger.*`). Those `javax.inject` annotations are
plain JVM-safe annotations and stay put.

**Delete** both files above. **Create** a single merged module in the app module:

`app/src/main/kotlin/app/readylytics/health/di/ScoringModule.kt` (note the actual
package prefixes `app.readylytics.health.core.model.domain.*` / 
`app.readylytics.health.core.scoring.domain.scoring.*`):

```kotlin
package app.readylytics.health.di

import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.scoring.domain.scoring.AdaptiveRhrBaselineProvider
import app.readylytics.health.core.scoring.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.core.scoring.domain.scoring.RasSourceModeBootstrapUseCase
import app.readylytics.health.core.scoring.domain.scoring.RhrBaselineProvider
import app.readylytics.health.core.scoring.domain.scoring.ScoringCalculator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScoringModule {
    @Binds
    @Singleton
    abstract fun bindScoringCalculator(impl: CompositeScoringCalculator): ScoringCalculator

    @Binds
    @Singleton
    abstract fun bindRhrBaselineProvider(impl: AdaptiveRhrBaselineProvider): RhrBaselineProvider

    companion object {
        @Provides
        @Singleton
        fun provideRasSourceModeBootstrapUseCase(
            settingsRepository: SettingsRepository,
            scoringHistoryRepository: ScoringHistoryRepository,
        ): RasSourceModeBootstrapUseCase = RasSourceModeBootstrapUseCase(settingsRepository, scoringHistoryRepository)
    }
}
```

### 3.4 De-Android `core:model`'s dependencies

`core:model` is an Android library that `core:scoring` depends on, but its source
has no `android.*`/`androidx.*`/`dagger.*`/`hilt.*` imports (verified). Its
current `hilt.android` / `androidx.core.ktx` dependencies would leak Android
AARs onto a JVM consumer's classpath. In `core/model/build.gradle.kts`, change:

```diff
 dependencies {
-    implementation(libs.androidx.core.ktx)
+    implementation(libs.kotlinx.coroutines.core)
     implementation(libs.kotlinx.serialization.json)
-    implementation(libs.hilt.android)
+    implementation(libs.javax.inject)
```

Leave `core:model` itself as an Android library for now (smallest blast radius).
`core:scoring` consumes it as a jar via the artifact-type rules in §3.5.

> **Alternative (larger blast radius):** `core:model` is Android-free, so it can
> itself become `kotlin("jvm")`. That removes the need for the artifact-type
> rules in §3.5, but changes what every Android consumer (`:core:database`,
> `:core:healthconnect`, all `:feature:*`, `:app`) receives. Consider only if the
> minimal path proves awkward. Do not mix into the same PR.

### 3.5 Convert `core/scoring/build.gradle.kts` to `kotlin("jvm")`

Replace the whole file. The `BuildTypeAttr` / `ArtifactType` rules below are
required so the JVM module can consume `core:model`'s Android-library output as a
plain jar. The JaCoCo setup mirrors the current file but uses JVM task names
(`test` instead of `testDebugUnitTest`, `classes/kotlin/main` instead of
`tmp/kotlin-classes/debug`).

```kotlin
import com.android.build.api.attributes.BuildTypeAttr

plugins {
    kotlin("jvm")
    id("jacoco")
}

kotlin {
    jvmToolchain(17)
}

configurations.matching { it.isCanBeResolved }.configureEach {
    attributes {
        attribute(BuildTypeAttr.ATTRIBUTE, objects.named(BuildTypeAttr::class.java, "debug"))
    }
}

dependencies {
    attributesSchema {
        attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE) {
            compatibilityRules.add(ArtifactTypeCompatibilityRule::class.java)
            disambiguationRules.add(ArtifactTypeDisambiguationRule::class.java)
        }
    }
}

class ArtifactTypeCompatibilityRule : AttributeCompatibilityRule<String> {
    override fun execute(details: CompatibilityCheckDetails<String>) {
        details.compatible()
    }
}

class ArtifactTypeDisambiguationRule : AttributeDisambiguationRule<String> {
    override fun execute(details: MultipleCandidatesDetails<String>) {
        val preferred = details.candidateValues.find { it == "android-classes-jar" }
            ?: details.candidateValues.find { it == "jar" }
        if (preferred != null) {
            details.closestMatch(preferred)
        }
    }
}

val fileFilter =
    listOf(
        "**/R.class",
        "**/R$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/*Test*.*",
        "android/**/*.*",
        "**/*_Factory*",
        "**/*_Impl*",
        "**/di/**",
    )

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("test")

    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("jacoco/test.exec")
        },
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
            fileFilter.forEach { exclude(it) }
        },
    )
    sourceDirectories.setFrom(
        files(
            "${project.projectDir}/src/main/java",
            "${project.projectDir}/src/main/kotlin",
        ),
    )

    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("app.readylytics.health.core.scoring.domain.scoring")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = 0.80.toBigDecimal()
            }
        }
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn("test")

    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("jacoco/test.exec")
        },
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
            fileFilter.forEach { exclude(it) }
        },
    )
    sourceDirectories.setFrom(
        files(
            "${project.projectDir}/src/main/java",
            "${project.projectDir}/src/main/kotlin",
        ),
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
```

Notes on what changed vs. the current file:

- `readylytics.android-library-conventions` + `alias(libs.plugins.ksp)` → `kotlin("jvm")`.
- The `android { … }` block (namespace, `buildConfig = true`, coverage flag) is
  removed entirely.
- `implementation(libs.androidx.core.ktx)` — dropped (unused; no `androidx.core` imports).
- `implementation(libs.kotlinx.coroutines.android)` → `implementation(libs.kotlinx.coroutines.core)`.
- `implementation(libs.hilt.android)` + `ksp(libs.hilt.compiler)` → `implementation(libs.javax.inject)`.
- `testImplementation(project(":core:database-schema"))` is handled separately in §3.7.
- **Jacoco floor caveat (verified 2026-09-04):** the current (Android) rule targets
  the wrong package (`app.readylytics.health.domain.scoring` — no such package), so
  the 80% floor is NOT currently enforced. Measuring the real package
  (`app.readylytics.health.core.scoring.domain.scoring`) yields **0.6**, not 0.8.
  The JVM build file above corrects the package, so `jacocoCoverageVerification`
  will start actually enforcing the floor and **will fail until coverage is raised
  to 0.8** — plan for that work when the migration proceeds (do not silently lower
  the floor; it is an explicit goal of §3.6).

### 3.6 Run `:core:scoring` tests

```bash
./gradlew :core:scoring:test --console=plain
./gradlew :core:scoring:jacocoCoverageVerification
```

Expected: all `core:scoring` tests pass as plain JVM tests, and the 80% coverage
floor on `app.readylytics.health.domain.scoring` still holds.

### 3.7 Resolve the `core:database-schema` test dependency (REQUIRED — decide here)

`core:scoring` has **three** test files that import Room entities from
`core:database-schema` (an Android module):

- `core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/ComputeSleepMetricsUseCaseTest.kt`
  (`DailySummaryEntity`, `SleepSessionEntity`)
- `core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/BaselineFreezeBehaviorTest.kt`
  (`DailySummaryEntity`)
- `core/scoring/src/test/kotlin/app/readylytics/health/domain/scoring/SleepScoringE2eTest.kt`
  (`DailySummaryEntity`, `SleepSessionEntity`)

These entities are `@Entity`/`@PrimaryKey`/`@ColumnInfo` data classes whose only
Android surface is `androidx.room.*` **annotations** (which live in
`androidx.room:room-common`, a JVM library) — the data class bodies are pure.
`core:database-schema` currently depends on `androidx.room:room-runtime` (Android).

A JVM `core:scoring` test task cannot consume an Android module on its classpath
the same way, so one of these is required:

- **(A) Refactor the three tests to use domain fixtures.** `core:model` already
  provides `app.readylytics.health.domain.model.DailySummary` and
  `…SleepSession`. Replace the `*Entity(...)` fixture builders with their domain
  equivalents and drop `testImplementation(project(":core:database-schema"))`.
  Cleanest from a purity standpoint; some fixture-shape work.
- **(B) Make the entities JVM-consumable.** Switch `core:database-schema` from
  `room-runtime` to `room-common` (annotations only) so its entity classes are
  JVM-compatible, then keep the test dependency (possibly via the artifact-type
  rules). Smaller test diff, but `core:database-schema` still needs `room-runtime`
  for its real (Android) consumers — verify this split doesn't break them.
- **(C) Split the entity data classes** into a JVM module shared by
  `core:database-schema` and `core:scoring`'s tests. Largest structural change.

Recommendation: **(A)** — it keeps `core:scoring` genuinely dependency-free of
Android and the fixtures are straightforward. Confirm the exact replacement by
inspecting the three test files at execution time.

### 3.8 Full build + pre-commit gate

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest :core:scoring:test
./gradlew lintRelease
```

Expected: full suite green, `lintRelease` at the phase-5 baseline (0 warnings).

---

## 4. Definition of done

- `:core:scoring` is `kotlin("jvm")`; `./gradlew :core:scoring:test` passes as a
  plain JVM task.
- `BuildConfig` no longer referenced by `core:scoring`; `ScoringDebug.kt` is the
  only debug flag.
- No `dagger.*`/`hilt.*` import remains in `core:scoring`; the merged
  `ScoringModule` lives in `:app` and Hilt still binds `ScoringCalculator` and
  `RhrBaselineProvider` correctly.
- The `core:database-schema` test dependency is resolved per §3.7 and all three
  affected tests still pass.
- `:core:scoring:jacocoCoverageVerification` still holds its 80% floor.
- Update the **Status** line at the top of this document from deferred to done, and drop the
  AGP-9.4.0 precondition note.

---

## 5. Rollback

Each step is independently revertible (`git checkout` the touched files). The
safe order to back out if something downstream breaks:

1. Revert §3.5 (`core/scoring/build.gradle.kts` → Android library).
2. Restore the two deleted Hilt module files (§3.3) and delete the merged app
   `ScoringModule.kt`.
3. Revert §3.4 (`core/model/build.gradle.kts`).
4. Revert §3.2 and §3.1.

`org.gradle.caching=true` (§1) is unrelated and stays.

---

## 6. References

- `docs/superpowers/plans/2026-08-18-phase5-performance-polish.md` — Task 5
  (Step 19a), the original execution plan and its deferred-outcome note.
- Root-cause investigation (2026-08-18): `android.builtInKotlin=true` →
  built-in `kotlin-compiler-32.3.1` vs standalone `kotlin-compiler-embeddable`
  `2.3.21`/`2.4.10` classpath-snapshot incompatibility.
