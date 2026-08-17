# Phase 3 Implementation Plan — Module Extraction, DI Distribution, Test Relocation, Coverage Floor (Steps 09–12)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute Phase 3 of `internal-docs/plans/ARCHITECTURE_REMEDIATION_PLAN.md`: extract a `core:database-schema` module (Step 09), distribute the Hilt modules to the modules that own their impls (Step 10), relocate 76 misplaced unit-test files out of `:app` into the core modules that own their subjects (Step 11), and add a 60% line-coverage floor on `app.readylytics.health.data.repository` in `:core:database` (Step 12). The last task updates `ARCHITECTURE_REMEDIATION_PLAN.md` to mark Steps 09–12 DONE with Outcome sections.

**Architecture:** Pure module + dependency refactor. `core:database-schema` becomes the home of all Room DAO interfaces and entities (pure types, no Room compiler, no @Database — `HealthDatabase` stays in `core:database`). Hilt `@Binds`/`@Provides` modules move to the module owning each impl (`core:database`, `core:scoring`, `core:healthconnect`); `:app` keeps only the 9 binds whose impls live in `:app`. Tests follow their subjects module-by-module; the coverage floor is enforced per-module with the same `JacocoCoverageVerification` shape `core:scoring`/`core:healthconnect` already use. No scoring formulas are touched — the determinism tests and golden snapshots are the proof.

**Tech Stack:** Kotlin, Gradle multi-module, Room (runtime + KSP, no room-compiler in the new module), Hilt/KSP (no Hilt Gradle plugin in core modules — proven by `core:scoring`/`core:healthconnect`), kotlinx.serialization, Robolectric + androidx.test:core for Room unit tests, Jacoco per-module coverage verification, git mv (history-preserving moves).

---

## Conventions That Apply to Every Task

- **Branch:** `feat/code-review` (phases 0–2 live there; it is 4 commits ahead of `main`). Verify with `git branch --show-current`; if not on it, stop and ask.
- **Before ANY move, run** `git status` and confirm the tree is clean.
- **Every moved file keeps its `package` statement unchanged.** Kotlin package = directory, so `git mv` to a new directory under the target module's `src/main/kotlin` (or `src/test/kotlin`) with the SAME relative path preserves the package. Do not rewrite `package` lines.
- **Test-count invariant (non-negotiable):** the total number of unit tests must stay **2,971 with 0 failures**. Moves never add or drop tests — a change in the count means a file was lost or duplicated. Record the exact count from the `testDebugUnitTest` summary line (e.g. "2971 tests completed, 0 failed") after every task.
- **Scoring math is OFF-LIMITS.** Do not edit `domain/scoring/**` production code, coefficients, or formulas. Any build failure in a moved test must be fixed via imports, test deps, or test-side code — never by changing production scoring logic. `WalkForwardDeterminismTest`/`ScoringDeterminismRegressionTest`/`SyncScopeDeterminismTest`/golden snapshot tests MUST pass identically before and after every task.
- **Pre-commit ritual (every task):** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`. Run `./gradlew lintRelease` once before the PR at the end.
- **codegraph:** after creating files run `codegraph index`; after any `git mv` run `codegraph sync`. Verify no stale paths in later searches.
- **Documentation:** `internal-docs/DATA_FLOW.md` is load-bearing (see Task 09). Public docs are unaffected by this phase (no behavior change).
- **Executable never needs `cd`:** all Gradle commands run from the repo root.

---

## Corrections to the Phase-3 spec in ARCHITECTURE_REMEDIATION_PLAN.md

The remediation plan was written before live verification. The following are **corrections** (not deviations). Document them in the final `ARCHITECTURE_REMEDIATION_PLAN.md` update exactly as written here:

1. **`core:database-schema` needs NO Room KSP/compiler.** The plan's step-09 snippet lists `alias(libs.plugins.ksp)`; it is unnecessary — DAO implementations are generated where `@Database` lives (`core:database`), exactly as `core:model` works today. The new module needs only the serialization plugin + `room.runtime` (annotations) + `kotlinx.serialization.json`.
2. **The step-09 plan understates the module dependency graph.** `:core:healthconnect` imports `data.local.dao`/`data.local.entity` in 6 main files (5 data-mappers + `HealthChangeSynchronizerImpl`), and `:app` imports them in 3 files (`DatabaseModule.kt`, `HealthDeviceRepository.kt`, `LocalRestoreManager.kt`). All of these need `implementation(project(":core:database-schema"))`. `:core:scoring` needs only `testImplementation(project(":core:database-schema"))` (its 3 tests `ComputeSleepMetricsUseCaseTest`, `BaselineFreezeBehaviorTest`, `SleepScoringE2eTest` import entities).
3. **Step 10 needs NO Hilt Gradle plugin anywhere.** `core:database` already has `ksp(libs.hilt.compiler)` (the plan claims it does not). `core:scoring` and `core:healthconnect` already build `@Binds`/`@Provides` with only KSP. The Hilt Gradle plugin only adds `@AndroidEntryPoint`/`@HiltViewModel` support, which core modules do not use.
4. **`RepositoryModule` holds 25 binds, not the "11/14" in the plan's notes.** Split is by where each impl lives: **14 → `core:database`** (10 `data/repository/*Impl` + `RoomAuditTrailRepository` + `RoomHealthIngestionStore` + `SelectedSourcePrunerImpl` + `SessionLinkReconcilerImpl` — enumerated exactly in Task 10 Step 4a), **2 scoring binds → `core:scoring`** (`ScoringCalculator`, `RhrBaselineProvider`), **9 → stay in `:app`** (impls in `app/data/preferences`).
5. **`DatabaseModule.kt` carries `internal fun requireDatabaseReady` (line 143)**, which the app's `DatabaseMigrationTest` imports. After the module moves to `core:database`, `internal` is no longer visible to `:app` — change it to a plain `fun` (drop `internal`) in the same move. `DatabaseMigrationTest` itself STAYS in `:app` (it tests the app DI wiring).
6. **DAO tests cannot move to `core:database-schema`.** They build `HealthDatabase` via `Room.inMemoryDatabaseBuilder` (`core:database`) → that would create a Gradle project cycle (`:core:database-schema --test--> :core:database --main--> :core:database-schema`). All Room-backed tests go to **`:core:database`**, and `core:database` keeps its own `dao/AuditEventDao.kt` + `entity/AuditEventEntity.kt`.
7. **Step 11's "48 name-matched tests" generator badly undercounts.** It only catches tests whose filename equals a subject filename. The import-based done-when (Task 11 Step 5) reveals the true scope: **76 test files + 4 test-support files** must leave `:app`. Among them: all 18 DAO/query/pagination tests, the golden-fixture package `domain/scoring/golden/` (8 files), `ScoringRepositoryBiphasicIntegrationTest`/`ScoringRepositoryN1Test`, the determinism suite, and the `BaselineComputer*` equivalence tests. 8 test files exercise `ScoringRepositoryImpl` in `:app` today — keeping them there would split golden fixtures across modules (breaking step 14) and starve `core:database`'s `data.repository` coverage (breaking step 12).
8. **`WalkForwardDeterminismTest`, `BackfillBaselinesUseCaseTest`, `SleepMetricsHelpersTest`, `BaselineComputer*`, and the golden package have subjects in `core:scoring` but import `ScoringHistoryRepositoryImpl`/DAOs → they must go to `core:database`** (which depends on `core:scoring`), not `core:scoring` (which cannot depend on `core:database`).
9. **`DatabaseMigrationTest` stays in `:app`** (correction to the step-11 "move everything named Migration" note) because it imports the app's `di.requireDatabaseReady`.
10. **Serializers/value-type tests whose subjects live in `:app` stay in `:app`** even though they import `core:model` value types (e.g. `CardConfigurationsSerializerTest`, `SyncPreferencesTest`, `UIPreferencesDeviceTest`). Their subjects are app classes; the import-based detector is a candidate enumerator, not the decision rule — the rule is "subject lives in a core module".

---

## Task 09: Extract `core:database-schema` (entities + DAOs)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `core/database-schema/build.gradle.kts`
- Modify: `build.gradle.kts` (root — `coverageProjects` list)
- Modify: `core/database/build.gradle.kts`
- Modify: `core/healthconnect/build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `database-benchmark/build.gradle.kts`
- Modify: `core/scoring/build.gradle.kts`
- Modify: `core/model/build.gradle.kts`
- Move (git mv): 34 files `core/model/.../data/local/{dao,entity}/` → `core/database-schema/.../data/local/{dao,entity}/`
- Modify: `internal-docs/DATA_FLOW.md`

- [ ] **Step 1: Verify the precondition — `core:model` has zero Room usage outside `data/local`**

Run: `rtk grep -rln "androidx.room" core/model/src/main/kotlin/ | rtk grep -v "data/local/"` — expect NO output (no Room references outside `dao/` and `entity/`). This is what makes removing `room.runtime` from `core:model` safe.

- [ ] **Step 2: Register the module in `settings.gradle.kts`**

Edit `settings.gradle.kts` — add `include(":core:database-schema")` directly after `include(":core:model")`:

```kotlin
include(":core:model")
include(":core:database-schema")
```

- [ ] **Step 3: Create `core/database-schema/build.gradle.kts`**

```kotlin
plugins {
    id("readylytics.android-library-conventions")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.readylytics.health.core.databaseschema"
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.kotlinx.serialization.json)
}
```

Notes: package of the moved code stays `app.readylytics.health.data.local.*` (namespace is independent of package). `room.runtime` provides the `@Entity/@Dao/@PrimaryKey` annotations and `Flow` return types resolve via `androidx.core.ktx`. No room-compiler, no ksp plugin, no Hilt.

- [ ] **Step 4: Add `:core:database-schema` to the root coverage aggregate**

Edit root `build.gradle.kts` line 22–28 — add it after `":core:model",`:

```kotlin
val coverageProjects = listOf(
    ":app",
    ":core:model", ":core:database-schema", ":core:scoring", ":core:database", ":core:healthconnect",
    ...
)
```

- [ ] **Step 5: Move the 34 files with `git mv` (packages unchanged)**

```bash
src=core/model/src/main/kotlin/app/readylytics/health/data/local
dst=core/database-schema/src/main/kotlin/app/readylytics/health/data/local
mkdir -p "$dst/dao" "$dst/entity"
for f in "$src"/dao/*.kt; do git mv "$f" "$dst/dao/"; done
for f in "$src"/entity/*.kt; do git mv "$f" "$dst/entity/"; done
```

Verify: `rtk ls "$dst/dao"` → 17 files (16 DAO interfaces + `SleepHrSample.kt`), `rtk ls "$dst/entity"` → 17 files (16 entities + `LocalDateSerializer.kt`). `core/model/.../data/local/` must now be empty.

- [ ] **Step 6: Add the consumer dependencies**

`core/database/build.gradle.kts` (after `implementation(project(":core:model"))`):

```kotlin
    implementation(project(":core:database-schema"))
```

`core/healthconnect/build.gradle.kts`:

```kotlin
    implementation(project(":core:database-schema"))
```

`app/build.gradle.kts` (after `implementation(project(":core:model"))`):

```kotlin
    implementation(project(":core:database-schema"))
```

`database-benchmark/build.gradle.kts` (its Room DAO access needs entity types on the compile classpath):

```kotlin
    implementation(project(":core:database-schema"))
```

`core/scoring/build.gradle.kts` (test-only; 3 existing tests import entities):

```kotlin
    testImplementation(project(":core:database-schema"))
```

- [ ] **Step 7: Remove the now-unused Room dep from `core:model`**

Edit `core/model/build.gradle.kts` — delete line 12 `implementation(libs.room.runtime)`. Keep `room.runtime` out; `core:model` no longer references Room types.

- [ ] **Step 8: Compile everything and confirm the Room schema is unchanged**

```bash
./gradlew :core:database-schema:compileDebugKotlin :core:database:compileDebugKotlin :core:healthconnect:compileDebugKotlin :core:scoring:compileDebugKotlin :app:compileDebugKotlin :database-benchmark:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. If a module fails with unresolved Room/entity references, it means a consumer was missed — check its `import app.readylytics.health.data.local.*` usage and add `implementation(project(":core:database-schema"))`.

Then verify the schema is byte-identical:

```bash
git status --short core/database/schemas/
```

Expected: NO output (the `@Database` and its migrations live in `core:database`; the moved entities are byte-identical, so no schema file changes).

- [ ] **Step 9: Bootstrap the per-module detekt baseline**

```bash
./gradlew :core:database-schema:detektBaseline
```

Expected: creates `core/database-schema/detekt-baseline.xml`. (Each module has its own baseline file — see `build-logic/.../readylytics.kotlin-android-conventions.gradle.kts`.)

- [ ] **Step 10: Update the load-bearing `internal-docs/DATA_FLOW.md`**

Find the module map / data-pipeline section that says DAOs/entities live in `core:model`. Update it to state:
- All Room DAO interfaces + entities live in **`core:database-schema`** (`app.readylytics.health.data.local.{dao,entity}`).
- `core:database` keeps `HealthDatabase`, `AuditEventDao`/`AuditEventEntity`, `Converters`, migrations, and the generated DAO implementations.
- `core:healthconnect` mappers import DAOs/entities from `core:database-schema`.
- The ingestion path (Health Connect → mappers → DAOs) is otherwise unchanged.

- [ ] **Step 11: Reindex codegraph**

```bash
codegraph sync
```

- [ ] **Step 12: Full verify**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, **2,971 tests, 0 failed** (unchanged), determinism tests green.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "refactor(core): extract core:database-schema module for DAOs and entities (phase 3 step 09)"
```

---

## Task 10: Distribute Hilt modules (Database, HealthConnect, Scoring, Repository)

Precondition from Task 09 verified: DI qualifiers (`@IoDispatcher`/`@DefaultDispatcher`/`@MainDispatcher`) already live in `core:model/.../di/CoroutineDispatchers.kt` (package `app.readylytics.health.di`) and `ApplicationScope` is in `core:model/.../di/ApplicationScope.kt`. No app-internal imports remain in the files moved below (verified for the 4 infra files and all 4 DI modules).

**Step 1 — infra + DatabaseModule → `core:database`** (one commit)

**Files:**
- Move (git mv), packages unchanged:
  - `app/src/main/kotlin/app/readylytics/health/data/security/KeyProvider.kt` → `core/database/src/main/kotlin/app/readylytics/health/data/security/`
  - `app/src/main/kotlin/app/readylytics/health/data/security/AndroidKeystoreKeyProvider.kt` → `core/database/src/main/kotlin/app/readylytics/health/data/security/`
  - `app/src/main/kotlin/app/readylytics/health/data/security/SqlCipherKeyManager.kt` → `core/database/src/main/kotlin/app/readylytics/health/data/security/`
  - `app/src/main/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGate.kt` → `core/database/src/main/kotlin/app/readylytics/health/data/migration/`
  - `app/src/main/kotlin/app/readylytics/health/di/DatabaseModule.kt` → `core/database/src/main/kotlin/app/readylytics/health/di/`
  - `app/src/test/kotlin/app/readylytics/health/data/security/SqlCipherKeyManagerTest.kt` → `core/database/src/test/kotlin/app/readylytics/health/data/security/`
  - `app/src/test/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGateTest.kt` → `core/database/src/test/kotlin/app/readylytics/health/data/migration/`
  - `app/src/androidTest/kotlin/app/readylytics/health/data/security/SqlCipherKeyManagerCrossProcessRaceTest.kt` → `core/database/src/androidTest/kotlin/app/readylytics/health/data/security/`

- [ ] **Step 1a: Move the 8 files**

```bash
appm=app/src/main/kotlin/app/readylytics/health
coredb=core/database/src/main/kotlin/app/readylytics/health
mkdir -p "$coredb/data/security" "$coredb/data/migration" "$coredb/di"
git mv "$appm/data/security/KeyProvider.kt" "$coredb/data/security/"
git mv "$appm/data/security/AndroidKeystoreKeyProvider.kt" "$coredb/data/security/"
git mv "$appm/data/security/SqlCipherKeyManager.kt" "$coredb/data/security/"
git mv "$appm/data/migration/DatabaseReadinessGate.kt" "$coredb/data/migration/"
git mv "$appm/di/DatabaseModule.kt" "$coredb/di/"
mkdir -p core/database/src/test/kotlin/app/readylytics/health/data/security core/database/src/test/kotlin/app/readylytics/health/data/migration
git mv app/src/test/kotlin/app/readylytics/health/data/security/SqlCipherKeyManagerTest.kt core/database/src/test/kotlin/app/readylytics/health/data/security/
git mv app/src/test/kotlin/app/readylytics/health/data/migration/DatabaseReadinessGateTest.kt core/database/src/test/kotlin/app/readylytics/health/data/migration/
mkdir -p core/database/src/androidTest/kotlin/app/readylytics/health/data/security
git mv app/src/androidTest/kotlin/app/readylytics/health/data/security/SqlCipherKeyManagerCrossProcessRaceTest.kt core/database/src/androidTest/kotlin/app/readylytics/health/data/security/
```

- [ ] **Step 1b: Fix `requireDatabaseReady` visibility**

Edit `core/database/src/main/kotlin/app/readylytics/health/di/DatabaseModule.kt` line 143: change `internal fun requireDatabaseReady(...)` to `fun requireDatabaseReady(...)` (drop `internal`) so `:app`'s `DatabaseMigrationTest` can still call it. Do not change the body.

- [ ] **Step 1c: Add test deps + testOptions to `core/database/build.gradle.kts`**

In the `android { }` block add:

```kotlin
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
```

In `dependencies` add:

```kotlin
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockk)
```

(`mockk` is needed now — `SqlCipherKeyManagerTest`/`DatabaseReadinessGateTest` use it, and Task 11's movers depend on it.)

- [ ] **Step 1d: Build + test + commit**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
```

Expected: 2,971 tests, 0 failed. `DatabaseMigrationTest` in `:app` still passes via the public `requireDatabaseReady`. Commit:

```bash
git add -A
git commit -m "refactor(core:database): move sqlcipher/db-module + infra from app, fix requireDatabaseReady visibility (phase 3 step 10a)"
```

**Step 2 — HealthConnectModule → `core:healthconnect`** (one commit)

**Files:**
- Move (git mv): `app/src/main/kotlin/app/readylytics/health/di/HealthConnectModule.kt` → `core/healthconnect/src/main/kotlin/app/readylytics/health/di/`

- [ ] **Step 2a: Move + verify**

```bash
mkdir -p core/healthconnect/src/main/kotlin/app/readylytics/health/di
git mv app/src/main/kotlin/app/readylytics/health/di/HealthConnectModule.kt core/healthconnect/src/main/kotlin/app/readylytics/health/di/
./gradlew :core:healthconnect:compileDebugKotlin :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. No build-file change is needed — `core:healthconnect` already has `ksp(libs.hilt.compiler)` and its 2 `@Binds` targets (`HealthConnectRepositoryImpl`, `HealthChangeSynchronizerImpl`) live in the module.

- [ ] **Step 2b: Commit**

```bash
git add -A
git commit -m "refactor(core:healthconnect): move HealthConnectModule binds home (phase 3 step 10b)"
```

**Step 3 — ScoringModule + new ScoringBindsModule → `core:scoring`** (one commit)

**Files:**
- Move (git mv): `app/src/main/kotlin/app/readylytics/health/di/ScoringModule.kt` → `core/scoring/src/main/kotlin/app/readylytics/health/di/`
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/di/ScoringBindsModule.kt`

- [ ] **Step 3a: Move ScoringModule**

```bash
mkdir -p core/scoring/src/main/kotlin/app/readylytics/health/di
git mv app/src/main/kotlin/app/readylytics/health/di/ScoringModule.kt core/scoring/src/main/kotlin/app/readylytics/health/di/
```

`ScoringModule` moves unchanged: it is an `object` whose `@Provides` `RasSourceModeBootstrapUseCase(settingsRepository: SettingsRepository, scoringHistoryRepository: ScoringHistoryRepository)` needs only `core:model` types, both available in `core:scoring`.

- [ ] **Step 3b: Create `ScoringBindsModule.kt`**

```kotlin
package app.readylytics.health.di

import app.readylytics.health.domain.scoring.AdaptiveRhrBaselineProvider
import app.readylytics.health.domain.scoring.CompositeScoringCalculator
import app.readylytics.health.domain.scoring.RhrBaselineProvider
import app.readylytics.health.domain.scoring.ScoringCalculator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ScoringBindsModule {
    @Binds
    @Singleton
    abstract fun bindScoringCalculator(impl: CompositeScoringCalculator): ScoringCalculator

    @Binds
    @Singleton
    abstract fun bindRhrBaselineProvider(impl: AdaptiveRhrBaselineProvider): RhrBaselineProvider
}
```

- [ ] **Step 3c: Build + commit**

```bash
./gradlew :core:scoring:compileDebugKotlin :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. Commit:

```bash
git add -A
git commit -m "refactor(core:scoring): move scoring DI (provides + binds) home (phase 3 step 10c)"
```

**Step 4 — split `RepositoryModule`: 14 binds → `core:database`, 2 → `core:scoring` (already moved in Step 3b), 9 stay in `:app`** (one commit)

**Files:**
- Create: `core/database/src/main/kotlin/app/readylytics/health/di/RepositoryModule.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/di/RepositoryModule.kt` (trim to 9 binds)

- [ ] **Step 4a: Create `core/database/.../di/RepositoryModule.kt` with the 14 impl-home binds**

```kotlin
package app.readylytics.health.di

import app.readylytics.health.data.audit.RoomAuditTrailRepository
import app.readylytics.health.data.local.RoomHealthIngestionStore
import app.readylytics.health.data.local.SelectedSourcePrunerImpl
import app.readylytics.health.data.local.SessionLinkReconcilerImpl
import app.readylytics.health.data.repository.BloodPressureRepositoryImpl
import app.readylytics.health.data.repository.BodyFatRepositoryImpl
import app.readylytics.health.data.repository.DailyMetricsRepositoryImpl
import app.readylytics.health.data.repository.DailySummaryRepositoryImpl
import app.readylytics.health.data.repository.HeartRateRepositoryImpl
import app.readylytics.health.data.repository.InsightDismissalRepositoryImpl
import app.readylytics.health.data.repository.ScoringHistoryRepositoryImpl
import app.readylytics.health.data.repository.ScoringRepositoryImpl
import app.readylytics.health.data.repository.WeightRepositoryImpl
import app.readylytics.health.data.repository.WorkoutRepositoryImpl
import app.readylytics.health.domain.audit.AuditTrailRepository
import app.readylytics.health.domain.repository.BloodPressureRepository
import app.readylytics.health.domain.repository.BodyFatRepository
import app.readylytics.health.domain.repository.DailyMetricsRepository
import app.readylytics.health.domain.repository.DailySummaryRepository
import app.readylytics.health.domain.repository.HeartRateRepository
import app.readylytics.health.domain.repository.InsightDismissalRepository
import app.readylytics.health.domain.repository.ScoringHistoryRepository
import app.readylytics.health.domain.repository.ScoringRepository
import app.readylytics.health.domain.repository.WeightRepository
import app.readylytics.health.domain.repository.WorkoutRepository
import app.readylytics.health.domain.sync.HealthIngestionStore
import app.readylytics.health.domain.sync.SelectedSourcePruner
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDailySummaryRepository(impl: DailySummaryRepositoryImpl): DailySummaryRepository

    @Binds
    @Singleton
    abstract fun bindDailyMetricsRepository(impl: DailyMetricsRepositoryImpl): DailyMetricsRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository

    @Binds
    @Singleton
    abstract fun bindHeartRateRepository(impl: HeartRateRepositoryImpl): HeartRateRepository

    @Binds
    @Singleton
    abstract fun bindWeightRepository(impl: WeightRepositoryImpl): WeightRepository

    @Binds
    @Singleton
    abstract fun bindBodyFatRepository(impl: BodyFatRepositoryImpl): BodyFatRepository

    @Binds
    @Singleton
    abstract fun bindBloodPressureRepository(impl: BloodPressureRepositoryImpl): BloodPressureRepository

    @Binds
    @Singleton
    abstract fun bindInsightDismissalRepository(impl: InsightDismissalRepositoryImpl): InsightDismissalRepository

    @Binds
    @Singleton
    abstract fun bindAuditTrailRepository(impl: RoomAuditTrailRepository): AuditTrailRepository

    @Binds
    @Singleton
    abstract fun bindScoringHistoryRepository(impl: ScoringHistoryRepositoryImpl): ScoringHistoryRepository

    @Binds
    @Singleton
    abstract fun bindScoringRepository(impl: ScoringRepositoryImpl): ScoringRepository

    @Binds
    @Singleton
    abstract fun bindSelectedSourcePruner(impl: SelectedSourcePrunerImpl): SelectedSourcePruner

    @Binds
    @Singleton
    abstract fun bindHealthIngestionStore(impl: RoomHealthIngestionStore): HealthIngestionStore

    @Binds
    @Singleton
    abstract fun bindSessionLinkReconciler(impl: SessionLinkReconcilerImpl): SessionLinkReconciler
}
```

That is **14 @Binds** (the 10 `data/repository/*Impl` + `RoomAuditTrailRepository` + `RoomHealthIngestionStore` + `SelectedSourcePrunerImpl` + `SessionLinkReconcilerImpl`). The 2 scoring binds (`ScoringCalculator`, `RhrBaselineProvider`) already moved in Step 3b.

- [ ] **Step 4b: Trim the app `RepositoryModule.kt` to the 9 preference binds**

Rewrite `app/src/main/kotlin/app/readylytics/health/di/RepositoryModule.kt` so it keeps ONLY these binds (impls all live in `app/data/preferences`), with the same `package app.readylytics.health.di` and `abstract class RepositoryModule` shape:

```kotlin
package app.readylytics.health.di

import app.readylytics.health.data.preferences.CardConfigurationRepositoryImpl
import app.readylytics.health.data.preferences.DataStoreCircadianThresholdPreferences
import app.readylytics.health.data.preferences.HealthChangeTokenStoreImpl
import app.readylytics.health.data.preferences.ResyncCheckpointStoreImpl
import app.readylytics.health.data.preferences.SettingsRepository
import app.readylytics.health.data.preferences.SleepLayoutRepositoryImpl
import app.readylytics.health.data.preferences.VitalsLayoutRepositoryImpl
import app.readylytics.health.data.preferences.WorkoutDetailLayoutRepositoryImpl
import app.readylytics.health.data.preferences.WorkoutsLayoutRepositoryImpl
import app.readylytics.health.domain.dashboard.CardConfigurationRepository
import app.readylytics.health.domain.preferences.CircadianThresholdPreferences
import app.readylytics.health.domain.preferences.SettingsRepository as DomainSettingsRepository
import app.readylytics.health.domain.sleep.SleepLayoutRepository
import app.readylytics.health.domain.sync.HealthChangeTokenStore
import app.readylytics.health.domain.sync.ResyncCheckpointStore
import app.readylytics.health.domain.vitals.VitalsLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.domain.workouts.WorkoutsLayoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindHealthChangeTokenStore(impl: HealthChangeTokenStoreImpl): HealthChangeTokenStore

    @Binds
    @Singleton
    abstract fun bindResyncCheckpointStore(impl: ResyncCheckpointStoreImpl): ResyncCheckpointStore

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsRepository): DomainSettingsRepository

    @Binds
    @Singleton
    abstract fun bindCircadianThresholdPreferences(
        impl: DataStoreCircadianThresholdPreferences,
    ): CircadianThresholdPreferences

    @Binds
    @Singleton
    abstract fun bindCardConfigurationRepository(impl: CardConfigurationRepositoryImpl): CardConfigurationRepository

    @Binds
    @Singleton
    abstract fun bindVitalsLayoutRepository(impl: VitalsLayoutRepositoryImpl): VitalsLayoutRepository

    @Binds
    @Singleton
    abstract fun bindSleepLayoutRepository(impl: SleepLayoutRepositoryImpl): SleepLayoutRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutsLayoutRepository(impl: WorkoutsLayoutRepositoryImpl): WorkoutsLayoutRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutDetailLayoutRepository(
        impl: WorkoutDetailLayoutRepositoryImpl,
    ): WorkoutDetailLayoutRepository
}
```

- [ ] **Step 4c: Build + full test + commit**

```bash
./gradlew :core:database:compileDebugKotlin :app:compileDebugKotlin
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 2,971 tests, 0 failed (Hilt graph complete: every `@Inject` constructor in `:app`/feature modules still resolvable). Sanity-check no core impl is still bound from `:app`:

```bash
rtk grep -rln "app.readylytics.health.data.repository\.\|app.readylytics.health.data.audit\.\|data.local.RoomHealthIngestionStore\|data.local.SelectedSourcePrunerImpl\|data.local.SessionLinkReconcilerImpl" app/src/main/kotlin/app/readylytics/health/di/
```

Expected: only `app/src/main/kotlin/app/readylytics/health/di/RepositoryModule.kt` matches are gone — i.e. no output. Commit:

```bash
git add -A
git commit -m "refactor(di): split RepositoryModule by impl home across core modules (phase 3 step 10d)"
```

**Step 5 — post-step-10 integrity**

- [ ] **Step 5a: codegraph sync + verify**

```bash
codegraph sync
```

- [ ] **Step 5b: Confirm the 6 app-scoped DI files that stay**

`app/src/main/kotlin/app/readylytics/health/di/` now holds only: `AndroidResourceProvider.kt`, `CoroutineDispatchersModule.kt`, `DataStoreModule.kt` (381 lines — provider of `DataStore<UserPreferencesProto>`, stays), `FeaturePortModule.kt`, `UtilModule.kt`, `WorkerModule.kt`, plus the trimmed `RepositoryModule.kt`. `DatabaseModule.kt`, `HealthConnectModule.kt`, `ScoringModule.kt` are gone. Verify:

```bash
rtk ls app/src/main/kotlin/app/readylytics/health/di/
```

---

## Task 11: Relocate 76 misplaced test files into the owning core modules

**Strategy.** The remediation plan's name-match generator is replaced by an **import-based detector** (user decision): a test belongs in a core module if the production classes it imports are defined in `core/**` main source. The move set below was produced by running that detector over `app/src/test` and classifying every hit: **76 test files + 4 test-support files move**; everything else either imports no core classes or is an app-scoped exception (subject lives in `:app`, or it is a whole-project meta test).

**Detector-silent tests (routed by subject location, not imports).** Four files have no `app.readylytics.health.*` imports (so the detector cannot route them) but their subjects unambiguously live in a core module by package:
- `StatusToMetricStatusTest.kt`, `StrainRatioStatusTest.kt` — package `app.readylytics.health.domain.model`; their subject (`MetricStatus` mapping) is `core:model/domain/model`. Move to `core:model`.
- `CanonicalMetricDisplayAuditTest.kt` — package `app.readylytics.health.domain.display`; subject is `core:model/domain/display`. Move to `core:model`.
- `DomainModelTest.kt` — package `app.readylytics.health.data.local.entity`; it is a pure entity construction test (no Room, no `HealthDatabase`, no `ApplicationProvider`), so it belongs **with the entities in `core:database-schema`**, not `core:database`.

**Support files that move with their package (NOT counted as tests):** `RecordingTransactionRunner.kt` (→ core:healthconnect), `GoldenFixtureDataBuilder.kt`, `GoldenFixtureTestFakes.kt`, `SyntheticDatasetGenerator.kt` (→ core:database).

**App-scoped exceptions that stay in `:app`** (documented; each is justified):
- Subjects live in `:app`: `DatabaseReadyStartupInitializerTest`, `PreferencesPrewarmerTest`, `ProductionReadinessStaticTest`, `CrashReportViewModelTest`, `LogcatCaptureViewModelTest`, `SyncViewModelTest`, `CrashReportShareIntentTest`, `FeaturePortBindingTest`, all 8 `workers/*Test` (`BirthdayCheckWorkerTest`, `DataCleanupWorkerTest`, `DataRollupWorkerTest`, `DatabaseMigrationWorkerTest`, `HealthResyncWorkerTest`, `LocalBackupWorkerTest`, `PeriodicHealthSyncWorkerTest`, `SyncNotificationsTest`), `DatabaseKeyRotatorTest`, `DatabaseMigrationControllerTest`, `UserUseCaseTest`, `HealthDeviceRepositoryTest`, `DatabaseMigrationTest` (uses app `di.requireDatabaseReady`), `EncryptionManagerTest` (package `app.readylytics.health.data.security`; its subject is the concrete app class `EncryptionManager` at `app/src/main/.../data/security/EncryptionManager.kt:23` — `core:model` holds only the interface).
- Named by the user as exceptions: `SettingsRepositoryTest`, `CardConfigurationRepositoryTest`, `VitalsLayoutRepositoryTest`, `SleepLayoutRepositoryTest`, `WorkoutsLayoutRepositoryTest`, `WorkoutDetailLayoutRepositoryTest`, `LocalBackupManagerTest`, `LocalRestoreManagerTest`, `LocalBackupSerializationRegressionTest`, `RestorePreferenceEnumRoundTripTest`, `SecureFileLogSinkTest`, `V7DatabaseMigrator*`, `CleanArchTest` (Konsist needs a module that sees the whole project).
- Serializer/value tests whose subjects are `:app` classes even though they import `core:model` value types: `CardConfigurationsSerializerTest`, `LegacyCardConfigurationSerializerTest`, `SyncPreferencesTest`, `UIPreferencesDeviceTest`, `UIPreferencesLastGlobalDisplayModeTest`, `VitalsLayoutConfigurationsSerializerTest` (subjects `CardConfigurationsSerializer`, `SyncPreferences`, `UIPreferences`, `VitalsLayoutConfigurations` are `app/data/preferences`).
- Whole-project meta test: `DocumentationDriftTest`.

**File list — `core:database` (44 test files + 3 support files).** All move with `git mv`, package unchanged; `core:database` after Task 09/10 has `core:model`, `core:scoring`, `core:database-schema`, `room.runtime`, robolectric, `androidx.test.core`, and (after this task) `mockk`.

From `app/src/test/kotlin/app/readylytics/health/`:

`data/local/dao/` (18): `BodyFatRecordDaoTest.kt`, `SourceRecordDaoTest.kt`, `BodyTemperatureRecordDaoTest.kt`, `WeightRecordDaoTest.kt`, `BloodPressureRecordDaoTest.kt`, `SleepStageDaoTest.kt`, `MinuteBucketDaoTest.kt`, `ConflictTargetedUpsertTest.kt`, `DailySummaryDaoQueryTest.kt`, `DeleteBySourceRecordIdTest.kt`, `DeleteByTimestampTest.kt`, `HeartRateMinuteBucketQueryTest.kt`, `HeartRateRangeAggregateQueryTest.kt`, `KeysetPaginationTest.kt`, `OffsetPaginationTest.kt`, `RollingWindowTest.kt`, `SleepMetricDaoOrderingTest.kt`, `SourceRecordIdSargableQueryTest.kt`

`data/repository/` (4): `ScoringRepositoryImplTest.kt`, `ScoringRepositoryBiphasicIntegrationTest.kt`, `ScoringRepositoryN1Test.kt`, `SelectedDateRepositoryTest.kt`

`data/mapper/` (1): `DailySummaryMapperTest.kt`

`data/local/` (5): `DataRollupManagerTest.kt`, `DailySummaryEntitySerializationTest.kt`, `QueryOptimizationTest.kt`, `WorkoutModelTrimpIngestionDeterminismTest.kt`, `WorkoutRouteIngestionPreservationTest.kt`

`domain/scoring/` (10): `BackfillBaselinesUseCaseTest.kt`, `BaselineComputerBackfillEquivalenceTest.kt`, `BaselineComputerN1FixTest.kt`, `BaselineComputerWalkForwardEquivalenceTest.kt`, `ScoringDeterminismRegressionTest.kt`, `ScoringPointInTimeRegressionTest.kt`, `ScoringSyncScopeOutputsDeterminismTest.kt`, `SyncScopeDeterminismTest.kt`, `WalkForwardDeterminismTest.kt`, and `domain/scoring/sleep/SleepMetricsHelpersTest.kt`

`domain/scoring/golden/` (5 tests + 3 support): `GoldenFixtureDataBuilderTest.kt`, `GoldenFixtureWalkForwardTest.kt`, `ScoringEquivalenceGoldenTest.kt`, `SyntheticDatasetGeneratorTest.kt`, `WalkForwardTransactionEquivalenceTest.kt` + `GoldenFixtureDataBuilder.kt`, `GoldenFixtureTestFakes.kt`, `SyntheticDatasetGenerator.kt`

`domain/sync/link/` (1): `SessionLinkReconcilerTest.kt`

**File list — `core:model` (19 test files).** No new deps needed (`mockk`, coroutines-test, junit already present). NOTE: `EncryptionManagerTest` does **not** move here — its subject is the concrete app class `app.readylytics.health.data.security.EncryptionManager` (see the app-scoped exceptions above); `core:model` holds only the `domain.security.EncryptionManager` interface.

From `app/src/test/kotlin/app/readylytics/health/`:

`workers/` (1): `WorkerSchedulerTest.kt`
`data/preferences/` (1): `GenderTest.kt`
`domain/cache/` (1): `DailyMetricCacheTest.kt`
`domain/util/` (1): `RetentionBoundsTest.kt`
`domain/model/` (5): `ResultTest.kt`, `HealthDataTypeTest.kt`, `LoadSourceSelectorTest.kt`, `InsightTypeTest.kt`, `DailyMetricsMapperTest.kt`
`domain/display/` (2): `MetricFormatterTest.kt`, `CanonicalMetricDisplayAuditTest.kt`
`domain/error/` (1): `ErrorBoundaryTest.kt`
`domain/sync/` (1): `HealthIngestionStoreTest.kt`
`domain/sync/link/` (1): `SessionLinkSweepPropertyTest.kt`
`data/healthconnect/` (3): `HeartRateMapperTest.kt`, `StepsMapperTest.kt`, `WorkoutMapperTest.kt` (subjects `HeartRateMapper`/`StepsMapper`/`WorkoutMapper` are `core:model/domain/sync/mappers`; package `data.healthconnect` is preserved)
`domain/model/` (2): `StatusToMetricStatusTest.kt`, `StrainRatioStatusTest.kt`

**File list — `core:database-schema` (1 test file).** Requires adding test deps (see Step 1b below) — this module currently has none.

From `app/src/test/kotlin/app/readylytics/health/`:

`data/local/entity/` (1): `DomainModelTest.kt` (pure entity construction; no Room/`HealthDatabase` — see detector-silent note)

**File list — `core:healthconnect` (10 test files + 1 support file).** No new deps (`mockk` present).

From `app/src/test/kotlin/app/readylytics/health/`:

`data/mapper/` (2): `OxygenSaturationDataMapperTest.kt`, `BodyTemperatureDataMapperTest.kt`
`data/healthconnect/` (1): `HealthChangeSynchronizerImplTest.kt`
`domain/sync/` (7): `DailySyncUseCaseTest.kt`, `ResyncRangeUseCaseTest.kt`, `ForegroundSyncControllerTest.kt`, `FullHistoricalResyncUseCaseTest.kt`, `FirstSetupDummyIngestionFlowTest.kt`, `ResyncCheckpointResumeTest.kt`, `DeviceSourceFilterTest.kt`
`domain/sync/` (support): `RecordingTransactionRunner.kt`

**File list — `core:scoring` (2 test files).**

From `app/src/test/kotlin/app/readylytics/health/`:

`domain/scoring/` (2): `HealthMetricsCalculatorTest.kt`, `CircadianConsistencyRepositoryTest.kt`

**Import fixes on move (all one-line, in the target module after the move):**

`import app.readylytics.health.data.preferences.SettingsRepository` → `import app.readylytics.health.domain.preferences.SettingsRepository` in these **11** files (the test mocks the type, so the interface swap compiles unchanged):

- `ForegroundSyncControllerTest.kt`, `FullHistoricalResyncUseCaseTest.kt`, `HealthChangeSynchronizerImplTest.kt` (core:healthconnect)
- `ScoringRepositoryImplTest.kt`, `ScoringRepositoryBiphasicIntegrationTest.kt`, `ScoringDeterminismRegressionTest.kt`, `BackfillBaselinesUseCaseTest.kt`, `ScoringPointInTimeRegressionTest.kt`, `ScoringRepositoryN1Test.kt`, `ScoringSyncScopeOutputsDeterminismTest.kt` (core:database)
- `CircadianConsistencyRepositoryTest.kt` (core:scoring)

`import app.readylytics.health.data.security.EncryptionManager` → `import app.readylytics.health.domain.security.EncryptionManager` in these **3** files:

- `ScoringRepositoryN1Test.kt`, `ScoringSyncScopeOutputsDeterminismTest.kt` (core:database)
- `CircadianConsistencyRepositoryTest.kt` (core:scoring)

So `ScoringRepositoryN1Test.kt`, `ScoringSyncScopeOutputsDeterminismTest.kt`, and `CircadianConsistencyRepositoryTest.kt` each need **both** fixes.

- [ ] **Step 0: Record the baseline**

```bash
./gradlew testDebugUnitTest
```

Expected: "2971 tests completed, 0 failed" (or whatever the current true total is — record it; every later task must reproduce the same total).

- [ ] **Step 1: Move the `core:database` set (44 + 3)**

Run the whole block, then apply the 9 one-line import edits (7 `SettingsRepository` + 2 `EncryptionManager`) across the 7 core:database files that need them (`ScoringRepositoryImplTest`, `ScoringRepositoryBiphasicIntegrationTest`, `ScoringDeterminismRegressionTest`, `BackfillBaselinesUseCaseTest`, `ScoringPointInTimeRegressionTest`, `ScoringRepositoryN1Test`, `ScoringSyncScopeOutputsDeterminismTest`):

```bash
src=app/src/test/kotlin/app/readylytics/health
dst=core/database/src/test/kotlin/app/readylytics/health
for p in data/local/dao data/repository data/mapper data/local domain/scoring domain/scoring/sleep domain/scoring/golden domain/sync/link; do mkdir -p "$dst/$p"; done
for f in BodyFatRecordDaoTest SourceRecordDaoTest BodyTemperatureRecordDaoTest WeightRecordDaoTest BloodPressureRecordDaoTest SleepStageDaoTest MinuteBucketDaoTest ConflictTargetedUpsertTest DailySummaryDaoQueryTest DeleteBySourceRecordIdTest DeleteByTimestampTest HeartRateMinuteBucketQueryTest HeartRateRangeAggregateQueryTest KeysetPaginationTest OffsetPaginationTest RollingWindowTest SleepMetricDaoOrderingTest SourceRecordIdSargableQueryTest; do git mv "$src/data/local/dao/$f.kt" "$dst/data/local/dao/"; done
for f in ScoringRepositoryImplTest ScoringRepositoryBiphasicIntegrationTest ScoringRepositoryN1Test SelectedDateRepositoryTest; do git mv "$src/data/repository/$f.kt" "$dst/data/repository/"; done
git mv "$src/data/mapper/DailySummaryMapperTest.kt" "$dst/data/mapper/"
for f in DataRollupManagerTest DailySummaryEntitySerializationTest QueryOptimizationTest WorkoutModelTrimpIngestionDeterminismTest WorkoutRouteIngestionPreservationTest; do git mv "$src/data/local/$f.kt" "$dst/data/local/"; done
for f in BackfillBaselinesUseCaseTest BaselineComputerBackfillEquivalenceTest BaselineComputerN1FixTest BaselineComputerWalkForwardEquivalenceTest ScoringDeterminismRegressionTest ScoringPointInTimeRegressionTest ScoringSyncScopeOutputsDeterminismTest SyncScopeDeterminismTest WalkForwardDeterminismTest; do git mv "$src/domain/scoring/$f.kt" "$dst/domain/scoring/"; done
git mv "$src/domain/scoring/sleep/SleepMetricsHelpersTest.kt" "$dst/domain/scoring/sleep/"
for f in GoldenFixtureDataBuilder GoldenFixtureDataBuilderTest GoldenFixtureTestFakes GoldenFixtureWalkForwardTest ScoringEquivalenceGoldenTest SyntheticDatasetGenerator SyntheticDatasetGeneratorTest WalkForwardTransactionEquivalenceTest; do git mv "$src/domain/scoring/golden/$f.kt" "$dst/domain/scoring/golden/"; done
git mv "$src/domain/sync/link/SessionLinkReconcilerTest.kt" "$dst/domain/sync/link/"
```

- [ ] **Step 1b: Add `mockk` to `core:database` test deps, then move `DomainModelTest` to `core:database-schema`**

`core:database` has no `mockk` today, but 16 of the moved files import `io.mockk` (`ScoringRepositoryImplTest`, `SelectedDateRepositoryTest`, the determinism/`BaselineComputer*`/`BackfillBaselinesUseCaseTest` set, `SessionLinkReconcilerTest`). Add to `core/database/build.gradle.kts` dependencies:

```kotlin
    testImplementation(libs.mockk)
```

`DomainModelTest` is a pure entity test and goes with the entities in `core:database-schema`, which currently has no test source set. Add to `core/database-schema/build.gradle.kts` dependencies:

```kotlin
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
```

Then move it:

```bash
mkdir -p core/database-schema/src/test/kotlin/app/readylytics/health/data/local/entity
git mv app/src/test/kotlin/app/readylytics/health/data/local/entity/DomainModelTest.kt core/database-schema/src/test/kotlin/app/readylytics/health/data/local/entity/
```

- [ ] **Step 2: Move the `core:model` set (19)**

```bash
src=app/src/test/kotlin/app/readylytics/health
dst=core/model/src/test/kotlin/app/readylytics/health
for p in workers data/preferences domain/cache domain/util domain/model domain/display domain/error domain/sync domain/sync/link data/healthconnect; do mkdir -p "$dst/$p"; done
git mv "$src/workers/WorkerSchedulerTest.kt" "$dst/workers/"
git mv "$src/data/preferences/GenderTest.kt" "$dst/data/preferences/"
git mv "$src/domain/cache/DailyMetricCacheTest.kt" "$dst/domain/cache/"
git mv "$src/domain/util/RetentionBoundsTest.kt" "$dst/domain/util/"
for f in ResultTest HealthDataTypeTest LoadSourceSelectorTest InsightTypeTest DailyMetricsMapperTest; do git mv "$src/domain/model/$f.kt" "$dst/domain/model/"; done
git mv "$src/domain/display/MetricFormatterTest.kt" "$dst/domain/display/"
git mv "$src/domain/display/CanonicalMetricDisplayAuditTest.kt" "$dst/domain/display/"
git mv "$src/domain/error/ErrorBoundaryTest.kt" "$dst/domain/error/"
git mv "$src/domain/sync/HealthIngestionStoreTest.kt" "$dst/domain/sync/"
git mv "$src/domain/sync/link/SessionLinkSweepPropertyTest.kt" "$dst/domain/sync/link/"
for f in HeartRateMapperTest StepsMapperTest WorkoutMapperTest; do git mv "$src/data/healthconnect/$f.kt" "$dst/data/healthconnect/"; done
git mv "$src/domain/model/StatusToMetricStatusTest.kt" "$dst/domain/model/"
git mv "$src/domain/model/StrainRatioStatusTest.kt" "$dst/domain/model/"
```

- [ ] **Step 3: Move the `core:healthconnect` set (10 + 1)**

```bash
src=app/src/test/kotlin/app/readylytics/health
dst=core/healthconnect/src/test/kotlin/app/readylytics/health
for p in data/mapper data/healthconnect domain/sync; do mkdir -p "$dst/$p"; done
git mv "$src/data/mapper/OxygenSaturationDataMapperTest.kt" "$dst/data/mapper/"
git mv "$src/data/mapper/BodyTemperatureDataMapperTest.kt" "$dst/data/mapper/"
git mv "$src/data/healthconnect/HealthChangeSynchronizerImplTest.kt" "$dst/data/healthconnect/"
for f in DailySyncUseCaseTest ResyncRangeUseCaseTest ForegroundSyncControllerTest FullHistoricalResyncUseCaseTest FirstSetupDummyIngestionFlowTest ResyncCheckpointResumeTest DeviceSourceFilterTest; do git mv "$src/domain/sync/$f.kt" "$dst/domain/sync/"; done
git mv "$src/domain/sync/RecordingTransactionRunner.kt" "$dst/domain/sync/"
```

Then apply the 3 `SettingsRepository` import edits (`ForegroundSyncControllerTest`, `FullHistoricalResyncUseCaseTest`, `HealthChangeSynchronizerImplTest`).

- [ ] **Step 4: Move the `core:scoring` set (2)**

```bash
src=app/src/test/kotlin/app/readylytics/health
dst=core/scoring/src/test/kotlin/app/readylytics/health
mkdir -p "$dst/domain/scoring"
git mv "$src/domain/scoring/HealthMetricsCalculatorTest.kt" "$dst/domain/scoring/"
git mv "$src/domain/scoring/CircadianConsistencyRepositoryTest.kt" "$dst/domain/scoring/"
```

Then apply BOTH import edits in `CircadianConsistencyRepositoryTest.kt` (`SettingsRepository` + `EncryptionManager`).

- [ ] **Step 5: Run the import-based done-when check**

Run this from the repo root (a fresh detection over `app/src/test`):

```bash
python3 - <<'EOF'
import os, re
root = "/Users/grl3lb/git/Readylytics"
core_main = {}
for m in ["core", "feature"]:
    for dp, dn, fs in os.walk(os.path.join(root, m)):
        if "/build/" in dp or "/src/test/" in dp or "/src/androidTest/" in dp: continue
        if "src/main/kotlin" not in dp: continue
        mod = "/".join(dp.replace(root+"/","").split("/")[:2])
        for f in fs:
            if f.endswith(".kt"): core_main[f[:-3]] = mod
def classes_in_file(p):
    s = open(p, encoding="utf-8", errors="replace").read()
    return set(re.findall(r"^\s*(?:public\s+|internal\s+)?(?:open\s+|abstract\s+|sealed\s+|data\s+|enum\s+)?(?:class|interface|object|typealias)\s+([A-Z]\w*)", s, re.M))
viol = []
for dp, dn, fs in os.walk(os.path.join(root, "app", "src", "test")):
    if "src/test/kotlin" not in dp: continue
    for f in sorted(fs):
        if not f.endswith(".kt"): continue
        p = os.path.join(dp, f)
        s = open(p, encoding="utf-8", errors="replace").read()
        imps = re.findall(r"^import (app\.readylytics\.health\.(?:[\w.]+))$", s, re.M)
        hits = []
        for imp in imps:
            segs = imp.split(".")
            for i in range(len(segs), 0, -1):
                cls = segs[i-1]
                if cls in ("health",) or cls[0].islower(): continue
                if cls in core_main:
                    hits.append(core_main[cls]); break
        if hits: viol.append((f, sorted(set(hits))))
for f, mods in viol: print(f"{f}: {mods}")
EOF
```

The output must list **exactly the app-scoped exception set** from Task 11's header (subjects in `:app`, user-named exceptions, serializer/value tests, `DocumentationDriftTest`). Any additional file printed means its subject lives in a core module — move it to that module with the same mechanics as Steps 1–4 and re-run. When the output contains only the exception set, record it in `internal-docs/plans/remediation-baseline.txt` under a new `§11 app-scoped exceptions` block.

- [ ] **Step 6: Full verify**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, **2,971 tests, 0 failed** — unchanged. The determinism + golden tests must still pass (they now run in `core:database`). If any moved test fails:
- For Room/Robolectric tests: confirm `testOptions` + `robolectric`/`androidx.test.core` are present in `core:database` (Task 10 Step 1c).
- For `io.mockk` import errors: confirm `testImplementation(libs.mockk)` is present (Task 11 Step 1b).
- For import errors: the import-fix list above.
- Never touch production scoring code.

- [ ] **Step 7: codegraph sync + commit**

```bash
codegraph sync
git add -A
git commit -m "test: relocate 76 misplaced unit tests to owning core modules (phase 3 step 11)"
```

---

## Task 12: `data.repository` coverage floor in `:core:database`

**Files:**
- Modify: `core/database/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 0: Confirm the coverage precondition the user flagged**

After Task 11, all 8 tests that exercise `ScoringRepositoryImpl` now run inside `core:database`, so its own exec data covers the `app.readylytics.health.data.repository` package. Verify the actual ratio BEFORE wiring the gate:

```bash
./gradlew :core:database:testDebugUnitTest --rerun-tasks
```

Then inspect the HTML report for `data/repository` — but the gate itself is the verification (Step 2). If the package ratio is below 0.60 at this point, STOP and report: the move was incomplete (a `data.repository` test file was left in `:app` — re-check Task 11 Step 5 output).

- [ ] **Step 1: Add Jacoco to `core:database/build.gradle.kts`**

Add `id("jacoco")` to the `plugins {}` block, `enableUnitTestCoverage` to the `android {}` block, and append the verification task. Final file:

```kotlin
plugins {
    id("readylytics.android-library-conventions")
    id("readylytics.room-conventions")
    alias(libs.plugins.kotlin.serialization)
    id("jacoco")
}

android {
    namespace = "app.readylytics.health.core.database"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
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
    dependsOn("testDebugUnitTest")

    executionData.setFrom(
        fileTree(layout.buildDirectory.get()) {
            include("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        },
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
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
            includes = listOf("app.readylytics.health.data.repository")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = 0.60.toBigDecimal()
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:scoring"))
    implementation(project(":core:database-schema"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)

    // Room & SQLCipher
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}
```

- [ ] **Step 2: Verify the floor passes on a cold run (evidence, not a cached green)**

```bash
./gradlew :core:database:jacocoCoverageVerification --rerun-tasks
```

Expected: BUILD SUCCESSFUL. The `--rerun-tasks` flag is mandatory — a cached green is not evidence (same caveat as remediation-plan step 01).

- [ ] **Step 3: Demonstrate the floor is enforced (fail → revert)**

Temporarily raise the limit to `0.70` in `core/database/build.gradle.kts`, then:

```bash
./gradlew :core:database:jacocoCoverageVerification --rerun-tasks
```

Expected: FAIL with "Rule violated for package app.readylytics.health.data.repository ... lines covered ratio is X, but expected minimum is 0.70". This proves the gate measures real coverage. Revert `0.70` → `0.60` and re-run — expected BUILD SUCCESSFUL.

- [ ] **Step 4: Wire the per-module gates into CI**

Edit `.github/workflows/ci.yml` — replace the two coverage steps:

```yaml
      - name: Generate coverage report
        if: ${{ !cancelled() }}
        run: ./gradlew jacocoTestReport

      - name: Enforce coverage gate (>= 30%)
        if: ${{ !cancelled() }}
        run: ./gradlew jacocoCoverageVerification
```

with:

```yaml
      - name: Generate coverage report
        if: ${{ !cancelled() }}
        run: ./gradlew jacocoTestReport

      - name: Enforce coverage gates (aggregate + module floors)
        if: ${{ !cancelled() }}
        run: ./gradlew jacocoCoverageVerification :core:scoring:jacocoCoverageVerification :core:healthconnect:jacocoCoverageVerification :core:database:jacocoCoverageVerification
```

- [ ] **Step 5: Full verify + commit**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest
```

Expected: 2,971 tests, 0 failed. Commit:

```bash
git add -A
git commit -m "build(core:database): enforce 60% line floor on data.repository package (phase 3 step 12)"
```

---

## Task 13: Update `ARCHITECTURE_REMEDIATION_PLAN.md` (final task)

**Files:**
- Modify: `internal-docs/plans/ARCHITECTURE_REMEDIATION_PLAN.md`

- [ ] **Step 1: Mark Steps 09–12 DONE**

Change the status of Steps 09, 10, 11, 12 from the in-progress/pending marker to `✅ DONE (Phase 3 — see below)` in the document's sequence/status section.

- [ ] **Step 2: Add Outcome sections for each step**

Append one Outcome section per step (mirroring how Phases 0–2 recorded their outcomes), each with: what changed, the commit hashes (from `git log --oneline`), and the verification evidence:

- **Step 09:** `core:database-schema` extracted with 34 files (17 DAOs incl. `SleepHrSample.kt`, 17 entities incl. `LocalDateSerializer.kt`); consumers updated; `room.runtime` removed from `core:model`; schema dir unchanged; per-module detekt baseline added; `coverageProjects` updated; `DATA_FLOW.md` updated.
- **Step 10:** infra + `DatabaseModule` (with public `requireDatabaseReady`) → `core:database` + 3 tests; `HealthConnectModule` → `core:healthconnect`; `ScoringModule` + `ScoringBindsModule` → `core:scoring`; `RepositoryModule` split 14/2/9 across `core:database`/`core:scoring`/`:app`.
- **Step 11:** 76 test files + 4 support files relocated (44+3 → `core:database`, 19 → `core:model`, 1 → `core:database-schema`, 10+1 → `core:healthconnect`, 2 → `core:scoring`); `mockk` added to `core:database` test deps, `junit`+`kotlin-test` added to `core:database-schema`; 11 `SettingsRepository` + 3 `EncryptionManager` import fixes; `EncryptionManagerTest` retained in `:app` (its subject is the concrete app class); `remediation-baseline.txt` §11 app-scoped exceptions recorded; total tests unchanged at 2,971.
- **Step 12:** 60% LINE floor on `app.readylytics.health.data.repository` in `core:database` verified with `--rerun-tasks`; floor demonstrated (0.70 fail → 0.60 pass); CI coverage step extended to all 4 verification tasks.

- [ ] **Step 3: Update the header + sequence table**

Update the plan's header status (e.g. "Phase 3 complete"), the sequence table markers for Steps 09–12, and the "next step" pointer (Step 13/next phase) to say Phase 3 is complete and the next phase is cleared to start.

- [ ] **Step 4: Update `remediation-baseline.txt`**

Add a `§Phase 3` block recording: the step-11 exception list, the "test count invariant held at 2,971 across steps 09–12" line, and the step-12 floor value.

- [ ] **Step 5: Final verification + commit**

```bash
./gradlew ktlintFormat && ./gradlew testDebugUnitTest && ./gradlew lintRelease
```

Expected: BUILD SUCCESSFUL (lintRelease clean), 2,971 tests, 0 failed. Commit:

```bash
git add -A
git commit -m "docs: close out phase 3 (steps 09-12) in remediation plan and baseline (phase 3 step 13)"
```

---

## Self-Review Notes (already applied)

- **Spec coverage:** every step of the remediation plan's Phase 3 (09 module extract, 10 DI distribution, 11 test relocation, 12 coverage floor) maps to one task; the final task updates the remediation doc as required. Step 14's `ScoringGoldenSnapshotTest` is protected by relocating the golden package in Task 11 (correction 7).
- **No placeholders:** every move, build-file edit, and import fix is stated explicitly with commands; the only "at execution" decision is the import-based detector output, which the plan defines as the acceptance check.
- **Type consistency:** `core:database-schema` namespace `app.readylytics.health.core.databaseschema`; module path `:core:database-schema`; packages preserved as `app.readylytics.health.data.local.{dao,entity}` everywhere.