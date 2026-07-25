# Startup Readiness Regression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent Hilt from resolving `HealthDatabase` while creating the application when the
external v7 migration is not complete.

**Architecture:** Keep the broad, indirectly Room-backed `SettingsRepository` behind
`dagger.Lazy` at the application boundary. Resolve it once only inside the existing
database-Ready initialization attempt; retain all current retry, cancellation, and scheduling
semantics.

**Tech Stack:** Kotlin, Hilt/Dagger, MockK, JUnit 4, Room, Gradle.

## Global Constraints

- Room must not open before `DatabaseReadiness.Ready`.
- Keep `DatabaseModule.requireDatabaseReady` fail-closed.
- Do not change migration, scoring, backup, or scheduling behavior.
- Update `internal-docs/DATA_FLOW.md` with the corrected startup dependency boundary.
- Run `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` and final
  `./gradlew lintRelease`.

---

### Task 1: Lazily resolve startup settings after database readiness

**Files:**

- Modify: `app/src/test/kotlin/app/readylytics/health/ProductionReadinessStaticTest.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/DatabaseReadyStartupInitializerTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt`
- Modify: `internal-docs/DATA_FLOW.md`

**Interfaces:**

- Consumes: `DatabaseReadiness`, `dagger.Lazy<T>`, and the existing bounded startup coordinator.
- Produces: `DatabaseReadyStartupInitializer(..., settingsRepository:
  Lazy<SettingsRepository>, ...)`.

- [ ] **Step 1: Add the static eager-graph regression**

Add to `ProductionReadinessStaticTest`:

```kotlin
@Test
fun `application keeps indirectly Room-backed settings lazy until database Ready`() {
    val content =
        projectFile(
            "app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt",
        ).readText()

    assertTrue(content.contains("lateinit var settingsRepo: Lazy<SettingsRepository>"))
    assertFalse(content.contains("lateinit var settingsRepo: SettingsRepository"))
}
```

- [ ] **Step 2: Run the static regression and verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.readylytics.health.ProductionReadinessStaticTest.application keeps indirectly Room-backed settings lazy until database Ready'
```

Expected: FAIL because the application currently injects eager `SettingsRepository`.

- [ ] **Step 3: Change startup behavior tests to a lazy settings dependency**

In `DatabaseReadyStartupInitializerTest`, add:

```kotlin
private val settingsRepositoryLazy = mockk<Lazy<SettingsRepository>>()
```

Stub it in `createInitializer()` and pass it to production:

```kotlin
private fun createInitializer(): DatabaseReadyStartupInitializer {
    every { settingsRepositoryLazy.get() } returns settingsRepository
    return DatabaseReadyStartupInitializer(
        healthSyncUseCase = healthSyncLazy,
        backfillHistoricalBaselines = backfillLazy,
        settingsRepository = settingsRepositoryLazy,
        workerScheduler = workerScheduler,
    )
}
```

Extend the migration-required assertion:

```kotlin
verify(exactly = 0) { settingsRepositoryLazy.get() }
```

Extend the successful once-only Ready assertion:

```kotlin
verify(exactly = 1) { settingsRepositoryLazy.get() }
```

- [ ] **Step 4: Implement the lazy application boundary**

In `HealthDashboardApplication` replace the eager field with:

```kotlin
@Inject
lateinit var settingsRepo: Lazy<SettingsRepository>
```

Continue passing `settingsRepo` into `DatabaseReadyStartupInitializer`.

In `DatabaseReadyStartupInitializer`, change the constructor field:

```kotlin
private val settingsRepository: Lazy<SettingsRepository>,
```

After the non-Ready/one-shot guard and inside the existing outer `try`, resolve once:

```kotlin
val settings = settingsRepository.get()
val backupSchedule = settings.backupSchedule.first()
val backgroundSyncEnabled = settings.backgroundSyncEnabled.first()
val periodicSyncMinutes =
    if (backgroundSyncEnabled) {
        settings.backgroundSyncIntervalMinutes.first()
    } else {
        null
    }
```

Leave all scheduler calls and exception/cancellation handling unchanged.

- [ ] **Step 5: Update the authoritative data-flow description**

Amend the startup-gate row in `internal-docs/DATA_FLOW.md` to state that
`SettingsRepository` is indirectly Room-backed through device preferences and is injected as
`dagger.Lazy`, then resolved only after `DatabaseReadiness.Ready`.

- [ ] **Step 6: Verify focused behavior and generated Hilt graph**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests '*DatabaseReadyStartupInitializerTest' \
  --tests '*ProductionReadinessStaticTest*database Ready*' \
  :app:hiltJavaCompileDebug
```

Expected: PASS.

Inspect:

```bash
rg -n 'injectSettingsRepo|DoubleCheck.lazy\\(settingsRepoProvider\\)' \
  app/build/generated/ksp/debug/java/app/readylytics/health/HealthDashboardApplication_MembersInjector.java
```

Expected: generated injection uses `DoubleCheck.lazy(settingsRepoProvider)`, not
`settingsRepoProvider.get()`.

- [ ] **Step 7: Run mandatory repository verification**

Run:

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
./gradlew lintRelease
git diff --check
```

Expected: all commands exit 0; formatter introduces no unrelated changes.

- [ ] **Step 8: Commit the bug fix**

```bash
git add \
  app/src/test/kotlin/app/readylytics/health/ProductionReadinessStaticTest.kt \
  app/src/test/kotlin/app/readylytics/health/DatabaseReadyStartupInitializerTest.kt \
  app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt \
  app/src/main/kotlin/app/readylytics/health/DatabaseReadyStartupInitializer.kt \
  internal-docs/DATA_FLOW.md
git commit -m "fix: defer startup settings until database ready"
```
