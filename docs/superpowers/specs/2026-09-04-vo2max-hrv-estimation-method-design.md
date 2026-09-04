# Design: Materko-Adapted (Resting HR + HRV) VO2 Max Estimation Method

> **Date:** 2026-09-04 (rev. 3 — final review pass)
> **Status:** APPROVED
> **Target Module(s):** `:core:model`, `:core:scoring`, `:core:database`, `:feature:settings`, `:feature:vitals`, `:feature:dashboard`, `:app`

---

## 1. Overview & Problem Statement

Readylytics estimates VO2 Max for users without wearable VO2 Max support using a single scientific method — the **Uth et al. (2004) Heart Rate Ratio Method** (`UthVo2MaxCalculator`):

```
VO2max = 15.3 × (HRmax / HRrest_baseline)
```

This design adds a **second, selectable estimation method** so users can choose which estimator drives the "estimated" VO2 Max shown in the Vitals/Dashboard cardio-fitness cards.

**Important framing decision (per critical review):** the second method is **not** an exact implementation of a published model. It is a **Readylytics experimental adaptation** of the **Materko (2018)** resting-HRV regression, because Health Connect cannot supply two of the three inputs the published model requires. It is framed as experimental in code, internal docs, and user-facing methodological copy, and the segmented-control label is chosen so the method **cannot reasonably be mistaken for the exact published Materko model** — without requiring the word "experimental" to appear in every short UI label. No published R²/SEE is attributed to the adaptation.

**Scope:** estimation-method selection only. No changes to the source-mode picker (`AUTO` / `WEARABLE_ONLY` / `ESTIMATED_ONLY`), no schema migration, no changes to readiness/sleep scoring formulas. Wearable VO2 Max remains the winner in `AUTO` mode.

---

## 2. Published Materko Model vs. Readylytics Adaptation

### 2.1 Published model (Materko 2018, *Open Acc Biostat Bioinform* 2(3). OABB.000536, fold #1)

```
VO2max = −13.05 + 0.05·MeanRR + 0.12·CDR + 0.05·pNN50
```

- MeanRR: mean NN interval from a **resting tachogram** (ms).
- CDR: cardiac deceleration rate — mean of positive successive NN differences, from a **resting tachogram** (ms).
- pNN50: percentage of successive NN differences > 50 ms, from a **resting tachogram**.
- Fitted jointly on 70 young, healthy, physically active **men** (mean age 22.0 ± 2.6 y). Cross-validated R² = 0.76, SEE = 4.40 ml/kg/min. **Population is narrow: male, young, active; not validated broadly across sex/age/clinical populations.**

The published R²/SEE apply **only** to this complete model with measured tachogram inputs. They are retained in reference comments for context but **must not** be presented as the accuracy of the Readylytics adaptation.

### 2.2 Readylytics adaptation (experimental)

Health Connect exposes HRV as **RMSSD samples only** (`hrv_records.rmssdMs`); the app has no raw beat-to-beat tachogram. The adaptation substitutes available data and drops the term that cannot be derived:

| Published input | Readylytics substitution | Deviation |
|---|---|---|
| MeanRR | `meanRR = 60000 / rhrBaselineBpm` | Approximation — MeanRR was tachogram-derived; here derived from the stable RHR baseline, which may itself be percentile-derived rather than a true arithmetic mean resting HR from a contemporaneous tachogram (can introduce systematic offset) |
| CDR | **omitted** | Cannot be recovered from RMSSD; no tachogram |
| pNN50 | `approxPnn50 = 200·(1 − Φ(50 / rmssd))` from the RMSSD baseline | Approximation — assumes successive NN differences ~ Normal(0, RMSSD²); normality not guaranteed physiologically; **not** true/measured pNN50 |

```
VO2max_adapted = −13.05 + 0.05·meanRR + 0.05·approxPnn50   (no CDR term)
```

The original intercept and remaining coefficients are retained **unchanged**; this is not a re-fitted regression. Deleting the CDR term while keeping the jointly-fitted coefficients is a documented statistical deviation — the result is an experimental estimator, not the published model. No attempt is made to invent replacement coefficients (no source data to refit).

**Explicit non-change — no synthetic CDR.** A Gaussian-proxy term such as `approxCdr ≈ RMSSD × √(2/π)` is deliberately **not** added. CDR carries distributional/asymmetry information that RMSSD does not preserve; adding it would stack unvalidated assumptions on top of the existing approximations. CDR stays omitted, and the estimator stays classified as Materko-adapted/experimental.

### 2.3 What actually drives the estimator

Substituting `meanRR = 60000/RHR`:

```
VO2max_adapted ≈ −13.05 + 3000/RHR + 0.05·approxPnn50
```

`approxPnn50` ∈ [0, 100] contributes at most 0–5 ml/kg/min. The estimator is therefore **predominantly driven by resting heart rate**, with HRV acting as a small adjustment. User-facing labels must reflect this (concise segmented-button label "Resting HR + HRV"; "experimental" conveyed in the description text, per §4.4), not imply HRV is the primary input.

---

## 3. Estimator

New file `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/MaterkoAdaptedVo2MaxCalculator.kt` — `@Singleton`, pure Kotlin, zero Android dependencies, deterministic and side-effect free.

```kotlin
fun estimate(
    rhrBaselineBpm: Float,
    hrvMuMssd: Float?,   // ln(RMSSD) day baseline; null → no estimate
    isCalibrated: Boolean,
): Float?
```

**HRmax is deliberately not an input** — the Materko-derived formula does not use it, and HRmax availability must not gate this estimator. The Uth estimator continues to use HRmax independently.

**Guards (return `null`):**
- `!isCalibrated`
- `rhrBaselineBpm < 30f` (mirror `MIN_PLAUSIBLE_RHR`; also reject non-finite/≤0)
- `hrvMuMssd == null`
- `rmssd = exp(hrvMuMssd)` outside `1.0..200.0` ms (mirrors Health Connect's own RMSSD validation range)

**Computation:**
```
meanRR       = 60000 / rhrBaselineBpm
approxPnn50  = 200·(1 − Φ(50 / rmssd))          // %, via erf approx (Abramowitz–Stegun 7.1.26)
raw          = −13.05 + 0.05·meanRR + 0.05·approxPnn50
```

**Out-of-domain → `null` (no silent clamping).** If `raw` falls outside `MIN_SUPPORTED_VO2_MAX..MAX_SUPPORTED_VO2_MAX`, return `null` rather than coercing to a boundary: an invalid extrapolation must not present as a real estimate. These bounds are **application-level supported/plausibility bounds** intended to reject unreasonable extrapolation — not absolute physiological limits. This is a documented application-level safety/display constraint, tested explicitly.

**Companion constants** (named, REF-commented):
- `INTERCEPT = −13.05f`, `MEAN_RR_COEFF = 0.05f`, `PNN50_COEFF = 0.05f` — REF: Materko 2018, OABB.000536, fold #1 (original full-model context only; see §2.1 disclaimer).
- `PNN50_THRESHOLD_MS = 50f`, `MIN_SUPPORTED_VO2_MAX = 15f`, `MAX_SUPPORTED_VO2_MAX = 95f`, `MIN_PLAUSIBLE_RHR = 30f`.
- `approxPnn50` private helper: monotonic increasing in `rmssd`, → 0 as `rmssd → 0`, bounded `[0, 100]`.

**KDoc must state, verbatim in substance:**
1. This is an experimental Readylytics adaptation, not the published Materko model.
2. Deviations: CDR omitted (no synthetic proxy); pNN50 approximated from RMSSD under a normality assumption; MeanRR derived from the RHR baseline, which may be percentile-derived rather than a true arithmetic mean resting HR from a contemporaneous tachogram (possible systematic offset).
3. Original model developed in young, healthy, physically active men; not broadly validated.
4. Published R²/SEE are context only, not attributable to this adaptation.
5. Out-of-domain estimates return `null` per application-level supported bounds.

---

## 4. Preferences, Settings & UI

### 4.1 Domain enum
New file `core/model/.../domain/preferences/Vo2MaxEstimationMethod.kt` (mirrors `Vo2MaxSourceMode.kt`):
```kotlin
enum class Vo2MaxEstimationMethod { HR_RATIO, MATERKO_ADAPTED }
```

### 4.2 UserPreferences & persistence
- `UserPreferences.vo2MaxEstimationMethod: Vo2MaxEstimationMethod = Vo2MaxEstimationMethod.HR_RATIO`.
- Proto field `vo2MaxEstimationMethod` (string) in `UserPreferencesSerializer` + `UserPreferencesSerializerExtensions` + `UserPreferencesMapperExtensions`.
- Backup round-trip: `BackupModels.UserPreferencesBackup` + `BackupPreferencesBuilder.buildScoringAndDevices` + `RestorePreferencesExtensions` + `RestorePreferencesApplier` (same shape as `vo2MaxSourceMode`).

### 4.3 Settings repository & events
- `PhysiologySettings.updateVo2MaxEstimationMethod(method)` (interface + `SettingsRepository` impl).
- New `SettingsEvent.Vo2MaxEstimationMethodChanged(method: Vo2MaxEstimationMethod)`.
- `PhysiologySettingsViewModel.onEvent`: update pref, then **`healthDataRefresh.refreshHistorical()`** exactly once (same intended path as gender/profile/RAS-factor changes — VO2 Max is historical-scope). No separate pending-recalc flag/button.

### 4.4 UI
In `PhysiologyProfileCategoryScreen`, a second `SingleChoiceSegmentedButtonRow` immediately below the existing `Vo2MaxSourcePicker`. Concise labels for the M3 segmented control (must not imply HRV is primary; "experimental" stays in the description text, not the button):
- `HR_RATIO` → "Heart rate ratio"
- `MATERKO_ADAPTED` → "Resting HR + HRV"

New strings: `vo2_max_method_title`, `vo2_max_method_hr_ratio`, `vo2_max_method_materko_adapted`, `vo2_max_method_description`. The description text under the picker explicitly communicates the status, e.g.:

> "Experimental Materko-adapted estimate using your resting heart rate and HRV baselines."

`PhysiologySettingsState` gains `vo2MaxEstimationMethod`; picker emits `Vo2MaxEstimationMethodChanged`.

---

## 5. Scoring Integration

### 5.1 `FinalSummaryAssembler.resolveVo2Max`
Branch on `inputs.context.prefs.vo2MaxEstimationMethod` (single walk-forward prefs snapshot per run, per SCORE-004 — all days in a run use the same method):
- `HR_RATIO` → `uthVo2MaxCalculator.estimate(hrMax, rhrBaselineBpm, isCalibrating = !isCalibrated)`, source tag `"ESTIMATED_UTH"` (existing behavior unchanged).
- `MATERKO_ADAPTED` → `materkoAdaptedVo2MaxCalculator.estimate(rhrBaselineBpm, hrvMuMssd, isCalibrated)`, source tag `"ESTIMATED_MATERKO_ADAPTED"`.

**Calibration semantics (explicit, no inversion):** the assembler already computes `isCalibrated`. Pass `isCalibrated` **directly** to the new calculator (guard `if (!isCalibrated) return null`). The Uth calculator keeps its existing `isCalibrating` contract (`!isCalibrated` at its call site). Both call sites use named arguments; the asymmetry is documented in a comment so a future edit cannot silently flip one.

**HRV input `hrvMuMssd`:** the day's scored ln-RMSSD baseline from the assembled summary (`withFatigue.hrvMuMssd`) — frozen per-day baseline when a snapshot exists, otherwise freshly computed for the day. `exp(hrvMuMssd)` is the RMSSD baseline (ms). This reuses the existing stable log-RMSSD baseline pipeline; **no new aggregation path** is introduced. Multiple overnight RMSSD samples already feed this baseline; note that aggregating samples still cannot reconstruct true pNN50 — `approxPnn50` is used regardless.

**RHR input `rhrBaselineBpm`:** Readylytics' existing stable RHR baseline from `initialBaselines.rhrBaselineValue` (frozen snapshot → override → adaptive sleep-RHR percentile). It is used as a **practical proxy** for the tachogram-derived MeanRR the published model requires — explicitly documented as an approximation. Because the Readylytics baseline may be percentile-derived rather than a true arithmetic mean resting HR from a contemporaneous tachogram, it can introduce systematic offset. This is the intended input; no new HR aggregation pipeline is introduced. The percentile-derived baseline is the stable counterpart to the overnight HRV observation window.

`FinalSummaryAssembler` constructor gains `materkoAdaptedVo2MaxCalculator` (wired in `ScoringDayUseCases`).

### 5.2 `Vo2MaxSourceResolver`
Signature change:
```kotlin
fun resolve(
    mode: Vo2MaxSourceMode,
    wearableVo2Max: Float?,
    estimatedVo2Max: Float?,
    estimatedSource: String?,   // "ESTIMATED_UTH" | "ESTIMATED_MATERKO_ADAPTED"
): Vo2MaxResolution
```
The estimated branches (`AUTO` fallback, `ESTIMATED_ONLY`) emit `estimatedSource`. Single production caller is `FinalSummaryAssembler`. `Vo2MaxSourceResolverTest` updated for the new signature + tag.

**No DB migration:** existing rows keep their stored `vo2MaxSource` values.

### 5.3 Source-tag → label mapping (repo-inspection finding)
Two UI sites map the persisted source string to a label and **must** learn the new tag, or it shows as a raw string / no label:
- `feature/vitals/.../CardioFitnessDetailScreen.kt:305` `sourceLabelRes` — add `"ESTIMATED_MATERKO_ADAPTED"` → new `CoreUiR.string.vo2_max_source_label_materko_adapted`.
- `feature/dashboard/.../DashboardCardioMetricPresentationFactory.kt:118` `resolveSourceLabel` — same mapping (its `else` currently falls back to the raw string).

---

## 6. Recalculation

Changing the method writes the pref and calls `healthDataRefresh.refreshHistorical()` (one call). This mirrors gender/profile/RAS-factor handling. A test asserts the VM emits the refresh exactly once through the intended `HealthDataRefresh` path. Walk-forward recompute re-derives each day's VO2 Max with the new method; determinism contracts (idempotent recompute, single-pref-snapshot-per-run) are unchanged.

---

## 7. Testing

| Test | Coverage |
|---|---|
| `MaterkoAdaptedVo2MaxCalculatorTest` (pure Kotlin) | guards: `!isCalibrated` → null; rhr < 30 / non-finite → null; `hrvMuMssd` null → null; `exp(hrvMuMssd)` outside 1–200 → null; **HRmax not required** (no param); out-of-domain raw → null (both below `MIN_SUPPORTED_VO2_MAX` and above `MAX_SUPPORTED_VO2_MAX`) |
| `approxPnn50` tests | → 0 for very low RMSSD; monotonic increasing with RMSSD; bounded `[0, 100]` |
| Φ/erf reference tests | Φ(0)=0.5, Φ(1)≈0.84134, Φ(−1)≈0.15866, Φ(1.96)≈0.97500 — verify the numerical helper directly, not only via final VO2max |
| Representative outputs | known-value matrix across several RHR/RMSSD combos (e.g. RHR 60→meanRR=1000; RMSSD 50→approxPnn50≈31.73%); assert exact expected VO2max_adapted |
| `Vo2MaxSourceResolverTest` | `estimatedSource` emitted for AUTO fallback + ESTIMATED_ONLY; tags distinguish `ESTIMATED_UTH` vs `ESTIMATED_MATERKO_ADAPTED` |
| `FinalSummaryAssembler` / repo tests | MATERKO_ADAPTED selects the new estimator; AUTO fallback uses chosen method; source tag persisted; null when `hrvMuMssd` null; calibration boolean cannot be inverted (both call sites) |
| Settings test mirroring `PhysiologyProfileVo2MaxSourceTest` | method picker renders + emits `Vo2MaxEstimationMethodChanged`; **switching method triggers `refreshHistorical()` exactly once** |
| Backup round-trip (`RestorePreferenceEnumRoundTripTest` pattern) | `vo2MaxEstimationMethod` survives backup/restore |
| UI mapping tests | `sourceLabelRes` / `resolveSourceLabel` render a label for `ESTIMATED_MATERKO_ADAPTED` |

---

## 8. Documentation Synchronization (Mandatory)

Per the repo's Documentation Synchronization Rule, the same change must update:
- `internal-docs/DATA_FLOW.md` — scoring path: new estimator, `Vo2MaxSourceResolver` signature, `resolveVo2Max` branch.
- `ABOUT.md`, `docs/about.md`, in-app About strings (`about_*` / `tooltip_*` in `values/strings.xml`) — new method explanation including: experimental-adaptation framing; the CDR/pNN50/MeanRR deviations (incl. the percentile-derived RHR-baseline-as-MeanRR proxy and its possible systematic offset); RHR-dominance; the **population caveat** (original model developed in young, healthy, physically active men; not broadly validated); and the supported-bounds (`MIN_SUPPORTED_VO2_MAX`..`MAX_SUPPORTED_VO2_MAX`) null-on-out-of-domain behavior. User-facing copy must make it impossible to mistake the method for the exact published Materko model (concise segmented-button label + explicit description text).
- `DocumentationDriftTest` must pass.

No `docs/index.md`/`docs/privacy.md` change: no new data collection or sharing.

---

## 9. Out of Scope

- Exercise/submaximal VO2 Max methods (need workout data; not resting-only).
- Raw tachogram capture (Health Connect exposes RMSSD only).
- Re-fitting regression coefficients (no source data).
- Changes to readiness, sleep, or load scoring formulas.
- Changes to the source-mode picker (`AUTO`/`WEARABLE_ONLY`/`ESTIMATED_ONLY`).
- Onboarding picker changes (method defaults to `HR_RATIO`; onboarding may keep setting only the source mode).
- Fixing the pre-existing `ResolveDailyBaselinesUseCase` RHR fallback to `DEFAULT_RHR_BPM` (60) when no data exists — both estimators inherit it; out of scope, noted as a known limitation.

---

## 10. Notes for Future Work

Approach chosen here is **minimal branch** (two standalone calculators + pref branch). If a third estimation method is ever added, refactor to a `Vo2MaxEstimator` interface + DI factory at that point; the blast radius today does not justify it.