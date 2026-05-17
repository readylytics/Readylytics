# Refactor SleepMetricsHelpersTest to use String IDs

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `SleepMetricsHelpersTest.kt` to use `String` IDs for `SleepSessionEntity` and `HeartRateRecordEntity`, and update entity constructors to match the current codebase.

**Architecture:** Update test helper functions and test cases to align with the domain model changes where IDs were transitioned from `Long` to `String`.

**Tech Stack:** Kotlin, MockK, JUnit 4, Room (Entities/DAOs).

---

### Task 1: Update Helper Functions

**Files:**
- Modify: `app/src/test/java/com/gregor/lauritz/healthdashboard/domain/scoring/sleep/SleepMetricsHelpersTest.kt`

- [ ] **Step 1: Update `mockSession` helper**

```kotlin
private fun mockSession(
    id: String = "1",
    startTime: Long = 1000L,
    endTime: Long = 10000L,
    durationMinutes: Int = 150,
    endZoneOffsetSeconds: Int? = 0,
): SleepSessionEntity =
    SleepSessionEntity(
        id = id,
        startTime = startTime,
        endTime = endTime,
        durationMinutes = durationMinutes,
        deepSleepMinutes = 90,
        remSleepMinutes = 30,
        lightSleepMinutes = 20,
        awakeMinutes = 10,
        efficiency = 0.9f,
        endZoneOffsetSeconds = endZoneOffsetSeconds,
    )
```

- [ ] **Step 2: Update `mockHeartRateRecord` helper**

```kotlin
private fun mockHeartRateRecord(
    timestampMs: Long = 1000L,
    bpm: Int = 60,
    sessionId: String? = null,
): HeartRateRecordEntity =
    HeartRateRecordEntity(
        id = "hr_${timestampMs}",
        beatsPerMinute = bpm,
        timestampMs = timestampMs,
        recordType = "SLEEP",
        sessionId = sessionId
    )
```

- [ ] **Step 3: Update `mockDailySummary` helper**

```kotlin
private fun mockDailySummary(
    dateMidnightMs: Long = 0L,
    nocturnalHrv: Int? = null,
): DailySummaryEntity =
    DailySummaryEntity(
        dateMidnightMs = dateMidnightMs,
        nocturnalHrv = nocturnalHrv,
    )
```

### Task 2: Update Test Cases in `CurrentNightHrvResolverTest`

**Files:**
- Modify: `app/src/test/java/com/gregor/lauritz/healthdashboard/domain/scoring/sleep/SleepMetricsHelpersTest.kt`

- [ ] **Step 1: Update session IDs and `coEvery` calls**
- Replace `1L` with `"1"` in `mockSession(id = "1")`.
- Update `coEvery { hrvDao.getSleepRmssdForSession("1") }`.

### Task 3: Update Test Cases in `WakeWindowHrCollectorTest`

**Files:**
- Modify: `app/src/test/java/com/gregor/lauritz/healthdashboard/domain/scoring/sleep/SleepMetricsHelpersTest.kt`

- [ ] **Step 1: Update session IDs and `coEvery` calls**
- Replace `1L` with `"1"`, `2L` with `"2"`.
- Ensure `mockHeartRateRecord` calls (if any in this class) are updated or use defaults.

### Task 4: Update Test Cases in `SleepNadirAnalyzerTest`

**Files:**
- Modify: `app/src/test/java/com/gregor/lauritz/healthdashboard/domain/scoring/sleep/SleepMetricsHelpersTest.kt`

- [ ] **Step 1: Update session IDs and `coEvery` calls**
- Replace `1L` with `"1"`.
- Update `coEvery { heartRateDao.getMinHrTimestamp("1") }`.

### Task 5: Verification

- [ ] **Step 1: Run the tests**
Run: `./gradlew :app:testDebugUnitTest --tests "com.gregor.lauritz.healthdashboard.domain.scoring.sleep.*"`
Expected: PASS
