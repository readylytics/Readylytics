# Weight Change Percentage Bug Remediation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the "Weight Change Under High Training Load" insight so its percentage renders correctly (e.g. "(2.3%)") instead of always "(0.0%)".

**Architecture:** The insight rule `WeightDriftTrainingLoadRule` computes a weight-change *fraction* (0–1, e.g. `0.018`) but the body string template expects a *percentage* (0–100). The fix rescales the rule's `percent` to ×100 and rescales `WEIGHT_DRIFT_PERCENT_THRESHOLD` from `0.02f` to `2.0f` (same physical threshold). A survey of all `InsightRule` implementations and every `%%` placeholder confirms this is the only affected insight. TDD: the failing format-regression test drives the rescale.

**Tech Stack:** Kotlin, JUnit4, Gradle multi-module (`:core:scoring`, `:feature:insights`). Pure JVM unit tests — zero Android dependencies.

---

## Context & Root Cause (verified)

`core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRule.kt:28-30`:
```kotlin
val deltaKg = todayWeight - oldestWeight
val percent = abs(deltaKg) / oldestWeight              // fraction, e.g. 0.018 — missing ×100
if (percent <= InsightConstants.WEIGHT_DRIFT_PERCENT_THRESHOLD) return null
```
`percent` is fed into `InsightParams.WeightDrift(percent = percent)` and rendered at `feature/insights/.../InsightDetailRepository.kt:108`:
```kotlin
is InsightParams.WeightDrift -> resources.getString(resId, params.deltaKg, params.percent)
```
against `strings.xml:58`:
```xml
<string name="insight_weight_drift_training_load_body">Your weight has changed by %1$.1f kg (%2$.1f%%) over the past week...</string>
```
`String.format("%.1f", 0.018f)` → `"0.0"`. Threshold `InsightConstants.WEIGHT_DRIFT_PERCENT_THRESHOLD = 0.02f` is internally consistent with the fraction scale; unit tests assert fractions (`percent = 0.025f`). Bug never caught because no test exercised string formatting.

**Survey result:** Checked every `InsightRule` under `core/scoring/.../domain/insights/` and every `%%` placeholder in `strings.xml`. Only two insights render `%%`:
- **HRV Drop + Low SpO2** (`avgSleepingSpo2`) — already 0–100 scale. Correct.
- **Weight Change Under High Training Load** — this bug.

No shared utility misused; no other files need changes for this bug class.

---

### Task 1: Write failing tests for percent-scale output

**Files:**
- Modify: `core/scoring/src/test/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRuleTest.kt`

- [ ] **Step 1: Update existing assertions to percent-scale expectations**

Change every expected `percent` from fraction to ×100 (`0.025f` → `2.5f`), fix the boundary test comment, and add a new format-path regression test. The full file becomes:

```kotlin
package app.readylytics.health.domain.insights

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.InsightType
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class WeightDriftTrainingLoadRuleTest {
    private val rule = WeightDriftTrainingLoadRule()
    private val today = LocalDate.of(2026, 6, 12)

    private fun context(
        todayWeightKg: Float? = 81f,
        strainRatio: Float? = 1.5f,
        recentDays: List<DailySummary> =
            listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
    ) = InsightContext(
        today = dailySummary(date = today, weightKg = todayWeightKg, strainRatio = strainRatio),
        circadianResult = CircadianConsistencyResult.MissingData,
        goalSleepMinutes = 480,
        recentDays = recentDays,
    )

    @Test
    fun `fires when weight drift exceeds threshold and strain ratio is high`() {
        val finding =
            rule.evaluate(
                context(
                    todayWeightKg = 82f,
                    recentDays = listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
                ),
            )

        // delta = 2, percent = 2/80 x 100 = 2.5 > 2.0
        assertEquals(InsightType.WEIGHT_DRIFT_TRAINING_LOAD, finding?.type)
        assertEquals(InsightParams.WeightDrift(deltaKg = 2f, percent = 2.5f), finding?.params)
    }

    @Test
    fun `does not fire when today weight is null`() {
        assertNull(rule.evaluate(context(todayWeightKg = null)))
    }

    @Test
    fun `does not fire when no other day has a weight reading`() {
        assertNull(rule.evaluate(context(recentDays = listOf(dailySummary(date = today.minusDays(1))))))
    }

    @Test
    fun `does not fire when recentDays is empty`() {
        assertNull(rule.evaluate(context(recentDays = emptyList())))
    }

    @Test
    fun `does not fire when percent drift is at threshold`() {
        // delta = 1.6, percent = 1.6 / 80 x 100 = 2.0 (exactly threshold)
        assertNull(
            rule.evaluate(
                context(
                    todayWeightKg = 81.6f,
                    recentDays = listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
                ),
            ),
        )
    }

    @Test
    fun `does not fire when strain ratio is at threshold`() {
        assertNull(rule.evaluate(context(strainRatio = 1.3f)))
    }

    @Test
    fun `does not fire when strain ratio is null`() {
        assertNull(rule.evaluate(context(strainRatio = null)))
    }

    @Test
    fun `fires for weight loss drift as well as gain`() {
        val finding =
            rule.evaluate(
                context(
                    todayWeightKg = 78f,
                    recentDays = listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
                ),
            )

        // delta = -2, percent = abs(-2)/80 x 100 = 2.5 > 2.0
        assertEquals(InsightType.WEIGHT_DRIFT_TRAINING_LOAD, finding?.type)
        assertEquals(InsightParams.WeightDrift(deltaKg = -2f, percent = 2.5f), finding?.params)
    }

    @Test
    fun `oldest weight reading in window is used as baseline`() {
        val recentDays =
            listOf(
                dailySummary(date = today.minusDays(1), weightKg = 81.9f),
                dailySummary(date = today.minusDays(6), weightKg = 80f),
            )
        val finding = rule.evaluate(context(todayWeightKg = 82f, recentDays = recentDays))

        // baseline should be the oldest (80f), delta = 2, percent = 2.5
        assertEquals(InsightParams.WeightDrift(deltaKg = 2f, percent = 2.5f), finding?.params)
    }

    @Test
    fun `percent param formats as a real percentage through the presentation path`() {
        val finding =
            rule.evaluate(
                context(
                    todayWeightKg = 82f,
                    recentDays = listOf(dailySummary(date = today.minusDays(6), weightKg = 80f)),
                ),
            )
        val params = finding?.params as InsightParams.WeightDrift

        val body =
            "Your weight has changed by %1$.1f kg (%2$.1f%%) over the past week while your training load is high"
        val rendered = String.format(Locale.US, body, params.deltaKg, params.percent)

        assertTrue("expected a real percentage, got: $rendered", rendered.contains("(2.5%)"))
        assertFalse("fraction scale would render 0.0%, got: $rendered", rendered.contains("0.0%"))
    }
}
```

- [ ] **Step 2: Run the test suite and confirm it fails**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests "app.readylytics.health.domain.insights.WeightDriftTrainingLoadRuleTest"`
Expected: FAIL — `assertEquals` on `percent` mismatches (`0.025f` vs `2.5f`), and the new `percent param formats…` test fails because the current rule returns `percent = 0.025f`, rendering `(0.0%)`.

- [ ] **Step 3: Commit the failing tests**

```bash
git add core/scoring/src/test/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRuleTest.kt
git commit -m "test: expect percent-scale output from WeightDriftTrainingLoadRule"
```

---

### Task 2: Rescale the rule and threshold

**Files:**
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRule.kt:29`
- Modify: `core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/InsightConstants.kt:25`

- [ ] **Step 1: Scale the rule's percent to 0–100**

`core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRule.kt:28-30`:
```kotlin
val deltaKg = todayWeight - oldestWeight
val percent = (abs(deltaKg) / oldestWeight) * 100f
if (percent <= InsightConstants.WEIGHT_DRIFT_PERCENT_THRESHOLD) return null
```

- [ ] **Step 2: Rescale the threshold constant**

`core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/InsightConstants.kt:25`:
```kotlin
const val WEIGHT_DRIFT_PERCENT_THRESHOLD = 2.0f // was 0.02f (fraction scale)
```
This preserves the exact physical threshold (0.02 fraction == 2.0 percent); only the scale changes.

- [ ] **Step 3: Run the tests and confirm they pass**

Run: `./gradlew :core:scoring:testDebugUnitTest --tests "app.readylytics.health.domain.insights.WeightDriftTrainingLoadRuleTest"`
Expected: PASS — all assertions match percent-scale values; the boundary test still returns `null` at exactly `2.0f`.

- [ ] **Step 4: Commit**

```bash
git add core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRule.kt core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/InsightConstants.kt
git commit -m "fix: render weight drift percentage on 0-100 scale in insight"
```

---

### Task 3: Sync internal insight documentation

**Files:**
- Modify: `internal-docs/INSIGHTS.md:291`

- [ ] **Step 1: Update the trigger formula and threshold**

`internal-docs/INSIGHTS.md:291` currently reads:
```
- `percent = abs(todayWeight - oldestWeight) / oldestWeight` > `WEIGHT_DRIFT_PERCENT_THRESHOLD` (0.02f).
```
Replace with:
```
- `percent = abs(todayWeight - oldestWeight) / oldestWeight * 100` > `WEIGHT_DRIFT_PERCENT_THRESHOLD` (2.0f).
```

- [ ] **Step 2: Verify no other doc drift**

Check `docs/insights.md` line 153 ("drifts more than 2%") and the in-app About/insight strings — they already describe the 2% threshold on the percent scale, so no change. Confirm `internal-docs/DATA_FLOW.md` has no weight-drift formula reference (it points to where formulas live, not coefficients).

- [ ] **Step 3: Commit**

```bash
git add internal-docs/INSIGHTS.md
git commit -m "docs: document percent-scale weight drift threshold"
```

---

### Task 4: Full verification

**Files:** none

- [ ] **Step 1: Format and run the full unit test suite**

Run: `./gradlew ktlintFormat`
Expected: no reformat diffs to review (or auto-applied).

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL — including `:core:scoring` and `:feature:insights` module suites.

- [ ] **Step 2: Run lint**

Run: `./gradlew lintRelease`
Expected: BUILD SUCCESSFUL, no new lint errors.

- [ ] **Step 3: Sanity-check the arithmetic**

A 1.8 kg change against an ~80 kg baseline = `1.8/80 × 100 = 2.25`, which renders `(2.3%)` via `%.1f` — not `(0.0%)`. Matches the format-path regression test's contract.

- [ ] **Step 4: Update the codegraph index**

Run: `codegraph sync` (source files changed under `core/scoring/.../domain/insights/`).

---

## Self-Review

- **Spec coverage:** Root cause (fraction vs percent) → Task 1 (failing tests) + Task 2 (rescale). Docs sync (threshold documented in `INSIGHTS.md`) → Task 3. Pre-commit convention (`ktlintFormat`, `testDebugUnitTest`, `lintRelease`) → Task 4. Recurrence guard (format-path regression test) → Task 1, `percent param formats…`.
- **Placeholder scan:** Every step has concrete code or an exact command; no "add error handling", no "similar to Task N".
- **Type consistency:** `InsightParams.WeightDrift(deltaKg, percent)` signature unchanged; expected values `2.5f` / `2.0f` consistent across Task 1 tests and Task 2 constants; `WEIGHT_DRIFT_PERCENT_THRESHOLD` referenced identically in rule, constant, and `INSIGHTS.md`.
- **Not changed (verified by survey):** `InsightDetailRepository.kt`, `strings.xml`, `InsightFinding.kt`, and all other `InsightRule` implementations remain untouched.
