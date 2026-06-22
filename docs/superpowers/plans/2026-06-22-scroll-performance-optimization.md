# Scroll Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate continuous scroll stutter and lag in the Vitals and Workouts tabs by applying hardware-backed render nodes (`Modifier.graphicsLayer { }`) to complex charts and list items, keeping them fully pre-rendered during vertical scrolling.

**Architecture:** We are preserving the `Column` + `verticalScroll` architecture to satisfy the constraint that items stay pre-rendered. We will append `.graphicsLayer { }` to the modifiers of all heavy UI components to draw them into an off-screen GPU buffer.

**Tech Stack:** Kotlin, Jetpack Compose (`Modifier.graphicsLayer`)

---

### Task 1: Apply `graphicsLayer` to VitalsScreen Charts

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/vitals/VitalsScreen.kt`

- [ ] **Step 1: Update the modifiers of the 3 `TrendCard`s**

In `VitalsScreen.kt`, find the three `TrendCard` instances (for HRV, RHR, and SpO2) and append `.graphicsLayer { }` to their existing modifiers. Ensure you import `androidx.compose.ui.graphics.graphicsLayer`.

```kotlin
// Example of what to change for all 3 TrendCards:
                    TrendCard(
                        title = stringResource(R.string.label_hrv_rmssd),
                        modifier = Modifier.padding(horizontal = 16.dp).graphicsLayer { },
                    ) {
```

Make this exact modifier change for:
1. `title = stringResource(R.string.label_hrv_rmssd)`
2. `title = stringResource(R.string.label_resting_heart_rate)`
3. `title = stringResource(R.string.label_oxygen_saturation)`

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/app/readylytics/health/ui/vitals/VitalsScreen.kt
git commit -m "perf: isolate Vitals charts in graphics layers"
```

### Task 2: Apply `graphicsLayer` to WorkoutsScreen Charts

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt`

- [ ] **Step 1: Update `RasWeeklyBar` Card modifier**

Find the `Card` that wraps `RasWeeklyBar` and append `.graphicsLayer { }`. Ensure you import `androidx.compose.ui.graphics.graphicsLayer`.

```kotlin
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .graphicsLayer { },
                    shape = MaterialTheme.shapes.large,
                ) {
```

- [ ] **Step 2: Update `AcwrChartCard` modifier**

Find the `AcwrChartCard` and append `.graphicsLayer { }` to its modifier.

```kotlin
                AcwrChartCard(
                    trimpPoints = uiState.dailyTrimp,
                    ratioPoints = uiState.dailyStrainRatio,
                    rangeStartMs = uiState.rangeStartMs,
                    rangeDays = rangeDays,
                    scrollState = scrollState,
                    zoomState = zoomState,
                    parentScrollInProgress = parentScrollInProgress,
                    modifier = Modifier.padding(horizontal = 16.dp).graphicsLayer { },
                )
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutStatsSection.kt
git commit -m "perf: isolate Workout charts in graphics layers"
```

### Task 3: Apply `graphicsLayer` to Workout List Items

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutListSection.kt`

- [ ] **Step 1: Update `WorkoutHistoryItem` modifier**

In `WorkoutHistoryItem`, find the `Card` wrapper and append `.graphicsLayer { }`. Ensure you import `androidx.compose.ui.graphics.graphicsLayer`.

```kotlin
    Card(
        modifier = modifier.fillMaxWidth().graphicsLayer { },
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/app/readylytics/health/ui/workouts/WorkoutListSection.kt
git commit -m "perf: isolate Workout history items in graphics layers"
```
