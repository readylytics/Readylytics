# Sleep Tab On-Screen Drag-and-Drop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add vitals-parity on-screen drag-and-drop to the Sleep tab edit mode — top cards, metric grid, and trend chart become draggable via long-press drag handles; the bottom sheet's up/down arrow reorder controls are removed.

**Architecture:** Extract the type-agnostic drag mechanics of `ReorderableCardGrid`/`ReorderableChartList` into two generic composables (`ReorderableGrid<Id, Config>`, `ReorderableList<Id, Config>`) driven by a new `ReorderableItem<Id>` interface implemented by all layout configs. The existing components become thin typed wrappers (unchanged public API). `SleepScreen` then wires the three Sleep sections through the generic components using the already-wired `onReorderSleep*` / `onToggleSleep*Visibility` ViewModel callbacks, so no persistence or delegate changes are needed.

**Tech Stack:** Kotlin, Jetpack Compose (Material Design 3), Robolectric (core/ui Compose tests), JUnit4, MockK.

## Global Constraints

- **Pre-Commit Validation:** `./gradlew ktlintFormat && ./gradlew testDebugUnitTest` must pass at the end of each task. Final task also runs `./gradlew lintRelease`.
- **Strings:** All user-facing strings live in `strings.xml`; removing unused ones is part of this work.
- **File size:** Keep files at/below 400 lines where practical.
- **Indexing:** After creating new files, run `codegraph index` (per AGENTS.md File Lifecycle).
- **Design source:** `docs/superpowers/specs/2026-08-13-sleep-tab-on-screen-drag-design.md`.

---

### Task 1: `ReorderableItem` interface + config implementations

**Files:**
- Create: `core/model/src/main/kotlin/app/readylytics/health/domain/layout/ReorderableItem.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardConfiguration.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartConfiguration.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepTopCardConfiguration.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepChartConfiguration.kt`
- Modify: `core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepMetricCardConfiguration.kt`
- Test: `core/model/src/test/kotlin/app/readylytics/health/domain/layout/ReorderableItemTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/model/src/test/kotlin/app/readylytics/health/domain/layout/ReorderableItemTest.kt`:

```kotlin
package app.readylytics.health.domain.layout

import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReorderableItemTest {
    @Test
    fun `card configuration exposes cardId as id`() {
        val config = CardConfiguration(CardId.HRV, isVisible = false, position = 3)
        assertEquals(CardId.HRV, config.id)
        assertFalse(config.isVisible)
        assertEquals(3, config.position)
    }

    @Test
    fun `vitals chart configuration exposes chartId as id`() {
        val config = VitalsChartConfiguration(VitalsChartId.HRV_TREND, isVisible = true, position = 1)
        assertEquals(VitalsChartId.HRV_TREND, config.id)
    }

    @Test
    fun `sleep top card configuration exposes cardId as id`() {
        val config = SleepTopCardConfiguration(SleepTopCardId.SLEEP_SCORE, isVisible = true, position = 0)
        assertEquals(SleepTopCardId.SLEEP_SCORE, config.id)
    }

    @Test
    fun `sleep chart configuration exposes chartId as id`() {
        val config = SleepChartConfiguration(SleepChartId.SLEEP_DURATION_TREND, isVisible = true, position = 0)
        assertEquals(SleepChartId.SLEEP_DURATION_TREND, config.id)
    }

    @Test
    fun `sleep metric card configuration exposes cardId as id`() {
        val config = SleepMetricCardConfiguration(SleepMetricCardId.DEEP_SLEEP, isVisible = true, position = 2)
        assertEquals(SleepMetricCardId.DEEP_SLEEP, config.id)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:model:testDebugUnitTest --tests "app.readylytics.health.domain.layout.ReorderableItemTest"`
Expected: FAIL — `unresolved reference: ReorderableItem` (interface does not exist yet).

- [ ] **Step 3: Create the interface**

Create `core/model/src/main/kotlin/app/readylytics/health/domain/layout/ReorderableItem.kt`:

```kotlin
package app.readylytics.health.domain.layout

/**
 * Shape shared by all reorderable layout configurations (dashboard cards, vitals charts,
 * sleep top cards / charts / metric cards). Exposes the id, visibility, and position that
 * the generic reorderable UI components drive off.
 */
interface ReorderableItem<Id> {
    val id: Id
    val isVisible: Boolean
    val position: Int
}
```

- [ ] **Step 4: Implement on the five config classes**

`core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardConfiguration.kt` — replace the data class declaration:

```kotlin
@Serializable
data class CardConfiguration(
    val cardId: CardId,
    val isVisible: Boolean = true,
    val position: Int = 0,
    @Serializable(with = NullableDashboardCardDisplayModeSerializer::class)
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
) : ReorderableItem<CardId> {
    override val id: CardId get() = cardId
}
```

Add import: `app.readylytics.health.domain.layout.ReorderableItem`

`core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartConfiguration.kt` — replace the data class declaration:

```kotlin
@Serializable
data class VitalsChartConfiguration(
    val chartId: VitalsChartId,
    val isVisible: Boolean,
    val position: Int,
) : ReorderableItem<VitalsChartId> {
    override val id: VitalsChartId get() = chartId
}
```

Add import: `app.readylytics.health.domain.layout.ReorderableItem`

`core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepTopCardConfiguration.kt` — replace the data class declaration:

```kotlin
@Serializable
data class SleepTopCardConfiguration(
    val cardId: SleepTopCardId,
    val isVisible: Boolean = true,
    val position: Int = 0,
    @Serializable(with = NullableDashboardCardDisplayModeSerializer::class)
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
) : ReorderableItem<SleepTopCardId> {
    override val id: SleepTopCardId get() = cardId
}
```

Add import: `app.readylytics.health.domain.layout.ReorderableItem`

`core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepChartConfiguration.kt` — replace the data class declaration:

```kotlin
@Serializable
data class SleepChartConfiguration(
    val chartId: SleepChartId,
    val isVisible: Boolean = true,
    val position: Int = 0,
) : ReorderableItem<SleepChartId> {
    override val id: SleepChartId get() = chartId
}
```

Add import: `app.readylytics.health.domain.layout.ReorderableItem`

`core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepMetricCardConfiguration.kt` — replace the data class declaration:

```kotlin
@Serializable
data class SleepMetricCardConfiguration(
    val cardId: SleepMetricCardId,
    val isVisible: Boolean = true,
    val position: Int = 0,
    @Serializable(with = NullableDashboardCardDisplayModeSerializer::class)
    val requestedDisplayMode: DashboardCardDisplayMode? = null,
) : ReorderableItem<SleepMetricCardId> {
    override val id: SleepMetricCardId get() = cardId
}
```

Add import: `app.readylytics.health.domain.layout.ReorderableItem`

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:model:testDebugUnitTest --tests "app.readylytics.health.domain.layout.ReorderableItemTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add core/model/src/main/kotlin/app/readylytics/health/domain/layout/ core/model/src/main/kotlin/app/readylytics/health/domain/dashboard/CardConfiguration.kt core/model/src/main/kotlin/app/readylytics/health/domain/vitals/VitalsChartConfiguration.kt core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepTopCardConfiguration.kt core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepChartConfiguration.kt core/model/src/main/kotlin/app/readylytics/health/domain/sleep/SleepMetricCardConfiguration.kt core/model/src/test/kotlin/app/readylytics/health/domain/layout/ReorderableItemTest.kt
git commit -m "feat(core): add ReorderableItem interface shared by layout configs"
```

---

### Task 2: Generic `ReorderableGrid` + `ReorderableList` composables

**Files:**
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableGrid.kt`
- Create: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableList.kt`
- Test: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableGridSleepTypeTest.kt`

- [ ] **Step 1: Write the failing test**

Create `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableGridSleepTypeTest.kt`:

```kotlin
package app.readylytics.health.core.ui.components.reorder

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the generic ReorderableGrid works for a NON-dashboard domain type (sleep), i.e.
 * that the type parameterization is real and the drag mechanics are type-agnostic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReorderableGridSleepTypeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun renderMap(): Map<SleepTopCardId, @Composable (SleepTopCardConfiguration) -> Unit> =
        SleepTopCardId.entries.associateWith { id ->
            val label = id.name
            @Composable { _: SleepTopCardConfiguration -> Text(label) }
        }

    private fun configs(): List<SleepTopCardConfiguration> =
        listOf(
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_SCORE, isVisible = true, position = 0),
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_DURATION_GAUGE, isVisible = true, position = 1),
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_BREAKDOWN_BAR, isVisible = true, position = 2),
        )

    private val fullWidthIds = setOf(SleepTopCardId.SLEEP_BREAKDOWN_BAR)

    @Test
    fun `drag handle starts controller drag for sleep card type`() {
        val controller = DragController(listOf(SleepTopCardId.SLEEP_SCORE, SleepTopCardId.SLEEP_DURATION_GAUGE))
        composeTestRule.setContent {
            ReorderableGrid(
                items = configs(),
                dataMap = renderMap(),
                isEditing = true,
                onItemReorder = {},
                onItemDropToRemove = {},
                fullWidthIds = fullWidthIds,
                controller = controller,
            )
        }

        // First "Drag to reorder" handle in display order belongs to SLEEP_SCORE (position 0).
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, 100f))
        }
        composeTestRule.waitForIdle()

        assertEquals(SleepTopCardId.SLEEP_SCORE, controller.draggedCardId)
    }

    @Test
    fun `dragging across slots emits reorder callback with sleep configs`() {
        var reordered: List<SleepTopCardConfiguration>? = null
        val controller = DragController(listOf(SleepTopCardId.SLEEP_SCORE, SleepTopCardId.SLEEP_DURATION_GAUGE))

        composeTestRule.setContent {
            ReorderableGrid(
                items = configs(),
                dataMap = renderMap(),
                isEditing = true,
                onItemReorder = { reordered = it },
                onItemDropToRemove = {},
                fullWidthIds = fullWidthIds,
                controller = controller,
            )
        }
        composeTestRule.waitForIdle()

        // SLEEP_SCORE + SLEEP_DURATION_GAUGE are paired side-by-side in one row. Drag SCORE's
        // center onto GAUGE's slot center, then lift. onItemReorder must fire with sleep configs.
        val sleepCenter = controller.slotBounds.getValue(SleepTopCardId.SLEEP_SCORE).center
        val gaugeCenter = controller.slotBounds.getValue(SleepTopCardId.SLEEP_DURATION_GAUGE).center
        val delta = Offset(gaugeCenter.x - sleepCenter.x, gaugeCenter.y - sleepCenter.y)

        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(delta)
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(SleepTopCardId.SLEEP_DURATION_GAUGE, reordered!!.first().cardId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.reorder.ReorderableGridSleepTypeTest"`
Expected: FAIL — `unresolved reference: ReorderableGrid`.

- [ ] **Step 3: Create the generic grid**

Create `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableGrid.kt`:

```kotlin
package app.readylytics.health.core.ui.components.reorder

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.readylytics.health.core.designsystem.dimens
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.domain.layout.ReorderableItem
import kotlin.math.roundToInt

/**
 * Grid that supports drag-and-drop reordering, generic over any [ReorderableItem].
 *
 * Source of truth for order during a drag is [DragController.pendingOrder]. Upstream [items]
 * is used only to look up the renderable config for each id and to seed/sync the controller
 * when no drag is active. Slot bounds live in a single shared coordinate space (the root
 * Column's local space), drag START is gated by the 48dp drag-handle bounds, and a bottom
 * delete drop-zone reuses the DragController delete mechanism (drop = onItemDropToRemove).
 *
 * Layout: items whose id is in [fullWidthIds] render full-width alone; the remaining items
 * pair into rows of two (a lone non-full-width item renders half-width with a spacer), which
 * reproduces both the dashboard gauge pairing and the sleep gauge pairing. Items in
 * [fixedHeightIds] render inside a fixed `dimens.cardHeight` box.
 */
@Composable
fun <Id : Any, Config : ReorderableItem<Id>> ReorderableGrid(
    items: List<Config>,
    dataMap: Map<Id, @Composable (Config) -> Unit>,
    isEditing: Boolean,
    onItemReorder: (List<Config>) -> Unit,
    onItemDropToRemove: (Id) -> Unit,
    fullWidthIds: Set<Id>,
    fixedHeightIds: Set<Id> = emptySet(),
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = MaterialTheme.spacing.small,
    controller: DragController<Id>? = null,
) {
    val configById: Map<Id, Config> =
        remember(items, dataMap.keys) {
            items
                .filter { it.isVisible && dataMap.containsKey(it.id) }
                .associateBy { it.id }
        }

    val dragController =
        remember {
            controller ?: DragController(
                items
                    .filter { it.isVisible && dataMap.containsKey(it.id) }
                    .sortedBy { it.position }
                    .map { it.id },
            )
        }

    // Sync controller from upstream when not actively dragging. Only the filtered + sorted
    // ids enter the controller so pendingOrder always matches what we actually render.
    LaunchedEffect(items, dataMap.keys) {
        val upstreamOrder =
            items
                .filter { it.isVisible && dataMap.containsKey(it.id) }
                .sortedBy { it.position }
                .map { it.id }
        dragController.syncFromUpstream(upstreamOrder)
    }

    // Render order is driven by the controller, not by upstream — gives the live drag preview.
    val displayableItems: List<Config> =
        dragController.pendingOrder.mapNotNull { configById[it] }

    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var deleteZoneTopPx by remember { mutableStateOf<Float?>(null) }

    val handleBounds = remember { mutableStateMapOf<Id, Rect>() }
    val hapticFeedback = LocalHapticFeedback.current

    val draggedId = dragController.draggedCardId

    val performDragEnd = {
        val result = dragController.onDragEnd()
        val draggedId = result.draggedId
        if (draggedId != null) {
            if (result.delete) {
                onItemDropToRemove(draggedId)
            } else {
                val updated =
                    result.finalOrder
                        .mapNotNull { id -> configById[id] }
                        .mapIndexed { index, config -> config.copy(position = index) }
                onItemReorder(updated)
            }
        }
    }

    val onHandlePositioned: (Id, LayoutCoordinates) -> Unit = { id, coords ->
        rootCoords?.let { root -> handleBounds[id] = root.localBoundingBoxOf(coords) }
    }

    // pointerInput(Unit) below never restarts across recomposition, so its closure would
    // otherwise capture stale performDragEnd/deleteZoneTopPx from the composition it first ran
    // in. rememberUpdatedState keeps it reading the current values.
    val currentDeleteZoneTopPx by rememberUpdatedState(deleteZoneTopPx)
    val currentPerformDragEnd by rememberUpdatedState(performDragEnd)

    Column(
        modifier =
            modifier
                .onGloballyPositioned { rootCoords = it }
                .then(
                    if (isEditing) {
                        Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val targetId =
                                        handleBounds.entries
                                            .firstOrNull { (_, rect) -> rect.contains(offset) }
                                            ?.key
                                    if (targetId != null) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragController.onDragStart(targetId)
                                    }
                                },
                                onDragEnd = { currentPerformDragEnd() },
                                onDragCancel = { currentPerformDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (dragController.draggedCardId != null) {
                                        dragController.onDrag(dragAmount, currentDeleteZoneTopPx)
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) {
        var index = 0
        while (index < displayableItems.size) {
            val item = displayableItems[index]

            if (item.id in fullWidthIds) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (draggedId == item.id) 1f else 0f)
                            .onGloballyPositioned { coords ->
                                rootCoords?.let { root ->
                                    dragController.updateSlotBounds(item.id, root.localBoundingBoxOf(coords))
                                }
                            },
                ) {
                    ReorderableSlot(
                        id = item.id,
                        content = { dataMap[item.id]!!(item) },
                        isEditing = isEditing,
                        isDragged = draggedId == item.id,
                        controller = dragController,
                        onHandlePositioned = onHandlePositioned,
                        fixedHeight = item.id in fixedHeightIds,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                index++
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
                ) {
                    val leftItem = displayableItems[index]
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .zIndex(if (draggedId == leftItem.id) 1f else 0f)
                                .onGloballyPositioned { coords ->
                                    rootCoords?.let { root ->
                                        dragController.updateSlotBounds(
                                            leftItem.id,
                                            root.localBoundingBoxOf(coords),
                                        )
                                    }
                                },
                    ) {
                        ReorderableSlot(
                            id = leftItem.id,
                            content = { dataMap[leftItem.id]!!(leftItem) },
                            isEditing = isEditing,
                            isDragged = draggedId == leftItem.id,
                            controller = dragController,
                            onHandlePositioned = onHandlePositioned,
                            fixedHeight = leftItem.id in fixedHeightIds,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                        )
                    }
                    index++

                    val isHalfWidth =
                        index < displayableItems.size &&
                            displayableItems[index].id !in fullWidthIds
                    if (isHalfWidth) {
                        val rightItem = displayableItems[index]
                        Box(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .zIndex(if (draggedId == rightItem.id) 1f else 0f)
                                    .onGloballyPositioned { coords ->
                                        rootCoords?.let { root ->
                                            dragController.updateSlotBounds(
                                                rightItem.id,
                                                root.localBoundingBoxOf(coords),
                                            )
                                        }
                                    },
                        ) {
                            ReorderableSlot(
                                id = rightItem.id,
                                content = { dataMap[rightItem.id]!!(rightItem) },
                                isEditing = isEditing,
                                isDragged = draggedId == rightItem.id,
                                controller = dragController,
                                onHandlePositioned = onHandlePositioned,
                                fixedHeight = rightItem.id in fixedHeightIds,
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            )
                        }
                        index++
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Delete drop zone at the bottom when editing.
        if (isEditing) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
            val isHovered = dragController.hoveringDeleteZone
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .onGloballyPositioned { coords ->
                            rootCoords?.let { root ->
                                deleteZoneTopPx = root.localBoundingBoxOf(coords).top
                            }
                        },
                color =
                    if (isHovered) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint =
                            if (isHovered) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))
                    Text(
                        text = stringResource(R.string.action_delete_drop_zone),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (isHovered) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

/**
 * Single drag-and-drop render slot: drag visuals on the dragged item, an optional 48dp drag
 * handle (gating drag START) and the item content. Shared by [ReorderableGrid] and
 * [ReorderableList]. Keying the slot by id at the call site keeps composition identity stable.
 */
@Composable
internal fun <Id : Any> ReorderableSlot(
    id: Id,
    content: @Composable () -> Unit,
    isEditing: Boolean,
    isDragged: Boolean,
    controller: DragController<Id>,
    onHandlePositioned: (Id, LayoutCoordinates) -> Unit,
    fixedHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .then(
                    if (isDragged) {
                        Modifier
                            .offset {
                                IntOffset(
                                    controller.dragOffset.x.roundToInt(),
                                    controller.dragOffset.y.roundToInt(),
                                )
                            }.graphicsLayer {
                                alpha = 0.9f
                                shadowElevation = 12.dp.toPx()
                                scaleX = 1.05f
                                scaleY = 1.05f
                            }
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .then(
                        if (isEditing) {
                            Modifier.padding(
                                horizontal = MaterialTheme.spacing.small,
                                vertical = MaterialTheme.spacing.extraSmall,
                            )
                        } else {
                            Modifier
                        },
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isEditing) {
                // Drag gesture detection lives on the root Column (survives pendingOrder swaps
                // reparenting this slot mid-drag). This Box only reports its own bounds so the
                // root's hit test can restrict drag START to this 48dp handle.
                val dragHandleDescription = stringResource(R.string.accessibility_drag_to_reorder)
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .semantics { contentDescription = dragHandleDescription }
                            .onGloballyPositioned { coords -> onHandlePositioned(id, coords) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (fixedHeight) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(MaterialTheme.dimens.cardHeight),
                        contentAlignment = Alignment.Center,
                    ) { content() }
                } else {
                    content()
                }
            }
        }
    }
}
```

- [ ] **Step 4: Create the generic single-column list**

Create `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableList.kt`:

```kotlin
package app.readylytics.health.core.ui.components.reorder

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.domain.layout.ReorderableItem
import kotlin.math.roundToInt

/**
 * Single-column list supporting drag-and-drop reordering, generic over any [ReorderableItem].
 *
 * Single-column counterpart to [ReorderableGrid]: every item is full-width, one per row, so the
 * paired-row layout branch is absent. Dropping onto the bottom "hide" zone calls [onItemHide]
 * (a reversible visibility toggle) instead of removal.
 */
@Composable
fun <Id : Any, Config : ReorderableItem<Id>> ReorderableList(
    items: List<Config>,
    dataMap: Map<Id, @Composable (Config) -> Unit>,
    isEditing: Boolean,
    onItemReorder: (List<Config>) -> Unit,
    onItemHide: (Id) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<Id>? = null,
) {
    val configById: Map<Id, Config> =
        remember(items, dataMap.keys) {
            items
                .filter { it.isVisible && dataMap.containsKey(it.id) }
                .associateBy { it.id }
        }

    val dragController =
        remember {
            controller ?: DragController(
                items
                    .filter { it.isVisible && dataMap.containsKey(it.id) }
                    .sortedBy { it.position }
                    .map { it.id },
            )
        }

    LaunchedEffect(items, dataMap.keys) {
        val upstreamOrder =
            items
                .filter { it.isVisible && dataMap.containsKey(it.id) }
                .sortedBy { it.position }
                .map { it.id }
        dragController.syncFromUpstream(upstreamOrder)
    }

    val displayableItems: List<Config> =
        dragController.pendingOrder.mapNotNull { configById[it] }

    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var hideZoneTopPx by remember { mutableStateOf<Float?>(null) }

    val handleBounds = remember { mutableStateMapOf<Id, Rect>() }
    val hapticFeedback = LocalHapticFeedback.current

    val draggedId = dragController.draggedCardId

    val performDragEnd = {
        val result = dragController.onDragEnd()
        val draggedId = result.draggedId
        if (draggedId != null) {
            if (result.delete) {
                onItemHide(draggedId)
            } else {
                val updated =
                    result.finalOrder
                        .mapNotNull { id -> configById[id] }
                        .mapIndexed { index, config -> config.copy(position = index) }
                onItemReorder(updated)
            }
        }
    }

    val onHandlePositioned: (Id, LayoutCoordinates) -> Unit = { id, coords ->
        rootCoords?.let { root -> handleBounds[id] = root.localBoundingBoxOf(coords) }
    }

    val currentHideZoneTopPx by rememberUpdatedState(hideZoneTopPx)
    val currentPerformDragEnd by rememberUpdatedState(performDragEnd)

    Column(
        modifier =
            modifier
                .onGloballyPositioned { rootCoords = it }
                .then(
                    if (isEditing) {
                        Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val targetId =
                                        handleBounds.entries
                                            .firstOrNull { (_, rect) -> rect.contains(offset) }
                                            ?.key
                                    if (targetId != null) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragController.onDragStart(targetId)
                                    }
                                },
                                onDragEnd = { currentPerformDragEnd() },
                                onDragCancel = { currentPerformDragEnd() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    if (dragController.draggedCardId != null) {
                                        dragController.onDrag(dragAmount, currentHideZoneTopPx)
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.small),
    ) {
        displayableItems.forEach { item ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (draggedId == item.id) 1f else 0f)
                        .onGloballyPositioned { coords ->
                            rootCoords?.let { root ->
                                dragController.updateSlotBounds(item.id, root.localBoundingBoxOf(coords))
                            }
                        },
            ) {
                ReorderableSlot(
                    id = item.id,
                    content = { dataMap[item.id]!!(item) },
                    isEditing = isEditing,
                    isDragged = draggedId == item.id,
                    controller = dragController,
                    onHandlePositioned = onHandlePositioned,
                    fixedHeight = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Hide drop zone at the bottom when editing. Reuses the delete-zone mechanism —
        // dropping here hides the item (reversible), not removes it.
        if (isEditing) {
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.medium))
            val isHovered = dragController.hoveringDeleteZone
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .onGloballyPositioned { coords ->
                            rootCoords?.let { root ->
                                hideZoneTopPx = root.localBoundingBoxOf(coords).top
                            }
                        },
                color =
                    if (isHovered) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VisibilityOff,
                        contentDescription = null,
                        tint =
                            if (isHovered) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.extraSmall))
                    Text(
                        text = stringResource(R.string.action_hide_drop_zone),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (isHovered) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :core:ui:testDebugUnitTest --tests "app.readylytics.health.core.ui.components.reorder.ReorderableGridSleepTypeTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableGrid.kt core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableList.kt core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/reorder/ReorderableGridSleepTypeTest.kt
git commit -m "feat(core): add generic ReorderableGrid and ReorderableList composables"
```

---

### Task 3: Thin typed wrappers (ReorderableCardGrid + ReorderableChartList)

**Files:**
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableCardGrid.kt` (rewrite as wrapper)
- Modify: `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableChartList.kt` (rewrite as wrapper)
- Verify: `core/ui/src/test/kotlin/app/readylytics/health/core/ui/components/ReorderableCardGridThresholdTest.kt` still passes

**Interfaces:** Consumes `ReorderableGrid`/`ReorderableList` from Task 2. Produces the original public API (unchanged signatures) so `DashboardScreen`, `VitalsScreen`, and `VitalsTrendSection` are untouched.

- [ ] **Step 1: Rewrite ReorderableCardGrid.kt as a wrapper**

Replace the ENTIRE contents of `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableCardGrid.kt` with:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.core.ui.components.reorder.ReorderableGrid
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId

// Cards that should span the entire width instead of pairing into a row.
private val FULL_WIDTH_CARDS =
    setOf(
        CardId.STEPS,
        CardId.INSIGHTS,
        CardId.AI_RECOMMENDATION,
    )

// Gauge dial cards that render inside a fixed-height box so paired rows stay uniform.
private val FIXED_HEIGHT_CARDS =
    setOf(
        CardId.SLEEP_SCORE,
        CardId.READINESS,
    )

@Immutable
data class CardConfigurationsList(
    val items: List<CardConfiguration>,
)

@Immutable
data class CardDataMap(
    val map: Map<CardId, @Composable (CardConfiguration) -> Unit>,
)

@Composable
fun ReorderableCardGrid(
    cardConfigurations: CardConfigurationsList,
    cardDataMap: CardDataMap,
    isEditing: Boolean,
    onCardRemove: (CardId) -> Unit,
    onCardReorder: (List<CardConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<CardId>? = null,
) {
    ReorderableGrid(
        items = cardConfigurations.items,
        dataMap = cardDataMap.map,
        isEditing = isEditing,
        onItemReorder = onCardReorder,
        onItemDropToRemove = onCardRemove,
        fullWidthIds = FULL_WIDTH_CARDS,
        fixedHeightIds = FIXED_HEIGHT_CARDS,
        modifier = modifier,
        controller = controller,
    )
}
```

- [ ] **Step 2: Rewrite ReorderableChartList.kt as a wrapper**

Replace the ENTIRE contents of `core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableChartList.kt` with:

```kotlin
package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.core.ui.components.reorder.ReorderableList
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId

@Immutable
data class ChartConfigurationsList(
    val items: List<VitalsChartConfiguration>,
)

@Immutable
data class ChartDataMap(
    val map: Map<VitalsChartId, @Composable (VitalsChartConfiguration) -> Unit>,
)

@Composable
fun ReorderableChartList(
    chartConfigurations: ChartConfigurationsList,
    chartDataMap: ChartDataMap,
    isEditing: Boolean,
    onChartHide: (VitalsChartId) -> Unit,
    onChartReorder: (List<VitalsChartConfiguration>) -> Unit,
    modifier: Modifier = Modifier,
    controller: DragController<VitalsChartId>? = null,
) {
    ReorderableList(
        items = chartConfigurations.items,
        dataMap = chartDataMap.map,
        isEditing = isEditing,
        onItemReorder = onChartReorder,
        onItemHide = onChartHide,
        modifier = modifier,
        controller = controller,
    )
}
```

- [ ] **Step 3: Run existing tests to verify no regressions**

Run: `./gradlew :core:ui:testDebugUnitTest`
Expected: PASS — includes `ReorderableCardGridThresholdTest`, `CardConfigurationsListTest`, `DragControllerTest`, and the new `ReorderableGridSleepTypeTest`.

- [ ] **Step 4: Commit**

```bash
git add core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableCardGrid.kt core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/ReorderableChartList.kt
git commit -m "refactor(core): make ReorderableCardGrid and ReorderableChartList thin typed wrappers"
```

---

### Task 4: Sleep screen rewiring — renderers + data-driven sections

**Files:**
- Create: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt`
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt`
- Test: `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderersTest.kt`

**Interfaces:** Consumes generic `ReorderableGrid`/`ReorderableList` (Task 2) and the existing `onReorderSleep*` / `onToggleSleep*Visibility` ViewModel callbacks. Produces the data-driven Sleep tab with on-screen drag.

- [ ] **Step 1: Write the failing test**

Create `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderersTest.kt`:

```kotlin
package app.readylytics.health.feature.sleep

import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SleepLayoutRenderersTest {
    @Test
    fun `top card data map covers every top card id`() {
        val map = buildSleepTopCardDataMap(SleepUiState(), null)
        assertEquals(SleepTopCardId.entries.toSet(), map.keys)
        SleepTopCardId.entries.forEach { assertNotNull(map[it]) }
    }

    @Test
    fun `metric card data map covers every metric card id`() {
        val map = buildSleepMetricCardDataMap(SleepUiState(), CircadianConsistencyResult.Calibrating, null)
        assertEquals(SleepMetricCardId.entries.toSet(), map.keys)
        SleepMetricCardId.entries.forEach { assertNotNull(map[it]) }
    }

    @Test
    fun `full width sleep top cards set excludes the gauges`() {
        assertEquals(
            setOf(
                SleepTopCardId.SLEEP_BREAKDOWN_BAR,
                SleepTopCardId.SLEEP_STAGES_TIMELINE,
                SleepTopCardId.SLEEP_HR_CHART,
            ),
            SLEEP_TOP_CARD_FULL_WIDTH_IDS,
        )
        assertEquals(
            setOf(SleepTopCardId.SLEEP_SCORE, SleepTopCardId.SLEEP_DURATION_GAUGE),
            SleepTopCardId.entries.toSet() - SLEEP_TOP_CARD_FULL_WIDTH_IDS,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :feature:sleep:testDebugUnitTest --tests "app.readylytics.health.feature.sleep.SleepLayoutRenderersTest"`
Expected: FAIL — `unresolved reference: buildSleepTopCardDataMap` etc.

- [ ] **Step 3: Create SleepLayoutRenderers.kt**

Create `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt`:

```kotlin
package app.readylytics.health.feature.sleep

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.core.ui.common.ScoreDialSkeleton
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.common.formatRoundedScoreDelta
import app.readylytics.health.core.ui.common.resolveOrNull
import app.readylytics.health.core.ui.components.TrendCard
import app.readylytics.health.core.ui.components.metriccard.UniversalCardDisplayMode
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCard
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricCardSpec
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricPresentation
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricScalePreparer
import app.readylytics.health.core.ui.components.metriccard.UniversalMetricVisual
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.model.deepSleepStatus
import app.readylytics.health.domain.model.efficiencyStatus
import app.readylytics.health.domain.model.remSleepStatus
import app.readylytics.health.domain.model.scoreStatus
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.scoring.toStatus
import app.readylytics.health.domain.scoring.toTimeString
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.domain.util.roundToPercentInt
import app.readylytics.health.feature.sleep.R
import app.readylytics.health.core.ui.R as CoreUiR

/** Full-width top cards (architecture bar, stages timeline, HR chart). The two gauges pair up. */
val SLEEP_TOP_CARD_FULL_WIDTH_IDS: Set<SleepTopCardId> =
    setOf(
        SleepTopCardId.SLEEP_BREAKDOWN_BAR,
        SleepTopCardId.SLEEP_STAGES_TIMELINE,
        SleepTopCardId.SLEEP_HR_CHART,
    )

@Composable
fun rememberSleepTopCardDataMap(
    uiState: SleepUiState,
    singleSessionVisual: SleepSessionData?,
): Map<SleepTopCardId, @Composable (SleepTopCardConfiguration) -> Unit> =
    remember(uiState, singleSessionVisual) {
        buildSleepTopCardDataMap(uiState, singleSessionVisual)
    }

/** Pure builder — unit-testable without composition. */
fun buildSleepTopCardDataMap(
    uiState: SleepUiState,
    singleSessionVisual: SleepSessionData?,
): Map<SleepTopCardId, @Composable (SleepTopCardConfiguration) -> Unit> =
    mapOf(
        SleepTopCardId.SLEEP_SCORE to
            @Composable { _: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    ScoreDialSkeleton()
                } else {
                    SleepScoreCard(
                        title = stringResource(R.string.sleep_score_gauge_title),
                        score = uiState.latestSummary?.sleepScore,
                        displayText =
                            uiState.latestMetrics?.sleepScoreRounded?.toString()
                                ?: stringResource(CoreUiR.string.metric_value_unavailable),
                        unitText = "",
                        deltaText =
                            formatRoundedScoreDelta(
                                currentRounded = uiState.latestMetrics?.sleepScoreRounded,
                                previousRounded = uiState.yesterdaySleepScoreRounded,
                            ).resolveOrNull(),
                        tooltipDescription = stringResource(CoreUiR.string.tooltip_sleep_score),
                    )
                }
            },
        SleepTopCardId.SLEEP_DURATION_GAUGE to
            @Composable { config: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    ScoreDialSkeleton()
                } else {
                    val sleepTimeGaugeData = uiState.sleepTimeGaugeData
                    val goalText =
                        DateFormatUtils.formatSleepDuration(
                            (uiState.goalSleepHours * 60f).toInt().coerceAtLeast(0),
                        )
                    SleepMetricCard(
                        title = stringResource(R.string.sleep_time_gauge_title),
                        rawValue = sleepTimeGaugeData.progress,
                        valueText = sleepTimeGaugeData.gaugeValueText,
                        unitText = sleepTimeGaugeData.gaugeUnitText,
                        maxScore = 1f,
                        status = sleepTimeGaugeData.status,
                        deltaText = sleepTimeGaugeData.deltaText.resolveOrNull(),
                        mode =
                            config.requestedDisplayMode?.toUniversalMode()
                                ?: UniversalCardDisplayMode.GAUGE,
                        tooltip = stringResource(CoreUiR.string.tooltip_sleep_duration, goalText),
                    )
                }
            },
        SleepTopCardId.SLEEP_BREAKDOWN_BAR to
            @Composable { _: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    SkeletonCard(height = 120.dp)
                } else {
                    TrendCard(title = stringResource(R.string.sleep_breakdown_title)) {
                        SleepArchitectureBar(
                            session = singleSessionVisual,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        SleepTopCardId.SLEEP_STAGES_TIMELINE to
            @Composable { _: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    SkeletonCard(height = 260.dp)
                } else {
                    TrendCard(title = stringResource(R.string.sleep_timeline_title)) {
                        SleepStagesChart(
                            session = singleSessionVisual,
                            stageTimeline = uiState.stageTimeline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        SleepTopCardId.SLEEP_HR_CHART to
            @Composable { _: SleepTopCardConfiguration ->
                if (uiState.isLoading) {
                    SkeletonCard(height = 260.dp)
                } else {
                    TrendCard(title = stringResource(R.string.sleep_hr_chart_title)) {
                        SleepHrChart(
                            session = singleSessionVisual,
                            samples = uiState.sleepHrSamples,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
    )

@Composable
fun rememberSleepMetricCardDataMap(
    uiState: SleepUiState,
    circadianResult: CircadianConsistencyResult,
    singleSessionVisual: SleepSessionData?,
): Map<SleepMetricCardId, @Composable (SleepMetricCardConfiguration) -> Unit> =
    remember(uiState, circadianResult, singleSessionVisual) {
        buildSleepMetricCardDataMap(uiState, circadianResult, singleSessionVisual)
    }

/** Pure builder — unit-testable without composition. */
fun buildSleepMetricCardDataMap(
    uiState: SleepUiState,
    circadianResult: CircadianConsistencyResult,
    singleSessionVisual: SleepSessionData?,
): Map<SleepMetricCardId, @Composable (SleepMetricCardConfiguration) -> Unit> {
    val session = singleSessionVisual
    val summary = uiState.latestSummary
    val metrics = uiState.latestMetrics

    val efficiencyStatus = session?.efficiencyStatus() ?: MetricStatus.NO_DATA
    val deepStatus = summary?.deepSleepStatus() ?: MetricStatus.NO_DATA
    val remStatus = summary?.remSleepStatus() ?: MetricStatus.NO_DATA

    return mapOf(
        SleepMetricCardId.CIRCADIAN_CONSISTENCY to
            @Composable { config: SleepMetricCardConfiguration ->
                val scoreText =
                    when (circadianResult) {
                        is CircadianConsistencyResult.Calibrating ->
                            stringResource(CoreUiR.string.spo2_calibrating)
                        is CircadianConsistencyResult.MissingData -> "—"
                        is CircadianConsistencyResult.Ready -> "${circadianResult.score.roundToPercentInt()}%"
                    }
                val windowText =
                    when (circadianResult) {
                        is CircadianConsistencyResult.Calibrating,
                        is CircadianConsistencyResult.MissingData,
                        -> null
                        is CircadianConsistencyResult.Ready ->
                            stringResource(
                                CoreUiR.string.label_circadian_median,
                                circadianResult.medianBedtimeMinutes.toTimeString(),
                                circadianResult.medianWakeMinutes.toTimeString(),
                            )
                    }
                val thresholdMinutes =
                    when (circadianResult) {
                        is CircadianConsistencyResult.Calibrating,
                        is CircadianConsistencyResult.MissingData,
                        -> 30
                        is CircadianConsistencyResult.Ready -> circadianResult.thresholdMinutes
                    }
                val tooltipText =
                    stringResource(CoreUiR.string.tooltip_circadian_score, thresholdMinutes)

                SleepMetricCard(
                    title = stringResource(CoreUiR.string.label_circadian_consistency),
                    valueText = scoreText,
                    secondaryText = windowText,
                    status = circadianResult.toStatus(),
                    tooltip = tooltipText,
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.SLEEP_EFFICIENCY to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(CoreUiR.string.card_title_sleep_efficiency),
                    valueText =
                        session?.let {
                            stringResource(
                                CoreUiR.string.card_efficiency_format,
                                it.efficiency.roundToPercentInt(),
                            )
                        } ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    secondaryText = stringResource(CoreUiR.string.card_goal_sleep_efficiency),
                    status = efficiencyStatus,
                    tooltip = stringResource(CoreUiR.string.card_tooltip_sleep_efficiency),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.DEEP_SLEEP to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(R.string.card_title_deep_sleep),
                    valueText =
                        metrics?.deepSleepPercentDisplay
                            ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    secondaryText = stringResource(R.string.card_target_deep_sleep),
                    status = deepStatus,
                    tooltip = stringResource(R.string.tooltip_deep_sleep),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.REM_SLEEP to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(R.string.card_title_rem_sleep),
                    valueText =
                        metrics?.remSleepPercentDisplay
                            ?: stringResource(CoreUiR.string.metric_value_unavailable),
                    secondaryText = stringResource(R.string.card_target_rem_sleep),
                    status = remStatus,
                    tooltip = stringResource(R.string.tooltip_rem_sleep),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.NAP_DURATION to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(R.string.card_title_nap_duration),
                    valueText = metrics?.napDurationDisplay ?: DateFormatUtils.formatSleepDuration(0),
                    status = MetricStatus.NEUTRAL,
                    tooltip = stringResource(R.string.tooltip_nap_duration),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
        SleepMetricCardId.NAP_COUNT to
            @Composable { config: SleepMetricCardConfiguration ->
                SleepMetricCard(
                    title = stringResource(R.string.card_title_nap_count),
                    valueText = metrics?.napCount?.toString() ?: "0",
                    status = MetricStatus.NEUTRAL,
                    tooltip = stringResource(R.string.tooltip_nap_count),
                    mode =
                        config.requestedDisplayMode?.toUniversalMode()
                            ?: UniversalCardDisplayMode.VALUE,
                )
            },
    )
}

@Composable
private fun SleepScoreCard(
    score: Float?,
    displayText: String,
    unitText: String,
    deltaText: String?,
    tooltipDescription: String,
    modifier: Modifier = Modifier,
    title: String,
) {
    SleepMetricCard(
        title = title,
        rawValue = score,
        valueText = displayText,
        unitText = unitText,
        status = score.scoreStatus(),
        tooltip = tooltipDescription,
        deltaText = deltaText,
        mode = UniversalCardDisplayMode.GAUGE,
        modifier = modifier,
    )
}

@Composable
private fun SleepMetricCard(
    title: String,
    valueText: String,
    status: MetricStatus,
    tooltip: String,
    modifier: Modifier = Modifier,
    unitText: String = "",
    secondaryText: String? = null,
    rawValue: Float? = null,
    maxScore: Float = 100f,
    deltaText: String? = null,
    mode: UniversalCardDisplayMode = UniversalCardDisplayMode.VALUE,
    tooltipDescription: String? = null,
) {
    val secondary = deltaText ?: secondaryText
    UniversalMetricCard(
        presentation =
            UniversalMetricPresentation(
                title = title,
                valueText = valueText,
                unitText = unitText,
                secondaryText = secondary,
                status = status,
                tooltip = tooltipDescription ?: tooltip,
                accessibilityDescription = "$title: $valueText",
                visual =
                    if (mode == UniversalCardDisplayMode.GAUGE) {
                        UniversalMetricScalePreparer.score(rawValue, 0f, maxScore)
                    } else {
                        UniversalMetricVisual.ValueOnly
                    },
            ),
        specification =
            UniversalMetricCardSpec(
                supportedModes = listOf(mode),
                usesDeltaPill = deltaText != null,
            ),
        requestedMode = mode,
        modifier = modifier,
    )
}

private fun DashboardCardDisplayMode.toUniversalMode(): UniversalCardDisplayMode =
    when (this) {
        DashboardCardDisplayMode.GAUGE -> UniversalCardDisplayMode.GAUGE
        DashboardCardDisplayMode.BAR -> UniversalCardDisplayMode.BAR
        DashboardCardDisplayMode.VALUE -> UniversalCardDisplayMode.VALUE
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :feature:sleep:testDebugUnitTest --tests "app.readylytics.health.feature.sleep.SleepLayoutRenderersTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Rewire SleepScreen.kt**

Replace the ENTIRE contents of `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt` with:

```kotlin
package app.readylytics.health.feature.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.common.MetricCardSkeleton
import app.readylytics.health.core.ui.common.TimeRange
import app.readylytics.health.core.ui.components.ChartDefaults
import app.readylytics.health.core.ui.components.EditModeFab
import app.readylytics.health.core.ui.components.SectionHeader
import app.readylytics.health.core.ui.components.StatusLegend
import app.readylytics.health.core.ui.components.reorder.ReorderableGrid
import app.readylytics.health.core.ui.components.reorder.ReorderableList
import app.readylytics.health.core.ui.dashboard.DateSwitcher
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.feature.sleep.R
import app.readylytics.health.feature.sleep.overview.SleepManagementBottomSheet
import kotlinx.coroutines.launch
import app.readylytics.health.core.ui.R as CoreUiR

@Composable
fun SleepRoute(viewModel: SleepViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val circadian by viewModel.circadianConsistencyFlow.collectAsStateWithLifecycle()
    val earliestDate by viewModel.earliestDate.collectAsStateWithLifecycle()

    SleepScreen(
        uiState = uiState,
        circadianConsistency = circadian,
        onPreviousDay = viewModel::onPreviousDay,
        onNextDay = viewModel::onNextDay,
        onDateSelected = viewModel::onDateSelected,
        onTrendRangeSelected = viewModel::onTrendRangeSelected,
        earliestDate = earliestDate,
        onToggleSleepManagement = viewModel::toggleSleepLayoutManagement,
        onCancelSleepManagement = viewModel::onCancelSleepLayoutManagement,
        onToggleSleepTopCardVisibility = viewModel::onToggleSleepTopCardVisibility,
        onReorderSleepTopCards = viewModel::onReorderSleepTopCards,
        onSleepTopCardDisplayModeChanged = viewModel::onSleepTopCardDisplayModeChanged,
        onToggleSleepChartVisibility = viewModel::onToggleSleepChartVisibility,
        onReorderSleepCharts = viewModel::onReorderSleepCharts,
        onToggleSleepMetricCardVisibility = viewModel::onToggleSleepMetricCardVisibility,
        onReorderSleepMetricCards = viewModel::onReorderSleepMetricCards,
        onSleepMetricCardDisplayModeChanged = viewModel::onSleepMetricCardDisplayModeChanged,
        onResetSleepLayoutToDefaults = viewModel::onResetSleepLayoutToDefaults,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepScreen(
    uiState: SleepUiState,
    circadianConsistency: CircadianConsistencyResult,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    modifier: Modifier = Modifier,
    onDateSelected: (java.time.LocalDate) -> Unit = {},
    onTrendRangeSelected: (TimeRange) -> Unit = {},
    earliestDate: java.time.LocalDate? = null,
    onToggleSleepManagement: () -> Unit = {},
    onCancelSleepManagement: () -> Unit = {},
    onToggleSleepTopCardVisibility: (SleepTopCardId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepTopCards: (List<SleepTopCardConfiguration>) -> Unit = {},
    onSleepTopCardDisplayModeChanged: (SleepTopCardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> },
    onToggleSleepChartVisibility: (SleepChartId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepCharts: (List<SleepChartConfiguration>) -> Unit = {},
    onToggleSleepMetricCardVisibility: (SleepMetricCardId, Boolean) -> Unit = { _, _ -> },
    onReorderSleepMetricCards: (List<SleepMetricCardConfiguration>) -> Unit = {},
    onSleepMetricCardDisplayModeChanged: (SleepMetricCardId, DashboardCardDisplayMode?) -> Unit = { _, _ -> },
    onResetSleepLayoutToDefaults: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    var showSleepManagement by rememberSaveable { mutableStateOf(false) }

    val singleSessionVisual = uiState.latestSession
    val (trendScrollState, trendZoomState) =
        ChartDefaults.rememberChartState(
            rangeDays = uiState.selectedTrendRange.days,
            key = uiState.selectedTrendRange,
        )

    val visibleCharts =
        remember(uiState.sleepChartConfigurations) {
            uiState.sleepChartConfigurations.filter { it.isVisible }.sortedBy { it.position }
        }
    val visibleMetricCards =
        remember(uiState.sleepMetricCardConfigurations) {
            uiState.sleepMetricCardConfigurations.filter { it.isVisible }.sortedBy { it.position }
        }

    val topCardDataMap =
        rememberSleepTopCardDataMap(
            uiState = uiState,
            singleSessionVisual = singleSessionVisual,
        )

    val trendChartDataMap: Map<SleepChartId, @Composable (SleepChartConfiguration) -> Unit> =
        mapOf(
            SleepChartId.SLEEP_DURATION_TREND to
                @Composable { _: SleepChartConfiguration ->
                    if (uiState.isLoading) {
                        SleepTrendSkeleton()
                    } else {
                        SleepTrendCard(
                            selectedRange = uiState.selectedTrendRange,
                            startOffsetPoints = uiState.trendStartOffsetPoints,
                            durationSpanPoints = uiState.trendDurationSpanPoints,
                            actualDurationPoints = uiState.trendActualDurationPoints,
                            trendDays = uiState.trendDays,
                            rangeStartMs = uiState.trendRangeStartMs,
                            scoringZoneId = uiState.trendScoringZoneId,
                            scrollState = trendScrollState,
                            zoomState = trendZoomState,
                            parentScrollInProgress = { scrollState.isScrollInProgress },
                            actualDurationSummary = uiState.trendActualDurationSummary,
                        )
                    }
                },
        )

    Box(modifier = modifier.fillMaxSize()) {
        if (showSleepManagement) {
            SleepManagementBottomSheet(
                topCardConfigurations = uiState.sleepTopCardConfigurations,
                chartConfigurations = uiState.sleepChartConfigurations,
                metricCardConfigurations = uiState.sleepMetricCardConfigurations,
                onTopCardVisibilityChanged = onToggleSleepTopCardVisibility,
                onChartVisibilityChanged = onToggleSleepChartVisibility,
                onMetricCardVisibilityChanged = onToggleSleepMetricCardVisibility,
                onTopCardDisplayModeChanged = onSleepTopCardDisplayModeChanged,
                onMetricCardDisplayModeChanged = onSleepMetricCardDisplayModeChanged,
                onResetToDefaults = onResetSleepLayoutToDefaults,
                onDismiss = {
                    scope.launch { sheetState.hide() }
                    showSleepManagement = false
                },
                sheetState = sheetState,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = MaterialTheme.spacing.pageTop, bottom = MaterialTheme.spacing.pageBottom),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            ) {
                DateSwitcher(
                    selectedDate = uiState.selectedDate,
                    onPreviousDay = onPreviousDay,
                    onNextDay = onNextDay,
                    onDateSelected = onDateSelected,
                    earliestDate = earliestDate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

            ReorderableGrid(
                items = uiState.sleepTopCardConfigurations,
                dataMap = topCardDataMap,
                isEditing = uiState.isManagingSleepTopCards,
                onItemReorder = onReorderSleepTopCards,
                onItemDropToRemove = { onToggleSleepTopCardVisibility(it, false) },
                fullWidthIds = SLEEP_TOP_CARD_FULL_WIDTH_IDS,
                verticalSpacing = MaterialTheme.spacing.pageSectionGapSmall,
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
            )

            if (visibleCharts.any { it.chartId == SleepChartId.SLEEP_DURATION_TREND }) {
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

                SectionHeader(
                    title = stringResource(R.string.sleep_trend_section_title),
                    enabled = !uiState.isLoading,
                )
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

                SingleChoiceSegmentedButtonRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                ) {
                    TimeRange.entries.forEachIndexed { index, range ->
                        SegmentedButton(
                            selected = uiState.selectedTrendRange == range,
                            onClick = { onTrendRangeSelected(range) },
                            enabled = !uiState.isLoading,
                            shape =
                                SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = TimeRange.entries.size,
                                ),
                            label = { Text(range.label) },
                        )
                    }
                }
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

                ReorderableList(
                    items = uiState.sleepChartConfigurations,
                    dataMap = trendChartDataMap,
                    isEditing = uiState.isManagingSleepCharts,
                    onItemReorder = onReorderSleepCharts,
                    onItemHide = { onToggleSleepChartVisibility(it, false) },
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                )
            }

            if (visibleMetricCards.isNotEmpty()) {
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapLarge))

                SectionHeader(title = stringResource(R.string.sleep_metrics_title))
                Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))

                if (uiState.isLoading) {
                    MetricsGridSkeleton(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal))
                } else {
                    ReorderableGrid(
                        items = uiState.sleepMetricCardConfigurations,
                        dataMap =
                            rememberSleepMetricCardDataMap(
                                uiState,
                                circadianConsistency,
                                singleSessionVisual,
                            ),
                        isEditing = uiState.isManagingSleepMetricCards,
                        onItemReorder = onReorderSleepMetricCards,
                        onItemDropToRemove = { onToggleSleepMetricCardVisibility(it, false) },
                        fullWidthIds = emptySet(),
                        verticalSpacing = MaterialTheme.spacing.pageSectionGapSmall,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    )
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGap))

            StatusLegend()

            if (!uiState.isManagingSleepLayout) {
                FilledTonalButton(
                    onClick = onToggleSleepManagement,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MaterialTheme.spacing.pageHorizontal,
                                vertical = MaterialTheme.spacing.pageSectionGap,
                            ),
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                ) {
                    Text(
                        text = stringResource(CoreUiR.string.action_customize),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        EditModeFab(
            isVisible = uiState.isManagingSleepLayout,
            onDoneClick = onToggleSleepManagement,
            onCancelClick = onCancelSleepManagement,
            onManageClick = { showSleepManagement = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(MaterialTheme.spacing.pageHorizontal),
        )
    }
}

@Composable
private fun MetricsGridSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.pageSectionGapSmall),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MetricCardSkeleton()
            }
        }
    }
}
```

Note: `Arrangement` is already imported in the import list above (used by `MetricsGridSkeleton`).

- [ ] **Step 6: Run the feature tests to verify nothing broke**

Run: `./gradlew :feature:sleep:testDebugUnitTest`
Expected: PASS — includes `SleepViewModelLayoutManagementTest`, `SleepViewModelTest`, `SleepLayoutRenderersTest`, and the existing sleep unit tests.

- [ ] **Step 7: Commit**

```bash
git add feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderersTest.kt
git commit -m "feat(sleep): wire on-screen drag-and-drop for top cards, metric grid, and trend chart"
```

---

### Task 5: Bottom sheet cleanup (remove arrow reorder controls) + strings

**Files:**
- Modify: `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheet.kt`
- Modify: `feature/sleep/src/main/res/values/strings.xml`
- Modify: `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheetTest.kt`

**Interfaces:** Reorder is now drag-only (Task 4). The sheet keeps visibility toggles, display-mode pickers, and reset — matching `VitalsManagementBottomSheet`.

- [ ] **Step 1: Replace SleepManagementBottomSheet.kt**

Replace the ENTIRE contents of `feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheet.kt` with:

```kotlin
package app.readylytics.health.feature.sleep.overview

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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.feature.sleep.R
import app.readylytics.health.core.ui.R as CoreUiR

/**
 * Unified bottom sheet for customizing the layout of the Sleep tab.
 *
 * Reordering happens on the Sleep screen via drag-and-drop while in edit mode; this sheet
 * provides visibility toggles, display-mode pickers, and reset-to-defaults for the three
 * sections (top cards, charts, metric cards).
 */
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
                    text = stringResource(R.string.sleep_manage_layout),
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = onResetToDefaults) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = stringResource(CoreUiR.string.action_reset_to_defaults),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text(stringResource(R.string.sleep_management_top_cards_section_title)) },
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text(stringResource(R.string.sleep_management_charts_section_title)) },
                )
                Tab(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    text = { Text(stringResource(R.string.sleep_management_metrics_section_title)) },
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.small))

            val sortedTopCards = remember(topCardConfigurations) { topCardConfigurations.sortedBy { it.position } }
            val sortedCharts = remember(chartConfigurations) { chartConfigurations.sortedBy { it.position } }
            val sortedMetricCards =
                remember(metricCardConfigurations) { metricCardConfigurations.sortedBy { it.position } }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                when (selectedTabIndex) {
                    0 -> {
                        items(sortedTopCards, key = { "top_card_${it.cardId.name}" }) { card ->
                            TopCardManagementItem(
                                card = card,
                                onVisibilityChanged = { visible -> onTopCardVisibilityChanged(card.cardId, visible) },
                                onDisplayModeChanged =
                                    onTopCardDisplayModeChanged?.let { callback ->
                                        { mode -> callback(card.cardId, mode) }
                                    },
                            )
                        }
                    }
                    1 -> {
                        items(sortedCharts, key = { "chart_${it.chartId.name}" }) { chart ->
                            ChartManagementItem(
                                chart = chart,
                                onVisibilityChanged = { visible -> onChartVisibilityChanged(chart.chartId, visible) },
                            )
                        }
                    }
                    2 -> {
                        items(sortedMetricCards, key = { "metric_card_${it.cardId.name}" }) { card ->
                            MetricCardManagementItem(
                                card = card,
                                onVisibilityChanged = { visible ->
                                    onMetricCardVisibilityChanged(card.cardId, visible)
                                },
                                onDisplayModeChanged =
                                    onMetricCardDisplayModeChanged?.let { callback ->
                                        { mode -> callback(card.cardId, mode) }
                                    },
                            )
                        }
                    }
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
                Text(stringResource(CoreUiR.string.action_done))
            }
        }
    }
}

@Composable
private fun TopCardManagementItem(
    card: SleepTopCardConfiguration,
    onVisibilityChanged: (Boolean) -> Unit,
    onDisplayModeChanged: ((DashboardCardDisplayMode?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(card.cardId.displayNameResId),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent =
            if (onDisplayModeChanged != null) {
                {
                    DisplayModeDropdownSelector(
                        selectedMode = card.requestedDisplayMode,
                        onModeSelected = onDisplayModeChanged,
                    )
                }
            } else {
                null
            },
        trailingContent = {
            Checkbox(
                checked = card.isVisible,
                onCheckedChange = onVisibilityChanged,
            )
        },
        modifier = modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
    )
}

@Composable
private fun ChartManagementItem(
    chart: SleepChartConfiguration,
    onVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(chart.chartId.displayNameResId),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Checkbox(
                checked = chart.isVisible,
                onCheckedChange = onVisibilityChanged,
            )
        },
        modifier = modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
    )
}

@Composable
private fun MetricCardManagementItem(
    card: SleepMetricCardConfiguration,
    onVisibilityChanged: (Boolean) -> Unit,
    onDisplayModeChanged: ((DashboardCardDisplayMode?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(card.cardId.displayNameResId),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        supportingContent =
            if (onDisplayModeChanged != null) {
                {
                    DisplayModeDropdownSelector(
                        selectedMode = card.requestedDisplayMode,
                        onModeSelected = onDisplayModeChanged,
                    )
                }
            } else {
                null
            },
        trailingContent = {
            Checkbox(
                checked = card.isVisible,
                onCheckedChange = onVisibilityChanged,
            )
        },
        modifier = modifier.padding(vertical = MaterialTheme.spacing.extraSmall),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayModeDropdownSelector(
    selectedMode: DashboardCardDisplayMode?,
    onModeSelected: (DashboardCardDisplayMode?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    val displayValue =
        when (selectedMode) {
            DashboardCardDisplayMode.GAUGE -> stringResource(R.string.sleep_management_display_mode_gauge)
            DashboardCardDisplayMode.BAR -> stringResource(R.string.sleep_management_display_mode_bar)
            DashboardCardDisplayMode.VALUE -> stringResource(R.string.sleep_management_display_mode_value)
            null -> stringResource(R.string.sleep_management_display_mode_default)
        }

    val modeOptions =
        listOf(
            null,
            DashboardCardDisplayMode.GAUGE,
            DashboardCardDisplayMode.BAR,
            DashboardCardDisplayMode.VALUE,
        )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.padding(top = MaterialTheme.spacing.extraSmall),
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.sleep_management_display_mode_label)) },
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
            modeOptions.forEach { option ->
                val label =
                    when (option) {
                        DashboardCardDisplayMode.GAUGE -> stringResource(R.string.sleep_management_display_mode_gauge)
                        DashboardCardDisplayMode.BAR -> stringResource(R.string.sleep_management_display_mode_bar)
                        DashboardCardDisplayMode.VALUE -> stringResource(R.string.sleep_management_display_mode_value)
                        null -> stringResource(R.string.sleep_management_display_mode_default)
                    }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onModeSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 2: Remove the now-unused arrow strings**

In `feature/sleep/src/main/res/values/strings.xml`, delete the two lines:

```xml
    <string name="sleep_management_move_up">Move up</string>
    <string name="sleep_management_move_down">Move down</string>
```

- [ ] **Step 3: Update SleepManagementBottomSheetTest**

In `feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheetTest.kt`, delete the now-obsolete test `reordering top card list updates position indices deterministically` (its swap logic no longer exists; reordering is drag-only on the screen). Keep the other three tests unchanged.

- [ ] **Step 4: Run the feature tests**

Run: `./gradlew :feature:sleep:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheet.kt feature/sleep/src/main/res/values/strings.xml feature/sleep/src/test/kotlin/app/readylytics/health/feature/sleep/overview/SleepManagementBottomSheetTest.kt
git commit -m "refactor(sleep): remove bottom-sheet arrow reorder controls (drag-only reorder)"
```

---

### Task 6: Final verification & clean-up

**Files:**
- No source changes unless verification fails.

- [ ] **Step 1: Run mandatory pre-commit check**

Run: `./gradlew ktlintFormat && ./gradlew testDebugUnitTest`
Expected: PASS. If ktlint rewrites files, review the diff and include it in the commit.

- [ ] **Step 2: Run release lint**

Run: `./gradlew lintRelease`
Expected: PASS (or only pre-existing warnings; no new errors).

- [ ] **Step 3: Refresh codegraph index**

Run: `codegraph index` (new files were created: `ReorderableItem.kt`, `ReorderableGrid.kt`, `ReorderableList.kt`, `SleepLayoutRenderers.kt`; AGENTS.md File Lifecycle requirement).

- [ ] **Step 4: Commit any remaining changes**

```bash
git add -A
git commit -m "chore(sleep): final cleanup for on-screen drag-and-drop"
```

---

## Plan Self-Review Notes

- **Spec coverage:** Section 2 (interface + generics) → Tasks 1-2; Section 2.3 (wrappers) → Task 3; Section 3 (Sleep wiring + sheet) → Tasks 4-5; Section 5 (strings) → Task 5 Step 2; Section 6 (testing) → each task plus Task 6; Section 7 (docs) → no `DATA_FLOW.md` change (UI-only), design spec documents this.
- **Type consistency:** `ReorderableItem<Id>` (`id`/`isVisible`/`position`) used consistently across Tasks 1-4. `ReorderableGrid`/`ReorderableList` param names (`items`, `dataMap`, `isEditing`, `onItemReorder`, `onItemDropToRemove`/`onItemHide`, `fullWidthIds`, `fixedHeightIds`, `verticalSpacing`) match between Task 2 definitions and Task 4 call sites. Wrapper signatures in Task 3 exactly match the originals consumed by `DashboardScreen`/`VitalsScreen`.
- **Placeholders:** none — every code step is complete.
