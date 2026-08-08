# Migrate horizontal bars to a continuous filled style with 20% tick dots

## Context

The dashboard's linear progress bars (Sleep Score, Readiness, HRV, and other
"Bar" display-mode cards, plus Daily Steps, the RAS weekly bar in Workouts,
and the sync progress screen) currently render with Material 3's default
`LinearProgressIndicator` gap + stop-indicator behavior: a visible break in
the track just before a small dot near the current progress value. This is
Material 3 "Expressive" stock behavior (confirmed via the app's baseline
profile referencing `getLinearIndicatorTrackGapSize` /
`drawStopIndicator` / `getLinearTrackStopIndicatorSize` — no app code
currently overrides these), not something custom-built in this repo.

The user wants these bars to read as one continuous filled capsule instead —
inspired by a "gradient weather" app's Cloud Cover Outlook widget (a
*vertical* pill-bar chart that is **not** part of this codebase; it's a pure
style reference for "no visible break," nothing to port). Per clarification,
the bars must **stay horizontal** — the vertical orientation of the
reference image is not being adopted, only its "no break" quality.

During clarification the ask evolved from "just remove the gap" to
reproducing the same 20%-increment tick-dot language the app's arc gauge
(`M3MetricGauge`) already uses, so the linear bar and the gauge share one
consistent visual vocabulary.

**Decisions confirmed with the user:**
1. Scope: all 4 horizontal `LinearProgressIndicator` call sites (see below), not just the dashboard score bars.
2. Fill stays flat/solid color — no gradient.
3. Tick dots at 20/40/60/80% — only drawn on the *unfilled* remainder of the track, disappearing once progress passes each mark. This exactly mirrors the existing gauge logic at `M3MetricGauge.kt:117-134` (`tickFractions.filter { it > progressToDraw }`).
4. One new shared composable, used by all 4 call sites, replacing their individual `LinearProgressIndicator` calls.
5. The weather image is style inspiration only — no code/component from it exists or needs to be pulled into this repo.

## Current call sites (all `androidx.compose.material3.LinearProgressIndicator`, all horizontal)

| # | Composable | File | Notes |
|---|---|---|---|
| 1 | `UniversalBarRenderer` | `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt:114-150` | Shared "Bar" mode for every configurable dashboard card (Sleep Score, Readiness, HRV, etc). Height = `dimens.metricTrackThickness` (12dp). Explicit `activeColor`/`trackColor`. |
| 2 | `StepsCardContent` | `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/StepsCard.kt:85-93` | Daily Steps dashboard card. Height = `dimens.miniBarHeight` (28dp). Uses `max = stepGoal / 0.75f` so the bar is visually "full" once at 75% of goal — this scaling stays as-is. No explicit colors (M3 defaults). |
| 3 | `RasWeeklyBar` | `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/RasWeeklyBar.kt:55-65` | Workouts feature RAS bar. Same `100f/0.75f` scaling idiom as Steps. Explicit `fillColor`/`trackColor`. Height = `miniBarHeight`. |
| 4 | `SyncProgressScreen` | `core/ui/src/main/kotlin/app/readylytics/health/core/ui/sync/SyncProgressScreen.kt:98-101` | Full-width bar on the sync screen. No explicit height/colors — pure M3 defaults. |

`core/ui` is already a dependency of `feature/workouts` (confirmed via `RasWeeklyBar.kt`'s existing import of `gaugeColor()` from `core.ui.components`), so a single shared composable living in `:core:ui` reaches all 4 sites without new module wiring.

Out of scope (flagged, not touched): `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/steps/StepsCard.kt` is a distinct composable used by the Step Detail screen — separate from the dashboard's `StepsCard`. Not part of the 4 confirmed sites; can be a follow-up if desired.

## Design: new shared composable `M3MetricBar`

New file: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3MetricBar.kt` (sibling to the existing `M3MetricGauge.kt`, same package, same naming family).

Rather than hand-rolling the fill/track drawing from scratch, reuse Material 3's own gapless rendering and layer the tick dots on top — smaller diff, keeps `LinearProgressIndicator`'s built-in progress semantics/animation for accessibility:

```kotlin
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
        animationSpec = if (animateProgress) tween(800, easing = FastOutSlowInEasing) else tween(0),
        label = "bar_progress",
    )
    val progressToDraw = if (animateProgress) animated else clamped

    Box(modifier = modifier.height(barHeight)) {
        LinearProgressIndicator(
            progress = { progressToDraw },
            modifier = Modifier.fillMaxSize(),
            color = activeColor,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,           // eliminates the M3 default gap
            drawStopIndicator = {},  // eliminates the M3 default stop dot
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val tickRadiusPx = MaterialTheme.dimens.metricGaugeTickDiameter.toPx() / 2f // resolved outside Canvas scope; see impl note
            listOf(0.2f, 0.4f, 0.6f, 0.8f)
                .filter { it > progressToDraw }
                .forEach { fraction ->
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

Notes for the implementer:
- `gapSize` and `drawStopIndicator` are the exact M3-Expressive `LinearProgressIndicator` parameters referenced in this app's baseline profile — confirms the installed Compose BOM (`2026.06.00`) supports them without a dependency bump. Verify the exact parameter names/signature against the resolved `material3` artifact in Android Studio before implementing (not independently verifiable in this read-only sandbox).
- Reuse `dimens.metricGaugeTickDiameter` (4dp) for tick size and the same `onSurfaceVariant @ 38% alpha` tick color the gauge uses, so bar and gauge ticks look identical.
- `progressFraction = null` (e.g. no data yet) should render like today: an inactive/neutral track, matching each call site's existing null-handling (`UniversalBarRenderer` already computes `activeColor = onSurfaceVariant` when `progressFraction == null`).
- No new `Dimens` tokens should be needed — reuse `metricTrackThickness`, `miniBarHeight`, `metricGaugeTickDiameter`.
- Keep `barHeight` a parameter (not hardcoded) since call sites use two different heights today (12dp vs 28dp) plus the sync screen's unspecified default.

## Per-call-site migration

1. **`UniversalBarRenderer`** (`UniversalMetricRenderers.kt:136-148`): replace the `LinearProgressIndicator` block with `M3MetricBar(progressFraction = progressFraction, activeColor = activeColor, trackColor = trackColor, barHeight = MaterialTheme.dimens.metricTrackThickness, modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.extraSmall).testTag(UNIVERSAL_BAR_TAG))`. Keep the `UNIVERSAL_BAR_TAG` test tag on the outer modifier so existing tests keep resolving the node.
2. **`StepsCardContent`** (`StepsCard.kt:85-93`): replace with `M3MetricBar`, keeping the existing `stepGoal / 0.75f` scaling math unchanged (only the rendering call changes) and `barHeight = MaterialTheme.dimens.miniBarHeight`. Decide default `activeColor`/`trackColor` to pass — today this call relies on M3 defaults (`primary`/`surfaceVariant`); pass those explicitly since `M3MetricBar` requires them.
3. **`RasWeeklyBar`** (`RasWeeklyBar.kt:55-65`): replace with `M3MetricBar`, passing existing `fillColor`/`trackColor`, `barHeight = MaterialTheme.dimens.miniBarHeight`, and keep the `semantics { contentDescription = chartSummary }` modifier on the outer `Modifier`.
4. **`SyncProgressScreen`** (`SyncProgressScreen.kt:98-101`): replace with `M3MetricBar`, passing `MaterialTheme.colorScheme.primary` / `MaterialTheme.colorScheme.surfaceVariant` explicitly (today's implicit M3 defaults) and a sensible `barHeight` (check current effective default height of `LinearProgressIndicator` — likely `4.dp` — and pass explicitly so visual size doesn't change).

## Testing

- Add `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/M3MetricBarTest.kt`, modeled on the existing `M3MetricGaugeTest.kt` pattern (Robolectric + `createComposeRule`):
  - Renders with `progressFraction = null` and clamps out-of-range values (mirrors `metricGauge_acceptsNullMarker_andClampsOutsideRange_...`).
  - Tick-dot visibility: at `progressFraction = 0.5f`, only the 0.6/0.8 ticks should be present in the Canvas layer; at `1.0f`, none should render. (Since ticks are Canvas-drawn with no semantics, this likely needs a pixel/screenshot-style check or a refactor to expose tick-fraction logic as a small pure function — mirror how `resolveHorseshoeGaugeGeometry` is a pure, directly-testable helper. Consider extracting a pure `visibleTickFractions(progress: Float): List<Float>` helper for direct unit testing rather than relying on Canvas introspection.)
  - No visible "gap": verify `gapSize = 0.dp` and `drawStopIndicator = {}` are actually wired (a compile-time/behavioral check, since Canvas pixel assertions are brittle in Robolectric).
- Re-run `DashboardVisualizationLayoutTest` and `DashboardVisualizationModesTest` (`feature/dashboard/src/test/kotlin/...`) since they exercise the dashboard card tree that includes `UniversalBarRenderer` — check they don't assert on `LinearProgressIndicator`-specific internals that would break with the swap.
- Existing tests for `StepsCard`, `RasWeeklyBar`, `SyncProgressScreen` (search each module's `src/test` for references) should be located and re-verified against the new composable.

## Documentation

This is a UI-only visual change — no scoring formulas, data flow, Room schema, or ingestion pipeline changes, so the `internal-docs/DATA_FLOW.md` / `ABOUT.md` / `docs/about.md` synchronization rules in `CLAUDE.md` do not apply. No new user-facing strings are introduced, so no `strings.xml` changes are expected.

## Verification (for the implementation PR, not part of this planning change)

1. `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` (mandatory pre-commit per `CLAUDE.md`).
2. `./gradlew installDebug` and manually check on-device/emulator:
   - Dashboard: Sleep Score, Readiness, HRV bars (when in Bar display mode) show one continuous filled capsule with visible tick dots only ahead of the fill — no visible break/stop-dot at the fill edge.
   - Daily Steps card: still fills to a continuous capsule at ≥75% of goal; tick dots visible below that.
   - Workouts: RAS weekly bar shows the same continuous-fill + tick-dot treatment.
   - Settings → "Resync Health Connect data": sync progress screen's bar shows continuous fill with ticks during a resync.
   - Confirm all bars remain horizontal (no rotation) in both portrait orientations tested.
3. `./gradlew lintRelease` at the end, per `CLAUDE.md`'s mandatory final step.

## Execution order (for the implementation PR)

1. Implement `M3MetricBar` (+ pure tick-fraction helper) in `:core:ui` with unit tests.
2. Migrate `UniversalBarRenderer` first (highest-visibility, matches the original screenshot) and visually verify on the dashboard.
3. Migrate `StepsCard`, `RasWeeklyBar`, `SyncProgressScreen`.
4. Update/extend existing tests touched by the swap.
5. Run ktlintFormat, testDebugUnitTest, manual device verification, then lintRelease.
