# Manage-Cards Shared Component Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the dashboard/sleep/vitals "manage cards" bottom sheets into one shared `ManagementBottomSheet` (plus shared `ModeSpec`, display-mode conversions, and an edit-mode screen scaffold) so future tabs can add manage-card UI with minimal code.

**Architecture:** Config-agnostic section/item model. A generic `ManagementBottomSheet` in `core/ui` renders header → optional tabs → rows (label + optional display-style dropdown + visibility checkbox) → Done. Each tab's sheet becomes a thin builder that maps its configs into `ManagementSection`s. Display-mode "Default" (reset) is unified via a nullable dropdown + nullable plumbing.

**Tech Stack:** Kotlin, Jetpack Compose (M3), Robolectric Compose tests, JUnit4.

**Spec:** `docs/superpowers/specs/2026-08-13-manage-cards-shared-component-design.md`

**Global constraints:** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` must pass at the end of each task. New files require `codegraph index` (Task 9). Target ≤400 lines/file.

---

### Task 1: Shared `ModeSpec` + `resolveRequestedMode` (core/model)

Replace the two parallel spec types (`DashboardCardSpec`, `SleepCardSpec`) with one shared `ModeSpec`, and DRY the requested→effective mode resolution.

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/ModeSpec.kt`
- Create: `core/model/src/test/kotlin/app/readylytics/health/domain/dashboard/ModeSpecTest.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalog.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepCardCatalog.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardToUniversalMapper.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardPreviews.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt`
- Modify: `core/model/src/test/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalogTest.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardWrapper.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTestBase.kt`

- [ ] **Step 1: Write the failing test**

Create `core/model/src/test/kotlin/app/readylytics/health/domain/dashboard/ModeSpecTest.kt`:

```kotlin
package app.readylytics.health.domain.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeSpecTest {
    private val allModes =
        ModeSpec(
            legacyDefaultMode = DashboardCardDisplayMode.VALUE,
            supportedModes = DashboardCardDisplayMode.entries,
        )

    @Test
    fun `null request resolves to legacy default`() {
        assertEquals(DashboardCardDisplayMode.VALUE, allModes.resolveRequestedMode(null))
    }

    @Test
    fun `supported request resolves to itself`() {
        assertEquals(DashboardCardDisplayMode.BAR, allModes.resolveRequestedMode(DashboardCardDisplayMode.BAR))
    }

    @Test
    fun `unsupported request resolves to legacy default`() {
        val valueOnly =
            ModeSpec(
                legacyDefaultMode = DashboardCardDisplayMode.VALUE,
                supportedModes = listOf(DashboardCardDisplayMode.VALUE),
            )
        assertEquals(
            DashboardCardDisplayMode.VALUE,
            valueOnly.resolveRequestedMode(DashboardCardDisplayMode.GAUGE),
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:model:testDebugUnitTest --tests "app.readylytics.health.domain.dashboard.ModeSpecTest"`
Expected: FAIL — `unresolved reference: ModeSpec`.

- [ ] **Step 3: Create `ModeSpec.kt`**

Create `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/ModeSpec.kt`:

```kotlin
package app.readylytics.health.domain.dashboard

/**
 * Visualization modes a layout item supports, plus its legacy default. Shared by the
 * dashboard/vitals catalog ([DashboardCardCatalog]) and the sleep catalog ([SleepCardCatalog]).
 */
data class ModeSpec(
    val legacyDefaultMode: DashboardCardDisplayMode,
    val supportedModes: List<DashboardCardDisplayMode>,
)

/**
 * Resolve a card's effective mode: an explicit [requested] mode wins when the card supports it,
 * otherwise the card's legacy default applies.
 */
fun ModeSpec.resolveRequestedMode(requested: DashboardCardDisplayMode?): DashboardCardDisplayMode =
    if (requested != null && supportedModes.contains(requested)) requested else legacyDefaultMode
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:model:testDebugUnitTest --tests "app.readylytics.health.domain.dashboard.ModeSpecTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Rewrite `DashboardCardCatalog.kt`**

Replace the ENTIRE contents of `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalog.kt` with:

```kotlin
package app.readylytics.health.domain.dashboard

object DashboardCardCatalog {
    fun spec(cardId: CardId): ModeSpec? = specs[cardId]

    fun requestedMode(configuration: CardConfiguration): DashboardCardDisplayMode =
        spec(configuration.cardId)?.resolveRequestedMode(configuration.requestedDisplayMode)
            ?: DashboardCardDisplayMode.VALUE

    fun applyGlobalDisplayMode(
        configurations: List<CardConfiguration>,
        mode: DashboardCardDisplayMode,
    ): List<CardConfiguration> =
        configurations.map { config ->
            val supported = spec(config.cardId)?.supportedModes.orEmpty()
            if (mode in supported) config.copy(requestedDisplayMode = mode) else config
        }

    fun resetAllDisplayModes(configurations: List<CardConfiguration>): List<CardConfiguration> =
        configurations.map { it.copy(requestedDisplayMode = null) }

    private val ALL_MODES =
        listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.BAR, DashboardCardDisplayMode.VALUE)
    private val ONLY_BAR = listOf(DashboardCardDisplayMode.BAR)
    private val ONLY_VALUE = listOf(DashboardCardDisplayMode.VALUE)

    private val specs: Map<CardId, ModeSpec> =
        mapOf(
            CardId.SLEEP_SCORE to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
            CardId.READINESS to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
            CardId.STEPS to ModeSpec(DashboardCardDisplayMode.BAR, ONLY_BAR),
            CardId.HRV to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_RHR to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_DURATION to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.STRAIN_RATIO to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.RAS_DAILY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.CIRCADIAN_CONSISTENCY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.RESTING_HR to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.SLEEP_EFFICIENCY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.HEART_RATE to ModeSpec(DashboardCardDisplayMode.VALUE, ONLY_VALUE),
            CardId.WEIGHT to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.BODY_FAT to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.BLOOD_PRESSURE to ModeSpec(DashboardCardDisplayMode.VALUE, ONLY_VALUE),
            CardId.OXYGEN_SATURATION to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            CardId.BODY_TEMPERATURE to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
        )
}
```

- [ ] **Step 6: Rewrite `SleepCardCatalog.kt`**

Replace the ENTIRE contents of `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepCardCatalog.kt` with:

```kotlin
package app.readylytics.health.domain.sleep

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.dashboard.ModeSpec
import app.readylytics.health.domain.dashboard.resolveRequestedMode

/**
 * Sleep layout items that are mode-switchable, mirroring the dashboard [DashboardCardCatalog].
 * Full-width top cards (architecture bar, stages timeline, HR chart) and the value-only metric
 * cards (nap duration, nap count) have no spec.
 */
object SleepCardCatalog {
    fun topCardSpec(id: SleepTopCardId): ModeSpec? = topCardSpecs[id]

    fun metricCardSpec(id: SleepMetricCardId): ModeSpec? = metricCardSpecs[id]

    fun requestedTopCardMode(configuration: SleepTopCardConfiguration): DashboardCardDisplayMode =
        topCardSpec(configuration.cardId)?.resolveRequestedMode(configuration.requestedDisplayMode)
            ?: DashboardCardDisplayMode.VALUE

    fun requestedMetricCardMode(configuration: SleepMetricCardConfiguration): DashboardCardDisplayMode =
        metricCardSpec(configuration.cardId)?.resolveRequestedMode(configuration.requestedDisplayMode)
            ?: DashboardCardDisplayMode.VALUE

    fun applyGlobalTopCardMode(
        configurations: List<SleepTopCardConfiguration>,
        mode: DashboardCardDisplayMode,
    ): List<SleepTopCardConfiguration> =
        configurations.map { config ->
            val supported = topCardSpec(config.cardId)?.supportedModes.orEmpty()
            if (mode in supported) config.copy(requestedDisplayMode = mode) else config
        }

    fun applyGlobalMetricCardMode(
        configurations: List<SleepMetricCardConfiguration>,
        mode: DashboardCardDisplayMode,
    ): List<SleepMetricCardConfiguration> =
        configurations.map { config ->
            val supported = metricCardSpec(config.cardId)?.supportedModes.orEmpty()
            if (mode in supported) config.copy(requestedDisplayMode = mode) else config
        }

    fun resetTopCardModes(configurations: List<SleepTopCardConfiguration>): List<SleepTopCardConfiguration> =
        configurations.map { it.copy(requestedDisplayMode = null) }

    fun resetMetricCardModes(configurations: List<SleepMetricCardConfiguration>): List<SleepMetricCardConfiguration> =
        configurations.map { it.copy(requestedDisplayMode = null) }

    private val ALL_MODES =
        listOf(
            DashboardCardDisplayMode.GAUGE,
            DashboardCardDisplayMode.BAR,
            DashboardCardDisplayMode.VALUE,
        )

    private val topCardSpecs: Map<SleepTopCardId, ModeSpec> =
        mapOf(
            SleepTopCardId.SLEEP_SCORE to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
            SleepTopCardId.SLEEP_DURATION_GAUGE to ModeSpec(DashboardCardDisplayMode.GAUGE, ALL_MODES),
        )

    private val metricCardSpecs: Map<SleepMetricCardId, ModeSpec> =
        mapOf(
            SleepMetricCardId.CIRCADIAN_CONSISTENCY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.SLEEP_EFFICIENCY to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.DEEP_SLEEP to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
            SleepMetricCardId.REM_SLEEP to ModeSpec(DashboardCardDisplayMode.VALUE, ALL_MODES),
        )
}
```

- [ ] **Step 7: Update `DashboardToUniversalMapper.kt` (toUniversalSpec signature)**

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardToUniversalMapper.kt`:
- Change the import `app.readylytics.health.domain.dashboard.DashboardCardSpec` → `app.readylytics.health.domain.dashboard.ModeSpec`.
- Replace the `toUniversalSpec` function:

```kotlin
internal fun DashboardCardSpec.toUniversalSpec(): UniversalMetricCardSpec =
    UniversalMetricCardSpec(
        supportedModes = supportedModes.map { it.toUniversalMode() },
        usesDeltaPill = cardId.usesDeltaPill(),
    )
```

with:

```kotlin
internal fun ModeSpec.toUniversalSpec(usesDeltaPill: Boolean): UniversalMetricCardSpec =
    UniversalMetricCardSpec(
        supportedModes = supportedModes.map { it.toUniversalMode() },
        usesDeltaPill = usesDeltaPill,
    )
```

Leave `toUniversalMode`, `toDashboardMode`, and `CardId.usesDeltaPill()` in this file for now (Task 2 moves the conversions).

- [ ] **Step 8: Update `DashboardCardFactory.kt` (toUniversalSpec call site)**

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`, inside `ConfigurableMetricCard` (`private fun ConfigurableMetricCard(...)`), replace:

```kotlin
specification = spec.toUniversalSpec(),
```

with:

```kotlin
specification = spec.toUniversalSpec(cardId.usesDeltaPill()),
```

- [ ] **Step 9: Update `DashboardMetricCardPreviews.kt`**

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardPreviews.kt`, every `X.toUniversalSpec()` call becomes `X.toUniversalSpec(CardId.<SAME_CARD>.usesDeltaPill())`. The `spec` variables there are built from a specific `CardId`; pass that same id. For example, for the sleep-score spec, replace `sleepScoreSpec.toUniversalSpec()` with `sleepScoreSpec.toUniversalSpec(CardId.SLEEP_SCORE.usesDeltaPill())`, and analogously for `sleepDurationSpec` → `CardId.SLEEP_DURATION`, `hrvSpec` → `CardId.HRV`, `weightSpec` → `CardId.WEIGHT`, and any remaining `toUniversalSpec()` calls with their card's `CardId`.

- [ ] **Step 10: Update `SleepLayoutRenderers.kt` (supportedModes helper param)**

In `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt`:
- Change the import `app.readylytics.health.domain.sleep.SleepCardSpec` → `app.readylytics.health.domain.dashboard.ModeSpec`.
- Change the helper signature:

```kotlin
private fun supportedModes(spec: SleepCardSpec?): List<UniversalCardDisplayMode> =
    spec?.supportedModes?.map { it.toUniversalMode() } ?: VALUE_ONLY_MODES
```

to:

```kotlin
private fun supportedModes(spec: ModeSpec?): List<UniversalCardDisplayMode> =
    spec?.supportedModes?.map { it.toUniversalMode() } ?: VALUE_ONLY_MODES
```

(No other change here yet — `toUniversalMode` is still the local private extension until Task 2.)

- [ ] **Step 11: Update `DashboardCardCatalogTest.kt`**

In `core/model/src/test/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalogTest.kt`, delete the `cardId` assertion (the `ModeSpec` no longer carries `cardId`):

```kotlin
assertEquals("Catalog key/spec cardId mismatch for $cardId", cardId, spec.cardId)
```

Everything else in the test (`.legacyDefaultMode`, `.supportedModes`, `requestedMode`, `applyGlobalDisplayMode`, `resetAllDisplayModes`) still compiles unchanged.

- [ ] **Step 12: Update dashboard test helpers**

In `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardWrapper.kt`:
- Change import `DashboardCardSpec` → `ModeSpec`.
- Change the `specification: DashboardCardSpec` parameter to `specification: ModeSpec`.
- Add a `usesDeltaPill: Boolean = false` parameter.
- Replace `specification = specification.toUniversalSpec(),` with `specification = specification.toUniversalSpec(usesDeltaPill),`.

In `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTestBase.kt`:
- Change import `DashboardCardSpec` → `ModeSpec`.
- Replace the `specification` field:

```kotlin
protected val specification =
    DashboardCardSpec(
        cardId = CardId.HRV,
        legacyDefaultMode = DashboardCardDisplayMode.VALUE,
        supportedModes = DashboardCardDisplayMode.entries,
    )
```

with:

```kotlin
protected val specification =
    ModeSpec(
        legacyDefaultMode = DashboardCardDisplayMode.VALUE,
        supportedModes = DashboardCardDisplayMode.entries,
    )
```

- Change the `setMetricCard` signature parameter `specification: DashboardCardSpec = this.specification` → `specification: ModeSpec = this.specification`.

- [ ] **Step 13: Run the full unit-test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (core/model, feature/dashboard, feature/sleep all compile and their tests pass).

- [ ] **Step 14: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/ModeSpec.kt core/model/src/test/kotlin/app/readylytics/health/domain/dashboard/ModeSpecTest.kt core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalog.kt core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepCardCatalog.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardToUniversalMapper.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardPreviews.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt core/model/src/test/kotlin/app/readylytics/health/domain/dashboard/DashboardCardCatalogTest.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardWrapper.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardVisualizationRegressionTestBase.kt
git commit -m "refactor(core): unify ModeSpec and resolveRequestedMode across catalogs"
```

---

### Task 2: Centralize display-mode conversions (core/ui)

Move `DashboardCardDisplayMode ↔ UniversalCardDisplayMode` into core/ui once; delete the three feature-local copies.

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/DisplayModeMappers.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardToUniversalMapper.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardPreviews.kt`
- Modify: `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardWrapper.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsCardFactory.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt`

- [ ] **Step 1: Create `DisplayModeMappers.kt`**

Create `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/DisplayModeMappers.kt`:

```kotlin
package app.readylytics.health.core.ui.components.metriccard

import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode

/** Map the domain display mode to the UI display mode. */
fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode =
    when (this) {
        DashboardCardDisplayMode.GAUGE -> UniversalCardDisplayMode.GAUGE
        DashboardCardDisplayMode.BAR -> UniversalCardDisplayMode.BAR
        DashboardCardDisplayMode.VALUE -> UniversalCardDisplayMode.VALUE
    }

/** Map the UI display mode back to the domain display mode. */
fun UniversalCardDisplayMode.toDashboardMode(): DashboardCardDisplayMode =
    when (this) {
        UniversalCardDisplayMode.GAUGE -> DashboardCardDisplayMode.GAUGE
        UniversalCardDisplayMode.BAR -> DashboardCardDisplayMode.BAR
        UniversalCardDisplayMode.VALUE -> DashboardCardDisplayMode.VALUE
    }
```

- [ ] **Step 2: Delete the copies in `DashboardToUniversalMapper.kt`**

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardToUniversalMapper.kt`, delete the two functions `internal fun DashboardCardDisplayMode.toUniversalMode()` and `internal fun UniversalCardDisplayMode.toDashboardMode()`, and add the import `app.readylytics.health.core.ui.components.metriccard.toUniversalMode`. Keep `toUniversalSpec` and `CardId.usesDeltaPill()`.

- [ ] **Step 3: Update `DashboardCardFactory.kt` imports**

`DashboardCardFactory.kt` uses `mode.toDashboardMode()` in `ConfigurableMetricCard`'s `onModeSelected`. Add the import `app.readylytics.health.core.ui.components.metriccard.toDashboardMode` (it previously resolved via the same-package `DashboardToUniversalMapper` function, which no longer exists).

- [ ] **Step 4: Update `DashboardMetricCardPreviews.kt` imports**

Add the import `app.readylytics.health.core.ui.components.metriccard.toUniversalMode` (the previews use `DashboardCardDisplayMode.X.toUniversalMode()` and previously resolved via the removed same-package function).

- [ ] **Step 5: Update `DashboardMetricCardWrapper.kt`**

In `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardWrapper.kt`, add imports:

```kotlin
import app.readylytics.health.core.ui.components.metriccard.toDashboardMode
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode
```

(It uses `requestedMode.toUniversalMode()` and `it.toDashboardMode()`; those previously resolved via the removed same-package functions.)

- [ ] **Step 6: Delete copies in `VitalsCardFactory.kt`**

In `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsCardFactory.kt`, delete the two private functions `private fun DashboardCardDisplayMode.toUniversalMode()` and `private fun UniversalCardDisplayMode.toDashboardMode()`, and add imports:

```kotlin
import app.readylytics.health.core.ui.components.metriccard.toDashboardMode
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode
```

- [ ] **Step 7: Delete copies in `SleepLayoutRenderers.kt`**

In `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt`, delete the two private functions `private fun DashboardCardDisplayMode.toUniversalMode()` and `private fun UniversalCardDisplayMode.toDashboardMode()`, and add imports:

```kotlin
import app.readylytics.health.core.ui.components.metriccard.toDashboardMode
import app.readylytics.health.core.ui.components.metriccard.toUniversalMode
```

- [ ] **Step 8: Run the full unit-test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/metriccard/DisplayModeMappers.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardToUniversalMapper.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardPreviews.kt feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardMetricCardWrapper.kt feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsCardFactory.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt
git commit -m "refactor(core): centralize display-mode conversions in core/ui"
```

---

### Task 3: Nullable `DisplayModeDropdownSelector` (core/ui)

Make the shared dropdown support "Default" (null) and a nullable callback.

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DisplayModeDropdownSelector.kt`
- Modify: `core/ui/src/main/res/values/strings.xml`
- Create: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DisplayModeDropdownSelectorTest.kt`

- [ ] **Step 1: Add the `mode_default` string**

In `core/ui/src/main/res/values/strings.xml`, after the `mode_value` line (near `menu_content_description_visualization_style`), add:

```xml
    <string name="mode_default">Default</string>
```

- [ ] **Step 2: Write the failing test**

Create `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DisplayModeDropdownSelectorTest.kt`:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayModeDropdownSelectorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `null selected mode renders Default label`() {
        composeTestRule.setContent {
            DisplayModeDropdownSelector(
                selectedMode = null,
                supportedModes = listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.VALUE),
                onModeSelected = {},
            )
        }
        composeTestRule.onNodeWithText("Default").assertExists()
    }

    @Test
    fun `selecting a mode invokes the callback`() {
        var selected: DashboardCardDisplayMode? = DashboardCardDisplayMode.GAUGE
        composeTestRule.setContent {
            DisplayModeDropdownSelector(
                selectedMode = selected,
                supportedModes = listOf(DashboardCardDisplayMode.GAUGE, DashboardCardDisplayMode.VALUE),
                onModeSelected = { selected = it },
            )
        }
        composeTestRule.onNodeWithText("Gauge").performClick()
        composeTestRule.onNodeWithText("Value").performClick()
        composeTestRule.waitForIdle()
        assertEquals(DashboardCardDisplayMode.VALUE, selected)
    }
}
```

(Note: the second test's `onModeSelected` writes to a captured local `var`; `selectedMode` is not a snapshot state, so re-reading it after the click still works because the callback runs synchronously during `waitForIdle`.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.DisplayModeDropdownSelectorTest"`
Expected: FAIL — the current `DisplayModeDropdownSelector` requires a non-null `selectedMode`/`DashboardCardDisplayMode`; passing `null` and `(DashboardCardDisplayMode?) -> Unit` does not compile.

- [ ] **Step 4: Rewrite `DisplayModeDropdownSelector.kt`**

Replace the ENTIRE contents of `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DisplayModeDropdownSelector.kt` with:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode

/**
 * Read-only exposed dropdown for picking a card's visualization mode. Options are "Default"
 * (null) followed by the card's supported modes. Used by the dashboard/vitals/sleep management
 * sheets, mirroring the on-card three-dot menu's option set plus a reset-to-default entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayModeDropdownSelector(
    selectedMode: DashboardCardDisplayMode?,
    supportedModes: List<DashboardCardDisplayMode>,
    onModeSelected: (DashboardCardDisplayMode?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val displayValue = modeLabel(selectedMode)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.padding(top = MaterialTheme.spacing.extraSmall),
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.display_mode_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.mode_default)) },
                onClick = {
                    onModeSelected(null)
                    expanded = false
                },
            )
            supportedModes.forEach { option ->
                DropdownMenuItem(
                    text = { Text(modeLabel(option)) },
                    onClick = {
                        onModeSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun modeLabel(mode: DashboardCardDisplayMode?): String =
    when (mode) {
        null -> stringResource(R.string.mode_default)
        DashboardCardDisplayMode.GAUGE -> stringResource(R.string.mode_gauge)
        DashboardCardDisplayMode.BAR -> stringResource(R.string.mode_bar)
        DashboardCardDisplayMode.VALUE -> stringResource(R.string.mode_value)
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.DisplayModeDropdownSelectorTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DisplayModeDropdownSelector.kt core/ui/src/main/res/values/strings.xml core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/DisplayModeDropdownSelectorTest.kt
git commit -m "feat(core): add Default option and nullable mode to display-mode dropdown"
```

---

### Task 4: Generic `ManagementBottomSheet` (core/ui)

Add the config-agnostic section/item model and the shared sheet.

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ManagementBottomSheet.kt`
- Create: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/ManagementBottomSheetTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/ManagementBottomSheetTest.kt`:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagementBottomSheetTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `single section renders no tabs`() {
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections =
                    listOf(
                        ManagementSection(
                            title = "Cards",
                            items = listOf(item("cardA", "Card A", supportedModes = listOf(DashboardCardDisplayMode.VALUE))),
                        ),
                    ),
                onResetToDefaults = {},
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(),
            )
        }
        composeTestRule.onNodeWithText("Cards").assertIsNotDisplayed()
    }

    @Test
    fun `multiple sections render tab labels`() {
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections =
                    listOf(
                        ManagementSection(title = "Cards", items = listOf(item("cardA", "Card A"))),
                        ManagementSection(title = "Charts", items = listOf(item("chartA", "Chart A"))),
                    ),
                onResetToDefaults = {},
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(),
            )
        }
        composeTestRule.onNodeWithText("Cards").assertIsDisplayed()
        composeTestRule.onNodeWithText("Charts").assertIsDisplayed()
    }

    @Test
    fun `visibility checkbox invokes callback`() {
        var toggled = false
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections =
                    listOf(
                        ManagementSection(
                            title = "Cards",
                            items =
                                listOf(
                                    item("cardA", "Card A", onVisibilityChanged = { toggled = it }),
                                ),
                        ),
                    ),
                onResetToDefaults = {},
                onDismiss = {},
                sheetState = rememberModalBottomSheetState(),
            )
        }
        // The row renders a Checkbox with no text label; toggling it requires locating the checkbox
        // by its semantic role. Use the headline text node's toggleable ancestor.
        composeTestRule.onNodeWithText("Card A").performClick()
        composeTestRule.waitForIdle()
        assertTrue(toggled)
    }

    @Test
    fun `done invokes onDismiss and reset icon invokes onReset`() {
        var dismissed = false
        var reset = false
        composeTestRule.setContent {
            ManagementBottomSheet(
                title = "Manage",
                sections = listOf(ManagementSection(title = "Cards", items = listOf(item("cardA", "Card A")))),
                onResetToDefaults = { reset = true },
                onDismiss = { dismissed = true },
                sheetState = rememberModalBottomSheetState(),
            )
        }
        composeTestRule.onNodeWithText("Done").performClick()
        composeTestRule.waitForIdle()
        assertTrue(dismissed)
        assertTrue(!reset)
    }

    private fun item(
        key: String,
        label: String,
        supportedModes: List<DashboardCardDisplayMode> = emptyList(),
        onVisibilityChanged: (Boolean) -> Unit = {},
    ) = ManagementItem(
        key = key,
        label = label,
        isVisible = true,
        supportedModes = supportedModes,
        requestedMode = null,
        onVisibilityChanged = onVisibilityChanged,
        onDisplayModeChanged = {},
    )
}
```

(Note: the "visibility checkbox invokes callback" test relies on the `ListItem` headline text's row being clickable? — if it is not, replace `onNodeWithText("Card A").performClick()` with `onNode(isToggleable()).performClick()`. Adjust to match how the checkbox can be located in this build.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.ManagementBottomSheetTest"`
Expected: FAIL — `unresolved reference: ManagementBottomSheet`.

- [ ] **Step 3: Create `ManagementBottomSheet.kt`**

Create `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ManagementBottomSheet.kt`:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode

data class ManagementItem(
    val key: String,
    val label: String,
    val isVisible: Boolean,
    val supportedModes: List<DashboardCardDisplayMode>,
    val requestedMode: DashboardCardDisplayMode?,
    val onVisibilityChanged: (Boolean) -> Unit,
    val onDisplayModeChanged: (DashboardCardDisplayMode?) -> Unit,
)

data class ManagementSection(
    val title: String,
    val items: List<ManagementItem>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagementBottomSheet(
    title: String,
    sections: List<ManagementSection>,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        var selectedTabIndex by remember { mutableIntStateOf(0) }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.spacing.pageSectionGap),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = MaterialTheme.spacing.pageHorizontal,
                            end = MaterialTheme.spacing.pageHorizontal,
                            bottom = MaterialTheme.spacing.small,
                        ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = onResetToDefaults) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = stringResource(R.string.action_reset_to_defaults),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (sections.size > 1) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    sections.forEachIndexed { index, section ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(section.title) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))
            }

            val activeSection = sections.getOrElse(selectedTabIndex) { sections.first() }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(activeSection.items, key = { it.key }) { item ->
                    ManagementRow(item)
                }
            }

            Button(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.End)
                        .padding(
                            end = MaterialTheme.spacing.pageHorizontal,
                            top = MaterialTheme.spacing.pageSectionGap,
                        ),
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@Composable
private fun ManagementRow(item: ManagementItem) {
    ListItem(
        headlineContent = {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent =
            if (item.supportedModes.isNotEmpty()) {
                {
                    DisplayModeDropdownSelector(
                        selectedMode = item.requestedMode,
                        supportedModes = item.supportedModes,
                        onModeSelected = item.onDisplayModeChanged,
                    )
                }
            } else {
                null
            },
        trailingContent = {
            Checkbox(
                checked = item.isVisible,
                onCheckedChange = item.onVisibilityChanged,
            )
        },
        modifier = Modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.ManagementBottomSheetTest"`
Expected: PASS (4 tests). If the visibility-checkbox test cannot locate the checkbox via the headline text, adjust it to use `onNode(isToggleable())`.

- [ ] **Step 5: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ManagementBottomSheet.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/ManagementBottomSheetTest.kt
git commit -m "feat(core): add generic ManagementBottomSheet with section/item model"
```

---

### Task 5: `rememberManageLayoutState` scaffold (core/ui)

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ManageLayoutState.kt`

- [ ] **Step 1: Create `ManageLayoutState.kt`**

Create `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ManageLayoutState.kt`:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/** State for a manage-layout bottom sheet shared by the dashboard/vitals/sleep screens. */
@OptIn(ExperimentalMaterial3Api::class)
class ManageLayoutState(
    val sheetState: SheetState,
    val isManageOpen: Boolean,
    val openManage: () -> Unit,
    val closeManage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberManageLayoutState(): ManageLayoutState {
    val sheetState = rememberModalBottomSheetState()
    var isManageOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    return ManageLayoutState(
        sheetState = sheetState,
        isManageOpen = isManageOpen,
        openManage = { isManageOpen = true },
        closeManage = {
            scope.launch { sheetState.hide() }
            isManageOpen = false
        },
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ManageLayoutState.kt
git commit -m "feat(core): add rememberManageLayoutState scaffold"
```

---

### Task 6: Nullable display-mode plumbing (dashboard/vitals)

Widen the dashboard/vitals display-mode callbacks to nullable so "Default" (reset) works.

**Files:**
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsCardFactory.kt`

- [ ] **Step 1: Make the delegate event nullable**

In `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt`, change the `DisplayModeChanged` event's `mode` type:

```kotlin
data class DisplayModeChanged(
    val cardId: CardId,
    val mode: DashboardCardDisplayMode,
) : CardManagementEvent
```

to:

```kotlin
data class DisplayModeChanged(
    val cardId: CardId,
    val mode: DashboardCardDisplayMode?,
) : CardManagementEvent
```

The `onEvent` handler body (`configuration.copy(requestedDisplayMode = event.mode)`) already handles null — no other change.

- [ ] **Step 2: Widen `DashboardViewModel.onCardDisplayModeChanged`**

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt`, change the `mode` parameter type to nullable:

```kotlin
fun onCardDisplayModeChanged(
    cardId: CardId,
    mode: DashboardCardDisplayMode?,
) {
    cardManagementDelegate.onEvent(CardManagementEvent.DisplayModeChanged(cardId, mode))
}
```

- [ ] **Step 3: Widen `VitalsViewModel.onVitalsCardDisplayModeChanged`**

In `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt`, change the `mode` parameter type to nullable:

```kotlin
fun onVitalsCardDisplayModeChanged(
    cardId: CardId,
    mode: DashboardCardDisplayMode?,
) {
    vitalsCardManagementDelegate.onEvent(CardManagementEvent.DisplayModeChanged(cardId, mode))
}
```

- [ ] **Step 4: Widen `DashboardScreen` param**

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`, change:

```kotlin
onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> },
```

to:

```kotlin
onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> },
```

- [ ] **Step 5: Widen `VitalsScreen` param**

In `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`, change:

```kotlin
onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> },
```

to:

```kotlin
onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> },
```

- [ ] **Step 6: Widen `DashboardCardFactory` callback param**

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt`, change BOTH callback parameter types (in `ConfigurableMetricCard` and `buildCardDataMap`) from `(CardId, DashboardCardDisplayMode) -> Unit` to `(CardId, DashboardCardDisplayMode?) -> Unit`. The `onModeSelected = { mode -> onCardDisplayModeChanged(cardId, mode.toDashboardMode()) }` call sites still compile (non-null value passed to a nullable parameter).

- [ ] **Step 7: Widen `VitalsCardFactory` callback param**

In `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsCardFactory.kt`, change `onVitalsCardDisplayModeChanged: (CardId, DashboardCardDisplayMode) -> Unit = { _, _ -> }` to `(CardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> }`.

- [ ] **Step 8: Run the full unit-test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (non-null callers remain valid).

- [ ] **Step 9: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardManagementDelegate.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsViewModel.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsCardFactory.kt
git commit -m "refactor(core): allow nullable display mode to support reset-to-default"
```

---

### Task 7: Rewrite the three sheets as thin wrappers + wire screens

**Files:**
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardManagementBottomSheet.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsManagementBottomSheet.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheet.kt`
- Modify: `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`
- Modify: `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`
- Modify: `feature/sleep/src/main/res/values/strings.xml`

- [ ] **Step 1: Rewrite `CardManagementBottomSheet.kt` (dashboard)**

Replace the ENTIRE contents of `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardManagementBottomSheet.kt` with:

```kotlin
package app.readylytics.health.feature.dashboard

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.components.ManagementBottomSheet
import app.readylytics.health.core.ui.components.ManagementItem
import app.readylytics.health.core.ui.components.ManagementSection
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardManagementBottomSheet(
    cards: List<CardConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode?) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ManagementBottomSheet(
        title = stringResource(R.string.manage_cards),
        sections =
            listOf(
                ManagementSection(
                    title = stringResource(R.string.manage_cards),
                    items =
                        cards.sortedBy { it.position }.map { card ->
                            ManagementItem(
                                key = "card_${card.cardId.name}",
                                label = stringResource(card.cardId.displayNameResId),
                                isVisible = card.isVisible,
                                supportedModes = DashboardCardCatalog.spec(card.cardId)?.supportedModes.orEmpty(),
                                requestedMode = card.requestedDisplayMode,
                                onVisibilityChanged = { onCardVisibilityChanged(card.cardId, it) },
                                onDisplayModeChanged = { onCardDisplayModeChanged(card.cardId, it) },
                            )
                        },
                ),
            ),
        onResetToDefaults = onResetToDefaults,
        onDismiss = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    )
}
```

- [ ] **Step 2: Rewrite `VitalsManagementBottomSheet.kt`**

Replace the ENTIRE contents of `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsManagementBottomSheet.kt` with:

```kotlin
package app.readylytics.health.feature.vitals.overview

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.components.ManagementBottomSheet
import app.readylytics.health.core.ui.components.ManagementItem
import app.readylytics.health.core.ui.components.ManagementSection
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardCatalog
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId
import app.readylytics.health.feature.vitals.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsManagementBottomSheet(
    cardConfigurations: List<CardConfiguration>,
    chartConfigurations: List<VitalsChartConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onChartVisibilityChanged: (VitalsChartId, Boolean) -> Unit,
    onCardDisplayModeChanged: (CardId, DashboardCardDisplayMode?) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ManagementBottomSheet(
        title = stringResource(R.string.vitals_manage_layout),
        sections =
            listOf(
                ManagementSection(
                    title = stringResource(R.string.vitals_management_cards_section_title),
                    items =
                        cardConfigurations.sortedBy { it.position }.map { card ->
                            ManagementItem(
                                key = "card_${card.cardId.name}",
                                label = stringResource(card.cardId.displayNameResId),
                                isVisible = card.isVisible,
                                supportedModes = DashboardCardCatalog.spec(card.cardId)?.supportedModes.orEmpty(),
                                requestedMode = card.requestedDisplayMode,
                                onVisibilityChanged = { onCardVisibilityChanged(card.cardId, it) },
                                onDisplayModeChanged = { onCardDisplayModeChanged(card.cardId, it) },
                            )
                        },
                ),
                ManagementSection(
                    title = stringResource(R.string.vitals_management_diagrams_section_title),
                    items =
                        chartConfigurations.sortedBy { it.position }.map { chart ->
                            ManagementItem(
                                key = "chart_${chart.chartId.name}",
                                label = stringResource(chart.chartId.displayNameResId),
                                isVisible = chart.isVisible,
                                supportedModes = emptyList(),
                                requestedMode = null,
                                onVisibilityChanged = { onChartVisibilityChanged(chart.chartId, it) },
                                onDisplayModeChanged = {},
                            )
                        },
                ),
            ),
        onResetToDefaults = onResetToDefaults,
        onDismiss = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    )
}
```

- [ ] **Step 3: Rewrite `SleepManagementBottomSheet.kt`**

Replace the ENTIRE contents of `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheet.kt` with:

```kotlin
package app.readylytics.health.feature.sleep.overview

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.components.ManagementBottomSheet
import app.readylytics.health.core.ui.components.ManagementItem
import app.readylytics.health.core.ui.components.ManagementSection
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.sleep.SleepCardCatalog
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.feature.sleep.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepManagementBottomSheet(
    topCardConfigurations: List<SleepTopCardConfiguration>,
    chartConfigurations: List<SleepChartConfiguration>,
    metricCardConfigurations: List<SleepMetricCardConfiguration>,
    onTopCardVisibilityChanged: (SleepTopCardId, Boolean) -> Unit,
    onChartVisibilityChanged: (SleepChartId, Boolean) -> Unit,
    onMetricCardVisibilityChanged: (SleepMetricCardId, Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    onTopCardDisplayModeChanged: ((SleepTopCardId, DashboardCardDisplayMode?) -> Unit)? = null,
    onMetricCardDisplayModeChanged: ((SleepMetricCardId, DashboardCardDisplayMode?) -> Unit)? = null,
) {
    ManagementBottomSheet(
        title = stringResource(R.string.sleep_manage_layout),
        sections =
            listOf(
                ManagementSection(
                    title = stringResource(R.string.sleep_management_top_cards_section_title),
                    items =
                        topCardConfigurations.sortedBy { it.position }.map { card ->
                            ManagementItem(
                                key = "top_card_${card.cardId.name}",
                                label = stringResource(card.cardId.displayNameResId),
                                isVisible = card.isVisible,
                                supportedModes = SleepCardCatalog.topCardSpec(card.cardId)?.supportedModes.orEmpty(),
                                requestedMode = card.requestedDisplayMode,
                                onVisibilityChanged = { onTopCardVisibilityChanged(card.cardId, it) },
                                onDisplayModeChanged = { onTopCardDisplayModeChanged?.invoke(card.cardId, it) },
                            )
                        },
                ),
                ManagementSection(
                    title = stringResource(R.string.sleep_management_charts_section_title),
                    items =
                        chartConfigurations.sortedBy { it.position }.map { chart ->
                            ManagementItem(
                                key = "chart_${chart.chartId.name}",
                                label = stringResource(chart.chartId.displayNameResId),
                                isVisible = chart.isVisible,
                                supportedModes = emptyList(),
                                requestedMode = null,
                                onVisibilityChanged = { onChartVisibilityChanged(chart.chartId, it) },
                                onDisplayModeChanged = {},
                            )
                        },
                ),
                ManagementSection(
                    title = stringResource(R.string.sleep_management_metrics_section_title),
                    items =
                        metricCardConfigurations.sortedBy { it.position }.map { card ->
                            ManagementItem(
                                key = "metric_card_${card.cardId.name}",
                                label = stringResource(card.cardId.displayNameResId),
                                isVisible = card.isVisible,
                                supportedModes = SleepCardCatalog.metricCardSpec(card.cardId)?.supportedModes.orEmpty(),
                                requestedMode = card.requestedDisplayMode,
                                onVisibilityChanged = { onMetricCardVisibilityChanged(card.cardId, it) },
                                onDisplayModeChanged = { onMetricCardDisplayModeChanged?.invoke(card.cardId, it) },
                            )
                        },
                ),
            ),
        onResetToDefaults = onResetToDefaults,
        onDismiss = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    )
}
```

- [ ] **Step 4: Wire `DashboardScreen` to `rememberManageLayoutState`**

In `feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt`:
- Add imports: `app.readylytics.health.core.ui.components.rememberManageLayoutState`.
- Remove `val sheetState = rememberModalBottomSheetState()` and `var showCardManagement by rememberSaveable { mutableStateOf(false) }`, and (if now unused) the `rememberModalBottomSheetState`, `rememberSaveable`, `mutableStateOf`, `getValue`, `setValue` imports.
- Add `val manageState = rememberManageLayoutState()`.
- Replace `if (showCardManagement)` with `if (manageState.isManageOpen)`.
- Replace the sheet's `onDismiss = { scope.launch { sheetState.hide() }; showCardManagement = false }` with `onDismiss = manageState.closeManage`.
- Replace `sheetState = sheetState` with `sheetState = manageState.sheetState`.
- Replace `onManageClick = onManageClick ?: { showCardManagement = true }` with `onManageClick = onManageClick ?: manageState.openManage`.
- If `scope` (the `rememberCoroutineScope`) is now unused elsewhere, remove `val scope = rememberCoroutineScope()` and the `kotlinx.coroutines.launch` import.

- [ ] **Step 5: Wire `VitalsScreen` to `rememberManageLayoutState`**

Apply the same transformation in `feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt` (`showVitalsManagement` → `manageState.isManageOpen`, `onDismiss = manageState.closeManage`, `sheetState = manageState.sheetState`, `onManageClick = manageState.openManage`), removing now-unused imports.

- [ ] **Step 6: Wire `SleepScreen` to `rememberManageLayoutState`**

Apply the same transformation in `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt` (`showSleepManagement` → `manageState.isManageOpen`, `onDismiss = manageState.closeManage`, `sheetState = manageState.sheetState`, `onManageClick = manageState.openManage`).

- [ ] **Step 7: Remove unused sleep display-mode strings**

In `feature/sleep/src/main/res/values/strings.xml`, delete the five lines:

```xml
    <string name="sleep_management_display_mode_label">Display mode</string>
    <string name="sleep_management_display_mode_default">Default</string>
    <string name="sleep_management_display_mode_gauge">Gauge</string>
    <string name="sleep_management_display_mode_bar">Bar</string>
    <string name="sleep_management_display_mode_value">Value</string>
```

- [ ] **Step 8: Run the full unit-test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/CardManagementBottomSheet.kt feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsManagementBottomSheet.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheet.kt feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardScreen.kt feature/vitals/src/main/kotlin/app/readylytics/health/feature/vitals/overview/VitalsScreen.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt feature/sleep/src/main/res/values/strings.xml
git commit -m "refactor(sleep,vitals,dashboard): use shared ManagementBottomSheet and manage-layout scaffold"
```

---

### Task 8: Update existing tests

**Files:**
- No source changes expected. This task verifies existing tests and adds one focused delegate test.

- [ ] **Step 1: Confirm `SleepManagementBottomSheetTest` still passes unchanged**

`feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheetTest.kt` tests pure sorting/`copy` of `SleepTopCardConfiguration`/`SleepMetricCardConfiguration`/`SleepChartConfiguration` — it does not reference the sheet composable, so it needs no changes.

Run: `./gradlew :feature:sleep:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 2: Add a nullable reset test to `DashboardViewModelTest`**

The nullable mode path (reset to null) is covered at the UI level by `DisplayModeDropdownSelectorTest` and `ManagementBottomSheetTest`; existing delegate/viewmodel tests remain valid because non-null callers still compile. Add one focused delegate-level assertion to `feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt` in its existing test class/fixture:

```kotlin
@Test
fun `display mode changed to null resets the card to default`() {
    viewModel.onCardDisplayModeChanged(CardId.HRV, DashboardCardDisplayMode.GAUGE)
    viewModel.onCardDisplayModeChanged(CardId.HRV, null)
    assertEquals(null, pendingConfigsFor(CardId.HRV).requestedDisplayMode)
}
```

(Use the file's existing helper/flow access pattern for `pendingConfigsFor`; if no such helper exists, assert via the delegate's `pendingConfigs` StateFlow directly, matching how that test already reaches the delegate.)

- [ ] **Step 3: Run the full unit-test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add feature/dashboard/src/test/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModelTest.kt
git commit -m "test: cover nullable display-mode reset path"
```

---

### Task 9: Final verification

**Files:** no source changes unless verification fails.

- [ ] **Step 1: Run pre-commit check**

Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 2: Run release lint**

Run: `./gradlew lintRelease`
Expected: PASS (or only pre-existing warnings).

- [ ] **Step 3: Refresh codegraph index**

Run: `codegraph index` (new files: `ModeSpec.kt`, `DisplayModeMappers.kt`, `ManagementBottomSheet.kt`, `ManageLayoutState.kt` and their tests).

- [ ] **Step 4: Commit any remaining changes**

```bash
git add -A
git commit -m "chore: final cleanup for shared manage-cards component"
```

---

## Plan Self-Review Notes

- **Spec coverage:** §4 ModeSpec → Task 1; §5 conversions → Task 2; §3 nullable dropdown → Task 3; §6 sheet+model → Task 4; §7 scaffold → Task 5; §9 nullable plumbing → Task 6; §8 wrappers → Task 7; §10 strings → Tasks 3 & 7; §11 tests → Tasks 3,4,8. §12 docs: no `DATA_FLOW.md` change.
- **Type consistency:** `ModeSpec` (`legacyDefaultMode`, `supportedModes`) and `resolveRequestedMode` are used consistently across Tasks 1,4,7. `ManagementItem`/`ManagementSection` field names (`key`, `label`, `isVisible`, `supportedModes`, `requestedMode`, `onVisibilityChanged`, `onDisplayModeChanged`) match between Task 4 definition and Task 7 call sites. `DisplayModeDropdownSelector` nullable signature matches Task 3 and Task 4 usage.
- **Placeholders:** none — every code step is complete.
