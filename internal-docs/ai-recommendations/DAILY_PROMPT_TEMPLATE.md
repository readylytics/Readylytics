# Readylytics Training Advisor — Daily Prompt Template

Status: implemented as a manual copy-to-external-AI-chat workflow. Source of truth for
the `DailyPromptFormatter` output; see `README.md` in this directory for scope and
integration details.

Fields marked **`UPSTREAM DATA REQUIRED`** are part of the input contract the system
prompt expects but are **not yet emitted** by the data layer
(`GetDailyPromptDataUseCase` / `DailyPromptFormatter`). They are declared here so the
prompt and data contract stay aligned; implementing them is a separate data-layer
task, not a scoring change.

This is the **per-day user-turn prompt**. It assumes `BASE_SYSTEM_PROMPT.md` is
already loaded as the system prompt for the conversation. `{{placeholder}}` values are
filled in per request; field names are taken verbatim from `DailySummary`
(`core/model/src/main/kotlin/app/readylytics/health/domain/model/DailySummary.kt`),
`WorkoutRecordEntity` (`core/model/src/main/kotlin/app/readylytics/health/data/local/entity/WorkoutRecordEntity.kt`),
and `WorkoutLoadMetrics` (`core/scoring/src/main/kotlin/app/readylytics/health/domain/scoring/ComputeWorkoutLoadMetricsUseCase.kt`).

A block written as `{{#each ...}}` denotes a repeatable section (render once per item
in the list) — this is prose notation, not a literal templating engine.

---

Today's data for **{{date}}** — generate a training recommendation per the Output
Contract defined in the system prompt.

## A. Profile & Calibration Context

- Physiology profile: `{{physiologyProfile}}` (ATHLETE / ACTIVE / SEDENTARY)
- Calibration phase: `{{calibrationPhase}}` (Calibration / Early Baseline / Maturing / Mature)
- Baseline observation count: `{{baselineObservationCount}}` valid days
- Is calibrating: `{{isCalibrating}}`
- Active Training Load source (drives Readiness): `{{activeTrainingLoadSource}}`
  (Workout only / Everyday heart-rate load)
- Everyday-load confidence (only meaningful if the source above is Everyday
  heart-rate load): `{{everydayLoadConfidence}}` (None / Low / Medium / High)
- Advisor data confidence: `{{advisorDataConfidence}}` (LOW / MEDIUM / HIGH)

## B. Today's Readiness & Baselines

- Readiness score (from the active source above): `{{readinessScore}}`
- Readiness band: `{{readinessBand}}` (POOR / WARNING / NEUTRAL / OPTIMAL /
  CALIBRATING)
- Permitted recommendation ceiling: `{{permittedRecommendation}}`
- Recommended action: `{{recommendedAction}}` (REST / ACTIVE_RECOVERY / TRAIN / null)
- Restoration sub-score (`sRest`): `{{sRest}}`
- HRV baseline: `{{hrvBaseline}}` (rounded) — mu `{{hrvMuMssd}}`, sigma `{{hrvSigmaMssd}}`
- Resting heart rate today: `{{restingHeartRate}}` bpm — vs. baseline ratio
  `{{restingHrRatio}}`, baseline sigma `{{rhrSigma}}`
- Last night's nocturnal HRV: `{{nocturnalHrv}}`
- z-scores vs. this person's own baseline (standard deviations from personal norm):
  HRV `{{zLnHrv}}`, RHR `{{zRhr}}`
- Baseline last (re)calculated: `{{baselineCalculatedAtDate}}`

## C. Yesterday's Sleep Score Breakdown

- Sleep Score: `{{sleepScore}}`
- Sleep duration: `{{sleepDurationMinutes}}` minutes
- Deep sleep: `{{deepSleepPercent}}`% — REM sleep: `{{remSleepPercent}}`%
- Supplemental sleep (naps): `{{supplementalSleepDurationMinutes}}` minutes across
  `{{napCount}}` nap(s)
- Average sleeping SpO2: `{{avgSleepingSpo2}}`
- Note: if `STAGES_MISSING` or `SUSPICIOUS_STAGE_RATIO` is active (see Section F),
  Architecture is not reliable for last night — Duration is carrying more weight in
  the Sleep Score than usual.

## D. Yesterday's Workout(s)

{{#each yesterdayWorkouts}}
- Type: `{{exerciseType}}`, duration `{{durationMinutes}}` min, avg HR `{{avgHr}}` bpm
- TRIMP: `{{trimp}}` (prefer `{{modelTrimp}}` when present — it reflects the person's
  selected TRIMP model; fall back to `{{trimp}}` only if `modelTrimp` is absent)
- HR zone breakdown (minutes): zone 1 `{{zone1Minutes}}`, zone 2 `{{zone2Minutes}}`,
  zone 3 `{{zone3Minutes}}`, zone 4 `{{zone4Minutes}}`, zone 5 `{{zone5Minutes}}`
- Gained strain from this session: `{{roundedGainedStrain}}` (precise:
  `{{preciseGainedStrain}}`) — how much this workout raised the Strain Ratio
- Load classification: `{{loadClassification}}` (VERY_LIGHT / LIGHT / MODERATE / HARD
  / VERY_HARD), intensity: `{{intensity}}`
{{/each}}

If there were no workouts yesterday: `{{restDayYesterday: true}}`. Cross-reference
Section F for whether `REST_DAY_SUCCESS` or `REST_DAY_NO_IMPACT` is active — this
tells you whether the rest day is showing up as a recovery benefit yet.

## E. Current Training Load State

(Each figure below is paired with its source — Workout only vs. Everyday heart-rate
load. The active source from Section A is the one that drives Readiness; treat the
other as secondary context only.)

- Acute Load (ATL, 7-day EMA of TRIMP): `{{atl}}`
- Chronic Load (CTL, 42-day EMA of TRIMP): `{{ctl}}`
- Strain Ratio (ATL ÷ CTL — internal term, describe qualitatively per the system
  prompt's Terminology rules): `{{strainRatio}}`
- Load Score (0–100): `{{loadScore}}`
- Current load state: `{{loadContext}}` (BELOW_TYPICAL / SWEET_SPOT / ELEVATED / HIGH
  / UNKNOWN)
- `recommended_load` envelope for the remaining training today:
  `{{recommendedLoad}}`
  (serialize the exact object `{ "qualitative": "LIGHT" | "MODERATE" | "NORMAL" |
  "HIGH" | null }`; keep the object when the value is null, rendering
  `{ "qualitative": null }`)
- 7-day RAS total (informational only — never drives Readiness):
  Workout only `{{totalRasWorkoutOnly}}`, Everyday HR `{{totalRasEverydayHr}}`
- Everyday-load coverage (only relevant if that source is active):
  `{{everydayCoverageMinutes}}` minutes

## E.1 Training Already Completed Today

- Workouts completed today: `{{todayCompletedWorkouts}}`
- TRIMP accumulated today: `{{todayTrimp}}`
- Training minutes today: `{{todayTrainingMinutes}}`
- Latest available data timestamp: `{{dataCurrentUntil}}` (ISO-8601)

The recommendation applies only to **additional** training after the latest available
data timestamp. No completed workouts is valid zero-load activity data, not a missing
`recommended_load` envelope.

## F. Active Recovery Flags

{{#each activeRecoveryFlags}}
- `{{flagName}}` — {{plainEnglishGloss}}
  (e.g. `OVERREACHING` — "training load has risen faster than your fitness can
  currently absorb"; `STRONG_RECOVERY_SIGNAL` — "your recovery markers are notably
  above your personal norm today"; `ILLNESS_ONSET` — "your overnight recovery signals
  are unusually different from your normal baseline"; `REST_DAY_SUCCESS` —
  "yesterday's rest day is showing up as a recovery benefit today")
{{/each}}

If no flags are active: `{{noFlagsActive: true}}`.

## G. Typical Workout Pattern (last {{lookbackMonths}} months)

Aggregation is implemented by `ComputeWorkoutPatternSummaryUseCase`
(`core/scoring/.../domain/airecommendation/`) over the three-month lookback window;
treat it as best-effort personalization context.

- Total workouts in window: `{{totalWorkoutsInWindow}}`

{{#each exerciseTypeBreakdown}}
- `{{exerciseType}}`: `{{frequencyPerWeek}}`/week, avg TRIMP `{{avgTrimp}}`, avg
  duration `{{avgDurationMinutes}}` min, usual load `{{avgLoadClassification}}`,
  typically on `{{preferredDaysOfWeek}}`
{{/each}}

- Average rest days per week: `{{restDaysPerWeekAvg}}`
- Days since last rest day: `{{mostRecentRestDayGapDays}}`
- Current consecutive training-day streak: `{{longestCurrentStreakDays}}`

Use this section only to personalize *what kind* of session to suggest (matching
established habits) once rest-vs-train and rough load have already been decided from
Sections B–F. It must never override a hard safety flag or the Readiness/Load-driven
call.

## H. Task

Using the data above, produce today's recommendation in the exact structure defined in
the system prompt's Output Contract — a single strict JSON object with every field
present (`null` where unknown). Cite the specific fields above (by the values given,
e.g. "Readiness band of NEUTRAL" or "Load Score of {{loadScore}}") that drove your
rationale.
