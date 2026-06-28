# Complete Feature Modularization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace dashboard-only modularization pilot with eight complete feature modules while reducing `:app` to Android entry points, navigation, DI composition, workers, and infrastructure implementations.

**Architecture:** Build shared Compose foundations and pure presentation ports first, then extract cohesive features one PR at a time using a strangler sequence. `:app` remains runnable after every task; features depend only on `:core:designsystem`, `:core:ui`, `:core:model`, and `:core:scoring` where required. No feature depends on `:app`, infrastructure modules, or another feature.

**Tech Stack:** Kotlin 2.3.21, AGP 9.2.1, Gradle Kotlin DSL, Jetpack Compose Material 3, Hilt/KSP, Room, Health Connect, WorkManager, Vico, JUnit, MockK, Robolectric, Konsist, JaCoCo.

---

## Scope, prerequisites, and invariants

Execute after Phase 4 convention-plugin and accessibility tasks are merged and green. Use an isolated worktree created with `superpowers:using-git-worktrees` when implementation starts. Keep each numbered task as one reviewable commit or PR; do not combine feature extractions.

Non-negotiable invariants:

- Room remains single source of truth; Health Connect remains ingestion-only.
- Pull-to-refresh still calls `ForegroundSyncController.triggerDailySync()` and syncs current day only.
- Historical resync remains `HealthResyncWorker`/WorkManager-backed with shared progress.
- Scoring formulas, coefficients, thresholds, and outputs remain byte-for-byte unchanged.
- `CancellationException` remains rethrown.
- Feature modules never import DAOs, Health Connect, WorkManager, DataStore, concrete repositories, `:app`, or other features.
- `internal-docs/DATA_FLOW.md` changes in every structural PR touching ingestion, Room, scoring, or module topology.
- New files require `codegraph index`; structural moves require `codegraph sync`.

## Final file and module structure

```text
build-logic/src/main/kotlin/
  readylytics.compose-library-conventions.gradle.kts
  readylytics.compose-feature-conventions.gradle.kts

core/designsystem/src/main/kotlin/app/readylytics/health/core/designsystem/
core/ui/src/main/kotlin/app/readylytics/health/core/ui/

feature/about/src/main/kotlin/app/readylytics/health/feature/about/
feature/insights/src/main/kotlin/app/readylytics/health/feature/insights/
feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/
feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/
feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/
feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/
feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/
feature/onboarding/src/main/kotlin/app/readylytics/health/feature/onboarding/

app/src/main/kotlin/app/readylytics/health/
  MainActivity.kt
  HealthDashboardApplication.kt
  PrivacyRationaleActivity.kt
  di/**
  workers/**
  data/**
  ui/navigation/**
  ui/scaffold/**
  ui/sync/SyncViewModel.kt
  ui/scaffold/ThemeViewModel.kt
```

## Shared ownership decisions

Move these cross-feature types before feature extraction:

- `DateSwitcher` from dashboard to `:core:ui` because dashboard, sleep, workouts, and vitals use it.
- `HeartRateDaySummary` and `HrSample` to `:core:ui` presentation models.
- `Baselines` to `:core:ui` presentation models.
- `HeightInputField` and `UnitSystemSelector` to `:core:ui` because settings and onboarding use them.
- `HeartRateCard` to `:feature:dashboard`; it is a dashboard card, not vitals detail UI.
- `HrTimelineChart` and all blood-pressure/weight/body-fat/steps components to `:feature:vitals`.
- `InsightDetailSheet` remains owned by `:feature:insights`; app shell supplies it to dashboard through a composable slot so dashboard does not depend on insights.

Move these shared string keys to `:core:ui`; keep every other string with its owning feature:

```text
accessibility_password_hide
accessibility_password_show
action_dismiss
back
dashboard_no_data
delta_down
delta_no_change
delta_up
error_backup_restore_validation
error_backup_wrong_password
gender_female
gender_male
gender_other
gender_prefer_not_to_say
heart_rate_title
hr_zone_n
label_gender
message_no_data_available
onboarding_dynamic_color_desc
onboarding_dynamic_color_label
restore_partial_success_message
```

---

### Task 1: Capture prerequisite and performance baseline

**Files:**
- Create: `internal-docs/FEATURE_MODULARIZATION_BASELINE.md`
- Inspect: `docs/superpowers/plans/2026-06-26-phase-4-long-term-hardening.md`
- Inspect: `settings.gradle.kts`
- Inspect: `app/build.gradle.kts`
- Inspect: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Verify prerequisite tasks and clean worktree**

Run:

```powershell
git status --short
Select-String -Path docs\superpowers\plans\2026-06-26-phase-4-long-term-hardening.md -Pattern '^## Task 1:|^## Task 6:|^- \[ \]|^- \[x\]'
```

Expected: worktree clean; convention-plugin and accessibility tasks show completed checkboxes. Stop if either prerequisite remains incomplete.

- [x] **Step 2: Run pre-move verification baseline**

Run:

```powershell
.\gradlew ktlintFormat
.\gradlew testDebugUnitTest
.\gradlew lint
.\gradlew :app:assembleDebug
.\gradlew jacocoTestReport
.\gradlew jacocoCoverageVerification
```

Expected: all commands pass before topology work starts.

- [x] **Step 3: Measure three clean and three incremental builds**

Run each timed command three times and record elapsed seconds:

```powershell
.\gradlew clean
Measure-Command { .\gradlew :app:assembleDebug --no-build-cache }
Measure-Command { .\gradlew :app:compileDebugKotlin }
```

For incremental measurement, touch `app/src/main/kotlin/app/readylytics/health/ui/about/AboutComponents.kt`, run `:app:compileDebugKotlin`, then restore timestamp/content without changing Git state.

- [x] **Step 4: Write baseline document**

Create `internal-docs/FEATURE_MODULARIZATION_BASELINE.md` with this structure and measured values:

```markdown
# Feature Modularization Baseline

## Commit

- SHA: output of `git rev-parse HEAD`
- Date: implementation date

## Modules

`:app`, `:core:model`, `:core:scoring`, `:core:database`, `:core:healthconnect`

## Verification

- `testDebugUnitTest`: pass
- `lint`: pass
- `:app:assembleDebug`: pass
- `jacocoCoverageVerification`: pass

## Build timings

Record all three clean-build samples, median clean time, all three incremental samples, and median incremental time.

## Ownership baseline

- App main Kotlin files: command output from `Get-ChildItem app/src/main/kotlin -Recurse -Filter *.kt`
- App test Kotlin files: command output from `Get-ChildItem app/src/test/kotlin -Recurse -Filter *.kt`
- Feature modules: none
```

- [x] **Step 5: Commit baseline**

```powershell
git add internal-docs/FEATURE_MODULARIZATION_BASELINE.md
git commit -m "docs: capture feature modularization baseline" -m "Constraint: Preserve pre-move build and coverage evidence`nConfidence: high`nScope-risk: narrow"
```

---

### Task 2: Add progressive architecture boundary enforcement

**Files:**
- Create: `app/src/test/kotlin/app/readylytics/health/architecture/FeatureModuleArchitectureTest.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/architecture/ExpectedFeatureModules.kt`
- Modify: `app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt`

- [x] **Step 1: Write scanner tests using an isolated fixture**

Create `ExpectedFeatureModules.kt`:

```kotlin
package app.readylytics.health.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureModuleArchitectureTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `declared feature modules exist and are included`() {
        val settings = File(root, "settings.gradle.kts").readText()
        expectedFeatureModules.forEach { name ->
            assertTrue(File(root, "feature/$name/build.gradle.kts").isFile, "Missing :feature:$name")
            assertTrue(settings.contains("include(\":feature:$name\")"), "Settings omit :feature:$name")
        }
    }

    @Test
    fun `existing feature modules have no forbidden project dependencies`() {
        val forbidden = listOf("project(\":app\")", "project(\":core:database\")", "project(\":core:healthconnect\")")
        featureDirectories().forEach { module ->
            val buildScript = File(module, "build.gradle.kts").readText()
            forbidden.forEach { dependency -> assertFalse(buildScript.contains(dependency), "${module.name}: $dependency") }
            featureDirectories().map { it.name }.forEach { peer ->
                assertFalse(buildScript.contains("project(\":feature:$peer\")"), "${module.name} depends on feature $peer")
            }
        }
    }

    @Test
    fun `existing feature sources have no forbidden imports`() {
        val forbidden = listOf(
            "app.readylytics.health.R",
            "app.readylytics.health.data.",
            "app.readylytics.health.workers.",
            "androidx.room.",
            "androidx.health.connect.",
            "androidx.work.",
        )
        featureDirectories().forEach { module ->
            File(module, "src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { source ->
                source.readLines().forEachIndexed { index, line ->
                    if (line.startsWith("import ")) {
                        forbidden.forEach { prefix ->
                            assertFalse(line.contains(prefix), "${source.relativeTo(root)}:${index + 1}: $prefix")
                        }
                        if (line.contains("app.readylytics.health.feature.")) {
                            assertTrue(
                                line.contains("app.readylytics.health.feature.${module.name}."),
                                "${source.relativeTo(root)}:${index + 1}: cross-feature import",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun featureDirectories(): List<File> =
        File(root, "feature").listFiles().orEmpty().filter { File(it, "build.gradle.kts").isFile }.sortedBy(File::getName)
}
```

- [x] **Step 2: Run scanner tests**

Run:

```powershell
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.FeatureModuleArchitectureTest
```

Expected: PASS with no feature modules; fixture-independent rules are now active for every later module.

- [x] **Step 3: Extend clean-architecture source rule**

In `CleanArchTest.kt`, add forbidden feature imports for app data/domain layers and forbid `app.readylytics.health.feature` imports outside `ui/navigation`, `ui/scaffold`, activities, and DI composition. Keep app shell as sole feature composition point.

- [x] **Step 4: Run architecture suite**

```powershell
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.CleanArchTest --tests app.readylytics.health.architecture.FeatureModuleArchitectureTest
```

Expected: PASS.

- [x] **Step 5: Commit boundary enforcement**

```powershell
git add app/src/test/kotlin/app/readylytics/health/CleanArchTest.kt app/src/test/kotlin/app/readylytics/health/architecture
git commit -m "test: enforce feature module boundaries" -m "Constraint: Permit feature imports only from app composition points`nConfidence: high`nScope-risk: narrow"
```

---

### Task 3: Add Compose library and feature convention plugins

**Files:**
- Modify: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/readylytics.compose-library-conventions.gradle.kts`
- Create: `build-logic/src/main/kotlin/readylytics.compose-feature-conventions.gradle.kts`
- Create: `app/src/test/kotlin/app/readylytics/health/build/FeatureConventionPluginPresenceTest.kt`

- [x] **Step 1: Write failing presence test**

```kotlin
package app.readylytics.health.build

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class FeatureConventionPluginPresenceTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `compose and feature conventions exist with required plugins`() {
        val compose = File(root, "build-logic/src/main/kotlin/readylytics.compose-library-conventions.gradle.kts")
        val feature = File(root, "build-logic/src/main/kotlin/readylytics.compose-feature-conventions.gradle.kts")
        assertTrue(compose.isFile)
        assertTrue(feature.isFile)
        assertContains(compose.readText(), "org.jetbrains.kotlin.plugin.compose")
        assertContains(feature.readText(), "com.google.dagger.hilt.android")
        assertContains(feature.readText(), "project(\":core:ui\")")
        assertContains(feature.readText(), "project(\":core:model\")")
    }
}
```

- [x] **Step 2: Run test and verify failure**

```powershell
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.build.FeatureConventionPluginPresenceTest
```

Expected: FAIL because both convention scripts are absent.

- [x] **Step 3: Add plugin artifacts to build logic**

Append to `build-logic/build.gradle.kts` dependencies:

```kotlin
implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:${libs.versions.kotlin.get()}")
implementation("com.google.dagger:hilt-android-gradle-plugin:${libs.versions.hilt.get()}")
implementation("org.jlleitschuh.gradle:ktlint-gradle:${libs.versions.ktlint.get()}")
```

- [x] **Step 4: Create Compose library convention**

Create `readylytics.compose-library-conventions.gradle.kts`:

```kotlin
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

plugins {
    id("readylytics.android-library-conventions")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
    jacoco
}

extensions.configure<LibraryExtension> {
    buildFeatures.compose = true
    testOptions.unitTests.isIncludeAndroidResources = true
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    add("implementation", platform(libs.findLibrary("androidx-compose-bom").get()))
    add("implementation", libs.findLibrary("androidx-compose-ui").get())
    add("implementation", libs.findLibrary("androidx-compose-foundation").get())
    add("implementation", libs.findLibrary("androidx-compose-material3").get())
    add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    add("testImplementation", libs.findLibrary("junit").get())
    add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
}
```

- [x] **Step 5: Create feature convention**

Create `readylytics.compose-feature-conventions.gradle.kts`:

```kotlin
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

plugins {
    id("readylytics.compose-library-conventions")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    add("implementation", project(":core:model"))
    add("implementation", project(":core:ui"))
    add("implementation", libs.findLibrary("hilt-android").get())
    add("ksp", libs.findLibrary("hilt-compiler").get())
    add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
    add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
    add("implementation", libs.findLibrary("androidx-hilt-navigation-compose").get())
    add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
    add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
    add("testImplementation", libs.findLibrary("mockk").get())
    add("testImplementation", libs.findLibrary("robolectric").get())
    add("testImplementation", libs.findLibrary("androidx-junit").get())
    add("testImplementation", libs.findLibrary("androidx-test-core").get())
    add("androidTestImplementation", platform(libs.findLibrary("androidx-compose-bom").get()))
    add("androidTestImplementation", libs.findLibrary("androidx-junit").get())
    add("androidTestImplementation", libs.findLibrary("androidx-compose-ui-test-junit4").get())
    add("androidTestImplementation", libs.findLibrary("mockk-android").get())
    add("debugImplementation", libs.findLibrary("androidx-compose-ui-test-manifest").get())
}
```

- [x] **Step 6: Verify convention build and presence test**

```powershell
.\gradlew :build-logic:build
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.build.FeatureConventionPluginPresenceTest
```

Expected: PASS.

- [x] **Step 7: Index and commit**

```powershell
codegraph index
git add build-logic app/src/test/kotlin/app/readylytics/health/build/FeatureConventionPluginPresenceTest.kt
git commit -m "build: add Compose feature conventions" -m "Constraint: Keep feature dependencies explicit beyond shared UI and model foundations`nConfidence: high`nScope-risk: moderate"
```

---

### Task 4: Extract `:core:designsystem`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `core/designsystem/build.gradle.kts`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/theme/Color.kt`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/theme/Spacing.kt`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/theme/Theme.kt`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/theme/ThemeColorUtils.kt`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/theme/Type.kt`
- Move: `app/src/test/kotlin/app/readylytics/health/ui/theme/ThemeTest.kt`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/theme/ThemeViewModel.kt` to `app/src/main/kotlin/app/readylytics/health/ui/scaffold/ThemeViewModel.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/architecture/DesignSystemOwnershipTest.kt`
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Write failing ownership test**

```kotlin
package app.readylytics.health.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignSystemOwnershipTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `theme primitives belong to core designsystem`() {
        assertTrue(File(root, "core/designsystem/build.gradle.kts").isFile)
        listOf("Color.kt", "Spacing.kt", "Theme.kt", "ThemeColorUtils.kt", "Type.kt").forEach { name ->
            assertFalse(File(root, "app/src/main/kotlin/app/readylytics/health/ui/theme/$name").exists(), name)
            assertTrue(File(root, "core/designsystem/src/main/kotlin/app/readylytics/health/core/designsystem/$name").exists(), name)
        }
    }
}
```

- [x] **Step 2: Run test and verify failure**

```powershell
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.DesignSystemOwnershipTest
```

Expected: FAIL because module and destination files do not exist.

- [x] **Step 3: Add module build and dependencies**

Add `include(":core:designsystem")` to `settings.gradle.kts`. Create:

```kotlin
plugins {
    id("readylytics.compose-library-conventions")
}

android {
    namespace = "app.readylytics.health.core.designsystem"
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.material.color.utilities)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
```

Add `implementation(project(":core:designsystem"))` to `app/build.gradle.kts`.

- [x] **Step 4: Move primitives and preserve app-owned theme state**

Use `git mv` for five primitive files and `ThemeTest.kt`. Change packages to `app.readylytics.health.core.designsystem`. Move `ThemeViewModel.kt` into app scaffold package, keeping its DataStore-backed state in app. Update imports throughout app and tests.

- [x] **Step 5: Verify module and app**

```powershell
.\gradlew :core:designsystem:testDebugUnitTest
.\gradlew :core:designsystem:lint
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.DesignSystemOwnershipTest
.\gradlew :app:assembleDebug
```

Expected: PASS.

- [x] **Step 6: Update topology docs, index, sync, commit**

Add `:core:designsystem` responsibility and dependency edge to `internal-docs/DATA_FLOW.md`.

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app core/designsystem internal-docs/DATA_FLOW.md
git commit -m "refactor: extract design system module" -m "Constraint: Keep preference-backed ThemeViewModel in app shell`nConfidence: high`nScope-risk: moderate"
```

---

### Task 5: Extract `:core:ui` and resolve shared presentation ownership

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `core/ui/build.gradle.kts`
- Create: `core/ui/src/main/res/values/strings.xml`
- Move selected files from `app/src/main/kotlin/app/readylytics/health/ui/common/**`
- Move selected files from `app/src/main/kotlin/app/readylytics/health/ui/components/**`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/dashboard/DateSwitcher.kt`
- Split: `app/src/main/kotlin/app/readylytics/health/ui/heartrate/HeartRateUiModels.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/sleep/SleepViewModel.kt`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/settings/HeightInputField.kt`
- Move: `app/src/main/kotlin/app/readylytics/health/ui/settings/common/UnitSystemSelector.kt`
- Move matching tests from `app/src/test/kotlin/app/readylytics/health/ui/common/**` and `ui/components/**`
- Create: `app/src/test/kotlin/app/readylytics/health/architecture/CoreUiOwnershipTest.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Write failing ownership test**

Create `CoreUiOwnershipTest.kt` asserting module exists and app no longer owns `DailyDataPoint.kt`, `TimeRange.kt`, `UiText.kt`, `DateSwitcher.kt`, `HeightInputField.kt`, or `UnitSystemSelector.kt`.

- [x] **Step 2: Run test and verify failure**

```powershell
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.CoreUiOwnershipTest
```

Expected: FAIL because `:core:ui` is absent.

- [x] **Step 3: Add core UI module**

Add `include(":core:ui")`. Create:

```kotlin
plugins {
    id("readylytics.compose-library-conventions")
}

android {
    namespace = "app.readylytics.health.core.ui"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
```

Add `implementation(project(":core:ui"))` to app.

- [x] **Step 4: Move generic common files**

Move these files to `core/ui/.../common/` and change package prefix to `app.readylytics.health.core.ui.common`:

```text
BaseViewModel.kt
CardLoader.kt
ChartUtils.kt
Constants.kt
DailyDataPoint.kt
DateFormatUtils.kt
HistoryItems.kt
ScoreDeltaFormatter.kt
ScreenHeaderSection.kt
SkeletonCard.kt
TimeRange.kt
UiText.kt
```

Keep `CardIdExtensionsUi.kt` with dashboard and `RasUtils.kt` with workouts/dashboard until those feature tasks.

- [x] **Step 5: Move generic components**

Move these files to `core/ui/.../components/` and change package prefix to `app.readylytics.health.core.ui.components`:

```text
CanvasChartTooltip.kt
ChartDefaults.kt
DataPointTooltip.kt
DayTimelineScale.kt
DropdownPreferenceItem.kt
HistoryCardLayout.kt
M3ScoreGaugeCard.kt
MetricCard.kt
MetricStatus.kt
MetricTooltip.kt
ReorderableCardGrid.kt
SectionHeader.kt
SettingsToggleItem.kt
StatusLegend.kt
TrendCard.kt
TrendCharts.kt
VicoChartTooltipOverlay.kt
ZoneBandDecoration.kt
ZoneBandUtils.kt
reorder/DragController.kt
```

Keep feature-specific components in app until their owner moves: blood-pressure charts, HR timeline, sleep charts, steps components, card-management components, insight card, physiology pickers, and `RasWeeklyBar`.

- [x] **Step 6: Move cross-feature presentation types**

Move `DateSwitcher.kt` to `core/ui/.../components/DateSwitcher.kt`. Split `HeartRateUiModels.kt` so `HrSample` and `HeartRateDaySummary` live in `core/ui/.../model/HeartRatePresentation.kt`. Move `Baselines` from `SleepViewModel.kt` into `core/ui/.../model/Baselines.kt`. Move `HeightInputField.kt` and `UnitSystemSelector.kt` into `core/ui/.../components/settings/`.

- [x] **Step 7: Move shared strings**

Move exactly the shared keys listed under “Shared ownership decisions” from app `strings.xml` to core UI `strings.xml`. Update moved code to import `app.readylytics.health.core.ui.R`. Leave feature-exclusive keys in app until their feature task.

- [x] **Step 8: Move tests and update all imports**

Move `DailyDataPointTest.kt`, `ScoreDeltaFormatterTest.kt`, `TimeRangeTest.kt`, `DayTimelineScaleTest.kt`, `MetricStatusContainerToneTest.kt`, `ReorderableCardGridThresholdTest.kt`, and `reorder/DragControllerTest.kt` to matching core UI test packages. Move `DateSwitcherTest.kt`, `ChartAccessibilityTest.kt`, and `MetricTooltipTest.kt` to core UI `androidTest`. Keep HR/sleep-specific component tests with their future feature owners.

- [x] **Step 9: Verify no scoring dependency leaked into core UI**

Run:

```powershell
rg -n "app\.readylytics\.health\.domain\.scoring|project\(\":core:scoring\"\)" core\ui
.\gradlew :core:ui:testDebugUnitTest
.\gradlew :core:ui:lint
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.CoreUiOwnershipTest
.\gradlew :app:assembleDebug
```

Expected: `rg` returns no matches; all Gradle tasks pass.

- [x] **Step 10: Update docs, index, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app core/ui internal-docs/DATA_FLOW.md
git commit -m "refactor: extract shared UI module" -m "Constraint: Keep scoring-specific and feature-specific components out of core UI`nConfidence: high`nScope-risk: broad"
```

---

### Task 6: Move remaining pure domain code out of `:app`

**Files:**
- Move to `:core:model`: app domain `backup/**`, `cache/**`, `circadian/**`, `common/**`, `dashboard/**`, `date/**`, `error/**`, `service/**`, `user/**`, `validation/**`
- Move to `:core:scoring`: app domain `calculation/**`, `insights/**`
- Keep in app: `app/src/main/kotlin/app/readylytics/health/domain/security/DatabaseKeyRotator.kt`
- Move matching tests from `app/src/test/kotlin/app/readylytics/health/domain/**`
- Modify: `core/model/build.gradle.kts`
- Modify: `core/scoring/build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `internal-docs/DATA_FLOW.md`
- Create: `app/src/test/kotlin/app/readylytics/health/architecture/AppDomainOwnershipTest.kt`

- [x] **Step 1: Write failing ownership test**

Assert app domain contains only `security/DatabaseKeyRotator.kt`, while listed pure domain directories exist under their target core module.

- [x] **Step 2: Run and verify failure**

```powershell
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.AppDomainOwnershipTest
```

Expected: FAIL with remaining app-domain paths.

- [x] **Step 3: Move pure model/use-case packages and tests**

Use `git mv` preserving package names for this task. Move matching tests to target module test roots. Do not rename domain packages during physical move; avoiding package churn keeps this task behavior-only.

- [x] **Step 4: Move calculation and insight engine packages to scoring**

Move all files under `domain/calculation/**`, `domain/insights/**`, and matching tests to `:core:scoring`. No formula or rule body changes allowed.

- [x] **Step 5: Add only required module dependencies**

Add coroutine/inject dependencies required by moved pure code to core module build files. Do not add Android, Compose, Room, Health Connect, DataStore, or WorkManager dependencies.

- [x] **Step 6: Verify purity and deterministic behavior**

```powershell
rg -n "^import android\.|^import androidx\." core\model\src\main core\scoring\src\main
.\gradlew :core:model:testDebugUnitTest
.\gradlew :core:scoring:testDebugUnitTest
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.AppDomainOwnershipTest
.\gradlew :app:assembleDebug
```

Expected: no Android imports; all tests pass, including scoring determinism suites.

- [x] **Step 7: Update docs, sync, commit**

Update pure-domain ownership and formula-location pointers in `internal-docs/DATA_FLOW.md` without copying formula derivations.

```powershell
codegraph sync
git add app core/model core/scoring internal-docs/DATA_FLOW.md
git commit -m "refactor: move pure domain code into core modules" -m "Constraint: Preserve scoring outputs and package names`nConfidence: high`nScope-risk: broad"
```

---

### Task 7: Introduce feature-facing settings, date, sync, and resync ports

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/preferences/UserPreferencesReader.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/preferences/FeatureSettingsPorts.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/date/SelectedDateStore.kt`
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/sync/FeatureSyncPorts.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/data/preferences/SettingsRepository.kt`
- Modify: `core/database/src/main/kotlin/app/readylytics/health/data/repository/SelectedDateRepository.kt`
- Modify: `core/healthconnect/src/main/kotlin/app/readylytics/health/domain/sync/ForegroundSyncController.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/domain/sync/HealthDataRefreshAdapter.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/domain/sync/HistoricalResyncControllerImpl.kt`
- Create: `app/src/main/kotlin/app/readylytics/health/di/FeaturePortModule.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/architecture/FeaturePortBindingTest.kt`
- Modify: feature-bound ViewModels still in app

- [x] **Step 1: Write failing assignability test**

Create `FeaturePortBindingTest.kt` using `Class.isAssignableFrom` to require concrete settings, selected-date, foreground-sync, refresh, and historical-resync implementations to implement their pure interfaces.

- [x] **Step 2: Add preference read and categorized write ports**

Create:

```kotlin
package app.readylytics.health.domain.preferences

import kotlinx.coroutines.flow.Flow

interface UserPreferencesReader {
    val userPreferences: Flow<UserPreferences>
}
```

Create `FeatureSettingsPorts.kt` with focused interfaces matching existing concrete method signatures:

```kotlin
package app.readylytics.health.domain.preferences

import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.TrimpModel
import java.time.LocalDate

interface AboutPreferences { suspend fun updateAboutDismissed(dismissed: Boolean) }

interface PhysiologySettings {
    suspend fun updateBirthday(date: LocalDate)
    suspend fun updateGender(gender: String?)
    suspend fun updateHeight(heightCm: Float?)
    suspend fun updatePhysiologyProfile(profile: PhysiologyProfile)
}

interface HeartRateZoneSettings {
    suspend fun updateMaxHeartRate(bpm: Int)
    suspend fun updateAutoCalculateMaxHr(enabled: Boolean)
    suspend fun updateManualZoneEditing(enabled: Boolean)
    suspend fun updateZonePercentages(z1Min: Float, z1Max: Float, z2Max: Float, z3Max: Float, z4Max: Float)
    suspend fun updateZoneBpms(z1Min: Int, z1Max: Int, z2Max: Int, z3Max: Int, z4Max: Int)
}

interface SleepSettings {
    suspend fun updateGoalSleepHours(hours: Float)
    suspend fun updateHrvBaselineOverride(rmssdMs: Float?)
    suspend fun updateRhrBaselineOverride(bpm: Float?)
    suspend fun updateRestingHrPercentile(percentile: Int)
    suspend fun updateStrainLoadSourceMode(mode: LoadSourceMode)
    suspend fun updateRasSourceMode(mode: LoadSourceMode)
}

interface ThresholdSettings {
    suspend fun updateHrvOptimalThreshold(value: Float)
    suspend fun updateHrvWarningThreshold(value: Float)
    suspend fun updateRhrOptimalThreshold(value: Float)
    suspend fun updateRhrWarningThreshold(value: Float)
    suspend fun updateConsistencyThresholdMinutes(minutes: Int)
    suspend fun updateConsistencyEvaluationDays(days: Int)
    suspend fun updateConsistencyBaselineDays(days: Int)
}

interface DisplaySettings {
    suspend fun updateAppTheme(theme: AppTheme)
    suspend fun updateDynamicColorEnabled(enabled: Boolean)
    suspend fun updateFallbackThemeColor(color: FallbackThemeColor)
    suspend fun updateCustomPaletteEnabled(enabled: Boolean)
    suspend fun updateCustomPrimaryColor(color: Long)
    suspend fun updateCustomSecondaryColor(color: Long)
    suspend fun updateCustomTertiaryColor(color: Long)
    suspend fun updateUnitSystem(unitSystem: UnitSystem)
    suspend fun updateRasScalingFactor(value: Float)
    suspend fun updateStepGoal(steps: Int)
    suspend fun updateRetentionDaysEnabled(enabled: Boolean)
    suspend fun updateRetentionDays(days: Int)
    suspend fun updateTrimpModel(model: TrimpModel)
    suspend fun updateBanisterMultiplier(value: Float)
    suspend fun updateChengBeta(value: Float)
    suspend fun updateItrimB(value: Float)
}

interface SyncSettings {
    suspend fun updateSyncPreference(pref: SyncPreference)
    suspend fun updateSyncIntervalHours(hours: Int)
    suspend fun updateBackgroundSyncEnabled(enabled: Boolean)
    suspend fun updateBackgroundSyncIntervalMinutes(minutes: Int)
}

interface DeviceSettings {
    suspend fun getAvailableDevices(): List<String>
    suspend fun clearDeviceCache()
    suspend fun updatePrimaryDevice(deviceName: String?)
    suspend fun updateDeviceForDataType(dataTypeKey: String, deviceLabel: String?)
    suspend fun updateDeviceChangeNoticeDismissed(dismissed: Boolean)
}

interface BackupSettings {
    suspend fun updateBackupDirectoryUri(uri: String?)
    suspend fun updateBackupPasswordHash(hash: String?)
    suspend fun updateBackupSchedule(schedule: BackupSchedule)
    suspend fun updateLastBackupTimestamp(timestamp: Long)
}
```

- [x] **Step 3: Add selected-date port**

Create `SelectedDateStore.kt`:

```kotlin
package app.readylytics.health.domain.date

import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

interface SelectedDateStore {
    val selectedDate: StateFlow<LocalDate>
    val earliestDate: StateFlow<LocalDate?>
    suspend fun updateSelectedDate(date: LocalDate)
    suspend fun resetToToday()
    suspend fun advanceTodayIfNeeded()
    suspend fun selectPreviousDay()
    suspend fun selectNextDay()
}
```

Make `core:database` `SelectedDateRepository` implement it without changing behavior. Keep implementation in `:core:database` because earliest-date state reads six DAOs.

- [x] **Step 4: Add pure sync ports and state**

Create `FeatureSyncPorts.kt`:

```kotlin
package app.readylytics.health.domain.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class RecalcProgress(val current: Int, val total: Int)
data class HistoricalResyncState(val running: Boolean, val current: Int, val total: Int)

interface ForegroundSyncGateway {
    val isSyncing: StateFlow<Boolean>
    val recalcProgress: StateFlow<RecalcProgress?>
    val syncCompletedEvent: SharedFlow<Unit>
    suspend fun evaluateAndSync()
    suspend fun triggerImmediateSync()
    suspend fun triggerDailySync()
}

interface HealthDataRefresh { suspend fun refreshAffectedWindow() }

interface HistoricalResyncController {
    val state: Flow<HistoricalResyncState>
    suspend fun requestHistoricalResync()
    fun schedulePeriodicSync(intervalMinutes: Long)
    fun cancelPeriodicSync()
}
```

Move `RecalcProgress` from `ForegroundSyncController.kt` into this core-model file. Implement gateway on existing controller. `HealthDataRefreshAdapter.refreshAffectedWindow()` delegates to existing `HealthSyncUseCase.sync()` with its current default eight-day window. `HistoricalResyncControllerImpl` maps WorkInfo progress keys to pure state and delegates scheduling to `WorkerScheduler`.

- [x] **Step 5: Bind ports in app DI**

Create `FeaturePortModule.kt` with `@Binds` methods for all implemented ports. Concrete `SettingsRepository` implements categorized preference interfaces; feature ViewModels inject only interfaces they use. Change `LocalBackupViewModel` to import existing core-model `domain.security.EncryptionManager`, not concrete app encryption class.

- [x] **Step 6: Convert all feature-bound ViewModels to ports**

Update About, Dashboard, Sleep, Workouts, Vitals, metric-detail, Settings, and Onboarding ViewModels. Remove imports of concrete `data.preferences.SettingsRepository`, `data.repository.SelectedDateRepository`, `HealthSyncUseCase`, `ForegroundSyncController`, `WorkerScheduler`, `HealthResyncWorker`, and concrete `EncryptionManager`.

- [x] **Step 7: Verify bindings and behavior**

```powershell
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.FeaturePortBindingTest
.\gradlew :app:testDebugUnitTest --tests "app.readylytics.health.ui.*"
.\gradlew :app:assembleDebug
```

Expected: PASS; existing ViewModel tests use port mocks and retain assertions.

- [x] **Step 8: Index and commit**

```powershell
codegraph index
codegraph sync
git add app core/model core/database core/healthconnect
git commit -m "refactor: add feature-facing presentation ports" -m "Constraint: Preserve existing sync windows, WorkManager resync, and preference semantics`nConfidence: high`nScope-risk: broad"
```

---

### Task 8: Extract `:feature:about`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/test/kotlin/app/readylytics/health/architecture/ExpectedFeatureModules.kt`
- Create: `feature/about/build.gradle.kts`
- Move: all seven files under `app/src/main/kotlin/app/readylytics/health/ui/about/`
- Create: `feature/about/src/test/kotlin/app/readylytics/health/feature/about/AboutViewModelTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/scaffold/MainNavHost.kt`
- Move: all `about_*` strings, `action_continue_to_app`, and About-exclusive resources
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Add `about` to expected module set and run failing test**

Set `expectedFeatureModules = setOf("about")`, then run architecture test. Expected: FAIL with missing `:feature:about`.

- [x] **Step 2: Create module**

```kotlin
plugins { id("readylytics.compose-feature-conventions") }

android { namespace = "app.readylytics.health.feature.about" }

dependencies { implementation(libs.play.services.oss.licenses) }
```

Include module in settings and add app dependency.

- [x] **Step 3: Move sources, resources, and test**

Move `AboutComponents.kt`, `AboutScreen.kt`, `AboutViewModel.kt`, `AppInfoSection.kt`, `ContributorsSection.kt`, `FeedbackSection.kt`, and `LicenseSection.kt`. Rename package to `app.readylytics.health.feature.about`. Add ViewModel test proving dismissal calls `AboutPreferences.updateAboutDismissed(true)` once.

- [x] **Step 4: Wire app navigation and verify**

Update `MainNavHost` import. Run:

```powershell
.\gradlew :feature:about:testDebugUnitTest
.\gradlew :feature:about:lint
.\gradlew :feature:about:compileDebugKotlin
.\gradlew :app:assembleDebug
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.FeatureModuleArchitectureTest
```

- [x] **Step 5: Docs, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app feature/about internal-docs/DATA_FLOW.md
git commit -m "refactor: extract about feature module" -m "Constraint: Preserve About copy and documentation drift checks`nConfidence: high`nScope-risk: moderate"
```

---

### Task 9: Extract `:feature:insights`

**Files:**
- Modify: module settings/dependencies and expected module set
- Create: `feature/insights/build.gradle.kts`
- Move: `InsightDetailRepository.kt`, `InsightDetailResourceSpec.kt`, `InsightDetailSheet.kt`
- Move tests: `InsightDetailContentWordingTest.kt`, `InsightDetailResourceSpecTest.kt`
- Move: all `insight_*` and `confidence_*` strings owned exclusively by insights
- Modify: `internal-docs/DATA_FLOW.md`

- [x] **Step 1: Expect `about` and `insights`; verify red**

Set expected set to `setOf("about", "insights")`. Run architecture test; expect missing module failure.

- [x] **Step 2: Create module**

```kotlin
plugins { id("readylytics.compose-feature-conventions") }
android { namespace = "app.readylytics.health.feature.insights" }
dependencies { implementation(project(":core:scoring")) }
```

- [x] **Step 3: Move code, tests, and resources**

Rename package to `app.readylytics.health.feature.insights`. Keep `InsightDetailSheet` public as app composition API. Do not add dashboard dependency.

- [x] **Step 4: Verify wording and independent compile**

```powershell
.\gradlew :feature:insights:testDebugUnitTest
.\gradlew :feature:insights:lint
.\gradlew :feature:insights:compileDebugKotlin
.\gradlew :app:assembleDebug
```

- [x] **Step 5: Docs, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app feature/insights internal-docs/DATA_FLOW.md
git commit -m "refactor: extract insights feature module" -m "Constraint: Dashboard integration remains app-composed; no feature-to-feature edge`nConfidence: high`nScope-risk: moderate"
```

---

### Task 10: Extract `:feature:sleep`

**Files:**
- Create: `feature/sleep/build.gradle.kts`
- Move: all seven files under `ui/sleep/`
- Move from components: `SleepArchitectureBar.kt`, `SleepStageBreakdownRow.kt`, `SleepStagesChart.kt`
- Move tests: `SleepTimeGaugeDataTest.kt`, `SleepViewModelTest.kt`, `SleepStagesChartTest.kt`
- Move sleep-exclusive strings
- Modify: `MainNavHost.kt`, module settings/dependencies, expected module set, `DATA_FLOW.md`

- [ ] **Step 1: Add `sleep` to expected set and verify red**

Expected module set becomes `about`, `insights`, `sleep`. Architecture test must fail before module creation.

- [ ] **Step 2: Create module**

```kotlin
plugins { id("readylytics.compose-feature-conventions") }
android { namespace = "app.readylytics.health.feature.sleep" }
dependencies {
    implementation(project(":core:scoring"))
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
}
```

- [ ] **Step 3: Move sources, tests, and strings**

Rename packages to `app.readylytics.health.feature.sleep`. Import shared `DateSwitcher`, `Baselines`, common charts, and strings from core UI. Keep no vitals dependency.

- [ ] **Step 4: Wire route and verify**

```powershell
.\gradlew :feature:sleep:testDebugUnitTest
.\gradlew :feature:sleep:lint
.\gradlew :feature:sleep:compileDebugKotlin
.\gradlew :app:assembleDebug
.\gradlew testDebugUnitTest
```

- [ ] **Step 5: Docs, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app feature/sleep internal-docs/DATA_FLOW.md
git commit -m "refactor: extract sleep feature module" -m "Constraint: Preserve sleep scoring and foreground-sync state semantics`nConfidence: high`nScope-risk: broad"
```

---

### Task 11: Extract `:feature:workouts`

**Files:**
- Create: `feature/workouts/build.gradle.kts`
- Move: all 19 files under `ui/workouts/**`
- Move from components: `RasWeeklyBar.kt`
- Move from common: `RasUtils.kt` if dashboard no longer imports it; otherwise split presentation-only formatter into core UI
- Move tests: all five workout test files and `mappers/WorkoutMapperTest.kt`
- Move workout-exclusive strings
- Modify: `MainNavHost.kt`, module settings/dependencies, expected set, `DATA_FLOW.md`

- [ ] **Step 1: Add `workouts` to expected set and verify red**

Run architecture test; expect missing module.

- [ ] **Step 2: Create module**

```kotlin
plugins { id("readylytics.compose-feature-conventions") }
android { namespace = "app.readylytics.health.feature.workouts" }
dependencies {
    implementation(project(":core:scoring"))
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
}
```

- [ ] **Step 3: Move list/detail sources, mapper tests, and resources**

Rename packages to `app.readylytics.health.feature.workouts`. Use core UI `DateSwitcher`; keep `WorkoutDetailRoute(workoutId, onBack)` as public app composition API.

Add `WorkoutDetailViewModelTest` cases proving an unknown workout ID produces existing controlled error state and never throws, and valid ID survives `SavedStateHandle` recreation.

- [ ] **Step 4: Wire list/detail routes and verify**

```powershell
.\gradlew :feature:workouts:testDebugUnitTest
.\gradlew :feature:workouts:lint
.\gradlew :feature:workouts:compileDebugKotlin
.\gradlew :app:assembleDebug
.\gradlew testDebugUnitTest
```

- [ ] **Step 5: Docs, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app feature/workouts internal-docs/DATA_FLOW.md
git commit -m "refactor: extract workouts feature module" -m "Constraint: Preserve workout detail arguments, TRIMP calculations, and scoring outputs`nConfidence: high`nScope-risk: broad"
```

---

### Task 12: Extract grouped `:feature:vitals`

**Files:**
- Create: `feature/vitals/build.gradle.kts`
- Move: all files under `ui/vitals/`, `ui/heartrate/`, `ui/bloodpressure/`, `ui/weight/`, `ui/bodyfat/`, `ui/steps/`
- Move components: `BloodPressureSplitChart.kt`, `BloodPressureTrendChart.kt`, `HrTimelineChart.kt`, `SingleBloodPressureChart.kt`, `StepsBar.kt`, `StepsCard.kt`
- Move tests: blood-pressure, body-fat, heart-rate, steps, weight ViewModel tests; HR timeline tests
- Move vitals-exclusive strings
- Modify: `MainNavHost.kt`, module settings/dependencies, expected set, `DATA_FLOW.md`

- [x] **Step 1: Add `vitals` to expected set and verify red**

Run architecture test; expect missing module.

- [x] **Step 2: Create module**

```kotlin
plugins { id("readylytics.compose-feature-conventions") }
android { namespace = "app.readylytics.health.feature.vitals" }
dependencies {
    implementation(project(":core:scoring"))
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
}
```

- [x] **Step 3: Move six UI packages into grouped feature namespace**

Use subpackages `overview`, `heartrate`, `bloodpressure`, `weight`, `bodyfat`, and `steps`. Import `HrSample`, `HeartRateDaySummary`, `Baselines`, and `DateSwitcher` from core UI. Move `HeartRateCard.kt` to dashboard-owned staging path in app, not vitals.

- [x] **Step 4: Move tests and resources**

Move exact ViewModel tests and component tests named in Files. Move vitals-exclusive keys; shared keys remain core UI.

- [x] **Step 5: Wire tab and five detail routes**

Update app imports for `VitalsRoute`, `HeartRateDetailRoute`, `BloodPressureDetailRoute`, `WeightDetailRoute`, `BodyFatDetailRoute`, and `StepDetailRoute`.

- [x] **Step 6: Verify grouped module and app**

```powershell
.\gradlew :feature:vitals:testDebugUnitTest
.\gradlew :feature:vitals:lint
.\gradlew :feature:vitals:compileDebugKotlin
.\gradlew :app:assembleDebug
.\gradlew testDebugUnitTest
```

- [x] **Step 7: Docs, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app feature/vitals internal-docs/DATA_FLOW.md
git commit -m "refactor: extract grouped vitals feature module" -m "Constraint: Keep metric detail flows cohesive and dashboard card ownership separate`nConfidence: high`nScope-risk: broad"
```

---

### Task 13: Extract `:feature:dashboard` and app-compose insight details

**Files:**
- Create: `feature/dashboard/build.gradle.kts`
- Move: seven remaining dashboard files after `DateSwitcher` extraction
- Move: staged `HeartRateCard.kt`
- Move from common: `CardIdExtensionsUi.kt`
- Move components: `CardManagementBottomSheet.kt`, `CircadianConsistencyCard.kt`, `EditModeFab.kt`, `EditModeIndicator.kt`, `InsightCard.kt`
- Move tests: `DashboardViewModelTest.kt`, `DashboardScreenTest.kt`, `CardConfigurationsListTest.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/scaffold/MainNavHost.kt`
- Move dashboard-exclusive strings
- Modify: module settings/dependencies, expected set, `DATA_FLOW.md`

- [ ] **Step 1: Add `dashboard` to expected set and verify red**

Run architecture test; expect missing module.

- [ ] **Step 2: Write failing dashboard composition test**

Add test proving dashboard exposes `onOpenInsight(InsightParams)` and accepts an `insightDetail` composable slot without importing insights package.

- [ ] **Step 3: Create module**

```kotlin
plugins { id("readylytics.compose-feature-conventions") }
android { namespace = "app.readylytics.health.feature.dashboard" }
dependencies { implementation(project(":core:scoring")) }
```

- [ ] **Step 4: Remove dashboard-to-insights and dashboard-to-vitals edges**

Move `HeartRateCard` into dashboard. Replace direct `InsightDetailRepository`/`InsightDetailSheet` construction with callback and composable slot. App `MainNavHost` owns selected insight state, obtains detail content through `:feature:insights`, and supplies sheet content to dashboard. Preserve bottom-sheet behavior.

- [ ] **Step 5: Move dashboard sources, tests, and resources**

Rename package to `app.readylytics.health.feature.dashboard`. Keep callbacks for sleep, workouts, vitals, and detail navigation; never import their routes.

- [ ] **Step 6: Verify no feature edge and app behavior**

```powershell
rg -n "feature\.(insights|vitals|sleep|workouts)" feature\dashboard
.\gradlew :feature:dashboard:testDebugUnitTest
.\gradlew :feature:dashboard:lint
.\gradlew :feature:dashboard:compileDebugKotlin
.\gradlew :feature:dashboard:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.readylytics.health.feature.dashboard.DashboardScreenTest
.\gradlew :app:assembleDebug
```

Expected: `rg` has no matches; tests pass.

- [ ] **Step 7: Docs, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app feature/dashboard internal-docs/DATA_FLOW.md
git commit -m "refactor: extract dashboard feature module" -m "Constraint: Compose insight details in app without feature-to-feature dependency`nConfidence: high`nScope-risk: broad"
```

---

### Task 14: Extract `:feature:settings`

**Files:**
- Create: `feature/settings/build.gradle.kts`
- Move: all 26 files under `ui/settings/**` except shared files already in core UI
- Move components: `BirthdayDatePickerField.kt`, `PhysiologyProfilePicker.kt`
- Move tests: all settings unit tests and settings-local Android tests
- Move settings-exclusive strings and resources
- Modify: `MainNavHost.kt`, module settings/dependencies, expected set, `DATA_FLOW.md`

- [ ] **Step 1: Add `settings` to expected set and verify red**

Run architecture test; expect missing module.

- [ ] **Step 2: Create module**

```kotlin
plugins { id("readylytics.compose-feature-conventions") }
android { namespace = "app.readylytics.health.feature.settings" }
dependencies {
    implementation(project(":core:scoring"))
    implementation(libs.androidx.documentfile)
    implementation(libs.material.color.utilities)
}
```

- [ ] **Step 3: Move settings sources and replace infrastructure imports**

Rename package to `app.readylytics.health.feature.settings`. Inject categorized settings ports, `HealthDataRefresh`, `HistoricalResyncController`, backup/restore domain services, and domain `EncryptionManager`. No WorkInfo, worker constants, DataStore, concrete settings repository, or HealthSyncUseCase imports remain.

- [ ] **Step 4: Move tests and resources**

Move HeartRate, Physiology, Settings, Sleep/Threshold, DataSource, GenderSelector, and ValidatingTextField tests. Update mocks to pure ports. Keep global `SyncViewModel` in app shell.

- [ ] **Step 5: Verify settings behavior and boundaries**

```powershell
rg -n "androidx\.work|HealthResyncWorker|WorkerScheduler|HealthSyncUseCase|data\.preferences\.SettingsRepository" feature\settings
.\gradlew :feature:settings:testDebugUnitTest
.\gradlew :feature:settings:lint
.\gradlew :feature:settings:compileDebugKotlin
.\gradlew :app:assembleDebug
.\gradlew testDebugUnitTest
```

Expected: forbidden-import search has no matches; all tests pass.

- [ ] **Step 6: Docs, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app feature/settings internal-docs/DATA_FLOW.md
git commit -m "refactor: extract settings feature module" -m "Constraint: Preserve durable resync, backup, security, validation, and preference behavior`nConfidence: high`nScope-risk: broad"
```

---

### Task 15: Extract `:feature:onboarding`

**Files:**
- Create: `feature/onboarding/build.gradle.kts`
- Move: onboarding screens, route, and ViewModels
- Keep/refactor: `app/src/main/kotlin/app/readylytics/health/ui/onboarding/PrivacyRationaleActivity.kt` into `app/src/main/kotlin/app/readylytics/health/PrivacyRationaleActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Move tests: `OnboardingRestoreViewModelTest.kt`, `OnboardingViewModelTest.kt`, `PrivacyRationaleViewModelTest.kt`
- Move accessibility tests created by Phase 4 Task 6 when feature-local
- Move onboarding-exclusive strings/resources
- Modify: `AppNavHost.kt`, module settings/dependencies, expected set, `DATA_FLOW.md`

- [ ] **Step 1: Add `onboarding` to expected set and verify red**

Expected set now contains all eight modules. Architecture test must fail before module creation.

- [ ] **Step 2: Create module**

```kotlin
plugins { id("readylytics.compose-feature-conventions") }
android { namespace = "app.readylytics.health.feature.onboarding" }
dependencies { implementation(libs.androidx.activity.compose) }
```

- [ ] **Step 3: Split Android entry point from feature UI**

Keep `PrivacyRationaleActivity` as thin app activity. It hosts exported feature composable and contains no onboarding screen implementation. Update manifest activity name to `.PrivacyRationaleActivity`. Move DeviceSelection, restore, onboarding route/screen, and all onboarding ViewModels to feature namespace.

- [ ] **Step 4: Replace settings-feature reuse with core UI**

Import `HeightInputField` and `UnitSystemSelector` from core UI. Inject pure preference/device/backup ports. No settings-feature dependency allowed.

- [ ] **Step 5: Move tests and resources; wire root navigation**

Update `AppNavHost` import. Preserve permission launcher, restore result, process recreation, and completion callbacks.

- [ ] **Step 6: Verify onboarding and permission paths**

```powershell
rg -n "feature\.settings|data\.preferences\.SettingsRepository|androidx\.work" feature\onboarding
.\gradlew :feature:onboarding:testDebugUnitTest
.\gradlew :feature:onboarding:lint
.\gradlew :feature:onboarding:compileDebugKotlin
.\gradlew :app:assembleDebug
.\gradlew testDebugUnitTest
```

Run Phase 4 onboarding accessibility connected test. Expected: forbidden search empty; all tests pass.

- [ ] **Step 7: Docs, sync, commit**

```powershell
codegraph index
codegraph sync
git add settings.gradle.kts app feature/onboarding internal-docs/DATA_FLOW.md
git commit -m "refactor: extract onboarding feature module" -m "Constraint: Keep Android activity entry point in app and preserve permission/restore behavior`nConfidence: high`nScope-risk: broad"
```

---

### Task 16: Reduce app to composition shell and enforce final topology

**Files:**
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/navigation/AppDestination.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/navigation/TabDestination.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/scaffold/MainNavHost.kt`
- Modify: `app/src/main/kotlin/app/readylytics/health/ui/scaffold/MainScaffold.kt`
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/kotlin/app/readylytics/health/architecture/CompleteFeatureTopologyTest.kt`
- Create: `app/src/test/kotlin/app/readylytics/health/architecture/FeatureResourceOwnershipTest.kt`
- Modify: `app/src/androidTest/kotlin/app/readylytics/health/ui/scaffold/MainScaffoldTest.kt`
- Modify: baseline profile and performance tests importing moved packages

- [ ] **Step 1: Write final failing topology test**

```kotlin
package app.readylytics.health.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CompleteFeatureTopologyTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `all eight features exist and app owns no feature presentation package`() {
        val actual = File(root, "feature").listFiles().orEmpty().filter { File(it, "build.gradle.kts").isFile }.map { it.name }.toSet()
        assertEquals(setOf("about", "insights", "sleep", "workouts", "vitals", "dashboard", "settings", "onboarding"), actual)
        val appUi = File(root, "app/src/main/kotlin/app/readylytics/health/ui")
        listOf("about", "insights", "sleep", "workouts", "vitals", "heartrate", "bloodpressure", "weight", "bodyfat", "steps", "dashboard", "settings", "onboarding").forEach {
            assertFalse(File(appUi, it).exists(), "App still owns ui/$it")
        }
    }
}
```

- [ ] **Step 2: Delete obsolete app packages and dependencies**

Remove empty feature directories, duplicate resources, feature-only Compose/Vico dependencies, and obsolete test source paths from app. Keep only navigation, scaffold, global sync, shell theme state, activities, DI, workers, and infrastructure.

Create `FeatureResourceOwnershipTest.kt` that parses every `src/main/res/values/*.xml`, groups `<string name="...">` declarations by module, and fails when one string name has multiple owners. This proves shared keys live only in `:core:ui` and feature-exclusive keys live only with their owner.

```kotlin
package app.readylytics.health.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class FeatureResourceOwnershipTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }
    private val modulePaths = listOf(
        "app", "core/designsystem", "core/ui",
        "feature/about", "feature/insights", "feature/sleep", "feature/workouts",
        "feature/vitals", "feature/dashboard", "feature/settings", "feature/onboarding",
    )

    @Test
    fun `string resources have one owning module`() {
        val declaration = Regex("""<string\s+name="([^"]+)"""")
        val owners = mutableMapOf<String, MutableSet<String>>()
        modulePaths.forEach { module ->
            File(root, "$module/src/main/res").walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { xml ->
                declaration.findAll(xml.readText()).forEach { match ->
                    owners.getOrPut(match.groupValues[1]) { linkedSetOf() }.add(module)
                }
            }
        }
        val duplicates = owners.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "String resources must have one owner: $duplicates")
    }
}
```

- [ ] **Step 3: Make root navigation tests feature-aware**

Update app tests to use feature entry points through app `NavHost`. Verify tab switching, detail bottom-bar visibility, About, onboarding, workout argument, insight sheet, and back navigation.

- [ ] **Step 4: Update performance and baseline-profile imports**

Update `BaselineProfileGenerator.kt`, `DashboardRecompositionTest.kt`, `RenderTest.kt`, and startup tests. Preserve journey selectors and measured behavior.

- [ ] **Step 5: Verify final topology and shell**

```powershell
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.architecture.CompleteFeatureTopologyTest
.\gradlew :app:assembleDebug
.\gradlew testDebugUnitTest
.\gradlew lint
```

Expected: PASS.

- [ ] **Step 6: Sync and commit**

```powershell
codegraph sync
git add app feature core settings.gradle.kts
git commit -m "refactor: reduce app to feature composition shell" -m "Constraint: App retains entry points, navigation, DI, workers, and infrastructure only`nConfidence: high`nScope-risk: broad"
```

---

### Task 17: Aggregate multi-module coverage and prove build isolation

**Files:**
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Modify: `internal-docs/FEATURE_MODULARIZATION_BASELINE.md`
- Create: `app/src/test/kotlin/app/readylytics/health/build/CoverageAggregationPresenceTest.kt`

- [ ] **Step 1: Write failing coverage-presence test**

Assert root build registers `jacocoTestReport` and `jacocoCoverageVerification`, includes every `:feature:*` module, and CI uploads root `build/reports/jacoco/jacocoTestReport`.

- [ ] **Step 2: Move aggregation to root build**

Apply `jacoco` at root. Register aggregate report depending on `testDebugUnitTest` for app, all core modules, and all eight feature modules. Collect each module's Kotlin class directory, source roots, and `.exec` data. Reuse current generated-code exclusions. Preserve overall instruction minimum `0.30` and existing per-package gates.

Use this root configuration, retaining current exclusion list in `coverageExclusions`:

```kotlin
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    jacoco
}

val coverageProjects = listOf(
    ":app",
    ":core:model", ":core:scoring", ":core:database", ":core:healthconnect",
    ":core:designsystem", ":core:ui",
    ":feature:about", ":feature:insights", ":feature:sleep", ":feature:workouts",
    ":feature:vitals", ":feature:dashboard", ":feature:settings", ":feature:onboarding",
)
val coverageExclusions = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*", "**/*Test*.*",
    "android/**/*.*", "**/hilt_aggregated_deps/**", "**/*_Factory*", "**/*_MembersInjector*",
    "**/Dagger*Component*.*", "**/*ComposableSingletons*", "**/databinding/**", "**/di/**",
    "**/*Proto*.*", "**/*Serializer*.*", "**/*$WhenMappings.*",
)
val coveredProjects = coverageProjects.map(::project)
val coveredClasses = coveredProjects.map { module ->
    module.fileTree("${module.layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
        exclude(coverageExclusions)
    }
}
val coveredSources = coveredProjects.flatMap { module ->
    listOf(module.file("src/main/kotlin"), module.file("src/main/java"))
}
val coveredExecutionData = coveredProjects.map { module ->
    module.file("${module.layout.buildDirectory.get()}/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn(coverageProjects.map { "$it:testDebugUnitTest" })
    sourceDirectories.setFrom(coveredSources)
    classDirectories.setFrom(coveredClasses)
    executionData.setFrom(coveredExecutionData.filter(File::exists))
    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacocoTestReport/jacocoTestReport.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/jacocoTestReport/html"))
    }
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("jacocoTestReport")
    sourceDirectories.setFrom(coveredSources)
    classDirectories.setFrom(coveredClasses)
    executionData.setFrom(coveredExecutionData.filter(File::exists))
    violationRules {
        rule { limit { counter = "INSTRUCTION"; value = "COVEREDRATIO"; minimum = "0.30".toBigDecimal() } }
        rule {
            element = "PACKAGE"
            includes = listOf("app.readylytics.health.workers")
            limit { counter = "LINE"; value = "COVEREDRATIO"; minimum = "0.60".toBigDecimal() }
        }
    }
}
```

- [ ] **Step 3: Remove app-only aggregate tasks and update CI paths**

Keep app test coverage enabled, but remove duplicate app-owned aggregate task definitions. Change CI upload path to `build/reports/jacoco/jacocoTestReport/` and label gate as 30%.

- [ ] **Step 4: Verify coverage remains measured**

```powershell
.\gradlew jacocoTestReport
.\gradlew jacocoCoverageVerification
```

Expected: PASS; XML contains packages under `app/readylytics/health/feature/` for all eight modules.

- [ ] **Step 5: Prove feature isolation**

Run a clean `:app:assembleDebug`, change one non-ABI line in `feature/about`, then run:

```powershell
.\gradlew :app:compileDebugKotlin --info
```

Expected: `:feature:about:compileDebugKotlin` and app compile execute; unrelated feature compile tasks remain UP-TO-DATE. Record evidence and post-move three-sample timings in baseline document. Investigate unexplained material regression before continuing.

- [ ] **Step 6: Commit coverage and evidence**

```powershell
git add build.gradle.kts app/build.gradle.kts .github/workflows/ci.yml internal-docs/FEATURE_MODULARIZATION_BASELINE.md app/src/test/kotlin/app/readylytics/health/build/CoverageAggregationPresenceTest.kt
git commit -m "build: aggregate feature coverage and verify isolation" -m "Constraint: Preserve 30 percent instruction gate and package-level checks`nConfidence: high`nScope-risk: moderate"
```

---

### Task 18: Update roadmap pointer and run final verification

**Files:**
- Modify: `docs/superpowers/plans/2026-06-26-phase-4-long-term-hardening.md`
- Modify: `internal-docs/DATA_FLOW.md`
- Verify: `ABOUT.md`, `docs/about.md`, `docs/privacy.md`, `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Replace old Task 7 pilot body with standalone-plan pointer**

Use this text:

```markdown
## Task 7: Complete Feature Modularization

Implementation moved to `docs/superpowers/plans/2026-06-27-complete-feature-modularization.md`.
Execute only after Tasks 1-6 pass. Scope is complete extraction of eight cohesive feature modules,
not a dashboard pilot. Each feature extraction remains an independent green commit/PR.
```

- [ ] **Step 2: Final documentation consistency check**

Confirm `DATA_FLOW.md` shows app shell, core UI/design system, all features, dependency direction, Room single source, Health Connect ingestion-only, and unchanged scoring location. Confirm no user-facing copy changed; if it did, update required About/privacy/site/string documents before proceeding.

- [ ] **Step 3: Run mandatory full verification**

```powershell
.\gradlew ktlintFormat
.\gradlew testDebugUnitTest
.\gradlew lint
.\gradlew :app:assembleDebug
.\gradlew :app:assembleRelease
.\gradlew jacocoTestReport
.\gradlew jacocoCoverageVerification
.\gradlew :app:testDebugUnitTest --tests app.readylytics.health.domain.sync.ForegroundSyncControllerTest
```

Expected: all pass. Foreground sync cancellation test confirms `CancellationException` propagation. Configure required release-signing environment variables before `assembleRelease`.

- [ ] **Step 4: Run connected smoke and performance journeys**

Run navigation, dashboard, settings, onboarding accessibility, baseline-profile, startup, and restore connected tests on emulator/API level used by CI. Expected: all pass with unchanged route and recovery behavior.

- [ ] **Step 5: Scan for forbidden remnants**

```powershell
rg -n "app\.readylytics\.health\.ui\.(about|insights|sleep|workouts|vitals|heartrate|bloodpressure|weight|bodyfat|steps|dashboard|settings|onboarding)" app feature core
rg -n "project\(\":(app|core:database|core:healthconnect|feature:)" feature
rg -n "T[B]D|T[O]DO|pilot dashboard|temporary adapter|compatibility adapter" feature core app internal-docs docs\superpowers\plans\2026-06-27-complete-feature-modularization.md
```

Expected: no obsolete package imports, forbidden module edges, placeholders, or compatibility adapters. Historical explanation and explicit “not a pilot” wording in plan are allowed.

- [ ] **Step 6: Re-index, sync, verify status, commit**

```powershell
codegraph index
codegraph sync
git status --short
git add docs/superpowers/plans/2026-06-26-phase-4-long-term-hardening.md internal-docs/DATA_FLOW.md ABOUT.md docs/about.md docs/privacy.md app/src/main/res/values/strings.xml
git commit -m "docs: finalize complete feature modularization" -m "Constraint: Keep data-flow and public documentation synchronized with final topology`nConfidence: high`nScope-risk: moderate"
```

Expected: commit contains only files that changed; omit unchanged About/privacy/site files from staging.

## Final acceptance checklist

- [ ] Eight feature modules compile independently.
- [ ] App owns no feature screen or feature ViewModel.
- [ ] No feature-to-feature, feature-to-app, feature-to-database, or feature-to-healthconnect edge exists.
- [ ] Shared UI has no scoring or infrastructure dependency.
- [ ] App navigation, deep links, process recreation, insight sheet, and bottom-bar behavior remain unchanged.
- [ ] Pull-to-refresh remains current-day-only.
- [ ] Historical resync remains WorkManager-backed with determinate progress.
- [ ] Backup/restore, permission recovery, and settings validation remain unchanged.
- [ ] Scoring determinism and documentation drift suites pass.
- [ ] Coverage includes moved classes and 30% overall gate passes.
- [ ] Feature-only edit leaves unrelated feature compile tasks up-to-date.
- [ ] Release/R8 and connected smoke verification pass.
- [ ] `internal-docs/DATA_FLOW.md` matches final module graph.
- [ ] No temporary adapters, duplicate resources, obsolete packages, or dead Gradle dependencies remain.
