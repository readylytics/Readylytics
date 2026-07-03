# Chart Picker Spacing Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the vertical spacing between the TimeRange pickers (segmented buttons) and their charts to `pageSectionGapSmall` (8.dp) on the Sleep, Vitals, and Workouts screens.

**Architecture:** Use `MaterialTheme.spacing` semantic spacing aliases (`pageSectionGapSmall`, `pageHorizontal`) to align pickers and charts.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3

## Global Constraints
* Keep `core:designsystem` spacing single source of truth.
* Add `import app.readylytics.health.core.designsystem.spacing` to all modified Compose files.

---

### Task 1: Align Sleep Screen Spacing

**Files:**
* Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`

**Interfaces:**
* Consumes: Spacing aliases from `core:designsystem`
* Produces: Clean picker-to-chart spacing on the Sleep Screen

- [ ] **Step 1: Modify `SleepScreen.kt`**
Change the vertical height spacer between the `SingleChoiceSegmentedButtonRow` and `SleepTrendCard`/`SleepTrendSkeleton` from `MaterialTheme.spacing.pageSectionGap` to `MaterialTheme.spacing.pageSectionGapSmall`.
```kotlin
<<<<
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

        if (uiState.isLoading) {
            SleepTrendSkeleton(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal))
        } else {
            SleepTrendCard(
====
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

        if (uiState.isLoading) {
            SleepTrendSkeleton(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal))
        } else {
            SleepTrendCard(
>>>>
```

- [ ] **Step 2: Compile and verify feature/sleep**
Run:
```powershell
.\gradlew :feature:sleep:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit Task 1**
```bash
git add feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt
git commit -m "refactor: align sleep screen chart picker spacing"
```

---

### Task 2: Clean Spacing in Vitals Screen

**Files:**
* Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`

**Interfaces:**
* Consumes: Spacing aliases from `core:designsystem`
* Produces: Clean picker-to-chart spacing on the Vitals Screen

- [ ] **Step 1: Modify `VitalsScreen.kt`**
Clean up raw `MaterialTheme.spacing.small` and `.medium` layout usages to semantic aliases:
```kotlin
<<<<
            // Time Range selection
            SectionHeader(
                title = stringResource(R.string.label_physiological_trends),
                enabled = !uiState.isLoading,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.small))
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.medium),
            ) {
...
            }

            Spacer(Modifier.height(MaterialTheme.spacing.small))

            // Chart 1: HRV Trend
            CardLoader(
                isLoading = uiState.isLoading,
                skeleton = {
                    SkeletonCard(
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
                        height = 250.dp,
                    )
                },
                content = {
                    val isCalibrating = uiState.latestSummary?.isCalibrating ?: false
                    TrendCard(
                        title = stringResource(R.string.label_hrv_rmssd),
                        modifier =
                            Modifier
                                .padding(horizontal = MaterialTheme.spacing.medium)
                                .graphicsLayer { },
                    ) {
====
            // Time Range selection
            SectionHeader(
                title = stringResource(R.string.label_physiological_trends),
                enabled = !uiState.isLoading,
            )
            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
            SingleChoiceSegmentedButtonRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
...
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

            // Chart 1: HRV Trend
            CardLoader(
                isLoading = uiState.isLoading,
                skeleton = {
                    SkeletonCard(
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                        height = 250.dp,
                    )
                },
                content = {
                    val isCalibrating = uiState.latestSummary?.isCalibrating ?: false
                    TrendCard(
                        title = stringResource(R.string.label_hrv_rmssd),
                        modifier =
                            Modifier
                                .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                                .graphicsLayer { },
                    ) {
>>>>
```

Also, verify any other instances of `MaterialTheme.spacing.medium` and `MaterialTheme.spacing.small` related to physiological trends section (like Chart 2 - RHR Trend) are mapped to `pageHorizontal` and `pageSectionGapSmall` respectively in the file.

- [ ] **Step 2: Compile and verify feature/vitals**
Run:
```powershell
.\gradlew :feature:vitals:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit Task 2**
```bash
git add feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt
git commit -m "refactor: clean spacing aliases in vitals screen"
```

---

### Task 3: Migrate Workouts Screen Spacing

**Files:**
* Modify: `feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutStatsSection.kt`

**Interfaces:**
* Consumes: Spacing aliases from `core:designsystem`
* Produces: Clean picker-to-chart spacing on the Workouts Screen

- [ ] **Step 1: Modify `WorkoutStatsSection.kt`**
Add design system spacing import:
```kotlin
import app.readylytics.health.core.designsystem.spacing
```
Replace the hardcoded spacing values:
```kotlin
<<<<
        Spacer(Modifier.height(8.dp))
        SectionHeader(
            title = stringResource(R.string.workout_stats_acwr_title),
            enabled = !uiState.isLoading,
        )
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        ) {
...
        }

        Spacer(Modifier.height(16.dp))
====
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SectionHeader(
            title = stringResource(R.string.workout_stats_acwr_title),
            enabled = !uiState.isLoading,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        ) {
...
        }

        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
>>>>
```

Also, update `SkeletonCard` padding at line 253 inside `WorkoutStatsSection.kt` from `horizontal = 16.dp` to `horizontal = MaterialTheme.spacing.pageHorizontal`.

- [ ] **Step 2: Compile and verify feature/workouts**
Run:
```powershell
.\gradlew :feature:workouts:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit Task 3**
```bash
git add feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutStatsSection.kt
git commit -m "refactor: align workouts acwr chart picker spacing"
```

---

### Task 4: Whole-Change Verification

**Files:**
* Verify touched files

- [ ] **Step 1: Run formatting, unit tests, and lint**
Run:
```powershell
.\gradlew ktlintFormat
.\gradlew testDebugUnitTest
.\gradlew lint
```
Expected: BUILD SUCCESSFUL (0 errors/warnings)

- [ ] **Step 2: Commit verified state**
```bash
git commit --allow-empty -m "style: verified picker-to-chart spacing alignment is correct"
```
