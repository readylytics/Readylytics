# Biphasic Nap Metric Cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Nap Duration and Naps Today metric cards to the Sleep page screen, persisting the required nap fields in Room and propagating them through the daily summary scoring pipeline.

**Architecture:** Add two new nullable properties (`supplementalSleepDurationMinutes`, `napCount`) to `DailySummary` / `DailySummaryEntity` and their corresponding display fields to `DailyMetrics`. Update `ScoringRepositoryImpl` to populate them from the biphasic `SleepDayAggregate`. Update `SleepScreen` to render a 3rd row with the two new cards.

**Tech Stack:** Kotlin, Jetpack Compose (Material Design 3), Android Room, Hilt.

## Global Constraints

- Domain logic must remain pure Kotlin (zero Android dependencies).
- Scoring calculations are read-only from the UI; the UI only displays these values.
- Centralize formatting of durations via `DateFormatUtils.formatSleepDuration()`.
- All user-facing strings must be in strings.xml and referenced via `stringResource()`.

---

### Task 1: Update Domain Models & Room Entity

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailySummary.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailyMetrics.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/DailySummaryEntity.kt`

**Interfaces:**
- Produces: Nullable `supplementalSleepDurationMinutes: Int?` and `napCount: Int?` in `DailySummary` and `DailySummaryEntity`.
- Produces: Nullable `napDurationDisplay: String?` and `napCount: Int?` in `DailyMetrics`.

- [ ] **Step 1: Update DailySummary domain model**
  Modify `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailySummary.kt` to add `supplementalSleepDurationMinutes` and `napCount`:
  ```kotlin
  // In DailySummary class definition:
  val supplementalSleepDurationMinutes: Int? = null,
  val napCount: Int? = null,
  ```

- [ ] **Step 2: Update DailyMetrics projection model**
  Modify `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailyMetrics.kt` to add `napDurationDisplay` and `napCount`:
  ```kotlin
  // In DailyMetrics class definition:
  val napDurationDisplay: String? = null,
  val napCount: Int? = null,
  ```

- [ ] **Step 3: Update DailySummaryEntity database class**
  Modify `core/model/src/main/kotlin/app/readylytics/health/data/local/entity/DailySummaryEntity.kt` to add Room columns:
  ```kotlin
  // In DailySummaryEntity class definition:
  val supplementalSleepDurationMinutes: Int? = null,
  val napCount: Int? = null,
  ```

- [ ] **Step 4: Commit changes**
  ```bash
  git commit -am "feat: add nap duration and count fields to domain models and Room entity"
  ```

---

### Task 2: Implement Database Version Bump and Migration

**Files:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt`
- Test: `core/database/src/androidTest/kotlin/app/readylytics/health/data/local/DatabaseMigrationInstrumentedTest.kt`

**Interfaces:**
- Produces: Migration `4 → 5` schema upgrade for `daily_summaries` table.

- [ ] **Step 1: Increment Database Version**
  Modify `core/database/src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt`:
  ```kotlin
  // In companion object:
  const val DATABASE_VERSION = 5
  ```

- [ ] **Step 2: Create Migration 4 to 5**
  Modify `core/database/src/main/kotlin/app/readylytics/health/data/local/DatabaseMigrations.kt`:
  ```kotlin
  // In DatabaseMigrations object:
  private val MIGRATION_4_5 =
      object : Migration(4, 5) {
          override fun migrate(db: SupportSQLiteDatabase) {
              db.execSQL("ALTER TABLE daily_summaries ADD COLUMN supplementalSleepDurationMinutes INTEGER")
              db.execSQL("ALTER TABLE daily_summaries ADD COLUMN napCount INTEGER")
          }
      }

  // Update DatabaseMigrations.all to include the new migration:
  val all: Array<Migration> =
      arrayOf(
          MIGRATION_1_2,
          MIGRATION_2_3,
          MIGRATION_3_4,
          MIGRATION_4_5,
      )
  ```

- [ ] **Step 3: Update Unit and Instrumented Migration Tests**
  Modify the migration assertions in `app/src/test/kotlin/app/readylytics/health/data/local/DatabaseMigrationTest.kt` or instrumented tests to run and verify migration 4 to 5 compiles and finishes without issues.

- [ ] **Step 4: Commit changes**
  ```bash
  git commit -am "feat: add Room migration from 4 to 5 for new nap columns"
  ```

---

### Task 3: Implement Mappers for DailySummary and DailyMetrics

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailySummaryMapper.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailyMetricsMapper.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/domain/model/DailySummaryMapperTest.kt`
- Test: `app/src/test/kotlin/app/readylytics/health/domain/model/DailyMetricsMapperTest.kt`

**Interfaces:**
- Consumes: New fields in `DailySummary` and `DailySummaryEntity`.
- Produces: Correct domain-to-entity mapping, and formatted `napDurationDisplay` in `DailyMetrics`.

- [ ] **Step 1: Implement mapping in DailySummaryMapper**
  Modify `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailySummaryMapper.kt` to map the new fields:
  ```kotlin
  // In toDomain:
  supplementalSleepDurationMinutes = entity.supplementalSleepDurationMinutes,
  napCount = entity.napCount,

  // In toEntity:
  supplementalSleepDurationMinutes = domain.supplementalSleepDurationMinutes,
  napCount = domain.napCount,
  ```

- [ ] **Step 2: Implement mapping and formatting in DailyMetricsMapper**
  Modify `core/model/src/main/kotlin/app/readylytics/health/domain/model/DailyMetricsMapper.kt`:
  ```kotlin
  // In toMetrics:
  napDurationDisplay = formatSleepDuration(summary.supplementalSleepDurationMinutes ?: 0),
  napCount = summary.napCount,
  ```

- [ ] **Step 3: Add unit tests in DailySummaryMapperTest**
  Modify `app/src/test/kotlin/app/readylytics/health/domain/model/DailySummaryMapperTest.kt` to assert that the fields round-trip correctly.

- [ ] **Step 4: Add unit tests in DailyMetricsMapperTest**
  Modify `app/src/test/kotlin/app/readylytics/health/domain/model/DailyMetricsMapperTest.kt` to verify `napDurationDisplay` formats correctly:
  ```kotlin
  @Test
  fun `nap duration maps and formats correctly`() {
      val summaryNull = DailySummary(date = date, supplementalSleepDurationMinutes = null, napCount = null)
      val metricsNull = DailyMetricsMapper.toMetrics(summaryNull, prefs)
      assertEquals("0h", metricsNull.napDurationDisplay) // Formatter output for 0
      assertNull(metricsNull.napCount)

      val summaryVal = DailySummary(date = date, supplementalSleepDurationMinutes = 45, napCount = 2)
      val metricsVal = DailyMetricsMapper.toMetrics(summaryVal, prefs)
      assertEquals("0h 45m", metricsVal.napDurationDisplay)
      assertEquals(2, metricsVal.napCount)
  }
  ```

- [ ] **Step 5: Run tests and commit**
  Run: `./gradlew :core:model:testDebugUnitTest` (or appropriate task)
  Verify: PASS
  ```bash
  git commit -am "feat: map and format nap fields in DailySummaryMapper and DailyMetricsMapper"
  ```

---

### Task 4: Hook Fields into Scoring Pipeline

**Files:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`

**Interfaces:**
- Consumes: `SleepAggregationContext.aggregate` containing supplemental segments info.
- Produces: Populated `supplementalSleepDurationMinutes` and `napCount` inside `DailySummaryEntity` during scoring.

- [ ] **Step 1: Populate fields in ScoringRepositoryImpl**
  Modify `core/database/src/main/kotlin/app/readylytics/health/data/repository/ScoringRepositoryImpl.kt`:
  ```kotlin
  // In computeDailySummary(targetDate, prefs), where DailySummaryEntity is copied (around line 266):
  var summary =
      (
          scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs)
              ?: DailySummaryEntity(dateMidnightMs = dayMidnightMs)
      ).copy(
          trimpWorkoutOnly = dailyTrimpRaw,
          trimpEverydayHr = trimpEverydayHr,
          rasWorkoutOnly = dailyRas,
          rasEverydayHr = dailyRasEverydayHr,
          totalRasWorkoutOnly = totalRasWorkoutOnly,
          totalRasEverydayHr = totalRasEverydayHr,
          everydayCoverageMinutes = everydayCoverageMinutes,
          everydayLoadConfidence = everydayLoadConfidence,
          weightKg = latestWeight?.weightKg,
          bodyFatPercent = latestBodyFat?.bodyFatPercent,
          bloodPressureSystolic = latestBP?.systolicMmHg,
          bloodPressureDiastolic = latestBP?.diastolicMmHg,
          supplementalSleepDurationMinutes = aggregatedSleep?.aggregate?.supplementalSleepDurationMinutes,
          napCount = aggregatedSleep?.aggregate?.supplementalBlocks?.size
      )
  ```

- [ ] **Step 2: Commit**
  ```bash
  git commit -am "feat: populate nap metrics from aggregate in scoring pipeline"
  ```

---

### Task 5: Add Strings Resources for UI

**Files:**
- Modify: `feature/sleep/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: 4 string resources for titles and tooltips.

- [ ] **Step 1: Add string resources**
  Modify `feature/sleep/src/main/res/values/strings.xml` to add:
  ```xml
  <string name="card_title_nap_duration">Nap Duration</string>
  <string name="card_title_nap_count">Naps Today</string>
  <string name="tooltip_nap_duration">Total supplemental sleep duration for this wake-day. Only sessions above the minimum counted duration threshold are included.</string>
  <string name="tooltip_nap_count">Number of naps or supplemental sleep sessions counted for this wake-day. Sessions shorter than the minimum counted duration setting are excluded.</string>
  ```

- [ ] **Step 2: Commit**
  ```bash
  git commit -am "feat: add nap metrics strings to feature/sleep resources"
  ```

---

### Task 6: Implement UI Cards in SleepScreen

**Files:**
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`
- Test: `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepScreenAdaptersTest.kt`

**Interfaces:**
- Consumes: `DailyMetrics.napDurationDisplay` and `DailyMetrics.napCount`.
- Produces: Renders third row with Nap Duration and Naps Today in SleepScreen.

- [ ] **Step 1: Add row to MetricsGrid**
  Modify `MetricsGrid` in `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt` to add a 3rd Row inside the Column:
  ```kotlin
  Row(
      modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
  ) {
      Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
          MetricCard(
              title = stringResource(R.string.card_title_nap_duration),
              value = metrics?.napDurationDisplay ?: DateFormatUtils.formatSleepDuration(0) ?: "0h",
              status = MetricStatus.NO_DATA,
              tooltip = stringResource(R.string.tooltip_nap_duration),
              onClick = null,
          )
      }
      Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
          MetricCard(
              title = stringResource(R.string.card_title_nap_count),
              value = metrics?.napCount?.toString() ?: "0",
              status = MetricStatus.NO_DATA,
              tooltip = stringResource(R.string.tooltip_nap_count),
              onClick = null,
          )
      }
  }
  ```

- [ ] **Step 2: Add row to MetricsGridSkeleton**
  Modify `MetricsGridSkeleton` in `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt` to add a 3rd Row inside the Column:
  ```kotlin
  Row(
      modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
  ) {
      Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
          MetricCardSkeleton()
      }
      Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
          MetricCardSkeleton()
      }
  }
  ```

- [ ] **Step 3: Add unit tests in SleepScreenAdaptersTest**
  Add test assertions verifying UI displays nap count and duration correctly or defaults to "0" and "0h".

- [ ] **Step 4: Verify and commit**
  ```bash
  git commit -am "feat: implement Nap Duration and Naps Today UI cards in SleepScreen"
  ```

---

### Task 7: Verification and Build Pass

**Files:**
- None (verification task)

- [ ] **Step 1: Format codebase**
  Run: `./gradlew ktlintFormat`
  Verify: Formats correctly with 0 errors.

- [ ] **Step 2: Run all unit tests**
  Run: `./gradlew testDebugUnitTest`
  Verify: PASS

- [ ] **Step 3: Run full release lint checks**
  Run: `./gradlew lintRelease`
  Verify: Runs successfully.
