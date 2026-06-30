# Readylytics — Instrumented & Unit Test Strategy
_Companion to `DATA_FLOW.md`. Covers tests that assert on the full pipeline from Health Connect
data → scoring engine → UI state, using deterministic seeded data._

---

## Overview

Tests in this strategy use a real Health Connect provider on an Android 16 (API 36) emulator
and a deterministic seeder (`testutil/HealthConnectSeeder.kt`) that inserts mathematically
predictable `RestingHeartRateRecord` and `HeartRateVariabilityRmssdRecord` data. All expected
values are computable from `testutil/SeedConstants.kt` — no magic numbers in assertions.

**Test matrix:**

| Area | Focus | Type | Count |
|------|-------|------|-------|
| 1 — Mathematical Engine | RHR nadir extraction (5th percentile) | Unit | 8 |
| 2 — Calibration (5 nights) | Dashboard calibrating state | UI + Instrumented | 7 |
| 3 — Baseline (14 nights) | Baseline unlock, score visibility | UI + Instrumented | 7 |
| 4 — Long-term Trends (42 nights) | Rolling averages, phase, charts | Instrumented | 11 |
| 5 — Edge Cases | Idempotency, boundary conditions | Instrumented + Unit | 4 |

---

## Seeder Reference

All tests call `HealthConnectClient.seedNocturnalData(period, today = today)` from
`app/src/androidTest/kotlin/app/readylytics/health/testutil/HealthConnectSeeder.kt`.

### Key constants (`SeedConstants.kt`)

```kotlin
RHR_NADIR_BPM         = 48L        // floor of all seeded nadir slots
HRV_RMSSD_MIN_MS      = 45.0       // floor of all seeded HRV records
DEFAULT_AVG_SLEEP_HRS = 8.0        // mean sleep over any cycle-of-5 nights
DEFAULT_VARIATION_HRS = 1.0

rhrNadirForDay(d) = 48 + (d % 5)              // 48, 49, 50, 51, 52, 48, ...
hrvRmssdForDay(d) = 45.0 + (d % 7) * 2.5     // 45.0, 47.5, ..., 60.0, 45.0, ...
sleepDurationForDay(d, 8.0, 1.0) via cycle [-1.0, -0.5, 0.0, 0.5, 1.0]
  → 7.0, 7.5, 8.0, 8.5, 9.0, 7.0, ...  (hours)
```

### Per-night record counts (defaults: 8 h avg, ±1 h)

| dayIndex | sleepHours | RHR records | HRV records |
|----------|------------|-------------|-------------|
| 0 | 7.0 | 70 | 7 |
| 1 | 7.5 | 70 | 7 |
| 2 | 8.0 | 80 | 8 |
| 3 | 8.5 | 80 | 8 |
| 4 | 9.0 | 90 | 9 |
| (repeats) | | | |

For DAYS_42: **3,270 RHR** and **327 HRV** records total. The read helpers in
`HealthConnectSeederTest` paginate automatically.

---

## Deterministic Expected Values

### RHR: 5th-Percentile Nadir

`SleepPercentileRhrCalculator` (`core/scoring/…/sleep/SleepPercentileRhrCalculator.kt`)
extracts the **5th percentile** of intra-sleep `RestingHeartRateRecord` values, not the minimum.

From the seeder's U-shaped BPM curve (max cap = nadirBpm + 14):

```
5th pct index = round(0.05 × (n-1))
  70 records → index 3 → sorted[3] = nadirBpm + 2
  80 records → index 4 → sorted[4] = nadirBpm + 2
  90 records → index 4 → sorted[4] = nadirBpm + 2
```

**Resulting `rhrBpm` in `DailySummary` per night:**

| dayIndex | nadirBpm | slots | expected rhrBpm |
|----------|----------|-------|----------------|
| 0 | 48 | 70 | **50** |
| 1 | 49 | 70 | **51** |
| 2 | 50 | 80 | **52** |
| 3 | 51 | 80 | **53** |
| 4 | 52 | 90 | **54** |

General rule: `rhrBpm = SeedConstants.rhrNadirForDay(d) + 2`

### RHR: Rolling Averages

```
7-day acute (days 0-6):
  values = [50, 51, 52, 53, 54, 50, 51]  sum = 361
  simple mean = 361 / 7 = 51.57 bpm

42-day chronic:
  8 complete cycles of [50,51,52,53,54] + [50,51] extra
  sum = 8×260 + 101 = 2181
  simple mean = 2181 / 42 = 51.93 bpm

acute/chronic ratio = 51.57 / 51.93 ≈ 0.993  (near-balanced load)
```

⚠️ **If `EverydayHeartRateLoadCalculator` uses EMA (not simple mean)**, the above values
will differ — older nights receive exponentially lower weight. Verify in
`core/scoring/…/EverydayHeartRateLoadCalculator.kt` before asserting exact bpm figures.
Use a ±0.5 bpm tolerance as a fallback bound.

### HRV: Rolling Mean (mu) and SD (sigma)

```
7-day HRV mu (days 0-6 hrvBase edge values):
  [45.0, 47.5, 50.0, 52.5, 55.0, 57.5, 60.0]  sum = 367.5
  mean = 367.5 / 7 = 52.5 ms

42-day HRV mu:
  6 complete cycles of 7 → mean = 52.5 ms  (exact, no remainder)

HRV sigma (population SD of one 7-day cycle):
  variance = Σ(x - 52.5)² / 7 = [56.25+25+6.25+0+6.25+25+56.25] / 7 = 175 / 7 = 25.0
  σ = √25.0 = 5.0 ms  (sample SD with Bessel = 5.59 ms)
  Assert: DailySummary.hrvSigmaMssd ∈ [5.0, 6.0]
```

⚠️ **nocturnalHrv source unknown.** The expected mu above assumes the edge (h=0) value
per night equals `hrvRmssdForDay(d)`. If the engine uses the 3-AM-nearest record or the
nightly average, the expected mu shifts. Confirm in `ComputeSleepMetricsUseCase.kt` before
hardcoding 52.5.

### Calibration constants

```
MIN_SESSIONS_FOR_CALIBRATION = 7     ScoringConstants.kt (via ComputeSleepMetricsUseCase.kt:243)
CALIBRATION_DAYS             = 7     AuditTrailFactory.kt
PROVISIONAL_DAYS             = 42    AuditTrailFactory.kt

Phase lookup (snapshotCalibrationPhase):
  nights < 7   → "Calibrating"
  nights < 42  → "Establishing Baseline"
  nights ≥ 42  → "Mature"

isCalibrating = (totalValidHrvNights < 7)  set in DailySummary
```

---

## Test Case Inventory

### Test File Layout

```
app/src/androidTest/kotlin/app/readylytics/health/
├── testutil/
│   ├── SeedConstants.kt          ← constants + math helpers (existing)
│   ├── HealthConnectSeeder.kt    ← seedNocturnalData() (existing)
│   └── HealthConnectSeederTest.kt ← seeder smoke tests (existing)
│
├── scoring/
│   └── SleepPercentileRhrCalculatorTest.kt     ← Area 1 (unit, pure Kotlin)
│
├── integration/
│   ├── CalibrationStateTest.kt                 ← Area 2
│   ├── BaselineStateTest.kt                    ← Area 3
│   └── LongTermTrendsTest.kt                   ← Area 4
│
└── ui/
    ├── CalibrationBannerUiTest.kt              ← Areas 2-3 (Compose UI)
    └── TrendChartUiTest.kt                     ← Area 4 (TC-T10)
```

---

### Area 1 — Mathematical Engine (Unit Tests)

**File:** `scoring/SleepPercentileRhrCalculatorTest.kt`
**No Android deps.** Construct records as plain data objects.
**Class under test:** `SleepPercentileRhrCalculator` (`core/scoring`)

| ID | Test name | Input | Expected output |
|----|-----------|-------|----------------|
| TC-M1 | `p5_day0_equals_nadirPlusTwo` | 70 records, nadirBpm=48, nadirSlot=40 | `rhrBpm == 50` |
| TC-M2 | `nadirSlot_time_within_3amWindow` | Same 70 records | `nadirTime` in [02:57, 03:03] UTC |
| TC-M3 | `p5_allFiveNights_parameterized` | Records for d=0..4 independently | `rhrBpm == nadirBpm + 2` for each |
| TC-M4 | `highSlots_notInfluencing_p5` | 70 records (43 slots at bpm=62) | `rhrBpm < 55`; `rhrBpm != 62` |
| TC-M5 | `p5_lessThan_overnightMean` | 70 records (mean ≈ 60.3) | `rhrBpm < 55` |
| TC-M6 | `bedtimeOffset_nadirAlways_in3amWindow` | d=0..4 (offsets 0,+15,+30,-15,-30 min) | All nadir times in [02:30, 03:30] UTC |
| TC-M7 | `hrv_archPeak_atNightMidpoint` | 7 HRV records, d=0 | max=52.5 at h=3; edges=45.0 |
| TC-M8 | `hrv_minimum_equalsSeedConstant` | 7 HRV records, d=0 | `min == 45.0 == HRV_RMSSD_MIN_MS` |

---

### Area 2 — Calibration State: 5 Nights

**Setup for all:** `client.seedNocturnalData(SeedPeriod.DAYS_5, today = today)`

| ID | Test name | Type | Expected output |
|----|-----------|------|----------------|
| TC-C1 | `isCalibrating_true_with5Nights` | Instrumented | `dailySummary.isCalibrating == true` |
| TC-C2 | `calibrationBanner_visible_with5Nights` | Compose UI | Banner node displayed |
| TC-C3 | `calibrationBanner_exactText` | Compose UI | `"Calibrating — averages shown may improve as more nights are recorded."` |
| TC-C4 | `phase_calibrating_with5Nights` | Instrumented | `snapshotCalibrationPhase == "Calibrating"` |
| TC-C5 | `sleepScore_restricted_during_calibration` | Compose UI | No numeric score node (or `"--"` placeholder) |
| TC-C6 | `isCalibrating_true_with6Nights` | Instrumented | `isCalibrating == true` (boundary: 6 < 7) |
| TC-C7 | `isCalibrating_false_at7Nights` | Instrumented | `isCalibrating == false` (7 ≥ 7) |

---

### Area 3 — Baseline State: 14 Nights

**Setup for all:** `client.seedNocturnalData(SeedPeriod.DAYS_14, today = today)`

| ID | Test name | Type | Expected output |
|----|-----------|------|----------------|
| TC-B1 | `isCalibrating_false_with14Nights` | Instrumented | `isCalibrating == false` |
| TC-B2 | `calibrationBanner_gone_with14Nights` | Compose UI | Banner node does not exist |
| TC-B3 | `phase_establishingBaseline_with14Nights` | Instrumented | `snapshotCalibrationPhase == "Establishing Baseline"` |
| TC-B4 | `sleepScore_numeric_visible_with14Nights` | Compose UI | Score node has text parseable as Int in [0,100] |
| TC-B5 | `dashboard_rhr_displays50bpm` | Compose UI | `"50"` visible in RHR section |
| TC-B6 | `hrvBaseline_unlocked_with14Nights` | Instrumented | `nocturnalHrv > 0`; `hrvBaseline != null` |
| TC-B7 | `baselineObservationCount_equals14` | Instrumented | `baselineObservationCount == 14` |

---

### Area 4 — Long-Term Trends: 42 Nights

**Setup for all:** `client.seedNocturnalData(SeedPeriod.DAYS_42, today = today)`

| ID | Test name | Type | Expected value | Tolerance |
|----|-----------|------|---------------|-----------|
| TC-T1 | `phase_mature_at42Nights` | Instrumented | `"Mature"` | exact |
| TC-T2 | `rhr42dayChronicAvg_equals51_93` | Instrumented | `rhrChronicAvg == 51.93 bpm` | ±0.05 |
| TC-T3 | `rhr7dayAcuteAvg_equals51_57` | Instrumented | `rhrAcuteAvg == 51.57 bpm` | ±0.05 |
| TC-T4 | `strainRatio_near1_stableLoad` | Instrumented | `strainRatioEverydayHr ∈ [0.98, 1.02]` | range |
| TC-T5 | `hrv7dayMu_equals52_5` | Instrumented | `hrvMuMssd == 52.5 ms` | ±0.1 |
| TC-T6 | `hrv42dayMu_equals7dayMu` | Instrumented | `hrvMuMssd == 52.5 ms` | ±0.1 |
| TC-T7 | `hrvSigma_inExpectedRange` | Instrumented | `hrvSigmaMssd ∈ [5.0, 6.0]` | range |
| TC-T8 | `rawRhr_minRecord_equals48bpm` | HC query | `records.minOf { bpm } == 48L` | exact |
| TC-T9 | `rawHrv_minRecord_equals45ms` | HC query | `records.minOf { rmssd } == 45.0` | exact |
| TC-T10 | `trendChart_renders42DataPoints` | Compose UI | chart accessibility nodes == 42 | exact |
| TC-T11 | `loadScore_strainRatio_stable` | Instrumented | `strainRatioEverydayHr ∈ [0.95, 1.05]` | range |

---

### Area 5 — Edge Cases & Boundary Conditions

| ID | Test name | Type | Expected output |
|----|-----------|------|----------------|
| TC-E1 | `doubleSeed_rhrBpm_unchanged` | Instrumented | `rhrBpm == 50` after 2× DAYS_5 seed |
| TC-E2 | `days5ThenDays42_recordCount_noDuplication` | HC query | count == `expectedRhrCount(DAYS_42)` |
| TC-E3 | `meanSleepDuration_exactlyAvgSleepHours` | Pure unit | `mean ∈ [7.99, 8.01]` for DAYS_5 and DAYS_42 |
| TC-E4 | `allNadir_withinWindow_42Nights` | HC query | 0 violations of [02:30, 03:30] UTC |

---

## Prerequisites Before Implementing Areas 1, 4

Three unknowns must be confirmed by reading source before assertions are hardcoded:

| # | Question | Where to look | Impact |
|---|----------|--------------|--------|
| P1 | Does `SleepPercentileRhrCalculator.collect()` require a `SleepSessionRecord` to define the time window? | `SleepPercentileRhrCalculator.kt` | If yes, seeder must also insert `SleepSessionRecord` |
| P2 | Which HRV record does `nocturnalHrv` use per night — min, mean, or 3-AM-nearest? | `ComputeSleepMetricsUseCase.kt` | Affects expected mu = 52.5 ms assumption |
| P3 | Is chronic RHR a simple mean or EMA? | `EverydayHeartRateLoadCalculator.kt` | Affects TC-T2 (51.93) and TC-T3 (51.57) |

---

## GrantPermissionRule Setup (copy-paste for each new test class)

```kotlin
@get:Rule
val grantPermissions: GrantPermissionRule = GrantPermissionRule.grant(
    "android.permission.health.READ_HEART_RATE",
    "android.permission.health.WRITE_HEART_RATE",
    "android.permission.health.WRITE_RESTING_HEART_RATE",
    "android.permission.health.READ_HEART_RATE_VARIABILITY",
    "android.permission.health.WRITE_HEART_RATE_VARIABILITY",
)
```

Required dependency (already in `app/build.gradle.kts`): `androidTestImplementation("androidx.test:rules:1.6.1")`

---

## Scoring Engine Constants Cross-Reference

| Constant | Value | File |
|----------|-------|------|
| `MIN_SESSIONS_FOR_CALIBRATION` | 7 | `ScoringConstants.kt` |
| `CALIBRATION_DAYS` | 7 | `AuditTrailFactory.kt` |
| `PROVISIONAL_DAYS` | 42 | `AuditTrailFactory.kt` |
| `ACUTE_DAYS` | 7 | `ScoringConstants.kt` |
| `CHRONIC_DAYS` | 42 | `ScoringConstants.kt` |
| `BASELINE_DAYS` | 30 | `ScoringConstants.kt` |
| `HRV_MU_WINDOW_DAYS` | 7 | `ScoringConstants.kt` |
| `HRV_SIGMA_WINDOW_DAYS` | 56 | `ScoringConstants.kt` |
| Percentile used for RHR nadir | 5 | `SleepPercentileRhrCalculator.kt` (default param) |
