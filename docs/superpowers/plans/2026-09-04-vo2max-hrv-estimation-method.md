# Materko-Adapted (Resting HR + HRV) VO2 Max Estimation Method — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second, user-selectable VO2 Max estimation method — an experimental Materko-adapted resting HR + HRV estimator — alongside the existing Uth heart-rate-ratio method, with settings UI, persistence, historical recompute, and docs sync.

**Architecture:** A new pure-Kotlin `MaterkoAdaptedVo2MaxCalculator` computes the estimate from the day's stable RHR baseline + ln-RMSSD HRV baseline. A new `Vo2MaxEstimationMethod` preference (`HR_RATIO` | `MATERKO_ADAPTED`) selects the estimator inside `FinalSummaryAssembler.resolveVo2Max`; `Vo2MaxSourceResolver` gains an `estimatedSource` param so the persisted `vo2MaxSource` tag distinguishes `ESTIMATED_UTH` from `ESTIMATED_MATERKO_ADAPTED`. Changing the method triggers `healthDataRefresh.refreshHistorical()` (one call). Spec: `docs/superpowers/specs/2026-09-04-vo2max-hrv-estimation-method-design.md`.

**Tech Stack:** Kotlin, Compose M3, Room, DataStore (protobuf prefs), Hilt, JUnit4 + Mockk, Vico (unchanged).

---

## File Map

**Create:**
- `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/preferences/Vo2MaxEstimationMethod.kt` — the enum.
- `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/MaterkoAdaptedVo2MaxCalculator.kt` — the estimator.
- `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/MaterkoAdaptedVo2MaxCalculatorTest.kt` — estimator tests.

**Modify:**
- `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/UserPreferences.kt` — new field (default `HR_RATIO`).
- `app/src/main/proto/user_preferences.proto` — enum + message field 99.
- `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapper.kt` — `toDomainMethod()`.
- `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapperExtensions.kt` — read field.
- `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesSerializerExtensions.kt` — write field.
- `app/src/main/kotlin/app/readylytics/health/data/preferences/PhysiologyPreferences.kt` — `updateVo2MaxEstimationMethod`.
- `app/src/main/kotlin/app/readylytics/health/data/preferences/SettingsRepository.kt` — override.
- `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/preferences/FeatureSettingsPorts.kt` — interface method.
- `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolver.kt` — signature + source constants.
- `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolverTest.kt` — updated tests.
- `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/FinalSummaryAssembler.kt` — estimator branch.
- `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayUseCases.kt` — field.
- `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt` — wiring.
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsEvent.kt` — event.
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsState.kt` — state field.
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/PhysiologySettingsViewModel.kt` — event handling.
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/category/PhysiologyProfileCategoryScreen.kt` — picker.
- `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/search/SettingsItemIds.kt` — new item id.
- `feature/settings/src/main/res/values/strings.xml` — 4 strings.
- `feature/settings/src/test/kotlin/app/readylytics/health/feature/settings/PhysiologySettingsViewModelTest.kt` — 2 tests.
- `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt` — backup field.
- `app/src/main/kotlin/app/readylytics/health/data/backup/BackupPreferencesBuilder.kt` — build.
- `app/src/main/kotlin/app/readylytics/health/data/backup/RestorePreferencesExtensions.kt` — restore.
- `app/src/test/kotlin/app/readylytics/health/data/backup/RestorePreferenceEnumRoundTripTest.kt` — round-trip test.
- `core/ui/src/main/res/values/strings.xml` — source label string.
- `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessDetailScreen.kt` — label mapping.
- `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardCardioMetricPresentationFactory.kt` — label mapping.
- `internal-docs/DATA_FLOW.md`, `ABOUT.md`, `docs/about.md`, `app/src/main/res/values/strings.xml` — docs.

---

### Task 1: Domain enum + UserPreferences field

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/preferences/Vo2MaxEstimationMethod.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/UserPreferences.kt:122`

- [ ] **Step 1: Create the enum**

```kotlin
package app.readylytics.health.core.model.domain.preferences

/**
 * Which resting estimator computes the "estimated" VO2 Max when
 * [Vo2MaxSourceMode] resolves to an estimate (ESTIMATED_ONLY or AUTO fallback).
 *
 * [HR_RATIO] is the Uth et al. (2004) heart-rate-ratio method. [MATERKO_ADAPTED]
 * is the experimental Materko-adapted resting HR + HRV estimator; see
 * `MaterkoAdaptedVo2MaxCalculator` for the documented deviations from the
 * published model.
 */
enum class Vo2MaxEstimationMethod {
    HR_RATIO,
    MATERKO_ADAPTED,
}
```

- [ ] **Step 2: Add the field to UserPreferences**

In `UserPreferences.kt`, directly after line 122 (`val vo2MaxSourceMode: Vo2MaxSourceMode = Vo2MaxSourceMode.AUTO,`), add:

```kotlin
    val vo2MaxEstimationMethod: Vo2MaxEstimationMethod = Vo2MaxEstimationMethod.HR_RATIO,
```

Add the import at the top of the file (the enum lives in `domain.preferences`, `UserPreferences` lives in `data.preferences`):

```kotlin
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
```

- [ ] **Step 3: Compile**

Run: `./gradlew :core:model:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/core/model/domain/preferences/Vo2MaxEstimationMethod.kt core/model/src/main/kotlin/app/readylytics/health/core/model/data/preferences/UserPreferences.kt
git commit -m "feat(core-model): add Vo2MaxEstimationMethod preference enum"
```

---

### Task 2: Protobuf field + mapper + serializer

**Files:**
- Modify: `app/src/main/proto/user_preferences.proto`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapper.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapperExtensions.kt:179`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesSerializerExtensions.kt:147`

- [ ] **Step 1: Add the proto enum + field**

In `user_preferences.proto`, directly after the `Vo2MaxSourceModeProto` enum (lines 57–62), add:

```proto
enum Vo2MaxEstimationMethodProto {
    VO2_MAX_METHOD_UNSET = 0;
    VO2_MAX_METHOD_HR_RATIO = 1;
    VO2_MAX_METHOD_MATERKO_ADAPTED = 2;
}
```

In `message UserPreferencesProto`, after the `vo2_max_source_mode = 98;` field (line 306), add:

```proto

    // Which resting estimator computes DailySummary.vo2Max when the source resolves to an estimate.
    // VO2_MAX_METHOD_UNSET (proto3 zero value) = never explicitly set; resolves to
    // Vo2MaxEstimationMethod.HR_RATIO at the domain read boundary.
    Vo2MaxEstimationMethodProto vo2_max_estimation_method = 99;
```

- [ ] **Step 2: Regenerate proto classes**

Run: `./gradlew :app:generateDebugProto`
(If that task name is not found, run `./gradlew :app:compileDebugKotlin` instead — protobuf codegen runs as part of the build.)

- [ ] **Step 3: Add the domain-mapping function**

In `UserPreferencesMapper.kt`, after `toDomainMode()` (lines 34–39), add:

```kotlin
fun Vo2MaxEstimationMethodProto.toDomainMethod(): Vo2MaxEstimationMethod =
    when (this) {
        Vo2MaxEstimationMethodProto.VO2_MAX_METHOD_MATERKO_ADAPTED -> Vo2MaxEstimationMethod.MATERKO_ADAPTED
        else -> Vo2MaxEstimationMethod.HR_RATIO
    }
```

Add the import:

```kotlin
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
```

- [ ] **Step 4: Read the field in the domain mapper**

In `UserPreferencesMapperExtensions.kt`, directly after line 179 (`vo2MaxSourceMode = proto.vo2MaxSourceMode.toDomainMode(),`), add:

```kotlin
        vo2MaxEstimationMethod = proto.vo2MaxEstimationMethod.toDomainMethod(),
```

- [ ] **Step 5: Write the field in the serializer**

In `UserPreferencesSerializerExtensions.kt`, directly after line 147 (`setVo2MaxSourceMode(mapVo2MaxSourceMode(domain.vo2MaxSourceMode))`), add:

```kotlin
        setVo2MaxEstimationMethod(mapVo2MaxEstimationMethod(domain.vo2MaxEstimationMethod))
```

And after the `mapVo2MaxSourceMode` helper (lines 169–174), add:

```kotlin
private fun mapVo2MaxEstimationMethod(method: Vo2MaxEstimationMethod): Vo2MaxEstimationMethodProto =
    when (method) {
        Vo2MaxEstimationMethod.HR_RATIO -> Vo2MaxEstimationMethodProto.VO2_MAX_METHOD_HR_RATIO
        Vo2MaxEstimationMethod.MATERKO_ADAPTED -> Vo2MaxEstimationMethodProto.VO2_MAX_METHOD_MATERKO_ADAPTED
    }
```

Add the import at the top:

```kotlin
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
```

- [ ] **Step 6: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/proto/user_preferences.proto app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapper.kt app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesMapperExtensions.kt app/src/main/kotlin/app/readylytics/health/data/preferences/UserPreferencesSerializerExtensions.kt
git commit -m "feat(prefs): persist vo2MaxEstimationMethod in protobuf preferences"
```

---

### Task 3: Preferences repository chain

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/core/model/domain/preferences/FeatureSettingsPorts.kt:28`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/PhysiologyPreferences.kt:184`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/SettingsRepository.kt:167`

- [ ] **Step 1: Add the port method**

In `FeatureSettingsPorts.kt`, inside `interface PhysiologySettings` after line 28 (`suspend fun updateVo2MaxSourceMode(mode: Vo2MaxSourceMode)`), add:

```kotlin
    suspend fun updateVo2MaxEstimationMethod(method: Vo2MaxEstimationMethod)
```

No import needed (same package `domain.preferences`).

- [ ] **Step 2: Implement in PhysiologyPreferences**

In `PhysiologyPreferences.kt`, directly after `updateVo2MaxSourceMode` (ends line 196), add:

```kotlin
        suspend fun updateVo2MaxEstimationMethod(method: Vo2MaxEstimationMethod) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setVo2MaxEstimationMethod(
                        when (method) {
                            Vo2MaxEstimationMethod.HR_RATIO -> Vo2MaxEstimationMethodProto.VO2_MAX_METHOD_HR_RATIO
                            Vo2MaxEstimationMethod.MATERKO_ADAPTED ->
                                Vo2MaxEstimationMethodProto.VO2_MAX_METHOD_MATERKO_ADAPTED
                        },
                    ).build()
            }
        }
```

Add the import at the top:

```kotlin
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
```

- [ ] **Step 3: Override in SettingsRepository**

In `SettingsRepository.kt`, directly after line 167 (`override suspend fun updateVo2MaxSourceMode(mode: Vo2MaxSourceMode) = physiology.updateVo2MaxSourceMode(mode)`), add:

```kotlin
        override suspend fun updateVo2MaxEstimationMethod(method: Vo2MaxEstimationMethod) =
            physiology.updateVo2MaxEstimationMethod(method)
```

Add the import at the top:

```kotlin
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/core/model/domain/preferences/FeatureSettingsPorts.kt app/src/main/kotlin/app/readylytics/health/data/preferences/PhysiologyPreferences.kt app/src/main/kotlin/app/readylytics/health/data/preferences/SettingsRepository.kt
git commit -m "feat(prefs): expose updateVo2MaxEstimationMethod repository chain"
```

---

### Task 4: Materko-adapted estimator (TDD)

**Files:**
- Create: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/MaterkoAdaptedVo2MaxCalculator.kt`
- Create: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/MaterkoAdaptedVo2MaxCalculatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterkoAdaptedVo2MaxCalculatorTest {
    private val calculator = MaterkoAdaptedVo2MaxCalculator()

    private fun hrvMu(rmssdMs: Float): Float = ln(rmssdMs.toDouble()).toFloat()

    @Test
    fun `returns null when not calibrated`() {
        assertNull(calculator.estimate(60f, hrvMu(50f), isCalibrated = false))
    }

    @Test
    fun `returns null for implausible rhr baseline`() {
        assertNull(calculator.estimate(20f, hrvMu(50f), true))
        assertNull(calculator.estimate(Float.NaN, hrvMu(50f), true))
    }

    @Test
    fun `returns null when hrv baseline missing`() {
        assertNull(calculator.estimate(60f, null, true))
    }

    @Test
    fun `returns null when rmssd outside health connect validation range`() {
        assertNull(calculator.estimate(60f, ln(0.5).toFloat(), true))
        assertNull(calculator.estimate(60f, ln(250.0).toFloat(), true))
    }

    @Test
    fun `returns null for out of supported domain instead of clamping`() {
        // rhr 200 -> meanRR 300 -> raw ~= -13.05 + 15 + 1.6, below MIN_SUPPORTED_VO2_MAX.
        assertNull(calculator.estimate(200f, hrvMu(50f), true))
    }

    @Test
    fun `computes expected value for representative inputs without any hrMax`() {
        // rhr 60 -> meanRR 1000; rmssd 50 -> approxPnn50 = 200*(1-Phi(1)) ~= 31.7311
        // raw = -13.05 + 50 + 1.5866 = 38.5366
        val result = calculator.estimate(60f, hrvMu(50f), true)
        assertEquals(38.54f, result!!, 0.01f)
    }

    @Test
    fun `approxPnn50 approaches zero for very low rmssd`() {
        assertTrue(calculator.approxPnn50(0.001f) < 1f)
    }

    @Test
    fun `approxPnn50 is monotonic increasing with rmssd`() {
        assertTrue(calculator.approxPnn50(20f) < calculator.approxPnn50(50f))
        assertTrue(calculator.approxPnn50(50f) < calculator.approxPnn50(100f))
    }

    @Test
    fun `approxPnn50 stays within zero and one hundred`() {
        listOf(5f, 20f, 50f, 100f, 200f).forEach { rmssd ->
            val p = calculator.approxPnn50(rmssd)
            assertTrue("pnn50=$p for rmssd=$rmssd", p in 0f..100f)
        }
    }

    @Test
    fun `standard normal cdf matches reference values`() {
        val tol = 1e-4
        assertEquals(0.5, calculator.standardNormalCdf(0.0), tol)
        assertEquals(0.84134, calculator.standardNormalCdf(1.0), tol)
        assertEquals(0.15866, calculator.standardNormalCdf(-1.0), tol)
        assertEquals(0.97500, calculator.standardNormalCdf(1.96), tol)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests "*MaterkoAdaptedVo2MaxCalculatorTest*"`
Expected: FAIL — `MaterkoAdaptedVo2MaxCalculator` unresolved (class does not exist).

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import javax.inject.Inject
import kotlin.math.exp

/**
 * Experimental Readylytics adaptation of the Materko (2018) resting-HRV VO2max regression
 * (Open Acc Biostat Bioinform 2(3). OABB.000536, fold #1).
 *
 * Published model: VO2max = -13.05 + 0.05*MeanRR + 0.12*CDR + 0.05*pNN50. This adaptation is NOT
 * the published model, and the published R2=0.76 / SEE=4.40 ml/kg/min do NOT apply. Deviations:
 *  1. CDR is omitted — it needs a raw beat-to-beat tachogram, which Health Connect does not expose
 *     (RMSSD only). No synthetic CDR proxy is added: CDR carries distributional/asymmetry
 *     information RMSSD cannot preserve.
 *  2. pNN50 is approximated from the RMSSD baseline as 200*(1 - Phi(50/rmssd)) under the assumption
 *     that successive NN differences are Normal(0, RMSSD^2); this is not measured pNN50 and the
 *     normality assumption is not guaranteed physiologically.
 *  3. MeanRR is derived as 60000 / rhrBaselineBpm from Readylytics' stable sleep/resting-HR baseline,
 *     which may be percentile-derived rather than a true arithmetic mean resting HR from a
 *     contemporaneous tachogram (possible systematic offset).
 * The original model was developed in young, healthy, physically active men and is not broadly
 * validated. Out-of-domain results return null per application-level supported bounds.
 */
class MaterkoAdaptedVo2MaxCalculator @Inject constructor() {

    fun estimate(
        rhrBaselineBpm: Float,
        hrvMuMssd: Float?,
        isCalibrated: Boolean,
    ): Float? {
        if (!isCalibrated) return null
        if (!rhrBaselineBpm.isFinite() || rhrBaselineBpm < MIN_PLAUSIBLE_RHR) return null
        if (hrvMuMssd == null || !hrvMuMssd.isFinite()) return null
        val rmssd = exp(hrvMuMssd)
        if (!rmssd.isFinite() || rmssd < MIN_RMSSD_MS || rmssd > MAX_RMSSD_MS) return null
        val meanRR = MEAN_RR_CONSTANT / rhrBaselineBpm
        val approxPnn50 = approxPnn50(rmssd)
        val raw = INTERCEPT + MEAN_RR_COEFF * meanRR + PNN50_COEFF * approxPnn50
        return raw.takeIf { it in MIN_SUPPORTED_VO2_MAX..MAX_SUPPORTED_VO2_MAX }?.toFloat()
    }

    internal fun approxPnn50(rmssd: Float): Float {
        val z = PNN50_THRESHOLD_MS / rmssd
        return (200.0 * (1.0 - standardNormalCdf(z))).toFloat()
    }

    internal fun standardNormalCdf(x: Double): Double = 0.5 * (1.0 + erf(x / SQRT_2))

    private fun erf(x: Double): Double {
        if (x == 0.0) return 0.0
        if (x < 0.0) return -erf(-x)
        val t = 1.0 / (1.0 + ERF_P * x)
        val poly = t * (ERF_A1 + t * (ERF_A2 + t * (ERF_A3 + t * (ERF_A4 + t * ERF_A5)))
        return 1.0 - poly * exp(-x * x)
    }

    companion object {
        // REF: Materko 2018, OABB.000536, fold #1 (original full-model context only).
        private const val INTERCEPT = -13.05
        private const val MEAN_RR_COEFF = 0.05
        private const val PNN50_COEFF = 0.05
        private const val MEAN_RR_CONSTANT = 60_000.0
        private const val PNN50_THRESHOLD_MS = 50.0
        private const val MIN_PLAUSIBLE_RHR = 30.0
        private const val MIN_RMSSD_MS = 1.0
        private const val MAX_RMSSD_MS = 200.0
        // Application-level supported/plausibility bounds (not physiological limits).
        private const val MIN_SUPPORTED_VO2_MAX = 15.0
        private const val MAX_SUPPORTED_VO2_MAX = 95.0
        // Abramowitz–Stegun 7.1.26 erf approximation coefficients.
        private const val ERF_P = 0.3275911
        private const val ERF_A1 = 0.254829592
        private const val ERF_A2 = -0.284496736
        private const val ERF_A3 = 1.421413741
        private const val ERF_A4 = -1.453152027
        private const val ERF_A5 = 1.061405429
        private const val SQRT_2 = 1.4142135623730951
    }
}
```

Note: `raw.takeIf { it in MIN_SUPPORTED_VO2_MAX..MAX_SUPPORTED_VO2_MAX }` uses a `Double` range; `it` is `Double` here because `raw` is a `Double`. This compiles.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests "*MaterkoAdaptedVo2MaxCalculatorTest*"`
Expected: PASS (all 10 tests green).

- [ ] **Step 5: Commit**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/MaterkoAdaptedVo2MaxCalculator.kt core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/MaterkoAdaptedVo2MaxCalculatorTest.kt
git commit -m "feat(core-scoring): add Materko-adapted resting HR + HRV VO2max estimator"
```

---

### Task 5: Vo2MaxSourceResolver signature + source tags

**Files:**
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolver.kt`
- Modify: `core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolverTest.kt`

- [ ] **Step 1: Update the resolver**

Replace the entire contents of `Vo2MaxSourceResolver.kt` with:

```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import javax.inject.Inject
import javax.inject.Singleton

data class Vo2MaxResolution(val vo2Max: Float?, val source: String?)

@Singleton
class Vo2MaxSourceResolver @Inject constructor() {
    fun resolve(
        mode: Vo2MaxSourceMode,
        wearableVo2Max: Float?,
        estimatedVo2Max: Float?,
        estimatedSource: String?,
    ): Vo2MaxResolution =
        when (mode) {
            Vo2MaxSourceMode.AUTO ->
                if (wearableVo2Max != null) {
                    Vo2MaxResolution(wearableVo2Max, SOURCE_WEARABLE)
                } else if (estimatedVo2Max != null) {
                    Vo2MaxResolution(estimatedVo2Max, estimatedSource)
                } else {
                    Vo2MaxResolution(null, null)
                }
            Vo2MaxSourceMode.WEARABLE_ONLY ->
                if (wearableVo2Max != null) {
                    Vo2MaxResolution(wearableVo2Max, SOURCE_WEARABLE)
                } else {
                    Vo2MaxResolution(null, null)
                }
            Vo2MaxSourceMode.ESTIMATED_ONLY ->
                if (estimatedVo2Max != null) {
                    Vo2MaxResolution(estimatedVo2Max, estimatedSource)
                } else {
                    Vo2MaxResolution(null, null)
                }
        }

    companion object {
        const val SOURCE_WEARABLE = "WEARABLE"
        const val SOURCE_ESTIMATED_UTH = "ESTIMATED_UTH"
        const val SOURCE_ESTIMATED_MATERKO_ADAPTED = "ESTIMATED_MATERKO_ADAPTED"
    }
}
```

- [ ] **Step 2: Update the resolver test**

Replace the test file contents with (note `estimatedVo2Max` / `estimatedSource` args and the distinct tag assertions):

```kotlin
package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Vo2MaxSourceResolverTest {
    private val resolver = Vo2MaxSourceResolver()

    @Test
    fun autoPrefersWearableOverEstimate() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = 48.0f,
            estimatedVo2Max = 45.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH,
        )
        assertEquals(48.0f, result.vo2Max)
        assertEquals("WEARABLE", result.source)
    }

    @Test
    fun autoFallsBackToUthEstimateWhenWearableNull() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = null,
            estimatedVo2Max = 45.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH,
        )
        assertEquals(45.0f, result.vo2Max)
        assertEquals("ESTIMATED_UTH", result.source)
    }

    @Test
    fun autoFallsBackToMaterkoAdaptedEstimateWhenWearableNull() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.AUTO,
            wearableVo2Max = null,
            estimatedVo2Max = 39.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_MATERKO_ADAPTED,
        )
        assertEquals(39.0f, result.vo2Max)
        assertEquals("ESTIMATED_MATERKO_ADAPTED", result.source)
    }

    @Test
    fun wearableOnlyIgnoresEstimate() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.WEARABLE_ONLY,
            wearableVo2Max = null,
            estimatedVo2Max = 45.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH,
        )
        assertNull(result.vo2Max)
        assertNull(result.source)
    }

    @Test
    fun wearableOnlyUsesWearableWhenAvailable() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.WEARABLE_ONLY,
            wearableVo2Max = 48.0f,
            estimatedVo2Max = 45.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH,
        )
        assertEquals(48.0f, result.vo2Max)
        assertEquals("WEARABLE", result.source)
    }

    @Test
    fun estimatedOnlyEmitsEstimatedSourceTag() {
        val result = resolver.resolve(
            mode = Vo2MaxSourceMode.ESTIMATED_ONLY,
            wearableVo2Max = 48.0f,
            estimatedVo2Max = 39.0f,
            estimatedSource = Vo2MaxSourceResolver.SOURCE_ESTIMATED_MATERKO_ADAPTED,
        )
        assertEquals(39.0f, result.vo2Max)
        assertEquals("ESTIMATED_MATERKO_ADAPTED", result.source)
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests "*Vo2MaxSourceResolverTest*"`
Expected: PASS (6 tests).

- [ ] **Step 4: Commit**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolver.kt core/scoring/src/test/kotlin/app/readylytics/health/core/scoring/domain/cardio/Vo2MaxSourceResolverTest.kt
git commit -m "feat(core-scoring): parameterize estimated-source tag in VO2max resolver"
```

---

### Task 6: Scoring integration

**Files:**
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayUseCases.kt:31`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt:66`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/FinalSummaryAssembler.kt`
- Modify: `core/database/src/test/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImplTest.kt`

- [ ] **Step 1: Add the calculator to ScoringDayUseCases**

In `ScoringDayUseCases.kt`, after `val uthVo2MaxCalculator: UthVo2MaxCalculator,` (line 30), add:

```kotlin
        val materkoAdaptedVo2MaxCalculator: MaterkoAdaptedVo2MaxCalculator = MaterkoAdaptedVo2MaxCalculator(),
```

Add the import:

```kotlin
import app.readylytics.health.core.scoring.domain.cardio.MaterkoAdaptedVo2MaxCalculator
```

(The default instance is safe: the calculator is stateless, so the 13 existing test fixtures and the benchmark that construct `ScoringDayUseCases(...)` compile unchanged.)

- [ ] **Step 2: Wire into ScoringRepositoryImpl**

In `ScoringRepositoryImpl.kt`, inside the `FinalSummaryAssembler(...)` constructor call, after `useCases.uthVo2MaxCalculator,` (line 74), add:

```kotlin
                useCases.materkoAdaptedVo2MaxCalculator,
```

- [ ] **Step 3: Update FinalSummaryAssembler**

Add the constructor parameter after `private val uthVo2MaxCalculator: UthVo2MaxCalculator,` (line 31):

```kotlin
    private val materkoAdaptedVo2MaxCalculator: MaterkoAdaptedVo2MaxCalculator,
```

Add the import:

```kotlin
import app.readylytics.health.core.scoring.domain.cardio.MaterkoAdaptedVo2MaxCalculator
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
```

Replace the `resolveVo2Max` method (currently lines 118–135) and its call site with:

Call site (currently line 77):

```kotlin
        val vo2MaxResolution = resolveVo2Max(inputs, isCalibrated, withFatigue.hrvMuMssd)
```

Method body:

```kotlin
    private suspend fun resolveVo2Max(
        inputs: Inputs,
        isCalibrated: Boolean,
        hrvMuMssd: Float?,
    ): Vo2MaxResolution {
        val prefs = inputs.context.prefs
        val (estimate, source) =
            when (prefs.vo2MaxEstimationMethod) {
                Vo2MaxEstimationMethod.HR_RATIO ->
                    uthVo2MaxCalculator.estimate(
                        hrMax = inputs.context.initialBaselines.hrMax,
                        rhrBaselineBpm = inputs.context.initialBaselines.rhrBaselineValue,
                        isCalibrating = !isCalibrated,
                    ) to Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH
                // Calibration semantics: `isCalibrated` is passed DIRECTLY here (guard inside the
                // calculator is `if (!isCalibrated) return null`). The Uth calculator above keeps its
                // existing `isCalibrating` contract, so its call site inverts (`!isCalibrated`). Do
                // not "unify" the two call sites — that is what would introduce an inversion bug.
                Vo2MaxEstimationMethod.MATERKO_ADAPTED ->
                    materkoAdaptedVo2MaxCalculator.estimate(
                        rhrBaselineBpm = inputs.context.initialBaselines.rhrBaselineValue,
                        hrvMuMssd = hrvMuMssd,
                        isCalibrated = isCalibrated,
                    ) to Vo2MaxSourceResolver.SOURCE_ESTIMATED_MATERKO_ADAPTED
            }
        val thirtyDaysMs = TimeUnit.DAYS.toMillis(30)
        val wearableLookbackMs = inputs.context.nextDayMidnightMs - thirtyDaysMs
        val wearableVo2Max = bodyMetricsDataLoader
            .loadLatestVo2Max(inputs.context.nextDayMidnightMs, wearableLookbackMs)
            ?.vo2Max
        return vo2MaxSourceResolver.resolve(
            mode = prefs.vo2MaxSourceMode,
            wearableVo2Max = wearableVo2Max,
            estimatedVo2Max = estimate,
            estimatedSource = source,
        )
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew :core:database:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Add integration test to ScoringRepositoryImplTest**

In `ScoringRepositoryImplTest.kt`, add a test that asserts the MATERKO_ADAPTED method selects the new estimator and persists the new tag, and that HR_RATIO still produces `ESTIMATED_UTH`. Use the same fixture style already present in that file (repo built with a seeded `daily_summaries` row carrying `hrvMuMssd` + a frozen `rhrBpm`). Add:

```kotlin
    @Test
    fun `materkoAdapted method computes estimate from hrv baseline and persists tag`() =
        runTest {
            val zoneId = ZoneId.of("UTC")
            val today = LocalDate.of(2026, 9, 1)
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            seedSummary(
                DailySummaryEntity(
                    todayMs,
                    baselineCalculatedAtDate = today.minusDays(1),
                    hrvMuMssd = ln(50.0).toFloat(),
                    rhrBpm = 60f,
                ),
            )
            withPreferences(
                prefs.copy(
                    vo2MaxEstimationMethod = Vo2MaxEstimationMethod.MATERKO_ADAPTED,
                    vo2MaxSourceMode = Vo2MaxSourceMode.ESTIMATED_ONLY,
                ),
            )
            val summary = repository.computeDailySummary(today)
            assertEquals(Vo2MaxSourceResolver.SOURCE_ESTIMATED_MATERKO_ADAPTED, summary.vo2MaxSource)
            assertEquals(38.54f, summary.vo2Max!!, 0.01f)
        }

    @Test
    fun `hrRatio method still emits uth tag`() =
        runTest {
            val zoneId = ZoneId.of("UTC")
            val today = LocalDate.of(2026, 9, 1)
            val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            seedSummary(
                DailySummaryEntity(
                    todayMs,
                    baselineCalculatedAtDate = today.minusDays(1),
                    hrvMuMssd = ln(50.0).toFloat(),
                    rhrBpm = 60f,
                    hrMax = 190f,
                ),
            )
            withPreferences(
                prefs.copy(
                    vo2MaxEstimationMethod = Vo2MaxEstimationMethod.HR_RATIO,
                    vo2MaxSourceMode = Vo2MaxSourceMode.ESTIMATED_ONLY,
                ),
            )
            val summary = repository.computeDailySummary(today)
            assertEquals(Vo2MaxSourceResolver.SOURCE_ESTIMATED_UTH, summary.vo2MaxSource)
        }
```

Check the existing `ScoringRepositoryImplTest.kt` helpers (`seedSummary`, `withPreferences`, `prefs`, `repository`, imports for `ln`) and reuse their exact signatures — adjust the snippets above to match the file's actual helper names and constructor shape. If the file already imports `kotlin.math.ln`, keep it; otherwise add `import kotlin.math.ln`.

- [ ] **Step 6: Run integration tests**

Run: `./gradlew :core:database:testDebugUnitTest --tests "*ScoringRepositoryImplTest*"`
Expected: PASS.

- [ ] **Step 7: Run the full core-database scoring test suite (detekt/boyscout + regression guard)**

Run: `./gradlew :core:database:testDebugUnitTest`
Expected: PASS (this exercises the 12 fixtures that construct `ScoringDayUseCases`; the new defaulted field must not break them).

- [ ] **Step 8: Commit**

```bash
git add core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringDayUseCases.kt core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImpl.kt core/database/src/main/kotlin/app/readylytics/health/core/database/data/repository/FinalSummaryAssembler.kt core/database/src/test/kotlin/app/readylytics/health/core/database/data/repository/ScoringRepositoryImplTest.kt
git commit -m "feat(core-database): branch VO2max estimation on Vo2MaxEstimationMethod"
```

---

### Task 7: Settings — event, state, view model, UI, strings

**Files:**
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsEvent.kt:231`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsState.kt:60`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/PhysiologySettingsViewModel.kt`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/category/PhysiologyProfileCategoryScreen.kt`
- Modify: `feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/search/SettingsItemIds.kt`
- Modify: `feature/settings/src/main/res/values/strings.xml`
- Modify: `feature/settings/src/test/kotlin/app/readylytics/health/feature/settings/PhysiologySettingsViewModelTest.kt`

- [ ] **Step 1: Add the event**

In `SettingsEvent.kt`, after `Vo2MaxSourceModeChanged` (lines 231–233), add:

```kotlin
    data class Vo2MaxEstimationMethodChanged(
        val method: Vo2MaxEstimationMethod,
    ) : SettingsEvent
```

Add the import:

```kotlin
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
```

- [ ] **Step 2: Add the state field**

In `SettingsState.kt`, inside `PhysiologySettingsState` after `vo2MaxSourceMode` (line 60), add:

```kotlin
    val vo2MaxEstimationMethod: Vo2MaxEstimationMethod = Vo2MaxEstimationMethod.HR_RATIO,
```

Add the import (the state file imports `Vo2MaxSourceMode` already; add the sibling import):

```kotlin
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
```

- [ ] **Step 3: Map + handle in the ViewModel**

In `PhysiologySettingsViewModel.kt`:
- In `uiState` mapping, after `vo2MaxSourceMode = prefs.vo2MaxSourceMode,` (line 79), add:

```kotlin
                        vo2MaxEstimationMethod = prefs.vo2MaxEstimationMethod,
```

- In `onEvent`, after the `Vo2MaxSourceModeChanged` branch (lines 114–117), add:

```kotlin
                is SettingsEvent.Vo2MaxEstimationMethodChanged ->
                    viewModelScope.launch {
                        physiologySettings.updateVo2MaxEstimationMethod(method = event.method)
                        // The estimator is historical-scope: every persisted day's VO2 Max must be
                        // recomputed under the new method. Exactly one refresh per change.
                        healthDataRefresh.refreshHistorical()
                    }
```

- [ ] **Step 4: Add the picker composable + item**

In `PhysiologyProfileCategoryScreen.kt`, add an import for `Vo2MaxEstimationMethod`, add a `SettingsCategoryListItem` for `SettingsItemIds.PHYSIOLOGY_VO2_MAX_METHOD` immediately after the existing `PHYSIOLOGY_VO2_MAX_SOURCE` list item (after line 64):

```kotlin
                SettingsCategoryListItem(SettingsItemIds.PHYSIOLOGY_VO2_MAX_METHOD) {
                    Vo2MaxEstimationMethodPicker(
                        selectedMethod = states.physiologyState.vo2MaxEstimationMethod,
                        onMethodSelected = {
                            intents.onPhysiologyEvent(SettingsEvent.Vo2MaxEstimationMethodChanged(it))
                        },
                        enabled = controlsEnabled,
                    )
                },
```

And add the composable after the existing `Vo2MaxSourcePicker` function (ends line 142):

```kotlin
@Composable
private fun Vo2MaxEstimationMethodPicker(
    selectedMethod: Vo2MaxEstimationMethod,
    onMethodSelected: (Vo2MaxEstimationMethod) -> Unit,
    enabled: Boolean,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(
            text = stringResource(R.string.vo2_max_method_title),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    SingleChoiceSegmentedButtonRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    ) {
        Vo2MaxEstimationMethod.entries.forEachIndexed { index, method ->
            SegmentedButton(
                selected = selectedMethod == method,
                onClick = { onMethodSelected(method) },
                enabled = enabled,
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = Vo2MaxEstimationMethod.entries.size,
                    ),
                label = {
                    Text(
                        text =
                            when (method) {
                                Vo2MaxEstimationMethod.HR_RATIO ->
                                    stringResource(R.string.vo2_max_method_hr_ratio)
                                Vo2MaxEstimationMethod.MATERKO_ADAPTED ->
                                    stringResource(R.string.vo2_max_method_materko_adapted)
                            },
                    )
                },
            )
        }
    }
    Text(
        text = stringResource(R.string.vo2_max_method_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.padding(
                horizontal = MaterialTheme.spacing.pageHorizontal,
                vertical = MaterialTheme.spacing.small,
            ),
    )
}
```

- [ ] **Step 5: Add the settings item id**

In `SettingsItemIds.kt`, add `PHYSIOLOGY_VO2_MAX_METHOD` next to the existing `PHYSIOLOGY_VO2_MAX_SOURCE` constant:

```kotlin
    const val PHYSIOLOGY_VO2_MAX_METHOD = "physiology_vo2_max_method"
```

(Confirm the file's constant style — the existing `PHYSIOLOGY_VO2_MAX_SOURCE` entry; match its format.)

- [ ] **Step 6: Add strings**

In `feature/settings/src/main/res/values/strings.xml`, after the `vo2_max_source_*` block (lines 295–299), add:

```xml
    <string name="vo2_max_method_title">Cardio Fitness (VO2 Max) Estimation Method</string>
    <string name="vo2_max_method_hr_ratio">Heart rate ratio</string>
    <string name="vo2_max_method_materko_adapted">Resting HR + HRV</string>
    <string name="vo2_max_method_description">Experimental Materko-adapted estimate using your resting heart rate and HRV baselines.</string>
```

- [ ] **Step 7: Add ViewModel tests**

In `PhysiologySettingsViewModelTest.kt`, add:

```kotlin
    @Test
    fun onEvent_vo2MaxMethodChanged_updatesPrefAndRefreshesHistoricalExactlyOnce() {
        viewModel.onEvent(
            SettingsEvent.Vo2MaxEstimationMethodChanged(Vo2MaxEstimationMethod.MATERKO_ADAPTED),
        )

        coVerify(timeout = 1000, exactly = 1) {
            physiologySettings.updateVo2MaxEstimationMethod(Vo2MaxEstimationMethod.MATERKO_ADAPTED)
        }
        coVerify(timeout = 1000, exactly = 1) { healthDataRefresh.refreshHistorical() }
    }

    @Test
    fun onEvent_vo2MaxSourceModeChanged_doesNotRefreshHistorical() {
        viewModel.onEvent(
            SettingsEvent.Vo2MaxSourceModeChanged(Vo2MaxSourceMode.ESTIMATED_ONLY),
        )

        coVerify(timeout = 1000, exactly = 1) {
            physiologySettings.updateVo2MaxSourceMode(Vo2MaxSourceMode.ESTIMATED_ONLY)
        }
        coVerify(timeout = 1000, exactly = 0) { healthDataRefresh.refreshHistorical() }
    }
```

Add imports at the top of the test file:

```kotlin
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
```

- [ ] **Step 8: Compile + run settings tests**

Run: `./gradlew :feature:settings:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsEvent.kt feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/SettingsState.kt feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/PhysiologySettingsViewModel.kt feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/category/PhysiologyProfileCategoryScreen.kt feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/search/SettingsItemIds.kt feature/settings/src/main/res/values/strings.xml feature/settings/src/test/kotlin/app/readylytics/health/feature/settings/PhysiologySettingsViewModelTest.kt
git commit -m "feat(settings): add VO2max estimation-method picker with historical refresh"
```

---

### Task 8: Backup round-trip

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt:180`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/BackupPreferencesBuilder.kt:94`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/backup/RestorePreferencesExtensions.kt:190`
- Modify: `app/src/test/kotlin/app/readylytics/health/data/backup/RestorePreferenceEnumRoundTripTest.kt`

- [ ] **Step 1: Add the backup model field**

In `BackupModels.kt`, after `val sleepScoreWeightProfile: String? = null,` (line 180), add:

```kotlin
    val vo2MaxEstimationMethod: String? = null,
```

- [ ] **Step 2: Build the field**

In `BackupPreferencesBuilder.kt`, inside `buildScoringAndDevices` after `sleepScoreWeightProfile = prefs.sleepScoreWeightProfile.name,` (line 106), add:

```kotlin
        vo2MaxEstimationMethod = prefs.vo2MaxEstimationMethod.name,
```

- [ ] **Step 3: Restore the field**

In `RestorePreferencesExtensions.kt`, inside `applyScoringSettings` after the `sleepScoreWeightProfile` block (ends line 199), add:

```kotlin
    backup.vo2MaxEstimationMethod?.let { raw ->
        val resolved = resolveProtoEnum(raw, "VO2_MAX_METHOD_", Vo2MaxEstimationMethodProto::valueOf)
        if (resolved != null) {
            vo2MaxEstimationMethod = resolved
        } else {
            logW(
                "RestorePreferencesApplier",
            ) { "Ignoring unrecognised vo2MaxEstimationMethod '$raw' in backup settings" }
        }
    }
```

`Vo2MaxEstimationMethodProto` is in the same package (`app.readylytics.health.data.preferences`) as this file — no import needed.

- [ ] **Step 4: Add round-trip coverage**

Open `RestorePreferenceEnumRoundTripTest.kt` and extend it with a case asserting `MATERKO_ADAPTED` survives build → restore, following the file's existing enum round-trip pattern (read the file first and mirror its helper style). Representative shape:

```kotlin
    @Test
    fun vo2MaxEstimationMethod_roundTrips() {
        val built = buildUserPreferencesBackup(
            UserPreferences(vo2MaxEstimationMethod = Vo2MaxEstimationMethod.MATERKO_ADAPTED),
            BackupLayoutSnapshots(),
        )
        assertEquals("MATERKO_ADAPTED", built.vo2MaxEstimationMethod)
    }
```

(Match the file's actual assertions — e.g. it may assert on the restored proto builder instead.)

- [ ] **Step 5: Compile + run backup tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*RestorePreferenceEnumRoundTripTest*"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/app/readylytics/health/data/backup/BackupModels.kt app/src/main/kotlin/app/readylytics/health/data/backup/BackupPreferencesBuilder.kt app/src/main/kotlin/app/readylytics/health/data/backup/RestorePreferencesExtensions.kt app/src/test/kotlin/app/readylytics/health/data/backup/RestorePreferenceEnumRoundTripTest.kt
git commit -m "feat(backup): back up and restore vo2MaxEstimationMethod"
```

---

### Task 9: UI source-label mappings + strings

**Files:**
- Modify: `core/ui/src/main/res/values/strings.xml:103`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessDetailScreen.kt:305`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardCardioMetricPresentationFactory.kt:118`
- Modify: `feature/vitals/src/test/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessPresentationTest.kt` (if it asserts label mapping; otherwise skip)

- [ ] **Step 1: Add the label string**

In `core/ui/src/main/res/values/strings.xml`, after `vo2_max_source_label_estimated` (line 103), add:

```xml
    <string name="vo2_max_source_label_materko_adapted">Estimated (Resting HR + HRV)</string>
```

- [ ] **Step 2: Map the tag in Vitals**

In `CardioFitnessDetailScreen.kt`, in `sourceLabelRes` (lines 305–310), add a branch:

```kotlin
        "ESTIMATED_MATERKO_ADAPTED" -> CoreUiR.string.vo2_max_source_label_materko_adapted
```

- [ ] **Step 3: Map the tag in Dashboard**

In `DashboardCardioMetricPresentationFactory.kt`, in `resolveSourceLabel` (lines 118–123), add a branch:

```kotlin
                "ESTIMATED_MATERKO_ADAPTED" ->
                    resourceProvider.getString(CoreUiR.string.vo2_max_source_label_materko_adapted)
```

- [ ] **Step 4: Compile**

Run: `./gradlew :feature:vitals:compileDebugKotlin :feature:dashboard:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/res/values/strings.xml feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/cardio/CardioFitnessDetailScreen.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardCardioMetricPresentationFactory.kt
git commit -m "feat(ui): label ESTIMATED_MATERKO_ADAPTED VO2max source in vitals and dashboard"
```

---

### Task 10: Documentation synchronization

**Files:**
- Modify: `internal-docs/DATA_FLOW.md`
- Modify: `ABOUT.md`
- Modify: `docs/about.md`
- Modify: `app/src/main/res/values/strings.xml` (tooltip/about strings)

- [ ] **Step 1: Update DATA_FLOW.md**

Locate the VO2 Max scoring-path section (the estimator / source-resolution paragraphs). Add a subsection or note covering:
- The estimated path branches on `Vo2MaxEstimationMethod` (`HR_RATIO` | `MATERKO_ADAPTED`).
- `MaterkoAdaptedVo2MaxCalculator` consumes the day's stable RHR baseline + `exp(hrvMuMssd)` RMSSD baseline; it is an experimental adaptation of the published Materko model (CDR omitted, pNN50 approximated from RMSSD, MeanRR derived from the RHR baseline).
- `Vo2MaxSourceResolver.resolve` now takes `estimatedVo2Max` + `estimatedSource`, emitting `ESTIMATED_UTH` or `ESTIMATED_MATERKO_ADAPTED`.
- No DB schema change; existing `ESTIMATED_UTH` rows unchanged.

- [ ] **Step 2: Update ABOUT.md**

In the Cardio Fitness / VO2 Max section, add the estimation-method explanation. Substance (write it clearly; this text is user-facing product documentation):

> Readylytics can estimate VO2 Max two ways when no wearable VO2 Max is available: the heart-rate-ratio method (HRmax ÷ resting HR) and an experimental Materko-adapted method combining resting heart rate and HRV baselines. The Materko-adapted method is an adaptation of a published regression (Materko 2018) from which the CDR term is omitted and pNN50 is approximated from RMSSD; it is predominantly driven by resting heart rate, and it is not equivalent to the published model nor validated across the broad population (the original model was developed in young, healthy, physically active men). Estimates use stable daily baselines.

- [ ] **Step 3: Mirror in docs/about.md**

Copy the same substance into `docs/about.md`'s matching section so it stays in sync with `ABOUT.md`.

- [ ] **Step 4: Add the in-app About/tooltip string**

In `app/src/main/res/values/strings.xml`, add a tooltip string capturing the same explanation (check whether the About screen / Cardio Fitness card already surfaces a `tooltip_*` for VO2 Max and extend that; otherwise add a new one):

```xml
    <string name="tooltip_vo2_max_estimation_method">Estimated VO2 Max can use the heart-rate-ratio method or the experimental Materko-adapted method (resting HR + HRV baselines). The adapted method is not the published Materko model; it omits the CDR term and approximates pNN50 from RMSSD.</string>
```

Wire it where the VO2 Max card/about content is rendered only if an existing tooltip slot exists for VO2 Max — otherwise leave the string defined for the About screen addition and note it in the commit. Do not invent a new UI surface.

- [ ] **Step 5: Run the documentation drift tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*DocumentationDriftTest*"`
Expected: PASS. If drift failures point at stale claims elsewhere, update them in this task.

- [ ] **Step 6: Commit**

```bash
git add internal-docs/DATA_FLOW.md ABOUT.md docs/about.md app/src/main/res/values/strings.xml
git commit -m "docs: document Materko-adapted VO2max estimation method"
```

---

### Task 11: Full verification + index

- [ ] **Step 1: Run the mandatory pre-commit suite**

Run:
```bash
./gradlew ktlintFormat && ./gradlew detekt && ./gradlew assembleDebug && ./gradlew testDebugUnitTest
```
Expected: all pass. Fix any new detekt issues introduced (boyscout rule — do not suppress; refactor instead).

- [ ] **Step 2: Run lint**

Run: `./gradlew lintRelease`
Expected: no new warnings attributable to this change.

- [ ] **Step 3: Re-index the codegraph**

Run: `codegraph index`
Expected: index updated for the new files.

- [ ] **Step 4: Final commit (if Step 1 reformatted anything)**

```bash
git status --short && git add -A && git commit -m "chore: format and verify VO2max estimation-method change"  # only if there are uncommitted changes
```

---

## Self-Review

**Spec coverage:** enum + pref + proto + mapper/serializer (spec §4.1–4.2) → Tasks 1–2; repository chain (§4.3) → Task 3; estimator + deviations + no-CDR + supported bounds (§2, §3) → Task 4; resolver + tags (§5.2) → Task 5; scoring branch + calibration semantics + hrvMuMssd/RHR inputs (§5.1) → Task 6; settings event/state/VM/UI/strings (§4.3–4.4) → Task 7; backup (§4.2) → Task 8; source-label mappings (§5.3) → Task 9; docs sync incl. population caveat + MeanRR/percentile caveat (§8) → Task 10; verification → Task 11. Out-of-scope items (source-mode picker, onboarding, DB migration, `DEFAULT_RHR_BPM` fallback) are intentionally absent.

**Placeholder scan:** Steps reference exact file paths, complete code, and exact commands. The only intentional adaptation is Task 6 Step 5 and Task 8 Step 4, where snippets must be matched to existing test-helper signatures — the plan states to reuse the file's actual helper names.

**Type consistency:** `Vo2MaxEstimationMethod` enum values (`HR_RATIO`, `MATERKO_ADAPTED`), source tags (`ESTIMATED_UTH`, `ESTIMATED_MATERKO_ADAPTED`), calculator signature `estimate(rhrBaselineBpm, hrvMuMssd, isCalibrated)`, and resolver signature `resolve(mode, wearableVo2Max, estimatedVo2Max, estimatedSource)` are identical across all tasks. `standardNormalCdf` / `approxPnn50` are declared `internal` for the reference tests and used consistently.