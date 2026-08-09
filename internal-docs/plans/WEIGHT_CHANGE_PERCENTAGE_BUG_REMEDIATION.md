# Remediation Plan: Weight Change Under High Training Load shows 0.0%

## Context

The "Weight Change Under High Training Load" insight card renders
"Your weight has changed by 1.8 kg (**0.0%**)" — the percentage is always
wrong (rounds to 0.0 for any realistic weight change). Root cause has been
traced to a single hand-rolled calculation. A full survey of every other
insight in the deterministic insight engine (all `InsightRule` implementations
under `core/scoring/.../domain/insights/`, and every `%%`-formatted string in
`feature/insights/src/main/res/values/strings.xml`) confirms this is an
**isolated bug**, not a shared-utility problem — no other insight has the
same defect. Fixing it restores correct percentage display without touching
any other insight.

## Root Cause

`WeightDriftTrainingLoadRule.kt` computes the weight-change **fraction**
(0–1 scale) but never multiplies by 100. The body string template expects a
value already on the 0–100 scale.

`core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRule.kt:28-30`
```kotlin
val deltaKg = todayWeight - oldestWeight
val percent = abs(deltaKg) / oldestWeight              // fraction, e.g. 0.018 — missing ×100
if (percent <= InsightConstants.WEIGHT_DRIFT_PERCENT_THRESHOLD) return null
```

This fraction is passed straight into `InsightParams.WeightDrift(percent = percent)`
(line 38) and rendered by:

`feature/insights/src/main/kotlin/app/readylytics/health/feature/insights/InsightDetailRepository.kt:108`
```kotlin
is InsightParams.WeightDrift -> resources.getString(resId, params.deltaKg, params.percent)
```

against the string:

`feature/insights/src/main/res/values/strings.xml:58`
```xml
<string name="insight_weight_drift_training_load_body">Your weight has changed by %1$.1f kg (%2$.1f%%) over the past week...</string>
```

`String.format("%.1f", 0.018f)` → `"0.0"`. The threshold constant is
internally consistent with the fraction scale
(`InsightConstants.WEIGHT_DRIFT_PERCENT_THRESHOLD = 0.02f`,
`core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/InsightConstants.kt:25`),
and the unit tests assert fraction values (e.g. `percent = 0.025f`) — so the
bug is confined to "the field is a fraction but the presentation layer treats
it as a percent," never caught because no test exercises the actual string
formatting.

## Survey Result (no other insight affected)

Checked every `InsightRule` under `core/scoring/.../domain/insights/` and
every `%%` placeholder in `strings.xml`. Only two insights render a `%%`:

- **HRV Drop + Low SpO2** (`insight_hrv_drop_low_spo2_body`) — uses
  `avgSleepingSpo2`, which is natively on a 0–100 scale already. Correct.
- **Weight Change Under High Training Load** — the bug above.

Every other insight (strain ratio, RAS points, step counts, bpm deltas,
minutes) displays raw units, not percentages, and is unaffected. There is no
shared "fraction → percent" utility being misused — this rule simply never
performed the conversion. No other files need changes for this bug class.

## Fix

1. **`core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRule.kt`**
   Scale to a true percentage before comparing/returning:
   ```kotlin
   val percent = (abs(deltaKg) / oldestWeight) * 100f
   ```
   (comparison against `InsightConstants.WEIGHT_DRIFT_PERCENT_THRESHOLD` on the
   next line is unchanged in structure, just now compares percent-scale values.)

2. **`core/scoring/src/main/kotlin/app/readylytics/health/domain/insights/InsightConstants.kt:25`**
   Rescale the threshold to match the new percent scale:
   ```kotlin
   const val WEIGHT_DRIFT_PERCENT_THRESHOLD = 2.0f // was 0.02f (fraction)
   ```

3. **`core/scoring/src/test/kotlin/app/readylytics/health/domain/insights/WeightDriftTrainingLoadRuleTest.kt`**
   Update all `percent = 0.025f` / `0.0125f` style assertions and inline
   comments to the ×100 values (e.g. `percent = 2.5f`), including the
   threshold-boundary test ("does not fire when percent drift is at
   threshold": 1.6/80×100 = 2.0, matches the new `2.0f` threshold — same
   input weights, updated expected value/comment).
   Add one assertion (or extend an existing test) that plugs `deltaKg`/`percent`
   into `InsightDetailRepository`'s actual `String.format` path (or a minimal
   equivalent) so the presentation-formatted string is covered by a test,
   preventing this exact fraction-vs-percent class of bug from going unnoticed
   again for this insight.

No changes needed to `InsightDetailRepository.kt`, `strings.xml`, or any other
insight rule — they're already correct.

## Verification

1. `./gradlew ktlintFormat`
2. `./gradlew testDebugUnitTest` — confirm `WeightDriftTrainingLoadRuleTest`
   passes with rescaled values and the whole `core/scoring` + `feature/insights`
   module test suites stay green.
3. Sanity-check the arithmetic manually: a 1.8 kg change against an ~80 kg
   baseline should now render as "(2.3%)" (or similar, matching real device
   data) instead of "(0.0%)".
4. `./gradlew lintRelease` at the end per project convention.
