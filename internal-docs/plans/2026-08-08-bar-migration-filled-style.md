# Horizontal Bars → Continuous Filled Style with 20% Tick Dots

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the 4 horizontal `LinearProgressIndicator` bars with one shared `M3MetricBar` composable that renders a continuous filled capsule (no M3 gap, no stop dot) plus 20/40/60/80% tick dots on the unfilled track, matching the arc gauge's visual vocabulary.

**Architecture:** One new `@Composable` in `:core:ui` (`M3MetricBar.kt`, sibling of `M3MetricGauge.kt`) wraps M3's own `LinearProgressIndicator` (with `gapSize = 0.dp` and `drawStopIndicator = {}` to kill the expressive gap/stop-dot) and overlays a `Canvas` that draws the gauge-style tick dots at 20/40/60/80% — filtered to only the unfilled remainder. All 4 call sites then pass their existing colors/heights/scaling into the shared bar. A tiny pure function `visibleTickFractions(progress)` holds the filter logic so it is unit-testable without Canvas introspection.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3, BOM `2026.06.00` → material3 `1.4.0`), Robolectric + `createComposeRule` (v2) for component tests.

## Global Constraints

- Pre-commit (mandatory per `AGENTS.md`): `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`; final step runs `./gradlew lintRelease`.
- All user-facing strings stay in `strings.xml` — **no new strings** are introduced by this change; do not hardcode any.
- Use existing design-system dimens tokens; no new `Dimens` fields.
- File target ≤ 400 lines (hard limit 800) — the new composable file stays ~90 lines.
- Bars must **remain horizontal** — no rotation anywhere.
- **Scoring math, data flow, Room schema, ingestion pipeline are OFF-LIMITS.** This is a UI-only change, so the `internal-docs/DATA_FLOW.md` / `ABOUT.md` / `docs/about.md` / `strings.xml` sync rules do **not** apply.
- **Scope is exactly 4 call sites.** Do not touch the other `LinearProgressIndicator` usages in the repo (`WorkoutMetricsDisplay.kt`, `CircadianThresholdSettingsSection.kt`, `MainScaffold.kt`, `DatabaseMigrationScreen.kt`, `feature/vitals/.../StepsCard.kt`) — they are confirmed out of scope.
- After creating/deleting any file, run `codegraph index` (AGENTS.md File Lifecycle).

### Verified material3 1.4.0 facts (from the resolved artifact source, do not re-derive)

- The `progress: () -> Float` overload of `LinearProgressIndicator` is:
  `LinearProgressIndicator(progress, modifier, color, trackColor, strokeCap, gapSize: Dp, drawStopIndicator: DrawScope.() -> Unit)`.
- `gapSize`/`drawStopIndicator` are **not** individually `@ExperimentalMaterial3Api`; the function only opts in internally, so call sites need **no** `@OptIn`. If the compiler still flags `ExperimentalMaterial3Api`, add `@OptIn(ExperimentalMaterial3Api::class)` to the `M3MetricBar` function — and nothing else.
- With `strokeCap = StrokeCap.Round`, the M3 source computes `adjustedGapSize = gapSize + strokeWidth`; the round-cap overhangs of fill and track exactly cancel, so `gapSize = 0.dp` renders a **visually gapless** capsule. `gapSize = 0.dp` truly eliminates the default 4dp (`TrackActiveSpace`) gap.
- Default indicator color = `colorScheme.primary`; default track color = `colorScheme.secondaryContainer` (NOT `surfaceVariant` — the source plan doc's guess was wrong; we pass `secondaryContainer` explicitly to preserve today's exact look).
- With no explicit height modifier the indicator renders at **4.dp** (`LinearIndicatorHeight` = token `Height`). Sync screen currently renders 4.dp.

---

## File Structure

- **Create** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt`
  - Owns: `M3MetricBar` composable + `METRIC_BAR_TICK_FRACTIONS` + pure `visibleTickFractions()`.
- **Create** `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricBarTest.kt`
  - Owns: pure-helper tests + a Robolectric render/clamp test.
- **Modify** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt:136-148` — swap bar for `M3MetricBar`, drop now-unused imports.
- **Modify** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/StepsCard.kt:85-93` — swap bar, drop unused imports.
- **Modify** `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt:55-65` — swap bar, drop unused imports.
- **Modify** `core/ui/src/main/kotlin/app/readylytics/health/core/ui/sync/SyncProgressScreen.kt:98-101` — swap bar, drop unused import.

`feature/workouts` already depends on `:core:ui` via `readylytics.compose-feature-conventions` (it already imports `gaugeColor` from `core.ui.components`) — no Gradle wiring changes.

---

### Task 1: Create `M3MetricBar` with tick-fraction helper

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt`
- Test: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricBarTest.kt`

**Interfaces:**
- Consumes: `MaterialTheme.dimens.metricTrackThickness` (default), `metricGaugeTickDiameter` (tick size), `MaterialTheme.colorScheme.onSurfaceVariant` (default tick color), M3 `LinearProgressIndicator` gapless params. These exist already.
- Produces (later tasks consume these exact names/signatures):
  - `@Composable fun M3MetricBar(progressFraction: Float?, activeColor: Color, trackColor: Color, modifier: Modifier = Modifier, tickColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f), barHeight: Dp = MaterialTheme.dimens.metricTrackThickness, animateProgress: Boolean = true)`
  - `internal val METRIC_BAR_TICK_FRACTIONS: List<Float> = listOf(0.2f, 0.4f, 0.6f, 0.8f)`
  - `internal fun visibleTickFractions(progress: Float): List<Float>`

- [ ] **Step 1: Write the failing test**

Create `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricBarTest.kt` (modeled on `M3MetricGaugeTest.kt`):

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M3MetricBarTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun visibleTickFractions_hidesTicksAtOrBeforeProgress() {
        assertEquals(listOf(0.2f, 0.4f, 0.6f, 0.8f), visibleTickFractions(0f))
        assertEquals(listOf(0.4f, 0.6f, 0.8f), visibleTickFractions(0.2f))
        assertEquals(listOf(0.6f, 0.8f), visibleTickFractions(0.5f))
        assertEquals(emptyList(), visibleTickFractions(0.8f))
        assertEquals(emptyList(), visibleTickFractions(1f))
    }

    @Test
    fun metricBar_acceptsNull_andClampsOutOfRange_progressSurfacedThroughSemantics() {
        composeTestRule.setContent {
            M3MetricBar(progressFraction = null, activeColor = Color.Red, trackColor = Color.Gray, animateProgress = false)
            M3MetricBar(progressFraction = 1.5f, activeColor = Color.Red, trackColor = Color.Gray, animateProgress = false)
            M3MetricBar(progressFraction = -0.2f, activeColor = Color.Red, trackColor = Color.Gray, animateProgress = false)
            M3MetricBar(progressFraction = 0.5f, activeColor = Color.Green, trackColor = Color.Gray, animateProgress = false)
        }
        // M3's LinearProgressIndicator surfaces the clamped progress via ProgressBarRangeInfo.
        composeTestRule.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..1f))).assertCountEquals(2)
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(1f, 0f..1f))).assertExists()
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).assertExists()
    }
}
```

Note on the second test: `null` and `-0.2f` both clamp to `0f` (2 nodes), `1.5f` clamps to `1f`, `0.5f` stays. `hasProgressBarRangeInfo` is stable in compose ui-test (`SemanticsMatcher.expectValue(ProgressBarRangeInfo, ...)`). `assertCountEquals` is on `SemanticsNodeInteractionCollection`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricBarTest"`
Expected: FAIL — `M3MetricBar`/`visibleTickFractions` are unresolved symbols.

- [ ] **Step 3: Write the implementation**

Create `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt`:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.dimens

internal val METRIC_BAR_TICK_FRACTIONS: List<Float> = listOf(0.2f, 0.4f, 0.6f, 0.8f)

internal fun visibleTickFractions(progress: Float): List<Float> =
    METRIC_BAR_TICK_FRACTIONS.filter { it > progress }

@Composable
fun M3MetricBar(
    progressFraction: Float?,
    activeColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    tickColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    barHeight: Dp = MaterialTheme.dimens.metricTrackThickness,
    animateProgress: Boolean = true,
) {
    val clamped = progressFraction?.coerceIn(0f, 1f) ?: 0f
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec =
            if (animateProgress) {
                tween(durationMillis = 800, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 0)
            },
        label = "bar_progress",
    )
    val progressToDraw = if (animateProgress) animated else clamped
    val tickDiameter = MaterialTheme.dimens.metricGaugeTickDiameter

    Box(modifier = modifier.height(barHeight)) {
        LinearProgressIndicator(
            progress = { progressToDraw },
            modifier = Modifier.fillMaxSize(),
            color = activeColor,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tickRadiusPx = tickDiameter.toPx() / 2f
            visibleTickFractions(progressToDraw).forEach { fraction ->
                drawCircle(
                    color = tickColor,
                    radius = tickRadiusPx,
                    center = Offset(size.width * fraction, size.height / 2f),
                )
            }
        }
    }
}
```

Implementation notes (why this is correct):
- `gapSize = 0.dp` + `StrokeCap.Round` → M3's `adjustedGapSize = 0 + strokeWidth`, and the round-cap overhangs of fill and track cancel exactly: gapless continuous capsule.
- `drawStopIndicator = {}` removes the stop dot.
- The ticks reuse `metricGaugeTickDiameter` (4dp) and the same `onSurfaceVariant @ 38%` color as the gauge, filtered with `visibleTickFractions` (mirrors `M3MetricGauge.kt:117-134`'s `filter { it > progressToDraw }`). On the flat track the 38%-alpha dots read as subtle darker dots — identical to the gauge.
- `tickDiameter` is read in composition scope (a `@Composable` `MaterialTheme` read cannot happen inside the `Canvas` draw lambda); only `.toPx()` (a `DrawScope` extension) happens inside the lambda.
- `progressFraction == null` → `clamped = 0f` → neutral track with all 4 ticks visible; call sites that already pick a neutral `activeColor` when null keep their look.
- No `@OptIn` expected (verified against material3 1.4.0 source). If the compiler flags `ExperimentalMaterial3Api`, add `@OptIn(ExperimentalMaterial3Api::class)` above `M3MetricBar`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.M3MetricBarTest"`
Expected: PASS (both tests).

Also run the existing gauge suite to confirm no shared-state breakage:
Run: `./gradlew :core:ui:testDebugUnitTest`
Expected: all pass.

- [ ] **Step 5: Format, index, commit**

```bash
./gradlew ktlintFormat
codegraph index
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricBarTest.kt
git commit -m "feat(core-ui): add M3MetricBar with gapless fill and gauge-style tick dots"
```

---

### Task 2: Migrate `UniversalBarRenderer` (dashboard score bars)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt:136-148`

**Interfaces:**
- Consumes: `M3MetricBar(progressFraction, activeColor, trackColor, modifier, barHeight, animateProgress)` from Task 1; `dimens.metricTrackThickness`, `spacing.extraSmall`, `UNIVERSAL_BAR_TAG` (already imported/used).
- Produces: same `UNIVERSAL_BAR_TAG` test tag on the bar's outer modifier, so all existing dashboard tests (`DashboardVisualizationModesTest`, `DashboardVisualizationLayoutTest`, `DashboardVisualizationRegressionTestBase`) keep resolving the node. No signature changes to `UniversalBarRenderer`.

- [ ] **Step 1: Replace the bar block**

In `UniversalMetricRenderers.kt`, replace lines 136-148 (the `val progress = ...` + `LinearProgressIndicator(...)` inside the `UniversalValueUnitColumn` track slot) with:

```kotlin
        M3MetricBar(
            progressFraction = progressFraction,
            activeColor = activeColor,
            trackColor = trackColor,
            barHeight = MaterialTheme.dimens.metricTrackThickness,
            animateProgress = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.extraSmall)
                    .testTag(UNIVERSAL_BAR_TAG),
        )
```

Notes:
- `animateProgress = false` preserves the current instant-render behavior (the gauge animates via its card config toggle; the bar has no such toggle — do not introduce animation here).
- The tag stays on the outer modifier exactly as today, so geometry/semantics tests keep passing.

- [ ] **Step 2: Remove now-unused imports**

Delete these two imports from `UniversalMetricRenderers.kt` (both become unused — ktlint will also flag them):
- `androidx.compose.material3.LinearProgressIndicator` (line 14)
- `androidx.compose.ui.graphics.StrokeCap` (line 23)

Add the import: `app.readylytics.health.core.ui.components.M3MetricBar`

(Verify nothing else in the file uses `StrokeCap`/`LinearProgressIndicator` first; `dimens`, `height`, `fillMaxWidth`, `padding`, `testTag` are still used elsewhere and must stay.)

- [ ] **Step 3: Run the dashboard regression tests**

Run: `./gradlew :feature:dashboard:testDebugUnitTest`
Expected: PASS — `DashboardVisualizationModesTest` (incl. `barMode_*`, `supportedModes_keepFixedBoundsAndVisibleText_...`, `valueAndBarModes_shareTheirValueUnitAndSecondaryGeometry`), `DashboardVisualizationLayoutTest` (incl. `barMode_keepsScoreOutsideTrack`, `barMode_keepsBarAndDeltaPillInsideCardBounds_atLargeFontScale`, `allModes_keepOriginalCardHeight`), `DashboardVisualizationRegressionTestBase`.

If `UNIVERSAL_BAR_TAG` geometry assertions fail, check that the tag is on the outer `M3MetricBar` modifier (the `Box`), not lost during the swap — do not "fix" a test to hide a tag regression.

- [ ] **Step 4: Format, index, commit**

```bash
./gradlew ktlintFormat
codegraph index
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt
git commit -m "feat(dashboard): render metric bars with M3MetricBar continuous fill and ticks"
```

---

### Task 3: Migrate `StepsCardContent` (Daily Steps dashboard card)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/StepsCard.kt:85-93`

**Interfaces:**
- Consumes: `M3MetricBar` (same package — no import needed); `dimens.miniBarHeight`; M3 scheme `primary` / `secondaryContainer` (these are exactly the M3 defaults the call relies on today — verified from material3 1.4.0 tokens; **not** `surfaceVariant`).
- Produces: unchanged `StepsCard`/`StepsCardContent` signatures; the `stepGoal / 0.75f` scaling math is preserved verbatim.

- [ ] **Step 1: Replace the bar block**

In `StepsCard.kt`, replace the `LinearProgressIndicator(...)` at lines 85-93 with:

```kotlin
        val count = stepCount ?: 0
        val max = stepGoal / 0.75f
        M3MetricBar(
            progressFraction = (count.toFloat() / max.coerceAtLeast(1f)).coerceIn(0f, 1f),
            activeColor = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            barHeight = MaterialTheme.dimens.miniBarHeight,
            animateProgress = false,
            modifier = Modifier.fillMaxWidth(),
        )
```

Notes:
- Keep the `stepGoal / 0.75f` idiom so the bar still reads "full" at ≥75% of goal.
- `primary`/`secondaryContainer` preserve the exact current colors (M3 defaults for this call). Do **not** use `surfaceVariant` here — it would change the track color.
- Ktlint is OK with a `val` block inside the `ColumnScope` track; alternatively inline the expression into `progressFraction`.

- [ ] **Step 2: Remove now-unused imports**

From `StepsCard.kt`:
- Remove `androidx.compose.material3.LinearProgressIndicator` (line 13)
- Remove `androidx.compose.ui.graphics.StrokeCap` (line 19)

(`dimens` stays — used at line 91 in `barHeight`; `height` stays — used by the Spacers; no `M3MetricBar` import needed since it's the same `core.ui.components` package.)

- [ ] **Step 3: Run affected tests**

Run: `./gradlew :feature:dashboard:testDebugUnitTest` (covers `steps_remainsBarOnlyAndOutsideDashboardMetricCard` which renders `StepsCard`)
Run: `./gradlew :core:ui:testDebugUnitTest` (covers any `core:ui` StepsCard usage)
Expected: PASS.

- [ ] **Step 4: Format, index, commit**

```bash
./gradlew ktlintFormat
codegraph index
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/StepsCard.kt
git commit -m "feat(steps-card): render daily steps bar with M3MetricBar continuous fill"
```

---

### Task 4: Migrate `RasWeeklyBar` (Workouts RAS bar)

**Files:**
- Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt:55-65`

**Interfaces:**
- Consumes: `M3MetricBar` (import `app.readylytics.health.core.ui.components.M3MetricBar`); existing `fillColor`/`trackColor`; `dimens.miniBarHeight`; the file's private `BAR_MAX = 100f / 0.75f`; the `chartSummary` string.
- Produces: unchanged `RasWeeklyBar` signature; the `contentDescription = chartSummary` semantics stays on the outer modifier.

- [ ] **Step 1: Replace the bar block**

In `RasWeeklyBar.kt`, replace the `LinearProgressIndicator(...)` at lines 55-65 with:

```kotlin
        M3MetricBar(
            progressFraction = (totalRas / BAR_MAX).coerceIn(0f, 1f),
            activeColor = fillColor,
            trackColor = trackColor,
            barHeight = MaterialTheme.dimens.miniBarHeight,
            animateProgress = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = chartSummary },
        )
```

Note: the semantics modifier moves to the outer `M3MetricBar` modifier (as the plan doc requires); the `contentDescription` is preserved for TalkBack.

- [ ] **Step 2: Remove now-unused imports**

From `RasWeeklyBar.kt`:
- Remove `androidx.compose.material3.LinearProgressIndicator` (line 11)
- Remove `androidx.compose.ui.graphics.StrokeCap` (line 18)
- Add `import app.readylytics.health.core.ui.components.M3MetricBar`

(`Canvas` stays — `RasDayLegendItem` still uses it; `dimens`/`height`/`fillMaxWidth`/`semantics`/`contentDescription` all still used.)

- [ ] **Step 3: Run workouts tests**

Run: `./gradlew :feature:workouts:testDebugUnitTest`
Expected: PASS (existing `RasSummaryValueTextStyleTest` etc.). This also proves `:core:ui`'s new composable compiles into the workouts module.

- [ ] **Step 4: Format, index, commit**

```bash
./gradlew ktlintFormat
codegraph index
git add feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt
git commit -m "feat(workouts): render RAS weekly bar with M3MetricBar continuous fill"
```

---

### Task 5: Migrate `SyncProgressScreen` (resync progress bar)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/sync/SyncProgressScreen.kt:98-101`

**Interfaces:**
- Consumes: `M3MetricBar` (import `app.readylytics.health.core.ui.components.M3MetricBar`); `RecalcProgress.fraction()` (already imported, returns `Float` 0f-1f); M3 scheme `primary` / `secondaryContainer`; current default height **4.dp** (verified from material3 1.4.0 token `Height = 4.dp`).
- Produces: unchanged `SyncProgressScreen` signature.

- [ ] **Step 1: Replace the bar block**

In `SyncProgressScreen.kt`, replace the `LinearProgressIndicator(...)` at lines 98-101 with:

```kotlin
            M3MetricBar(
                progressFraction = progress?.fraction(),
                activeColor = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.secondaryContainer,
                barHeight = 4.dp,
                animateProgress = false,
                modifier = Modifier.fillMaxWidth(),
            )
```

Notes:
- `barHeight = 4.dp` reproduces the current effective thickness exactly (the bar had no explicit height → M3 renders 4.dp). The plan doc's "no visual size change" requirement.
- `primary`/`secondaryContainer` = today's implicit M3 defaults (again, not `surfaceVariant`).
- `progress?.fraction()` is already nullable-friendly: `null` → empty track with all 4 ticks.

- [ ] **Step 2: Remove the now-unused import**

From `SyncProgressScreen.kt`:
- Remove `androidx.compose.material3.LinearProgressIndicator` (line 18)
- Add `import app.readylytics.health.core.ui.components.M3MetricBar`

(`fillMaxWidth`, `dp` still used elsewhere in the file.)

- [ ] **Step 3: Run core/ui tests**

Run: `./gradlew :core:ui:testDebugUnitTest`
Expected: PASS. (No dedicated `SyncProgressScreen` unit test exists; the module must still compile and all component tests pass.)

- [ ] **Step 4: Format, index, commit**

```bash
./gradlew ktlintFormat
codegraph index
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/sync/SyncProgressScreen.kt
git commit -m "feat(sync): render resync progress bar with M3MetricBar continuous fill"
```

---

### Task 6: Full verification

**Files:** none (no code changes).

**Interfaces:** n/a — this task verifies Tasks 1-5 hold together.

- [ ] **Step 1: Full unit test pass + format**

```bash
./gradlew ktlintFormat
./gradlew testDebugUnitTest
```

Expected: green across `:core:ui`, `:feature:dashboard`, `:feature:workouts` and every other module.

- [ ] **Step 2: Manual on-device verification**

Run: `./gradlew installDebug` and check on a device/emulator (do **not** uninstall the production `app.readylytics.health` build — AGENTS.md device rule):
- Dashboard with Sleep Score / Readiness / HRV in **Bar** display mode: one continuous filled capsule with tick dots only ahead of the fill edge — no gap, no stop dot.
- Daily Steps card: continuous capsule; ticks visible below 75% of goal; fills fully at ≥75%.
- Workouts → RAS weekly bar: same continuous fill + ticks; still horizontal.
- Settings → "Resync Health Connect data": sync progress screen bar is continuous with ticks during a resync.
- **Visual check point (sync bar):** at `barHeight = 4.dp` the 4dp tick dots are as tall as the bar. Confirm they read as ticks, not as the old stop-dot look. If they read wrong, this is a design follow-up (e.g. give the sync screen a taller `barHeight`) — do not silently change the size in this PR.
- Confirm all bars stay horizontal in both portrait orientations.

- [ ] **Step 3: Lint**

```bash
./gradlew lintRelease
```

Expected: no new findings.

- [ ] **Step 4: Final indexing + wrap-up**

```bash
codegraph index
```

Confirm `internal-docs/plans/bar-migration-filled-style.md` (the source plan doc) still matches the shipped behavior; no doc-sync updates are required (UI-only change). No `strings.xml` changes.

---

## Self-Review Notes

- **Spec coverage:** The source plan doc's 4 call sites are Tasks 2-5; the shared composable + pure helper are Task 1; existing-test re-verification is folded into each task's run step; full verification/lint is Task 6. Out-of-scope `LinearProgressIndicator` usages are listed in Global Constraints and deliberately not touched.
- **Corrections to the source plan doc (deliberate):** (1) M3's default track color is `secondaryContainer`, not `surfaceVariant` — passing `surfaceVariant` would change the Steps/Sync bar look, so we pass `secondaryContainer`; (2) sync bar default height confirmed as exactly `4.dp` (not "likely"); (3) call sites pass `animateProgress = false` so the only visual deltas are the requested ones (continuous fill + ticks), since the bar has no animation toggle.
- **Type/name consistency:** `M3MetricBar`, `METRIC_BAR_TICK_FRACTIONS`, `visibleTickFractions`, and `barHeight`/`animateProgress` parameter names are identical across all 5 tasks.
