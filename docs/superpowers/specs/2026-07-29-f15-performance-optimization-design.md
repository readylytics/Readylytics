# F15 Performance Optimization: Remember `zoneBandColors`

## Overview
This design document covers the implementation of item F15 from the `PERFORMANCE_OPTIMIZATION_PLAN.md`.
The goal is to eliminate per-recomposition allocations of `List<Color>` when rendering chart zone bands by memoizing the `zoneBandColors` utility function.

## Proposed Changes

### 1. Update `ZoneBandUtils.kt`
Add a new `@Composable` function `rememberZoneBandColors` in `app/readylytics/health/core/ui/components/ZoneBandUtils.kt`.
It will have the same signature as the existing `zoneBandColors` function, including all optional alpha parameters, to allow custom opacity overrides (which are used in blood pressure charts).

The function will use Compose's `remember` block to cache the resulting list of colors. The `remember` key will include all parameters to ensure the cache invalidates correctly if any input changes:

```kotlin
@Composable
fun rememberZoneBandColors(
    bands: List<ZoneBand>,
    extendedColors: ExtendedColors,
    primaryContainer: Color,
    errorContainer: Color,
    optimalAlpha: Float = ChartZoneAlphas.HIGH,
    neutralAlpha: Float = ChartZoneAlphas.RESTING,
    warningAlpha: Float = ChartZoneAlphas.HIGH,
    criticalAlpha: Float = ChartZoneAlphas.HIGH,
): List<Color> = remember(
    bands,
    extendedColors,
    primaryContainer,
    errorContainer,
    optimalAlpha,
    neutralAlpha,
    warningAlpha,
    criticalAlpha
) {
    zoneBandColors(
        bands = bands,
        extendedColors = extendedColors,
        primaryContainer = primaryContainer,
        errorContainer = errorContainer,
        optimalAlpha = optimalAlpha,
        neutralAlpha = neutralAlpha,
        warningAlpha = warningAlpha,
        criticalAlpha = criticalAlpha
    )
}
```

### 2. Update Call Sites
Replace usages of `zoneBandColors` with `rememberZoneBandColors` in the following chart files:

1. `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendCharts.kt`
2. `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureTrendChart.kt`
3. `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/SingleBloodPressureChart.kt`

This will remove the per-recomposition list allocations and prevent churning the `remember(bands, colors, minY, maxY)` key comparisons that follow these calls.

## Testing & Verification
- Verify that charts (including Vitals and Blood Pressure charts) compile successfully.
- Manually run the app to ensure charts render identically as before.
- Review Layout Inspector to confirm that `zoneBandColors` allocations are removed from the recomposition path during scrolling or data refreshing.
