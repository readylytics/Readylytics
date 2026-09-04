# Design: HRV-Based VO2 Max Estimation Method (Materko-Adapted)

> **Date:** 2026-09-04
> **Status:** APPROVED
> **Target Module(s):** `:core:model`, `:core:scoring`, `:core:database`, `:feature:settings`, `:app`

---

## 1. Overview & Problem Statement

Readylytics estimates VO2 Max for users without wearable VO2 Max support using a single scientific method — the **Uth et al. (2004) Heart Rate Ratio Method** (`UthVo2MaxCalculator`):

```
VO2max = 15.3 × (HRmax / HRrest_baseline)
```

This design adds a **second, selectable estimation method based on resting HRV**, following the **Materko (2018)** prediction model, so users can choose which estimator drives the "estimated" VO2 Max shown in the Vitals/Dashboard cardio-fitness cards.

The change requires:
1. A new pure-Kotlin HRV-based estimator (`HrvMaterkoVo2MaxCalculator`).
2. A new preference `Vo2MaxEstimationMethod` (`HR_RATIO` | `HRV_MATERKO`), with settings UI and backup round-trip.
3. A branch in `FinalSummaryAssembler.resolveVo2Max` that computes the selected method's estimate.
4. An `estimatedSource` parameter on `Vo2MaxSourceResolver.resolve` so the persisted `vo2MaxSource` tag identifies which estimator produced the value.
5. A historical recompute trigger when the method changes.

**Scope:** estimation-method selection only. No changes to the source-mode picker (`AUTO` / `WEARABLE_ONLY` / `ESTIMATED_ONLY`), no schema migration, no changes to readiness/sleep scoring formulas. Wearable VO2 Max remains the winner in `AUTO` mode.

---

## 2. Data Constraints

Health Connect exposes HRV as **RMSSD samples only** (`hrv_records.rmssdMs`); the app has no raw beat-to-beat tachogram. Materko's published model needs three resting inputs:

| Materko input | Derivable from app data? |
|---|---|
| Mean RR interval | Yes — `meanRR = 60000 / rhrBaselineBpm` |
| pNN50 | Approximated — normal-distribution model `pNN50 = 200·(1−Φ(50/rmssd))` from RMSSD baseline |
| CDR (cardiac deceleration rate) | No — needs raw tachogram; term **omitted** (documented deviation) |

Note (context): in Materko's study the mean RR interval dominates (`r = 0.75`); RMSSD alone adds little (`r = 0.30`). The HRV-based estimate is therefore RHR-dominated, but it is a distinct, citable estimator that also responds to vagal-tone baseline changes.

---

## 3. Formula & Estimator

New file `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/HrvMaterkoVo2MaxCalculator.kt` — `@Singleton`, pure Kotlin, zero Android dependencies.

```kotlin
fun estimate(
    hrMax: Float,
    rhrBaselineBpm: Float,
    hrvMuMssd: Float?,   // ln(RMSSD) baseline for the day; null → no estimate
    isCalibrating: Boolean,
): Float?
```

**Guards (return `null`):**
- `isCalibrating`
- `hrMax < 90f`
- `rhrBaselineBpm < 30f`
- `hrvMuMssd == null`
- `rmssd = exp(hrvMuMssd)` outside `1.0..200.0` ms (mirrors Health Connect's own RMSSD validation range)

**Computation:**
```
meanRR  = 60000 / rhrBaselineBpm            // ms
pNN50   = 200·(1 − Φ(50 / rmssd))           // %, standard-normal CDF via erf approximation
raw     = −13.05 + 0.05·meanRR + 0.05·pNN50 // Materko 2018, fold #1
result  = raw.coerceIn(15f, 95f)
```

**Companion constants** (all named, with REF comments):
- `INTERCEPT = −13.05f`, `MEAN_RR_COEFF = 0.05f`, `PNN50_COEFF = 0.05f` — REF: Materko 2018, *Open Acc Biostat Bioinform* 2(3). OABB.000536, Table 4 fold #1 (R²=0.76, SEE=4.40 ml/kg/min).
- `PNN50_THRESHOLD_MS = 50f`
- `MIN_PLAUSIBLE_HR_MAX = 90f`, `MIN_PLAUSIBLE_RHR = 30f`
- `PHYSIOLOGICAL_MIN_VO2 = 15f`, `PHYSIOLOGICAL_MAX_VO2 = 95f`

**Documented deviations from the original** (recorded in the file's KDoc and in About copy):
1. CDR term omitted — Health Connect cannot supply the raw tachogram required to compute it.
2. pNN50 derived from the RMSSD baseline via a normality assumption on successive NN differences, rather than counted from a tachogram.

Standard-normal CDF Φ is implemented via the Abramowitz–Stegun 7.1.26 erf approximation in a small pure-Kotlin helper (kept local to the calculator; do not add a math dependency).

---

## 4. Preferences, Settings & UI

### 4.1 Domain enum
New file `core/model/.../domain/preferences/Vo2MaxEstimationMethod.kt` (mirrors `Vo2MaxSourceMode.kt`):
```kotlin
enum class Vo2MaxEstimationMethod { HR_RATIO, HRV_MATERKO }
```

### 4.2 UserPreferences & persistence
- `UserPreferences.vo2MaxEstimationMethod: Vo2MaxEstimationMethod = Vo2MaxEstimationMethod.HR_RATIO`.
- Proto field `vo2MaxEstimationMethod` (string) in `UserPreferencesSerializer` + `UserPreferencesSerializerExtensions` + `UserPreferencesMapperExtensions` (to/from domain enum).
- Backup round-trip: `BackupModels.UserPreferencesBackup` + `BackupPreferencesBuilder.buildScoringAndDevices` + `RestorePreferencesExtensions` + `RestorePreferencesApplier` (same shape as `vo2MaxSourceMode`).

### 4.3 Settings repository & events
- `PhysiologySettings.updateVo2MaxEstimationMethod(method)` (interface + `SettingsRepository` impl).
- New `SettingsEvent.Vo2MaxEstimationMethodChanged(method: Vo2MaxEstimationMethod)`.
- `PhysiologySettingsViewModel.onEvent`: update pref, then **`healthDataRefresh.refreshHistorical()`** — VO2 Max is historical-scope, same trigger as gender/profile/RAS-factor changes. (This is the "recalc" the user accepted; no separate pending-recalc flag/button needed.)

### 4.4 UI
In `PhysiologyProfileCategoryScreen`, a second `SingleChoiceSegmentedButtonRow` immediately below the existing `Vo2MaxSourcePicker`:
- `HR_RATIO` → "Heart rate ratio (HRmax/RHR)"
- `HRV_MATERKO` → "HRV-based (Materko)"

New strings (all in `values/strings.xml`): `vo2_max_method_title`, `vo2_max_method_hr_ratio`, `vo2_max_method_hrv`, `vo2_max_method_description`. Plus About/tooltip copy per §7.

`PhysiologySettingsState` gains `vo2MaxEstimationMethod`; `PhysiologyProfileCategoryScreen` passes it to the new picker.

---

## 5. Scoring Integration

### 5.1 `FinalSummaryAssembler.resolveVo2Max`
Branch on `inputs.context.prefs.vo2MaxEstimationMethod`:
- `HR_RATIO` → `uthVo2MaxCalculator.estimate(hrMax, rhrBaselineBpm, isCalibrated)`, source tag `"ESTIMATED_UTH"` (existing behavior unchanged).
- `HRV_MATERKO` → `hrvMaterkoVo2MaxCalculator.estimate(hrMax, rhrBaselineBpm, hrvMuMssd, isCalibrated)`, source tag `"ESTIMATED_MATERKO"`.

**HRV input `hrvMuMssd`:** the day's scored ln-RMSSD baseline from the assembled summary (`withFatigue.hrvMuMssd`) — the frozen per-day baseline when a snapshot exists, otherwise the freshly-computed baseline for that day. `exp(hrvMuMssd)` is the RMSSD baseline. This satisfies the requirement to use the stable daily baseline rather than the current night's raw mean. When it is null (calibrating / no HRV), the HRV method degrades to null → no estimated VO2 Max, consistent with Uth's calibrating behavior.

`FinalSummaryAssembler` constructor gains `hrvMaterkoVo2MaxCalculator` (wired in `ScoringDayUseCases`).

### 5.2 `Vo2MaxSourceResolver`
Signature change:
```kotlin
fun resolve(
    mode: Vo2MaxSourceMode,
    wearableVo2Max: Float?,
    estimatedVo2Max: Float?,
    estimatedSource: String?,   // "ESTIMATED_UTH" | "ESTIMATED_MATERKO"
): Vo2MaxResolution
```
The estimated branches (`AUTO` fallback, `ESTIMATED_ONLY`) emit `estimatedSource` instead of the hard-coded `"ESTIMATED_UTH"`. Single production caller is `FinalSummaryAssembler`; test callers updated.

**No DB migration:** existing rows keep their stored `vo2MaxSource` values.

---

## 6. Testing

| Test | Coverage |
|---|---|
| `HrvMaterkoVo2MaxCalculatorTest` (pure Kotlin) | null guards (calibrating, low hrMax, low rhr, null hrvMu, rmssd out of 1–200); clamp floor/ceiling; known-value check (rhr=60→meanRR=1000; rmssd=50→pNN50≈31.73%); monotonicity of pNN50 vs rmssd |
| `Vo2MaxSourceResolverTest` | `estimatedSource` emitted for AUTO fallback + ESTIMATED_ONLY; HRV tag flows through |
| `FinalSummaryAssembler` / `ScoringRepositoryImplTest` | HRV method selects Materko; AUTO fallback uses chosen method; source tag persisted; HRV method null when hrvMu null |
| Settings test mirroring `PhysiologyProfileVo2MaxSourceTest` | method picker renders + emits `Vo2MaxEstimationMethodChanged` |
| Backup round-trip (`RestorePreferenceEnumRoundTripTest` pattern) | `vo2MaxEstimationMethod` survives backup/restore |

---

## 7. Documentation Synchronization (Mandatory)

Per the repo's Documentation Synchronization Rule, the same change must update:
- `internal-docs/DATA_FLOW.md` — scoring path: new estimator, `Vo2MaxSourceResolver` signature, `resolveVo2Max` branch.
- `ABOUT.md`, `docs/about.md` — VO2 Max estimation-method explanation (both methods, deviations caveat).
- In-app About strings (`about_*` / `tooltip_*` in `values/strings.xml`) — same copy.
- `DocumentationDriftTest` must pass.

No `docs/index.md`/`docs/privacy.md` change: no new data collection or sharing.

---

## 8. Out of Scope

- Exercise/submaximal VO2 Max methods (need workout data; not resting-only).
- Raw tachogram capture (Health Connect exposes RMSSD only).
- Changes to readiness, sleep, or load scoring formulas.
- Changes to the source-mode picker (`AUTO`/`WEARABLE_ONLY`/`ESTIMATED_ONLY`).
- More than one new estimation method (future methods should revisit the interface/factory shape in §9).

---

## 9. Notes for Future Work

Approach chosen here is **minimal branch** (two standalone calculators + pref branch). If a third estimation method is ever added, refactor to a `Vo2MaxEstimator` interface + DI factory at that point; the blast radius today does not justify it.