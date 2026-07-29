# F15 Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement F15 to eliminate per-recomposition allocations of `List<Color>` when rendering chart zone bands by memoizing `zoneBandColors`.

**Architecture:** We add a new `@Composable` function `rememberZoneBandColors` in `ZoneBandUtils.kt` that caches the list using Compose's `remember` tied to all input parameters. We then update three callers to use this cached version instead of computing on each recomposition.

**Tech Stack:** Kotlin, Jetpack Compose

## Global Constraints

- Pre-commit (mandatory): `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`.

---

### Task 1: Create `rememberZoneBandColors`

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ZoneBandUtils.kt`

**Interfaces:**
- Produces: `@Composable fun rememberZoneBandColors(...)` matching the signature of `zoneBandColors` exactly.

- [ ] **Step 1: Write the implementation**

Update `ZoneBandUtils.kt` to include the new memoized function. Add this directly after the `zoneBandColors` function definition:

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

- [ ] **Step 2: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ZoneBandUtils.kt
git commit -m "perf: add rememberZoneBandColors to cache zone bands"
```

### Task 2: Update `TrendCharts.kt` call site

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendCharts.kt`

**Interfaces:**
- Consumes: `@Composable fun rememberZoneBandColors(...)`

- [ ] **Step 1: Update the code**

Change `zoneBandColors(...)` to `rememberZoneBandColors(...)` in `TrendCharts.kt` (around line 225). No new imports are needed as they share a package.

```kotlin
    val colors = rememberZoneBandColors(bands, extendedColors, primaryContainer, errorContainer)
```

- [ ] **Step 2: Run verification**

Run: `./gradlew :core:ui:ktlintFormat`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/TrendCharts.kt
git commit -m "perf(core-ui): use rememberZoneBandColors in TrendCharts"
```

### Task 3: Update Blood Pressure chart call sites

**Files:**
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureTrendChart.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/SingleBloodPressureChart.kt`

**Interfaces:**
- Consumes: `@Composable fun rememberZoneBandColors(...)`

- [ ] **Step 1: Update `BloodPressureTrendChart.kt`**

Change import from `app.readylytics.health.core.ui.components.zoneBandColors` to `app.readylytics.health.core.ui.components.rememberZoneBandColors`.

Change the call at line 151:

```kotlin
    val colors =
        rememberZoneBandColors(
            bands = bands,
            extendedColors = extendedColors,
            primaryContainer = primaryContainer,
            errorContainer = errorContainer,
            optimalAlpha = 0.45f,
        )
```

- [ ] **Step 2: Update `SingleBloodPressureChart.kt`**

Change import from `app.readylytics.health.core.ui.components.zoneBandColors` to `app.readylytics.health.core.ui.components.rememberZoneBandColors`.

Change the call at line 153:

```kotlin
    val colors =
        rememberZoneBandColors(
            bands = bands,
            extendedColors = extendedColors,
            primaryContainer = primaryContainer,
            errorContainer = errorContainer,
            optimalAlpha = 0.45f,
        )
```

- [ ] **Step 3: Run full verification**

Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/BloodPressureTrendChart.kt
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/bloodpressure/SingleBloodPressureChart.kt
git commit -m "perf(vitals): use rememberZoneBandColors in blood pressure charts"
```
