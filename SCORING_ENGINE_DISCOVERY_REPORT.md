# Scoring Engine Scientific Discovery Report

> **Scope & method.** This is a *discovery* document, not a remediation plan. It establishes the
> factual current state of the Readylytics scoring engine on branch `feature/mvp`, verifies each
> formula against `ABOUT.md` (intended product behavior) and `docs/DATA_FLOW.md` (intended
> architecture/ownership), and converts every item that cannot be proven from code or documentation
> into an explicit clarification question. **No production code, formula, or behavior was changed.**
> Literature verification was performed **code-vs-docs / offline** by direction of the product owner:
> implementations are compared against the citations ABOUT.md itself makes, and any claim that would
> require independent literature lookup is raised as a clarification question rather than asserted.
>
> All paths are rooted at `app/src/main/kotlin/app/readylytics/health/`. Line numbers reference the
> state of `feature/mvp` at the time of audit.

---

# Executive Summary

The scoring engine is a pure-Kotlin domain layer (`domain/scoring/**`, zero Android dependencies) that
computes three daily scores — **Sleep Score**, **Circadian Consistency**, and **Readiness** — plus
supporting metrics (TRIMP, PAI, ATL/CTL, Strain Ratio, HRV/RHR baselines). Constants are centralized
in `ScoringConstants.kt` with `REF:` literature tags. The core mathematical formulas are
**well-implemented and, for the most part, faithfully match ABOUT.md and DATA_FLOW.md**, including the
Readiness weights (0.4/0.3/0.3), the two-consecutive-night emergency caps (70 / 50), the Gaussian Load
decay, the age-banded sleep-architecture targets, the lnRMSSD HRV z-score, the percentile nocturnal-RHR
floor, and the profile-tiered HRV sensitivity.

The audit nonetheless surfaced a small number of **material discrepancies**, the most important of which
is in **Circadian Consistency**: the elaborate profile-specific and shift-worker behavior described in
ABOUT.md is **computed into a `CircadianConsistencyConfig` that the live circadian score never consumes**.
The displayed circadian score uses a flat, profile-independent threshold (`prefs.consistencyThresholdMinutes`,
default 30) and contains **no day-of-week / shift-worker logic at all**.

**Determinism:** within a fixed system configuration (same SQLite data, same preferences, same target
date, same system timezone), the engine produces **identical outputs**. Sorting is fully tie-broken,
percentiles use a deterministic rounded-rank index, baselines freeze, and all sync paths funnel through
a single orchestrator. The **one** identified non-data input is `ZoneId.systemDefault()`.

### Tallies

| Category | Count |
| :-- | :-- |
| Total findings (notable observations) | 27 |
| Documentation↔implementation mismatches | 6 |
| Undocumented behaviors | 9 |
| Clarification questions | 9 |
| Determinism violations (data-driven) | 0 (1 configuration-level caveat: timezone) |

---

# Formula Inventory

Each block records the implemented math **verbatim** (copied Kotlin), with `file:line`, coefficients,
thresholds, clamps, defaults, and the scientific/documentation source as stated in code or ABOUT.md.

## Sleep

### Sleep Score (composite)
- **Source File / Function:** `domain/scoring/strategies/SleepScoringStrategy.kt:107-127` — `computeSleepScore`
- **Inputs:** `durationMinutes, efficiency, deepSleepMinutes, remSleepMinutes, goalSleepHours, sRest, userAge, stagesSuspicious, sleepTargets`
- **Output:** Float (intended 0–100)
- **Equation (verbatim):**
  ```kotlin
  val durationWeight = if (stagesSuspicious) 0.75f else Sleep.WEIGHT_DURATION
  val archWeight = if (stagesSuspicious) 0.00f else Sleep.WEIGHT_ARCHITECTURE
  return durationWeight * sDur + archWeight * sArch + Sleep.WEIGHT_RESTORATION * sRest
  ```
- **Coefficients:** `WEIGHT_DURATION=0.50`, `WEIGHT_ARCHITECTURE=0.25`, `WEIGHT_RESTORATION=0.25` (`ScoringConstants.kt:72-74`). Suspicious-stage mode: duration 0.75, architecture 0.00, restoration 0.25.
- **Clamps:** none on the composite itself (sub-scores are individually clamped).
- **Scientific Source (code REF):** Buysse 1989 (PSQI); Buysse 2014 (RU-SATED); Knutson 2017 (`ScoringConstants.kt:71`).
- **Documentation Source:** ABOUT.md "Sleep Score" (Duration 50% / Architecture 25% / Restoration 25%); DATA_FLOW.md §2.5.

### Duration sub-score (sDur)
- **Source:** `SleepScoringStrategy.kt:18-37` — `computeDurationSubScore`
- **Equation (verbatim):**
  ```kotlin
  val tstTerm = (durationMinutes / 60f / goalSleepHours).coerceIn(0f, 1f) * 100f
  val effBanded = when {
      efficiency >= Sleep.EFF_EXCELLENT_THRESHOLD -> Sleep.EFF_EXCELLENT_SCORE
      efficiency >= Sleep.EFF_GOOD_THRESHOLD -> Sleep.EFF_GOOD_SCORE
      efficiency >= Sleep.EFF_FAIR_THRESHOLD -> Sleep.EFF_FAIR_SCORE
      efficiency >= Sleep.EFF_POOR_THRESHOLD -> Sleep.EFF_POOR_SCORE
      else -> Sleep.EFF_VERY_POOR_SCORE
  }
  return (Sleep.WEIGHT_TST_IN_DURATION * tstTerm + Sleep.WEIGHT_EFF_IN_DURATION * effBanded).coerceIn(0f, 100f)
  ```
- **Coefficients:** TST weight 0.7, efficiency weight 0.3 (`ScoringConstants.kt:79-80`).
- **Efficiency bands (`ScoringConstants.kt:81-89`):** ≥90→100; ≥85→85; ≥75→65; ≥65→40; else→15.
- **Defaults:** `DEFAULT_GOAL_SLEEP_HOURS=8f` (`ScoringConstants.kt:50`).
- **Clamp:** `coerceIn(0,100)` on output; TST ratio `coerceIn(0,1)`.
- **Doc Source:** ABOUT.md ("compared to your goal … small adjustment for … efficiency").

### Architecture sub-score (sArch), Deep, REM
- **Source:** `SleepScoringStrategy.kt:39-61` — `computeArchSubScore`
- **Equation (verbatim):**
  ```kotlin
  if (durationMinutes == 0) return 0f
  val deepPct = deepSleepMinutes / durationMinutes.toFloat()
  val remPct = remSleepMinutes / durationMinutes.toFloat()
  val resolvedTargets = sleepTargets ?: SleepArchitectureTargetFactory.create(userAge)
  val deepComponent = (deepPct / resolvedTargets.deepPercentage).coerceAtMost(1f) * 100f
  val remComponent = (remPct / resolvedTargets.remPercentage).coerceAtMost(1f) * 100f
  return Sleep.WEIGHT_DEEP_COMPONENT * deepComponent + Sleep.WEIGHT_REM_COMPONENT * remComponent
  ```
- **Coefficients:** deep 0.5, REM 0.5 (`ScoringConstants.kt:75-76`).
- **Age-band targets** (`components/SleepArchitectureTargets.kt:8-29`, factory `SleepArchitectureTargetFactory.kt:4-10`):
  | Age | Deep | REM |
  | :-- | :-- | :-- |
  | 18–29 | 0.20 | 0.22 |
  | 30–49 | 0.18 | 0.21 |
  | 50–59 | 0.15 | 0.20 |
  | 60+ | 0.12 | 0.19 |
- **Interpolation:** none — discrete bands.
- **Missing/implausible stages:** when `stagesSuspicious` is true, architecture weight is zeroed in the composite (see Sleep Score). Plausibility bounds: deep>40% or REM>45% → invalid; deep+REM>70% → suspicious (`ScoringConstants.kt:31-33`; logic `LoadScoringStrategy.kt` `validateNight`).
- **Scientific Source (code REF):** Ohayon 2004 (`SleepArchitectureTargets.kt`).
- **Doc Source:** ABOUT.md age-band table — **identical values**.

### Restoration sub-score (sRest)
- **Source:** `SleepScoringStrategy.kt:63-105` — `computeRestorationSubScore`; late-nadir penalty `ComputeSleepMetricsUseCase.kt:280`.
- **Equation (verbatim):**
  ```kotlin
  val hrvScore = loadStrategy.computeHrvScore(zHrv, saturationZ)
  val rhrScore = (50f - 25f * zRhr).coerceIn(0f, 100f)
  return if (restorationWeights != null) restorationWeights.hrvWeight * hrvScore + restorationWeights.rhrWeight * rhrScore
         else Restoration.WEIGHT_HRV_SCORE * hrvScore + Restoration.WEIGHT_RHR_SCORE * rhrScore
  ```
  ```kotlin
  if (nadirCtx.isLateNadir) sRest *= ScoringConstants.Restoration.LATE_NADIR_PENALTY   // 0.95
  ```
- **Coefficients:** HRV 0.5, RHR 0.5 (`ScoringConstants.kt:97-98`). Late-nadir penalty 0.95 when HR nadir occurs after 67% of session (`Restoration.LATE_NADIR_THRESHOLD=0.67`, `LATE_NADIR_PENALTY=0.95`, `ScoringConstants.kt:100-102`; trigger `LoadScoringStrategy.kt:188-197`).
- **Scientific Source (code REF):** Trinder 2001 (late-nadir); Plews 2013/Buchheit 2014 (lnRMSSD).
- **Doc Source:** ABOUT.md "Restoration (25%)"; late-nadir penalty **not documented**.

## Recovery

### HRV z-score (lnRMSSD)
- **Source:** `domain/scoring/strategies/LoadScoringStrategy.kt:43-68` — `computeHrvZScore`
- **Equation (verbatim):**
  ```kotlin
  val lnToday = ln(currentRmssdMs.coerceAtLeast(0.001f))
  val mu = when {
      frozenLnMu != null -> frozenLnMu
      baselineOverride != null -> ln(baselineOverride.coerceAtLeast(0.001f))
      else -> lnMuHistory.mean()
  }
  val sigma = frozenLnSigma ?: hrvSigma(lnSigmaHistory, sigmaPrior)
  return (lnToday - mu) / sigma
  ```
- **Confirmed:** the z-score path uses **lnRMSSD** (natural log), with all values floored at 0.001 before `ln`.
- **Doc Source:** ABOUT.md glossary ("we work with the natural log of RMSSD (lnRMSSD) internally").

### HRV score (z → 0–100, with saturation)
- **Source:** `LoadScoringStrategy.kt:70-82` — `computeHrvScore`
- **Equation (verbatim):**
  ```kotlin
  val adjustedZ = if (z > saturationZ) saturationZ + ScoringConstants.HRV_SCORE_SATURATION_SLOPE * (z - saturationZ) else z
  return (50f + 25f * adjustedZ).coerceIn(0f, 100f)
  ```
- **Coefficients:** default saturation knee 1.5, slope 0.25 (`ScoringConstants.kt:36-37`). Per-profile saturation knee via `ScoringConfigFactory.hrvSaturationZForProfile` (Athlete 1.2, Active/General 1.5, Sedentary/Shift 2.0, `ScoringConfigFactory.kt:110-117`).
- **Doc Source:** the saturation/dampening curve is **not documented**; the per-profile knee corresponds to ABOUT.md's "HRV sensitivity by profile."

### RHR z-score & score
- **Source:** `LoadScoringStrategy.kt:84-98` — `computeRhrZScore`; score in `SleepScoringStrategy.kt:98`.
- **Equation (verbatim):**
  ```kotlin
  val mu = baselineOverride ?: rhrHistory.median()
  val sigma = frozenSigma ?: rhrHistory.takeIf { it.size > 1 }?.stdev()?.takeIf { it > 0f } ?: (mu * 0.05f).coerceAtLeast(1f)
  return (currentRhrBpm - mu) / sigma
  ```
  `rhrScore = (50f - 25f * zRhr).coerceIn(0f, 100f)`
- **Note:** RHR baseline uses the **median** (not mean) of history; **no** log transform (raw bpm). Sigma fallback = 5% of median (min 1 bpm).

### Illness / Overreaching detection (recovery caps)
- **Source:** `LoadScoringStrategy.kt:100-165` — `computeRecoveryFlags`; thresholds `components/EmergencyFlagThresholds.kt`.
- **Equation (verbatim):**
  ```kotlin
  val todayOverreaching = zLnHrv > thresholds.overreachingZHrvThreshold && zRhr < thresholds.overreachingZRhrThreshold
  // ... prevOverreaching computed identically for yesterday ...
  if (todayOverreaching && prevOverreaching) flags += RecoveryFlag.OVERREACHING

  val todayIllness = zLnHrv < thresholds.illnessZHrvThreshold &&
      (rhrDeltaBpm != null && rhrDeltaBpm >= thresholds.illnessRhrDeltaBpm || zRhr >= thresholds.illnessZRhrThreshold)
  // ... prevIllness ...
  if (todayIllness && prevIllness) flags += RecoveryFlag.ILLNESS_ONSET
  ```
- **Thresholds (profile-tiered HRV; `ScoringConfigFactory.kt:91-108`):** overreaching/illness HRV z = ±1.2 (Athlete), ±1.5 (Active/General, default), ±2.0 (Sedentary/Shift). Fixed (not tiered): `overreachingZRhr=-2.0`, `illnessZRhr=2.0`, `illnessRhrDeltaBpm=5` (`EmergencyFlagThresholds.kt:6-12`).
- **Two-consecutive-night requirement:** enforced via `prevOverreaching`/`prevIllness` (verbatim above).
- **Other flags (informational):** `CALIBRATING`, `HRV_MISSING`, `STAGES_MISSING`, `NADIR_DELAYED`, `WORKOUT_IMPACT` (yesterdayTrimp≥120), `REST_DAY_SUCCESS`/`REST_DAY_NO_IMPACT` (`LoadScoringStrategy.kt:117-160`).
- **Doc Source:** ABOUT.md "Emergency signals" (two consecutive nights; Mishra 2020, Le Meur 2013).

## Circadian

### Circadian Consistency (live/displayed score)
- **Source:** `domain/scoring/CircadianConsistencyRepository.kt:78-156` — `compute` / `scoreDeviation` / `normalizeMinutes`.
- **Equation (verbatim):**
  ```kotlin
  val threshold = prefs.consistencyThresholdMinutes          // default 30, NOT profile-derived
  val evalCount = prefs.consistencyEvaluationDays            // default 7
  val baselineCount = prefs.consistencyBaselineDays          // default 14
  // baseline = median bed/wake over most-recent `baselineCount` sessions
  // dailyScores = per-session (bedScore+wakeScore)/2 over most-recent `evalCount` sessions
  return CircadianConsistencyResult.Ready(score = dailyScores.average().toFloat(), ...)
  ```
  ```kotlin
  private fun scoreDeviation(deviation: Float, threshold: Float): Float = when {
      deviation <= threshold -> 100f
      deviation <= threshold + 60f -> 100f * (1f - (deviation - threshold) / 60f)
      else -> 0f
  }
  ```
- **Median over minutes-of-day with midnight wrap (verbatim):**
  ```kotlin
  return if (minutes < 12 * 60) minutes + 1440 else minutes   // pre-noon treated as "past midnight"
  ```
- **Defaults (`SettingsDefaults.kt:45-47`):** threshold 30, evaluation 7, baseline 14.
- **Nap exclusion:** sessions `< 180` minutes excluded (`NAP_THRESHOLD_MINUTES`, `CircadianConsistencyRepository.kt:20,89`).
- **Calibration gate:** `< 3` valid baseline sessions → `Calibrating` (`MIN_BASELINE_SESSIONS=3`).
- **Status bands:** ≥80 OPTIMAL, ≥60 NEUTRAL, ≥40 WARNING, else POOR (`:40-45`).
- **Doc Source:** ABOUT.md "Circadian Consistency."

### Profile threshold strategy (NOT consumed by the live score — see Audit M1/M2)
- **Source:** `domain/circadian/RegularUserCircadianStrategy.kt:6-16`, `ShiftWorkerCircadianStrategy.kt:5-14`, `CircadianStrategyFactory.kt:5-11`, wired in `ScoringConfigFactory.kt:119-134`.
- **Regular thresholds (verbatim):** Athlete 20, Active 30, General 30, Sedentary 45, ShiftWorker `Int.MAX_VALUE`.
- **Shift worker (verbatim):** `return override ?: Int.MAX_VALUE` — no day-of-week logic anywhere.
- **Consumption:** the resulting `CircadianConsistencyConfig` flows only into `ScoringConfig` (validated in `ScoringConfigValidator.kt:26`, logged in `ComputeSleepMetricsUseCase.kt:74`); **no scoring code reads `ScoringConfig.circadianConsistency.thresholdMinutes`**. The displayed score uses `prefs.consistencyThresholdMinutes` instead.

## Training

### TRIMP — Banister (operational default)
- **Source:** `domain/scoring/PaiCalculator.kt:22-46` — `calculateDailyTrimp` (BANISTER).
- **Equation (verbatim):**
  ```kotlin
  val a = if (isMale) Trimp.BANISTER_MALE_A else Trimp.BANISTER_FEMALE_A
  val b = if (isMale) Trimp.BANISTER_MALE_B else Trimp.BANISTER_FEMALE_B
  durationMinutes * hrR * a * exp(b * hrR) * banisterMultiplier
  ```
  where `hrR = ((hrAvg - rhrBaseline) / (hrMax - rhrBaseline)).coerceIn(0f,1f)`.
- **Coefficients:** male a=0.64, b=1.92; female a=0.86, b=1.67 (`ScoringConstants.kt:146-149`). `banisterMultiplier` per profile (Athlete 1.0 … Sedentary/Shift 1.75) — **undocumented**.
- **Scientific Source (code REF):** Banister 1991; Morton 1990.

### TRIMP — Cheng (LT-TRIMP)
- **Source:** `PaiCalculator.kt:48-68`.
- **Equation (verbatim):**
  ```kotlin
  if (ltBpm <= 0f) return 0f
  val weight = if (hrAvg <= ltBpm) 0.5f * (hrAvg - rhrBaseline) / (ltBpm - rhrBaseline).coerceAtLeast(1f)
               else { val f = ((hrAvg - ltBpm) / (hrMax - ltBpm).coerceAtLeast(1f)).coerceIn(0f,1f); 0.5f + sexFactor * f * exp(chengBeta * f) }
  durationMinutes * weight
  ```
- **Coefficients:** `chengBeta=0.09` default (per profile 0.07/0.09/0.11); `sexFactor` reuses Banister a (0.64/0.86). LT from `prefs.zone3MaxBpm`.
- **Scientific Source (code REF):** Cheng et al. 1992.

### TRIMP — iTRIMP (Manzi)
- **Source:** `PaiCalculator.kt:69-73`.
- **Equation (verbatim):** `durationMinutes * hrR * exp(itrimB * hrR)` with `itrimB=2.1` default (per profile 2.9/2.1/1.5).
- **Scientific Source (code REF):** Manzi et al. 2009.

### PAI
- **Source:** `PaiCalculator.kt:80-128`.
- **Equation (verbatim):**
  ```kotlin
  val paiD = dailyTrimp * scalingFactor
  return paiD.coerceAtMost(ScoringConstants.Pai.DAILY_CAP)   // 75
  ```
  Tiered accumulation (`:92-117`): 0–50 ×1.0, 50–100 ×0.5, 100+ ×0.25. Readiness adjustment (`:122-128`): `paiD * (readinessScore / 100)`.
- **Scaling factors:** Athlete 0.15, Active 0.18, General 0.20, Sedentary 0.25, Shift 0.20 (`ScoringConstants.kt:134-138`).
- **Scientific Source (code REF):** "Whoop PAI model" (`ScoringConstants.kt:133`) — a **custom TRIMP→PAI scaling**, not the HUNT-study PAI.

### ATL / CTL (EMA)
- **Source:** `domain/scoring/strategies/PaiScoringStrategy.kt:12-83`.
- **Equation (verbatim, with-decay):**
  ```kotlin
  val alpha = 2.0 / (windowDays + 1)
  var ewma = dailyTrimpByDate[rangeStart]?.toDouble() ?: 0.0
  while (!date.isAfter(rangeEnd)) { val trimp = dailyTrimpByDate[date]?.toDouble() ?: 0.0; ewma = trimp * alpha + ewma * (1.0 - alpha); date = date.plusDays(1) }
  ```
- **Parameters:** ATL window 7 (α=2/8=0.25), CTL window 42 (α=2/43≈0.0465) (`ScoringConstants.kt:10-11`). Missing days → 0.0 TRIMP. List-based variant seeds with a 7-sample SMA warmup; default seed `DEFAULT_FITNESS_LEVEL=35`.
- **Strain Ratio:** `if (ctl > 0f) atl / ctl else 0f` (`PaiScoringStrategy.kt:12-15`).
- **Doc Source:** ABOUT.md/DATA_FLOW.md (CTL 42-day, ATL 7-day EMA).

### Load Score (Strain Ratio → score)
- **Source:** `LoadScoringStrategy.kt:23-27`.
- **Equation (verbatim):**
  ```kotlin
  if (sr <= Strain.SR_SWEET_SPOT_MAX) return Strain.OPTIMAL_SWEET_SPOT_SCORE   // 1.3 -> 100
  val excess = sr - Strain.SR_SWEET_SPOT_MAX
  return (100f * exp(-Strain.QUADRATIC_PENALTY_K * excess * excess)).coerceIn(0f, 100f)   // k=2.5
  ```
- **Scientific Source (code REF):** Gabbett 2016; Windt & Gabbett 2018 (threshold widened 1.2→1.3).

### Max Heart Rate
- **Source:** `domain/util/HeartRateFormulas.kt:5-23` (Tanaka default `208 - 0.7*age`), fallback `components/MaxHeartRateCalculator.kt:23-26` (Karvonen `220 - age`, floored).
- **Doc Source:** **undocumented** in ABOUT.md/DATA_FLOW.md.

## Readiness

### Readiness composite + caps (effective equation)
- **Source:** `LoadScoringStrategy.kt:167-186` — `computeReadinessScore`.
- **Equation (verbatim):**
  ```kotlin
  var rs = Readiness.WEIGHT_RESTORATION * sRest + Readiness.WEIGHT_SLEEP * sleepScore + Readiness.WEIGHT_LOAD * loadScore
  if (RecoveryFlag.OVERREACHING in recoveryFlags) rs = rs.coerceAtMost(Readiness.OVERREACHING_MAX_SCORE)   // 70
  if (RecoveryFlag.ILLNESS_ONSET in recoveryFlags) rs = rs.coerceAtMost(Readiness.ILLNESS_MAX_SCORE)       // 50
  return rs.coerceIn(0f, 100f)
  ```
- **Final effective equation:**
  `Readiness = clamp( min( min(0.4·sRest + 0.3·SleepScore + 0.3·LoadScore, 70 if OVERREACHING), 50 if ILLNESS_ONSET ), 0, 100)`
  — i.e. base composite, then OVERREACHING cap (70), then ILLNESS cap (50; dominant if both), then clamp.
- **Coefficients:** 0.4 / 0.3 / 0.3 (`ScoringConstants.kt:109-111`). Caps 70 / 50 (`:117,123`).
- **Caps that DO apply:** OVERREACHING, ILLNESS_ONSET **only**. `WORKOUT_IMPACT` / `REST_DAY_*` flags do **not** cap the number.
- **Doc Source:** ABOUT.md "Readiness."

## Baselines

### RHR nocturnal-floor percentile + 30-day baseline
- **Source:** `domain/scoring/sleep/SleepPercentileRhrCalculator.kt:36-85`; live recompute `BaselineComputer.kt:44-104`.
- **Equation (verbatim, percentile index):**
  ```kotlin
  val index = Math.round((percentile / 100.0) * (size - 1)).toInt().coerceIn(0, size - 1)
  return this[index].beatsPerMinute   // samples pre-sorted ASC by bpm
  ```
  Baseline = `median` of historical per-night percentile values; ratio = current/baseline.
- **Defaults:** percentile 5 (`SettingsDefaults.RESTING_HR_PERCENTILE=5`; validator range 1–15, `SettingsValidators.kt:18`). Baseline window 30 days (`BASELINE_DAYS`). Fallback `DEFAULT_RHR_BPM=60`.
- **Doc Source:** ABOUT.md glossary (RHR = low percentile of overnight HR, default 5%, nightly-frozen floor).

### HRV baselines (μ, σ) + blending
- **Source:** μ/σ z-score path `LoadScoringStrategy.kt:29-68`; window construction `BaselineComputer.kt` `HrvWindows`; frozen baseline `ComputeHistoricalBaselinesUseCase.kt:40-63`.
- **σ blending (verbatim):**
  ```kotlin
  val w = ((n - HRV_SIGMA_BLEND_MIN_N).toFloat() / (HRV_SIGMA_BLEND_MAX_N - HRV_SIGMA_BLEND_MIN_N)).coerceIn(0f, 1f)
  val blended = w * lnHrvValues.stdev() + (1f - w) * sigmaPrior
  return blended.coerceAtLeast(Restoration.MIN_LN_SIGMA)   // 0.04
  ```
- **Windows / constants:** μ window 7 days, σ window 56 days, blend n 7→60 (`ScoringConstants.kt:18-21`). Profile ln-σ priors: Athlete 0.10, Active 0.15, General 0.18, Sedentary/Shift 0.20 (`PhysiologyProfile.kt`).
- **Frozen baseline (ln-space):** `hrvMuMssd = mean(ln(nightly means))`; `HrvBaselineProvider.getPreciseHrvBaseline` returns `exp(mu)` (raw ms) for display.
- **Convenience baseline (raw):** `BaselineComputer.computeHrvBaseline` returns `median(nightly RAW means)` (arithmetic) — a **different** statistic from the ln-mean (see Clarification Q7).
- **stdev:** sample (N−1, Bessel) (`util/MathUtils.kt:33-47`).

### Phase / calibration / freeze / maturation
- **Source:** `components/Phase.kt:3-15`, `components/PhaseCalculator.kt:4-9`; calibration gate `ScoringRepositoryImpl.kt:186-188` and `ComputeSleepMetricsUseCase.kt:233-236`; freeze `BaselineComputer.kt:183-189` + `ComputeHistoricalBaselinesUseCase.kt:52-63`.
- **Phase enum (verbatim):**
  ```kotlin
  daysSinceInstall < 7 -> Phase.CALIBRATION
  daysSinceInstall < 42 -> Phase.PROVISIONAL
  else -> Phase.MATURE
  ```
- **Calibration gate:** `MIN_SESSIONS_FOR_CALIBRATION = 7` valid sessions (data-driven, not calendar days).
- **Freeze:** once `DailySummaryEntity.baselineCalculatedAtDate != null`, baseline recompute is skipped (returns null / stored value used).

---

# Scientific Validation

Classification labels: `SCIENTIFICALLY_SUPPORTED`, `REASONABLE_ENGINEERING_APPROXIMATION`,
`WEAK_EVIDENCE`, `POTENTIALLY_INCORRECT`, `NEEDS_CLARIFICATION`. Per product-owner direction, literature
claims are taken from the citations ABOUT.md/code already make; anything requiring independent literature
confirmation is marked `NEEDS_CLARIFICATION`.

| Metric | Classification | Basis (impl + doc + cited literature) |
| :-- | :-- | :-- |
| HRV via lnRMSSD z-score | SCIENTIFICALLY_SUPPORTED | `ln()` applied in `LoadScoringStrategy.kt:59`; ABOUT.md cites Plews 2013 / Buchheit 2014 for log transform — implementation matches the cited method. |
| Age-banded deep/REM targets | SCIENTIFICALLY_SUPPORTED | `SleepArchitectureTargets.kt` values **identical** to ABOUT.md table; code & doc both cite Ohayon 2004. |
| Percentile nocturnal RHR floor | REASONABLE_ENGINEERING_APPROXIMATION | Low-percentile overnight HR as resting floor; rounded-rank percentile. Documented rationale; exact 5th-percentile choice is an engineering default. |
| RHR baseline = median of nightly percentiles | REASONABLE_ENGINEERING_APPROXIMATION | Robust central tendency; consistent with "stable night-to-night baseline" goal. |
| Banister TRIMP coefficients | NEEDS_CLARIFICATION | Code values (M 0.64/1.92, F 0.86/1.67) match the commonly-cited Banister/Morton constants per the code REF, but independent literature confirmation was out of scope. The per-profile `banisterMultiplier` (1.0–1.75) is undocumented and has no stated source. |
| Cheng LT-TRIMP | NEEDS_CLARIFICATION | Piecewise LT form is plausible; exact continuity/coefficients vs Cheng 1992 not independently verified (offline). |
| iTRIMP (Manzi) | NEEDS_CLARIFICATION | Exponential individualized form present; `itrimB` 1.5/2.1/2.9 per profile not independently verified against Manzi 2009. |
| PAI (TRIMP×scaling, tiers, cap 75) | WEAK_EVIDENCE | Self-described as "Whoop PAI model," **not** HUNT-study PAI; tiered accumulation & readiness-adjustment have no cited source. |
| ATL/CTL EMA (α=2/(N+1), 7 & 42d) | SCIENTIFICALLY_SUPPORTED | Standard EMA load model (Banister/“Bannister-style” fitness-fatigue); windows match ABOUT/DATA_FLOW. |
| Strain Ratio / Load Gaussian decay | REASONABLE_ENGINEERING_APPROXIMATION | ABOUT.md explicitly states the curve shape is the team's modelling choice approximating Gabbett 2016; honest-limitations §4 acknowledges ACWR is descriptive not predictive. |
| Readiness 0.4/0.3/0.3 + caps | REASONABLE_ENGINEERING_APPROXIMATION | Weights are an engineering composite; two-consecutive-night gating cites Mishra 2020 / Le Meur 2013. |
| Profile-tiered HRV sensitivity (1.2/1.5/2.0) | SCIENTIFICALLY_SUPPORTED (as documented) | Matches ABOUT.md's profile table; Athlete higher sensitivity cites Le Meur 2013. |
| HRV-score saturation (knee+slope) | NEEDS_CLARIFICATION | Undocumented dampening of high-HRV; no cited basis for slope 0.25. |
| Late-nadir 5% penalty | WEAK_EVIDENCE | Code REF cites Trinder 2001; ABOUT.md notes "5% penalty pending internal validation" (`ScoringConstants.kt:100`). |
| Circadian regularity scoring | REASONABLE_ENGINEERING_APPROXIMATION | Median-anchored deviation with linear decay; ABOUT cites Windred 2023. (But see Audit M1/M2 — profile/shift behavior not wired into the live score.) |
| Tanaka HRmax default | SCIENTIFICALLY_SUPPORTED | `208 - 0.7·age` is the Tanaka 2001 formula (per code REF); undocumented in product docs but standard. |
| Phase maturation (σ blend to n=60) | REASONABLE_ENGINEERING_APPROXIMATION | Blending population prior → personal σ as n grows is sound; specific n=60 cutoff cites Plews 2013 / Kubios practice. |

---

# Documentation vs Implementation Audit

Labels (only these allowed): `EXACT_MATCH`, `PARTIAL_MATCH`, `MISMATCH`, `UNDOCUMENTED_BEHAVIOR`,
`NEEDS_CLARIFICATION`.

| Metric | ABOUT.md | DATA_FLOW.md | Implementation | Result |
| :-- | :-- | :-- | :-- | :-- |
| Sleep Score weights (50/25/25) | 50/25/25 | 50/25/25 | 0.50/0.25/0.25 (`SleepScoringStrategy.kt`) | EXACT_MATCH |
| Suspicious-stage reweight (dur 0.75 / arch 0.0) | not described | not described | `SleepScoringStrategy.kt:123-124` | UNDOCUMENTED_BEHAVIOR |
| Duration efficiency banding (0.7/0.3 + bands) | "small adjustment for efficiency" | silent | `SleepScoringStrategy.kt:18-37` | PARTIAL_MATCH |
| Deep/REM age-band targets | full table | silent | identical table (`SleepArchitectureTargets.kt`) | EXACT_MATCH |
| Restoration = 0.5·HRV + 0.5·RHR | 25% restoration; HRV+RHR z | §2.5 | 0.5/0.5 (`ScoringConstants.kt:97-98`) | EXACT_MATCH |
| HRV uses lnRMSSD | yes | implied | `ln()` in z-score (`LoadScoringStrategy.kt`) | EXACT_MATCH |
| HRV-score saturation curve | not described | not described | `LoadScoringStrategy.kt:70-82` | UNDOCUMENTED_BEHAVIOR |
| HRV sensitivity by profile (±1.2/1.5/2.0) | full table | silent | `ScoringConfigFactory.kt:91-117` | EXACT_MATCH |
| Late-nadir penalty (0.95) | not described | not described | `ComputeSleepMetricsUseCase.kt:280` | UNDOCUMENTED_BEHAVIOR |
| RHR percentile floor (5%, 30d median) | yes | §2.2 | `SleepPercentileRhrCalculator.kt` | EXACT_MATCH |
| Readiness = 0.4·Rest + 0.3·Sleep + 0.3·Load | yes | §2.5 | `LoadScoringStrategy.kt:167-186` | EXACT_MATCH |
| "Load is the primary driver" | states so | — | Restoration has highest weight (0.4); Load 0.3 | MISMATCH |
| Readiness caps (70 illness?/overreach) two-night | overreach+illness, 2 nights | "illness, overreaching" | caps for OVERREACHING(70)+ILLNESS(50), 2-night | EXACT_MATCH |
| Readiness also capped by workout-impact/rest-day | not stated | "capped by … workout impact, rest day success" | only OVERREACHING+ILLNESS cap | MISMATCH |
| Load curve (sr≤1.3→100; exp(−2.5·(sr−1.3)²)) | yes | Strain Ratio | `LoadScoringStrategy.kt:23-27` | EXACT_MATCH |
| Load threshold 1.3 vs "~1.5 risk zone" | both stated, reconciled in §4 | 1.3 | sweet-spot 1.3 | EXACT_MATCH (see Clarification Q4) |
| ATL/CTL EMA (7d / 42d) | week / 6 weeks | 42d/7d EMA | α=2/(N+1), 7 & 42 (`PaiScoringStrategy.kt`) | EXACT_MATCH |
| EMA missing-day=0 + SMA warmup | not described | not described | `PaiScoringStrategy.kt:29-83` | UNDOCUMENTED_BEHAVIOR |
| TRIMP Banister default + sex factors | TRIMP/zones glossary | BANISTER default, sex-specific | `PaiCalculator.kt:22-46` | EXACT_MATCH |
| Banister per-profile multiplier (1.0–1.75) | not described | not described | `PaiCalculator` + `PhysiologyProfile` | UNDOCUMENTED_BEHAVIOR |
| Cheng / iTRIMP models | "LT-TRIMP … zones" | CHENG / I_TRIMP variants | `PaiCalculator.kt:48-73` | PARTIAL_MATCH |
| PAI conversion + tiers + readiness-adjust | TRIMP glossary only | "PAI = TRIMP×scaling (cap 75)" | `PaiCalculator.kt:80-128` | PARTIAL_MATCH |
| Max HR formula (Tanaka/Karvonen) | not described | not described | `HeartRateFormulas.kt` / `MaxHeartRateCalculator.kt` | UNDOCUMENTED_BEHAVIOR |
| Circadian regular thresholds applied to score | Athlete 20 / Active 30 / Sedentary 45 | §3 strategies | live score uses flat `prefs` (default 30); strategy result unused | MISMATCH |
| Circadian shift-worker per-weekday (4 wks) | full description | strategies | no day-of-week logic; strategy returns `Int.MAX_VALUE`, unused by score | MISMATCH |
| Circadian windows (14d baseline / 7d avg) | 14-day median, 7-day avg | — | baseline 14 / eval 7 (`SettingsDefaults`) | EXACT_MATCH |
| Circadian midnight wrap + nap<3h exclusion | biphasic caveat, naps excluded | — | `normalizeMinutes` + `NAP_THRESHOLD_MINUTES=180` | EXACT_MATCH |
| Calibration gate (≥7 sessions) | "Days 1–7 calibration" (calendar) | "<7 sessions/42d" | `MIN_SESSIONS_FOR_CALIBRATION=7` (session count) | PARTIAL_MATCH |
| Phase model (4 phases; mature day 60) | 4 phases (Calib 1-7, Prov 8-21, Matur 22-60, Mature 60+) | — | 3-phase `Phase` enum (boundaries 7, 42) | MISMATCH |
| σ blend population→personal | days 22–60 blending | — | n 7→60 blend (`hrvSigma`) | EXACT_MATCH |
| DATA_FLOW package root path | — | `com/gregor/lauritz/healthdashboard/` | actual `app/readylytics/health/` | MISMATCH |
| Determinism: identical data → identical scores | (product req) | stable-order/freeze contracts | deterministic except `ZoneId.systemDefault()` | PARTIAL_MATCH |

---

# Determinism Investigation

**Product requirement:** *Identical raw Health Connect data must always produce identical scores.*

**Explicit answer — Can identical SQLite inputs ever produce different outputs?**
**NO**, within a fixed system configuration (same SQLite rows, same `UserPreferences`, same target date,
same system timezone, same frozen-baseline state). **One configuration-level caveat exists** (timezone),
described below. No *data-driven* non-determinism was found.

Evidence:

1. **Sorting / tie-breaking — deterministic.** `SessionLinker.kt:55` uses `compareBy({ startTime }, { id })`;
   `BaselineComputer` uses `compareBy({ endTime }, { id })`; HR DAO reads order by
   `(sessionId, beatsPerMinute, timestampMs, id)`. The "stable-order scoring contract" is stated in
   DATA_FLOW.md §1.4 and corroborated by tests.
2. **Percentile — deterministic.** Rounded-rank index `Math.round((p/100)*(n-1)).coerceIn(0,n-1)` into a
   pre-sorted list (`SleepPercentileRhrCalculator.kt`, `BaselineComputer.kt`). No interpolation ambiguity.
3. **Floating point / rounding — deterministic & stable.** `kotlin.math.round` / `Math.round` (half-up)
   used for display rounding (`ScoringRepositoryImpl.kt:159,163`); medians/means iterate ordered lists.
   `groupBy` yields insertion-ordered `LinkedHashMap` (no hash-order accumulation).
4. **Baseline generation & freezing — deterministic & data-bounded.** Once
   `baselineCalculatedAtDate != null`, recompute is skipped and stored values are used
   (`BaselineComputer.kt:183-189`; `ScoringRepositoryImpl.kt:85-91`). `BaselineFreezeBehaviorTest` and
   `BaselineComputerBackfillEquivalenceTest` assert batched backfill is identical to per-day computation.
5. **Historical replay vs daily sync vs resync — single code path.** All funnel through
   `ScoringRepositoryImpl.computeDailySummary(targetDate)` (`:72`). `WalkForwardDeterminismTest`,
   `SyncScopeDeterminismTest`, and `ScoringSyncScopeOutputsDeterminismTest` assert that 60-day vs 365-day
   vs unbounded scopes (and presence/absence of future data) yield identical sleep/readiness/HRV/RHR/μ/σ.
6. **Calibration / maturation — data-driven, not wall-clock.** Calibration uses session counts over a
   window derived from the `targetDate` parameter (`ScoringRepositoryImpl.kt:186-188`); phase uses
   `installDate` vs the passed `currentDate` (`ScoringConfigFactory.kt`), never `now()`.
7. **No `now()` / `Random` / hash-ordered accumulation in scoring paths** (agent grep across
   `domain/scoring/**`).

**Configuration-level caveat (not a data-driven violation):** scoring resolves day boundaries via
`ZoneId.systemDefault()` (`ScoringRepositoryImpl.kt:74`). The same database scored under a different
system timezone could produce different day windows and therefore different scores. Timezone is a device
configuration input, not part of the raw Health Connect data. See Open Risks and Clarification Q8.

---

# Open Risks

1. **Circadian profile/shift-worker logic is not wired into the displayed score (HIGH).** The profile-aware
   `CircadianConsistencyConfig` (with shift-worker disable) is computed and validated but never consumed by
   `CircadianConsistencyRepository`, which uses a flat `prefs.consistencyThresholdMinutes` (default 30) and
   has no day-of-week handling. Users on Athlete/Sedentary/Shift profiles are scored on the same ±30 bar
   unless they manually change the setting — contradicting ABOUT.md. (Audit: M1, M2.)
2. **Two parallel circadian threshold systems (MEDIUM).** `ScoringConfig.circadianConsistency.thresholdMinutes`
   (profile-derived) and `prefs.consistencyThresholdMinutes` (live score) can diverge with no reconciliation;
   the profile one is effectively dead for scoring purposes (validator + log only).
2. **Phase model divergence (MEDIUM).** Code has 3 phases (mature at day 42) while ABOUT.md describes 4
   (mature at day 60). Day-60 maturation *is* approximated by the σ-blend ramp to n=60, but the documented
   "Maturing (22–60)" band and the Provisional cutoff (21) have no direct counterpart in `Phase`.
3. **DATA_FLOW.md package path is stale (LOW).** The document roots all paths at
   `com/gregor/lauritz/healthdashboard/`, but code lives at `app/readylytics/health/`. Every path citation
   in DATA_FLOW.md is technically incorrect, even where the relative structure matches.
4. **Timezone-dependent day windows (MEDIUM for cross-device).** `ZoneId.systemDefault()` makes scores
   reproducible per-device but potentially divergent across timezones/DST for the same data.
5. **Undocumented modifiers can surprise users (LOW–MEDIUM).** Late-nadir 5% penalty, HRV-score saturation,
   per-profile Banister multiplier, and PAI tiered accumulation/readiness-adjustment all change outputs but
   are absent from ABOUT.md.
6. **Two HRV-baseline statistics coexist (LOW).** `computeHrvBaseline` returns a raw arithmetic median of
   nightly means, while the frozen z-score baseline is `exp(mean(ln …))` (geometric). Which is authoritative
   for chart display is not documented (Clarification Q7).

---

# Clarification Questions for Product Owner

### NEEDS CLARIFICATION — Q1: Should profile-specific circadian thresholds affect the displayed score?
**Question:** ABOUT.md states circadian thresholds vary by profile (Athlete ±20, Active ±30, Sedentary ±45),
but the live `CircadianConsistencyRepository` uses a single `prefs.consistencyThresholdMinutes` (default 30)
for all profiles. Is the flat-threshold behavior intended, or should onboarding/profile selection set the
threshold per profile?
**Reason:** Cannot be resolved from code — the profile-derived `CircadianConsistencyConfig` is computed but
never consumed by the scoring path, and `consistencyThresholdMinutes` is never written from the profile.
**Conflicting Evidence:** ABOUT.md "Profile-specific thresholds" table vs `CircadianConsistencyRepository.kt:83`
(`prefs.consistencyThresholdMinutes`) and `ScoringConfigFactory.kt:119-134` (config built but unused for score).

### NEEDS CLARIFICATION — Q2: Is shift-worker per-weekday circadian logic supposed to exist?
**Question:** ABOUT.md describes shift workers being judged against the median of *the same weekday* over 4
weeks (±30 min). No day-of-week logic exists anywhere; `ShiftWorkerCircadianStrategy` only returns
`Int.MAX_VALUE` (which, even if consumed, would make every night score 100). Was this feature descoped, or
is it missing?
**Reason:** No implementation evidence of weekday bucketing or a 4-week shift-worker window.
**Conflicting Evidence:** ABOUT.md "Special handling for shift workers" vs `ShiftWorkerCircadianStrategy.kt:5-14`
and `CircadianConsistencyRepository.kt:78-131` (no profile/weekday branch).

### NEEDS CLARIFICATION — Q3: Is "Load is the primary driver" of Readiness accurate?
**Question:** ABOUT.md says "The load component is the primary driver," but the weights are Restoration 0.4,
Sleep 0.3, Load 0.3 — Restoration is the largest. Should the wording change, or are the weights wrong?
**Reason:** Internal contradiction within ABOUT.md vs `ScoringConstants.kt:109-111`.

### NEEDS CLARIFICATION — Q4: Is the Strain Ratio sweet-spot 1.3 (not 1.5) intended?
**Question:** The Load curve begins decaying at sr=1.3 (`SR_SWEET_SPOT_MAX`), while ABOUT.md's narrative and
limitations reference Gabbett's "~1.5" elevated-risk zone. ABOUT.md §4 reconciles this (the curve shape is a
modelling choice), but please confirm 1.3 is the intended decay onset rather than 1.5.
**Reason:** Two numbers appear in the docs; code uses 1.3.
**Conflicting Evidence:** `ScoringConstants.kt:64` (1.3) vs ABOUT.md "Honest limitations" §4 ("above ~1.5").

### NEEDS CLARIFICATION — Q5: Should WORKOUT_IMPACT / REST_DAY flags cap Readiness?
**Question:** DATA_FLOW.md §2.5 says Readiness is "capped by recovery flags (illness, overreaching, workout
impact, rest day success)," but only OVERREACHING and ILLNESS_ONSET actually cap the number. Are workout-impact
and rest-day flags purely informational, or should they also cap?
**Reason:** DATA_FLOW.md overstates the caps vs `LoadScoringStrategy.kt:167-186`.

### NEEDS CLARIFICATION — Q6: Is the 4-phase (day-60 maturity) model or the 3-phase (day-42) enum authoritative?
**Question:** ABOUT.md describes Calibration (1–7), Provisional (8–21), Maturing (22–60), Mature (60+), but
`Phase` has only CALIBRATION (<7), PROVISIONAL (<42), MATURE (≥42). Which is correct for user-facing phase
labelling?
**Reason:** Phase boundaries (21, 60) in ABOUT.md have no counterpart in code.
**Conflicting Evidence:** ABOUT.md "How long until your scores stabilise" vs `Phase.kt` / `PhaseCalculator.kt`.

### NEEDS CLARIFICATION — Q7: Which HRV baseline statistic is authoritative for display?
**Question:** `computeHrvBaseline` returns the raw arithmetic median of nightly means, while the frozen
z-score baseline is the geometric mean (`exp(mean(ln …))`) surfaced by `HrvBaselineProvider` as `exp(mu)`.
Which should charts/cards show, and is the divergence intentional?
**Reason:** Two different central-tendency definitions exist for "HRV baseline"; ABOUT.md is not explicit.

### NEEDS CLARIFICATION — Q8: Is cross-timezone score reproducibility a requirement?
**Question:** Scores depend on `ZoneId.systemDefault()` for day boundaries. The determinism guarantee holds
per-device; the same data scored in a different timezone may differ. Is per-device determinism sufficient, or
must scores be timezone-invariant (requiring an explicit stored zone)?
**Reason:** Determinism requirement does not state whether timezone is part of "identical raw data."

### NEEDS CLARIFICATION — Q9: Should the undocumented scoring modifiers be documented (and are their constants final)?
**Question:** Late-nadir 5% penalty (marked "pending internal validation" in code), HRV-score saturation
(slope 0.25), per-profile Banister multiplier (1.0–1.75), and PAI tiered accumulation/readiness-adjustment
all materially affect outputs but are not in ABOUT.md. Should they be documented, and are the constants final?
**Reason:** No documentation source; `ScoringConstants.kt:100` explicitly flags the nadir penalty as pending
validation.

---

# Recommended Next Investigation

*(Investigation targets only — no fixes proposed.)*

1. **Trace the full circadian ownership chain end-to-end** (onboarding → `prefs.consistencyThresholdMinutes`
   → `CircadianConsistencyRepository`) and confirm whether any code path ever writes the profile default into
   the live threshold, to settle Q1/Q2 definitively.
2. **Enumerate all consumers of `ScoringConfig`** (beyond the validator/log) to determine whether the
   profile-aware circadian config, `hrvSaturationZ`, and `emergencyFlags` are uniformly applied, or whether
   any other field shares the "computed-but-unused" pattern found for circadian.
3. **Confirm Banister/Cheng/iTRIMP coefficients against primary literature** (Banister 1991, Morton 1990,
   Cheng 1992, Manzi 2009) once online verification is in scope, including the undocumented per-profile
   multipliers/betas.
4. **Audit `MAX_VALID_*` / validity gates' interaction with calibration counts** to verify the "≥7 valid
   sessions" gate behaves as the calendar-day narrative in ABOUT.md implies.
5. **Timezone/DST replay test:** score a fixed dataset under two zones to quantify the magnitude of the
   `ZoneId.systemDefault()` caveat for Q8.
6. **Reconcile DATA_FLOW.md path root** with the actual package and re-validate every `file:line` ownership
   citation in that document.
