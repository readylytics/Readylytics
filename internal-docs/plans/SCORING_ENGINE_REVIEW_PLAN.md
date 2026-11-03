# Scoring Engine Review: Findings and Fix Plan

- **Date:** 2026-11-02
- **Reviewed commit:** `57c6316`
- **Status:** Proposal. No code has been changed. Tier 1 items are defects with local fixes. Tier 2 items need a product or methodology decision before any change.
- **Scope:** sleep score, restoration (HRV/RHR z-scores), baselines and calibration, training load (TRIMP, ATL/CTL, strain ratio, load score), readiness, training readiness, circadian consistency, everyday-HR load, TSB and VO2max. Code was checked against `ABOUT.md`, and every numeric example below was computed and verified.

## Notation and path abbreviations

| Symbol | Meaning |
| --- | --- |
| ATL / CTL | Acute / chronic training load: an EWMA of daily TRIMP with α = 2/(N+1), N = 7 / 42 |
| SR | Strain ratio = ATL / CTL. Load score = 100 if SR ≤ 1.3, else 100·exp(−2.5·(SR−1.3)²) |
| z (HRV) | (ln RMSSD_today − μ_ln) / σ_ln. HRV score = 50 + 25·z, with saturation above a profile-specific z |
| z (RHR) | (RHR_today − μ) / σ. RHR score = 50 − 25·z |
| sRest | Restoration = w_hrv·HRV score + w_rhr·RHR score (×0.95 for a late nadir) |
| Readiness | 0.4·sRest + 0.3·Sleep + 0.3·Load, capped at 50 on ILLNESS_ONSET |

| Prefix | Expands to |
| --- | --- |
| `S/` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/scoring/` |
| `C/` | `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/` |
| `R/` | `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/` |
| `M/` | `core/model/src/main/kotlin/app/readylytics/health/core/model/` |

---

## Tier 1: Defects (proposed for fixing)

### 1. ATL/CTL EWMA initialisation is inconsistent, and the load score collapses after a first workout. Severity: HIGH

**Where:** `S/strategies/RasScoringStrategy.kt:78-99` (`computeEmaWithDecay`), fed by `R/ReadinessSummaryCoordinator.kt:212-237` (`resolveTrimpSeries`). That map holds only the workout days from the last 84 days, plus today.

- **ATL and CTL start differently.** Each series starts at `min(earliestEntry, rangeEnd − (N−1))`. ATL is seeded with the first workout's raw TRIMP. When there are fewer than 42 days of history, CTL starts before the data, so it is zero-padded.
  - *Example:* a new user doing 100 TRIMP/day for 20 days gets ATL = 100, CTL = 100·(1 − (41/43)^20) = 61.4, SR = 1.63 and Load = 76, despite perfectly steady training.
- **The `size == 1` shortcut creates a cliff** (line 84).
  - On the day of a first workout (or the first after more than 84 days off), the map holds one entry, so ATL = CTL = T, SR = 1 and Load = 100.
  - On the next day the map holds two entries, giving ATL = 18.75, CTL = 4.43 (T = 100), SR = 4.23 and **Load ≈ 0**, so Readiness drops by about 30 points.
- **The seed gets weight 1 instead of α, so the result depends on how the data is stored.** `{d−70: 0, d−60: 100}` and `{d−60: 100}` give different CTL values, although missing days are documented as zero. The seed's residual weight is (1−α)^k: 9.2% at 50 days and 1.8% at 84 days. The 84-day window slides, so the seed changes from day to day.
- **The chart disagrees with the stored score.** `computeEmaSeries` feeds the Workouts chart (`feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsStateFactory.kt:442-477`) and the dashboard strain increase (`feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/ObserveDashboardStrainIncreaseUseCase.kt`). It is given one entry per day, with explicit zeros, and backfills a placeholder `35` before its start date. The stored score uses the sparse map, so the two ATL/CTL values differ.
- **Dead code with different semantics.** The list-based `computeEma` (seed 35, SMA over the first 7 days) is still exposed through `ScoringCalculator`.

**Fix:**
- Use one EWMA implementation everywhere. Iterate over every calendar day from a fixed origin, zero-initialise it (or seed ATL and CTL identically), and remove the `size == 1` special case.
- Add a minimum-history guard in `S/BuildLoadSeriesUseCase.kt`: return a neutral Load = 100, flagged as insufficient history, until there are at least 28 days of TRIMP history or CTL is above a small floor. ACWR is unstable when chronic load is near zero.
- Point the chart and dashboard callers at the same function, and remove the legacy `computeEma`.

### 2. Sleep-stage minutes are truncated per segment. Severity: HIGH (systematic bias)

**Where:** `M/domain/sync/mappers/SleepDataMapper.kt:18`. Each stage's duration is converted with `(end − start) / 60_000` integer division *per segment*, and the results are then summed.

- The expected loss is 0.25 to 0.5 min per segment, or about 10 to 30 min per night at 40 to 60 segments.
- Segments shorter than a minute disappear completely.
- This biases total sleep time, efficiency and deep/REM % downward. That lowers the Duration and Architecture sub-scores and affects night-validity gating (the 240-min minimum).

**Fix:** add up milliseconds per stage type and convert to minutes once. Stored rows are only corrected by re-ingestion, through the existing Settings "Resync Health Connect data" flow.

### 3. Devices that report "SLEEPING" only score Architecture = 0. Severity: HIGH for affected devices

**Where:**
- `core/healthconnect/src/main/kotlin/app/readylytics/health/core/healthconnect/data/healthconnect/HealthConnectRecordConverters.kt:53-55` maps `STAGE_TYPE_SLEEPING` to LIGHT, which gives deep = REM = 0 and light > 0.
- `S/SleepBaselineMetrics.kt:70-80` (`isStagesSuspicious`) only detects deep = REM = light = 0.
- `S/strategies/LoadScoringStrategy.kt:176-209` (`validateNight`) only checks upper bounds.

The night therefore goes through the full-weight path with sArch = 0, losing 20 points of sleep score under the Balanced profile. `ABOUT.md` ("Suspicious sleep-stage reweight") promises a reweight when "no deep or REM sleep [is] detected at all".

**Fix:** treat `deep == 0 && rem == 0` as "no stage breakdown", which sends the night to the degraded (Duration + Restoration) weights.

### 4. Personal HRV σ never reaches full weight. Severity: MEDIUM (contradicts the docs)

**Where:** `M/domain/scoring/ScoringConstants.kt:19-21` sets `HRV_SIGMA_WINDOW_DAYS = 56` but `HRV_SIGMA_BLEND_MAX_N = 60`. The blend is in `S/strategies/LoadScoringStrategy.kt:32-47`.

- n is the number of valid nights in the 56-day window, so the blend weight w = (n − 7)/53 tops out at **0.925**. σ is always at least 7.5% population prior.
- `ABOUT.md` says Mature (60+ nights) users get personal variability.
- The docs say blending begins in Maturing (21–59), but the code starts at n = 7.
- The blend is linear in σ rather than in σ². This is minor.

**Fix:** set `HRV_SIGMA_BLEND_MAX_N = HRV_SIGMA_WINDOW_DAYS`, and correct the phase description of the blend in the docs.

### 5. RHR z-score has no σ floor and includes tonight's value. Severity: MEDIUM

**No σ floor.**
- **Where:** `S/SleepBaselineMetrics.kt:93-99` and `S/strategies/LoadScoringStrategy.kt:90-104`. σ = `stdev(rhrValues)` with only a `> 0` guard.
- RHR is an integer percentile, and stable sleepers can have an SD of 0.6 to 1 bpm. A 3-bpm rise then gives z ≈ 3 to 5 and RHR score = 0, wiping out the RHR half of Restoration.
- HRV has `MIN_LN_SIGMA`; RHR has no equivalent.

**Self-inclusion (decision OD-5).**
- **Where:** `S/HistoricalRhrWindow.kt:21-27` puts tonight's value into both μ (the median) and σ.
- This attenuates z and caps it at √n (2.65 with 7 nights). With realistic spread, a true +3σ spike reads as z ≈ 2.0 with 7 nights, and a true +4σ spike reads as about 3.2 with 30 nights.
- Yesterday's illness criterion uses *only* `zRhr ≥ 2.0` (`S/components/RecoveryFlagEvaluator.kt:71-75`), with no bpm-delta path unlike today's. Illness detection is therefore systematically desensitised, especially in the first weeks.

**Fix:** add `RHR_MIN_SIGMA_BPM` (proposed 1.5 bpm), with prior shrinkage like HRV uses. Recommend excluding tonight from the z population; that requires revisiting OD-5.

### 6. A day's RHR baseline changes once it is frozen. Severity: MEDIUM (determinism)

A day's first (live) pass and later frozen re-scores use different RHR baselines.

- **Different μ statistic.**
  - Live z: μ = median of `rhrHistoryBetween`, which is *ungated* and includes nights without HRV or shorter than 4 h (`S/BaselineZScoreComputer.kt:56-64`).
  - Frozen re-score: μ = the persisted `rhrBpm`, which is the *gated* nadir median (`S/ResolveDailyBaselinesUseCase.kt:49-63`, `S/AssembleDailySummaryUseCase.kt:55`).
- **Different rounding.** `baselineRhrValue` is `frozenRhr.toInt()` (truncation) on frozen passes but `roundToInt()` on live ones (`S/ComputeSleepMetricsUseCase.kt:130-137`). A median of 52.5 moves `rhrDeltaBpm` by 1, which can flip ILLNESS_ONSET (the ≥5 bpm rule) and `isRhrOptimal`.
- **Late-arriving HRV.** If a night had no HRV when the day froze, `hrvSigmaMssd` was persisted as null. HRV that arrives later is then scored with σ = prior.

**Fix:** use the same population for the live z-score μ and the persisted `rhrBpm`, use `roundToInt()` everywhere, and persist σ even when the night has no HRV.

### 7. The late-nadir rule compares clock time with sleep time. Severity: MEDIUM

**Where:** `S/sleep/SleepNadirAnalyzer.kt:39-45` compares elapsed *clock* time since the core start with 0.67 × `coreSleepDurationMinutes`, which is *total sleep time*.

- With 8 h in bed at 92% efficiency, the cutoff falls about 26 min early. It is earlier still for merged multi-segment cores, whose gaps count as clock time.
- This inflates NADIR_DELAYED and the 0.95 Restoration penalty.
- The nadir is a single-sample argmin, so one artefact can move it.

**Fix:** use `RecoveryWindow.clockDurationMinutes` (already computed in `S/sleep/SleepDayModels.kt:121-128`). Optionally, locate the nadir on a rolling 5-min median.

### 8. Naps dilute the Architecture percentage. Severity: MEDIUM

**Where:** the scoring session built in `R/ReadinessSummaryCoordinator.kt:78-88` uses:
- `durationMinutes` = core plus **all** naps;
- deep/REM = core plus **architecture-eligible** naps only (`S/sleep/SleepDayAggregator.kt:105-117`).

The numerator and denominator cover different sets of segments. *Example:* a 420-min core with 20% deep plus a stage-less 60-min nap gives deep 17.5%, cutting the Architecture sub-score by about 12.7 points. `ABOUT.md` says naps "won't rewrite your overnight" metrics.

**Fix:** divide stage minutes by the sleep minutes of the segments whose stages are counted.

### 9. Circadian wake times wrap at noon. Severity: MEDIUM

**Where:** `S/CircadianWakeBaseline.kt:80-87` normalises *wake* times with a fixed noon pivot, the same as bedtimes.

- A wake time of 11:55 maps to 2155 and one of 12:05 maps to 725: a 1,430-min "deviation" that scores 0. The median also breaks when wake times straddle noon.
- In addition, the evaluation nights are part of their own baseline median (`S/CircadianConsistencyRepository.kt:121-138`), which pulls deviations toward zero.

**Fix:** compute deviations circularly (wrap to ±720 min), and use a median that is safe across the wrap point (rotate around the circular mean first).

### 10. `HrCoverageValidator` measures span, not coverage. Severity: LOW

**Where:** `S/sleep/HrCoverageValidator.kt:19-27` sums consecutive gaps with no cap, which equals last − first. Two samples at 23:00 and 06:59 count as 100% coverage.

Impact is limited today, because historical nights use `assumeCoverageValid = true` and only tonight's +1 toward maturity is gated.

**Fix:** cap each gap's contribution (for example at 5 min).

### 11. The Contributors duration score ignores the user's hypersomnia setting. Severity: LOW

**Where:** `S/ComputeSleepMetricsUseCase.kt:508-513` computes `durationScore` without `hypersomniaOnsetRatio`, so the displayed contributor can differ from the sub-score used in the sleep score.

**Fix:** pass `scoringConfig.hypersomniaOnsetRatio`.

---

## Tier 2: Methodology concerns (need a product decision; no change proposed yet)

12. **Restoration is centred at 50 and counted twice.**
    - `50 ± 25z` gives an average night a score of 50. With perfect sleep and normal load, the Sleep Score maxes out at **87.5** and Readiness at **≈ 76.25**, so "Excellent" requires above-baseline physiology. Absolute scales (duration = 100 at goal) are mixed with relative ones.
    - sRest enters Readiness directly (0.4) and through the Sleep Score (0.3 × 0.25), for an **effective weight of 0.475** (0.52 under Recovery Focused). `ABOUT.md` says 0.4.
13. **Profile-tiered z thresholds double-count noise.**
    - A z-score already divides by σ, so P(|Z| > k) depends only on k. "More noise, so a larger threshold" is therefore not a valid statistical argument.
    - In Early Baseline, the profile σ priors (0.10 / 0.15 / 0.20, `M/data/preferences/PhysiologyProfile.kt`) multiply with the thresholds (1.2 / 1.5 / 2.0, `S/ScoringConfigFactory.kt:113-137`). An Athlete is flagged at about an 11% HRV drop, a Sedentary user only at about 33%. Per-night false-positive rates are 11.5% versus 2.3%.
14. **The choice of TRIMP model breaks absolute thresholds.** ACWR is scale-free, but these are not:
    - the 120 / 10 TRIMP workout-impact and rest-day flags;
    - the TRIMP/min intensity bands;
    - the TSB zones;
    - RAS scaling;
    - the 70 × gain fatigue cut-off.

    At hrR = 0.7, iTRIMP/min ≈ 3.04 versus Banister ≈ 1.72, so switching models moves workouts up about one intensity category.
15. **TSB zones are borrowed but don't fit.** With α = 2/(N+1), the effective time constants are τ_ATL ≈ 3.5 d and τ_CTL ≈ 21 d, not the 7 / 42 d the +25 / −10 / −30 zones assume, and TRIMP is not TSS. The zones are uncalibrated for this implementation (`C/TrainingStressBalanceCalculator.kt`).
16. **Everyday-HR load depends on sample density.** Every minute bucket with at least one sample counts as a full minute of TRIMP (`S/EverydayHeartRateLoadCalculator.kt:62-93`). A watch that samples every 10 minutes under-counts by about 10× compared with continuous sampling, and the result is added to time-integrated workout TRIMP.
17. **Uth VO2max is biased upward.** The nocturnal 5th-percentile RHR is fed into a formula fitted on supine morning RHR (`C/UthVo2MaxCalculator.kt`). At HRmax 183, a nocturnal RHR of 48 versus a morning RHR of 58 gives 58.3 versus 48.3 ml/kg/min, about 20% high.
18. **Minor items:**
    - HRV override priority is inconsistent: frozen μ beats the override in the z-score (`S/strategies/LoadScoringStrategy.kt:66-71`), but the override beats frozen μ in the optimal flags (`S/SleepBaselineMetrics.kt:40-42`).
    - Pooled core efficiency is TST-weighted when it should be TIB-weighted (`R/ReadinessSummaryCoordinator.kt:138-144`).
    - Workout TRIMP holds the last sample across gaps with no cap (`S/ComputeWorkoutTrimpUseCase.kt:94-107`).
    - Banister uses male coefficients for unset or other gender (`S/RasCalculator.kt:46-50`).
    - Doc drift: `ABOUT.md` gives a single HRV saturation z of 1.5, but the code is profile-tiered (1.2 / 1.5 / 2.0). The age table lists 12% deep sleep for 60+, but the continuous formula gives 14.6% at age 60 and only reaches 12% at 76.

---

## Implementation plan (Tier 1 Phased Roadmap)

The 11 Tier 1 defects are decomposed into 4 sequential, isolated phases to minimize risk, keep review diffs focused, and preserve architectural integrity. Each phase carries its own independent test suite, documentation synchronization, and review cycle.

### Phase 1: Ingestion & Data Validation (#2, #3, #10)
*Status: Planned (`docs/superpowers/specs/2026-11-02-scoring-engine-phase1-ingestion-validation-design.md`, `docs/superpowers/plans/2026-11-02-scoring-engine-phase1-ingestion-validation.md`)*

- **Task 1.1:** Create `SleepStageAccumulator` in `core:model` for sub-minute stage duration aggregation in milliseconds with unbiased rounding `(ms + 30_000L) / 60_000L` (#2).
- **Task 1.2:** Integrate `SleepStageAccumulator` into `SleepDataMapper` (`mapSleepSession` and `mapSleepSessionStages`) to eliminate per-segment truncation loss (#2).
- **Task 1.3:** Create `StageIntegrityStatus` enum (`VALID`, `NO_BREAKDOWN`, `IMPLAUSIBLE_RATIO`) and `StageIntegrityEvaluator` in `core:scoring` (#3).
- **Task 1.4:** Integrate `StageIntegrityEvaluator` into `SleepBaselineMetrics.isStagesSuspicious` to treat `deep == 0 && rem == 0` as `NO_BREAKDOWN` and trigger the Duration + Restoration reweighting for generic `STAGE_TYPE_SLEEPING` devices (#3).
- **Task 1.5:** Create `IntervalCoverageAccumulator` in `core:scoring` to cap inter-sample HR gaps at 5 minutes (`300_000L`) (#10).
- **Task 1.6:** Integrate `IntervalCoverageAccumulator` into `HrCoverageValidator.isValid` to evaluate active coverage instead of sample span (#10).
- **Task 1.7:** Synchronize `internal-docs/DATA_FLOW.md` (§Ingestion, §Suspicious stages, §Coverage validation), verify `DocumentationDriftTest`, and run full pre-commit verification suite.

---

### Phase 2: Sleep Timing, Naps & Circadian (#7, #8, #9, #11)
*Status: Proposed*

- **Task 2.1:** Late-Nadir Clock Time: Update `SleepNadirAnalyzer` to compare nadir elapsed clock time with `RecoveryWindow.clockDurationMinutes` instead of total sleep time (`coreSleepDurationMinutes`) (#7).
- **Task 2.2:** Nap Architecture Denominator: Update `ReadinessSummaryCoordinator` and `SleepDayAggregator` so deep and REM percentages divide by the sleep minutes of stage-eligible segments only, preventing stage-less naps from diluting architecture (#8).
- **Task 2.3:** Circular Circadian Deviation: Update `CircadianWakeBaseline` and `CircadianConsistencyRepository` to compute wake time deviations circularly (wrapping to ±720 min) and use a safe circular median across noon (#9).
- **Task 2.4:** Contributors Hypersomnia Ratio: Pass `scoringConfig.hypersomniaOnsetRatio` in `ComputeSleepMetricsUseCase` to align displayed duration contributor score with the actual sleep score (#11).
- **Task 2.5:** Synchronize `DATA_FLOW.md` and `ABOUT.md` circadian/timing sections and add unit tests.

---

### Phase 3: Baselines & Statistical Calibration (#4, #5, #6)
*Status: Proposed*

- **Task 3.1:** Personal HRV σ Blend Ceiling: Set `HRV_SIGMA_BLEND_MAX_N = HRV_SIGMA_WINDOW_DAYS` (56) in `ScoringConstants.kt` so personal variability reaches 1.0 weight at maturity, and align documentation (#4).
- **Task 3.2:** RHR σ Floor: Add `RHR_MIN_SIGMA_BPM = 1.5` in `ScoringConstants` and apply in `LoadScoringStrategy.computeRhrZScore` and `computeBaselineMetrics` (#5).
- **Task 3.3:** RHR Self-Inclusion Resolution: Revisit decision OD-5 regarding current-night inclusion in the baseline median/stdev population (#5).
- **Task 3.4:** RHR Live vs. Frozen Baseline Alignment: Unify RHR μ population between live and frozen passes, enforce `roundToInt()` consistently, and persist σ even when HRV is null (#6).
- **Task 3.5:** Synchronize `DATA_FLOW.md` and `ABOUT.md` baseline sections and verify baseline freeze behavior tests.

---

### Phase 4: Training Load & Unified EWMA Engine (#1)
*Status: Proposed*

- **Task 4.1:** Unified Calendar-Day EWMA: Implement a single calendar-day EWMA (point evaluation + series) in `RasScoringStrategy`, zero-initialized over every calendar day from a fixed origin, removing the `size == 1` cliff (#1).
- **Task 4.2:** Load History Guard: Add minimum history guard in `BuildLoadSeriesUseCase` (return neutral Load = 100 with insufficient history flag until 28 days of history or CTL floor) (#1).
- **Task 4.3:** Unify UI Chart & Dashboard Callers: Point `WorkoutsStateFactory` and `ObserveDashboardStrainIncreaseUseCase` at the unified EWMA implementation (#1).
- **Task 4.4:** Clean Legacy EWMA Code: Remove legacy list-based `computeEma` and backfill placeholder code (#1).
- **Task 4.5:** Synchronize `DATA_FLOW.md` (§Load) and `ABOUT.md` (§Training Load), update strings/tooltips, and verify chart/load equivalence tests.

---

### History & Rollout
Historical scores change only when the user runs the existing "Recalculate scores" / "Resync Health Connect data" flows. No new triggers or database schema migrations are added.

**Constraints:**
- Stay within the ≤ 400 lines/file target.
- Add no new detekt issues or `@Suppress`.
- Keep scoring logic in pure Kotlin.
- Mirror the source packages in tests.

## Verification

New unit tests (`core/scoring/src/test/...`, `core/model/src/test/...`):

- **EWMA:**
  - A sparse map and the same data with explicit zero days give the same result.
  - Load changes continuously from a first-workout day to the next day.
  - The chart series equals the stored series.
- **Sleep mapping and staging:**
  - `SleepDataMapper` handles 30-s and sub-minute segments without loss.
  - A SLEEPING-only night takes the degraded weights.
  - A nap doesn't change deep %.
- **Baselines:**
  - The HRV σ blend weight reaches 1.0.
  - The RHR σ floor applies.
  - Re-scoring a day live and then frozen gives identical z values and flags.
- **Timing and coverage:**
  - The late-nadir threshold uses clock time.
  - Wake times of 11:55 and 12:05 deviate by 10 min.
  - Two-sample HR coverage is invalid.

Then run `./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest`, followed by `./gradlew lintRelease`, and `codegraph index` for any new files.
