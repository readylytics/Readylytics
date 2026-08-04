# Universal Metric Card Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the dashboard metric card into `core/ui` and replace custom legacy cards across the Sleep and Vitals tabs to reduce LoC and ensure visual consistency.

**Architecture:** Move presentation models and card composables to `core/ui/components/metriccard`. Features will map their domain states to the generic `UniversalMetricPresentation` and render via the shared `UniversalMetricCard`.

**Tech Stack:** Kotlin, Compose M3

## Global Constraints
No logic changes to metrics or scores. Reusing M3 theme containers. Maintain all unit test coverage.

---

### Task 1: Extract Presentation Models to Core UI

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricPresentation.kt`
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalCardDisplayMode.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricPresentation.kt` (delete it)

**Interfaces:**
- Produces: `UniversalMetricPresentation`, `UniversalMetricVisual`, `UniversalMetricUnavailableReason`, `UniversalCardDisplayMode` for next tasks.

- [x] **Step 1: Write Core UI Models**
Copy contents of `DashboardMetricPresentation.kt` and rename classes: `UniversalMetricPresentation`, `UniversalMetricVisual`, `UniversalMetricUnavailableReason`.

- [x] **Step 2: Write UniversalCardDisplayMode enum**
```kotlin
package app.readylytics.health.core.ui.components.metriccard

enum class UniversalCardDisplayMode {
    GAUGE, BAR, VALUE
}
```

- [x] **Step 3: Delete old DashboardMetricPresentation**
Remove `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricPresentation.kt` and fix compiler errors in dashboard if needed (or leave for next tasks if moving in steps).

- [x] **Step 4: Commit**
```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricPresentation.kt
git commit -m "refactor: extract UniversalMetricPresentation to core/ui"
```

---

### Task 2: Create UniversalMetricCardSpec in Core UI

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricCardSpec.kt`

**Interfaces:**
- Produces: `UniversalMetricCardSpec` data class

- [x] **Step 1: Write UniversalMetricCardSpec**
```kotlin
package app.readylytics.health.core.ui.components.metriccard

data class UniversalMetricCardSpec(
    val supportedModes: List<UniversalCardDisplayMode>,
    val usesDeltaPill: Boolean = false
)
```

- [x] **Step 2: Commit**
```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricCardSpec.kt
git commit -m "feat: add UniversalMetricCardSpec"
```

---

### Task 3: Extract DashboardMetricCard and Renderers to Core UI

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricCard.kt`
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/UniversalMetricRenderers.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCard.kt` (delete it)
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricRenderers.kt` (delete it)
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricScalePreparer.kt` (move to core/ui)

**Interfaces:**
- Consumes: Models from Task 1 and 2.
- Produces: `UniversalMetricCard` composable.

- [x] **Step 1: Move components and rename**
Copy `DashboardMetricCard`, `DashboardMetricRenderers`, and `DashboardMetricScalePreparer` into `core/ui/components/metriccard/`. Rename `Dashboard` prefixes to `Universal` (e.g. `UniversalMetricCard`, `UniversalGaugeRenderer`). Update references to use `UniversalMetricCardSpec` and `UniversalMetricPresentation`.

- [x] **Step 2: Refactor UniversalMetricCard signature**
Make the signature generic:
```kotlin
@Composable
fun UniversalMetricCard(
    presentation: UniversalMetricPresentation,
    specification: UniversalMetricCardSpec,
    requestedMode: UniversalCardDisplayMode,
    isEditing: Boolean = false,
    onModeSelected: (UniversalCardDisplayMode) -> Unit = {},
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
)
```

- [x] **Step 3: Commit**
```bash
git add core/ui/ feature/dashboard/
git commit -m "refactor: extract UniversalMetricCard components to core/ui"
```

---

### Task 4: Migrate Dashboard Feature

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/usecase/DashboardMetricPresentationFactory.kt`

- [x] **Step 1: Update Presentation Factory**
Change `DashboardMetricPresentationFactory` to construct and return `UniversalMetricPresentation` instances instead of `DashboardMetricPresentation`. 

- [x] **Step 2: Update Dashboard Screen Rendering**
In `DashboardScreen.kt`, map `DashboardCardSpec` to `UniversalMetricCardSpec` before passing it to `UniversalMetricCard`.

- [x] **Step 3: Run Tests & Commit**
```bash
./gradlew feature:dashboard:testDebugUnitTest
git commit -am "refactor: migrate dashboard to use UniversalMetricCard"
```

---

### Task 5: Migrate Sleep Tab

**Files:**
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`

- [x] **Step 1: Replace M3ScoreGaugeCard with UniversalMetricCard**
In `SleepScreen.kt`, replace the `M3ScoreGaugeCard` usages with `UniversalMetricCard`. Map the `sleepScore` and `progress` state into `UniversalMetricPresentation`. Set `requestedMode = UniversalCardDisplayMode.GAUGE`.

- [x] **Step 2: Replace MetricCard & CircadianConsistencyCard**
In `MetricsGrid`, replace `MetricCard` and `CircadianConsistencyCard` usages with `UniversalMetricCard`. Map data to `UniversalMetricPresentation` and use `UniversalCardDisplayMode.VALUE`.

- [x] **Step 3: Run Tests & Commit**
```bash
./gradlew feature:sleep:testDebugUnitTest
git commit -am "refactor: migrate sleep screen to UniversalMetricCard"
```

---

### Task 6: Migrate Vitals Tab

**Files:**
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`

- [x] **Step 1: Replace old cards in Vitals**
In `VitalsScreen.kt` (and its components like `VitalsGaugeRow.kt` if applicable), replace legacy cards with `UniversalMetricCard`. Map Vitals states to `UniversalMetricPresentation` and select the appropriate mode (`VALUE` or `GAUGE`).

- [x] **Step 2: Run Tests & Commit**
```bash
./gradlew feature:vitals:testDebugUnitTest
git commit -am "refactor: migrate vitals screen to UniversalMetricCard"
```

---

### Task 7: Cleanup Legacy Cards

**Files:**
- Delete: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/MetricCard.kt`
- Delete: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/M3ScoreGaugeCard.kt`
- Delete: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/CircadianConsistencyCard.kt`

- [x] **Step 1: Delete files**
Remove the unused components.

- [x] **Step 2: Verify Build & Commit**
```bash
./gradlew assembleDebug
git commit -am "refactor: remove legacy metric cards"
```
