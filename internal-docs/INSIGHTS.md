# Readylytics Insights

Total Insights: 19

---

## Elevated Blood Pressure Under High Strain

Identifier:
BP_ELEVATED_HIGH_STRAIN

Description:
Your systolic blood pressure is %1$d mmHg above your recent baseline while your strain ratio of %2$.1f indicates high training load.

Trigger:
Generated when:
- `today.bloodPressureSystolic` is not null.
- There are at least `MIN_BP_BASELINE_SAMPLES` (3) blood pressure readings in the past 7 days (excluding today).
- `drift = todaySystolic - baselineSystolic` > `BP_SYSTOLIC_DRIFT_THRESHOLD_MMHG` (10 mmHg).
- `strainRatio` > `STRAIN_HIGH_RATIO_THRESHOLD` (1.3f).

---

## Circadian Shift Delayed Recovery

Identifier:
CIRCADIAN_SHIFT_RECOVERY_MISS

Description:
No recovery signal triggered despite a rest day. This correlates with a bedtime %1$d min later than your average, which shifted your circadian rhythm and impacted overnight recovery.

Trigger:
Generated when:
- `RecoveryFlag.REST_DAY_NO_IMPACT` is present in today's recovery flags.
- `circadianResult` is of type `CircadianConsistencyResult.Ready`.
- Bedtime offset `latestBedtimeOffsetMinutes` > `CIRCADIAN_BEDTIME_OFFSET_THRESHOLD_MINUTES` (90 minutes).

---

## High Strain and Sleep Deficit

Identifier:
HIGH_STRAIN_SLEEP_DEFICIT

Description:
Your acute strain ratio of %1$.1f combined with %2$d minutes less sleep than your goal is suppressing your recovery.

Trigger:
Generated when:
- `RecoveryFlag.ILLNESS_ONSET` is present in today's recovery flags.
- `strainRatio` > `STRAIN_HIGH_RATIO_THRESHOLD` (1.3f).
- `sleepDurationMinutes` < `goalSleepMinutes * SLEEP_DEFICIT_RATIO` (0.85f).
- Note: `sleepDeficitMinutes` is calculated as `goalSleepMinutes - sleepDurationMinutes`.

---

## HRV Below Baseline Multiple Nights

Identifier:
HRV_DECLINE_STREAK

Description:
Your HRV has been below your baseline for %1$d consecutive nights, which can signal accumulating fatigue.

Trigger:
Generated when:
- The last `HRV_DECLINE_STREAK_DAYS` (3) consecutive distinct days have non-null `zLnHrv`.
- Every one of those `zLnHrv` values is < `0.0f` (indicating HRV below baseline).
- Every one of those days also has its rounded nocturnal HRV (`DailySummary.nocturnalHrv`) strictly below its rounded HRV baseline (`DailyMetricsMapper.hrvBaselineRounded`) — i.e. the same whole-ms comparison the dashboard tooltip uses to decide `↑`/`↓`/`=`. This guards against a day where `zLnHrv` is a hair negative purely from float noise in the `ln()`/mean pipeline while the displayed HRV rounds to the same ms value as baseline (dashboard would show "on baseline", not "below") — such a day no longer counts toward the streak.

---

## Possible Illness or Breathing Disruption

Identifier:
HRV_DROP_LOW_SPO2

Description:
Your HRV dropped sharply to a z-score of %1$.1f and your average sleeping blood oxygen fell to %2$.0f%%, which can indicate the onset of illness or a breathing disruption such as sleep apnea.

Trigger:
Generated when:
- `zLnHrv` < `HRV_DROP_ZSCORE_THRESHOLD` (-1.5f).
- `avgSleepingSpo2` < `SPO2_HYPOXIA_THRESHOLD` (94.0f).

---

## Late Heart Rate Nadir

Identifier:
LATE_NADIR

Description:
Your heart rate nadir occurred later than usual last night, suggesting delayed recovery.

Trigger:
Generated when:
- `RecoveryFlag.NADIR_DELAYED` is present in today's recovery flags.
- Where `RecoveryFlag.NADIR_DELAYED` is triggered when:
  `minHrTimestampMs - sessionStartMs` > `(sessionDurationMinutes * 60 * 1000L) * LATE_NADIR_THRESHOLD` (0.67f).

---

## Delayed Recovery with Elevated Resting Heart Rate

Identifier:
LATE_NADIR_ELEVATED_RHR

Description:
Your heart rate nadir was delayed and your resting heart rate was %1$.1f bpm above baseline, suggesting your body is still working to recover.

Trigger:
Generated when:
- `today.readinessResult.diagnostics.lateNadir` == `true`.
- `rhrDeltaBpm` > `RHR_ELEVATED_DELTA_BPM` (5.0f).

---

## Late Recovery and Short Sleep

Identifier:
LATE_NADIR_SHORT_SLEEP

Description:
Your heart rate nadir occurred late in the night, and you slept %1$d minutes less than your %2$d minute goal, limiting overnight restoration.

Trigger:
Generated when:
- `today.readinessResult.diagnostics.lateNadir` == `true`.
- `sleepDurationMinutes` < `goalSleepMinutes * SLEEP_DEFICIT_RATIO` (0.85f).

---

## Strong Recovery Signal

Identifier:
STRONG_RECOVERY_SIGNAL

Description:
Your HRV and resting heart rate were unusually favorable compared with your baseline.

Trigger:
Generated when:
- `RecoveryFlag.STRONG_RECOVERY_SIGNAL` is present in today's recovery flags.
- Where `RecoveryFlag.STRONG_RECOVERY_SIGNAL` is triggered when the favorable recovery condition is met on both **today and yesterday (two consecutive nights)**:
  - `zLnHrv` > `strongRecoveryZHrvThreshold` (1.5f) AND `zRhr` < `strongRecoveryZRhrThreshold` (-2.0f).
- This signal is informational only. It does not cap readiness and does not mean the app emits user-visible `OVERREACHING`.

---

## RAS Depleted Despite Training Load

Identifier:
RAS_DEPLETION_HIGH_STRAIN

Description:
Your RAS score of %1$.0f is low even though your strain ratio of %2$.1f shows a high training load, suggesting your effort isn't translating into RAS gains.

Trigger:
Generated when:
- `totalRasWorkoutOnly` < `RAS_DEPLETION_THRESHOLD` (50.0f).
- `strainRatio` > `RAS_DEPLETION_STRAIN_RATIO_THRESHOLD` (1.0f).

---

## Weekly RAS Below Target

Identifier:
RAS_WEEKLY_UNDERPERFORMANCE

Description:
Your rolling weekly RAS total of %1$.0f is below the recommended target of %2$.0f.

Trigger:
Generated when:
- Sum of `totalRasWorkoutOnly` over the last 7 distinct days is < `RAS_WEEKLY_TARGET` (150.0f).
- Note: Requires at least one day in the last 7 days to have a non-null `totalRasWorkoutOnly`.

---

## HRV Data Missing

Identifier:
RECOVERY_HRV_MISSING

Description:
Today's readiness score is based on resting heart rate and sleep only because no HRV reading was recorded last night.

Trigger:
Generated when:
- `RecoveryFlag.HRV_MISSING` is present in today's recovery flags.
- Note: `RecoveryFlag.HRV_MISSING` triggers if there was no sleep HRV reading recorded last night.

---

## Unusual Sleep Stage Proportions

Identifier:
RECOVERY_SUSPICIOUS_STAGE_RATIO

Description:
Last night's deep/REM sleep proportions looked implausible, so your sleep score reflects duration and restoration only, not architecture.

Trigger:
Generated when:
- `RecoveryFlag.SUSPICIOUS_STAGE_RATIO` is present in today's recovery flags.
- Note: `RecoveryFlag.SUSPICIOUS_STAGE_RATIO` triggers when the deep+REM fraction of the night's aggregate sleep duration is statistically implausible (deep >40%, REM >45%, or deep+REM sum >70% — see `LoadScoringStrategy.validateNight`). This is a ratio check on aggregate session columns, not a check on whether the per-record stage timeline (used by the hypnogram) is empty — a night can have a full, populated hypnogram while still tripping this flag because the *proportions* look implausible.
- `RecoveryFlag.STAGES_MISSING` is the retired legacy name for this flag, kept in the `RecoveryFlag` enum only so historical persisted rows (written before the rename) still parse; new scoring code never emits it.

---

## Rest Day

Identifier:
REST_DAY_NO_IMPACT

Description:
Your rest day did not lead to a significant HRV rebound.

Trigger:
Generated when:
- `RecoveryFlag.REST_DAY_NO_IMPACT` is present in today's recovery flags.
- Where `RecoveryFlag.REST_DAY_NO_IMPACT` is triggered when:
  `yesterdayTrimp` < `10.0f` AND `currentHrv` < `yesterdayHrv * hrvOptimalThreshold` (1.10f default) AND `isCurrentHrvOptimal` is `false`.

---

## Rest Day Success

Identifier:
REST_DAY_SUCCESS

Description:
Your dedicated rest day resulted in an optimal HRV rebound. [Note: The app appends \" Your night's rest was perfect!\" if sleepScore >= 85f and sleepDurationMinutes >= goalSleepMinutes]

Trigger:
Generated when:
- `RecoveryFlag.REST_DAY_SUCCESS` is present in today's recovery flags.
- Where `RecoveryFlag.REST_DAY_SUCCESS` is triggered when `yesterdayTrimp` < `10.0f` AND either:
  - `currentHrv` >= `yesterdayHrv * hrvOptimalThreshold` (1.10f default) — a significant relative HRV increase, regardless of optimal status, OR
  - `isCurrentHrvOptimal` is `true` AND `isPreviousHrvOptimal` is `false` — a genuine transition into the optimal HRV range.
- Note: if `isCurrentHrvOptimal` is `true` but neither condition above holds (HRV was already optimal yesterday and today, with no significant further rise), **no insight is shown** — `RecoveryFlag.REST_DAY_NO_IMPACT` is also suppressed in this case, since nothing meaningfully changed.

---

## Potential Illness Detected

Identifier:
SICK_INDICATOR

Description:
Elevated resting heart rate and suppressed HRV indicate your body may be fighting something off.

Trigger:
Generated when:
- `RecoveryFlag.ILLNESS_ONSET` is present in today's recovery flags.
- Where `RecoveryFlag.ILLNESS_ONSET` is triggered when the illness condition is met on both **today and yesterday (two consecutive nights)**:
  - **today:** `zLnHrv` < `illnessZHrvThreshold` (-1.5f) AND (`rhrDeltaBpm` >= `illnessRhrDeltaBpm` (5.0f) OR `zRhr` >= `illnessZRhrThreshold` (2.0f))
  - **yesterday:** `yesterdayZLnHrv` < `illnessZHrvThreshold` (-1.5f) AND `yesterdayZRhr` >= `illnessZRhrThreshold` (2.0f).

---

## Low Daily Activity

Identifier:
STEP_SHORTFALL

Description:
You took %1$d steps today, well short of your %2$d step goal.

Trigger:
Generated when:
- `stepGoal` > 0.
- `stepCount` < `stepGoal * STEP_GOAL_SHORTFALL_RATIO` (0.7f).
- Safe Bedtime Check: If `circadianResult` is of type `CircadianConsistencyResult.Ready`, then `nowMinutesOfDay` >= `medianBedtimeMinutes - STEP_SHORTFALL_LEAD_TIME_MINUTES` (180 minutes).

---

## Weight Change Under High Training Load

Identifier:
WEIGHT_DRIFT_TRAINING_LOAD

Description:
Your weight has changed by %1$.1f kg (%2$.1f%%) over the past week while your training load is high, which can indicate fluid shifts or under-fueling.

Trigger:
Generated when:
- `today.weightKg` is not null.
- There is a past weight reading in the last 7 days (excluding today).
- `percent = abs(todayWeight - oldestWeight) / oldestWeight` > `WEIGHT_DRIFT_PERCENT_THRESHOLD` (0.02f).
- `strainRatio` > `STRAIN_HIGH_RATIO_THRESHOLD` (1.3f).

---

## Training Load May Be Affecting Recovery

Identifier:
LOAD_SPIKE_RECOVERY_STRAIN

Description:
Your recent training load is high, and your recovery markers show signs of strain.

Trigger:
Generated when:
- A load spike is present:
  - `strainRatio` > `STRAIN_HIGH_RATIO_THRESHOLD` (1.3f), OR
  - `yesterdayTrimp` >= `120.0f`, OR
  - acute 7-day load / chronic 28-day load >= `1.5f` with enough history.
- Recovery strain is also present through at least one recovery marker:
  - low HRV compared with baseline,
  - elevated resting heart rate,
  - readiness score < `60.0f`, OR
  - short sleep compared with goal.
- This replaces overreaching-style user-visible behavior. It does not diagnose overtraining.

---

## Workout Impact

Identifier:
WORKOUT_IMPACT

Description:
Yesterday's high-strain workout appears to be carrying into today because both HRV and RHR are no longer in their optimal ranges.

Trigger:
Generated when:
- `RecoveryFlag.WORKOUT_IMPACT` is present in today's recovery flags.
- Where `RecoveryFlag.WORKOUT_IMPACT` is triggered when:
  - `yesterdayTrimp` >= `120.0f`;
  - current HRV is no longer optimal;
  - current RHR is no longer optimal;
  - current HRV has dropped from yesterday by more than the configured HRV optimal-threshold percentage.
