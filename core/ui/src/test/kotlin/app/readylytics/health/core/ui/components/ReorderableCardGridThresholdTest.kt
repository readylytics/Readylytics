package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.ui.components.reorder.DragController
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.dashboard.DashboardCardDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests verifying that ReorderableCardGrid wires bounds → DragController
 * → center-cross detection correctly, and that drag-gesture detection is isolated to the
 * per-card drag handle (Task 8) rather than the whole card body.
 *
 * Detailed algorithm unit tests live in DragControllerTest. These tests focus on
 * the integration contract: correct bounds registration leads to correct detection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReorderableCardGridThresholdTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var controller: DragController

    @Before
    fun setUp() {
        controller = DragController(listOf(CardId.SLEEP_SCORE, CardId.HRV, CardId.STEPS))
        // SLEEP_SCORE: y 0..150, HRV: y 150..300, STEPS: y 300..450
        controller.updateSlotBounds(CardId.SLEEP_SCORE, Rect(0f, 0f, 200f, 150f))
        controller.updateSlotBounds(CardId.HRV, Rect(0f, 150f, 200f, 300f))
        controller.updateSlotBounds(CardId.STEPS, Rect(0f, 300f, 200f, 450f))
    }

    // draggedCenter inside neighbor bounds → reorder fires
    @Test
    fun centerCrossDetection_draggedCenterInBounds_reorderFires() {
        // SLEEP_SCORE center starts at y=75 (midpoint of 0..150).
        // Drag down 150px → center.y = 75 + 150 = 225, inside HRV (y 150..300).
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 150f), deleteZoneTop = null)

        val order = controller.pendingOrder
        assertEquals(CardId.HRV, order[0])
        assertEquals(CardId.SLEEP_SCORE, order[1])
    }

    // draggedCenter outside all neighbor bounds → no reorder
    @Test
    fun centerCrossDetection_draggedCenterNotInBounds_noReorder() {
        // SLEEP_SCORE center starts at y=75. Drag only 10px → center.y = 85, still inside SLEEP_SCORE (0..150).
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 10f), deleteZoneTop = null)

        assertEquals(
            listOf(CardId.SLEEP_SCORE, CardId.HRV, CardId.STEPS),
            controller.pendingOrder,
        )
    }

    // draggedCenter.y >= deleteZoneTop → hoveringDeleteZone becomes true
    @Test
    fun deleteZoneDetection_draggedCenterCrossesZoneTop_hoveringTrue() {
        // SLEEP_SCORE center starts at y=75; deleteZoneTop=200.
        // Drag down 130px → center.y = 75 + 130 = 205 >= 200.
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 130f), deleteZoneTop = 200f)

        assertTrue(controller.hoveringDeleteZone)
    }

    // Asymmetric card heights: center-cross detection still picks the correct neighbor
    @Test
    fun centerCrossDetection_asymmetricHeights_stillDetectsCorrectly() {
        // Asymmetric heights: SLEEP_SCORE 0..200, HRV 200..280, STEPS 280..400
        val ctrl = DragController(listOf(CardId.SLEEP_SCORE, CardId.HRV, CardId.STEPS))
        ctrl.updateSlotBounds(CardId.SLEEP_SCORE, Rect(0f, 0f, 200f, 200f))
        ctrl.updateSlotBounds(CardId.HRV, Rect(0f, 200f, 200f, 280f))
        ctrl.updateSlotBounds(CardId.STEPS, Rect(0f, 280f, 200f, 400f))

        // SLEEP_SCORE center = y 100. Drag down 150px → center.y = 250, inside HRV (200..280).
        ctrl.onDragStart(CardId.SLEEP_SCORE)
        ctrl.onDrag(Offset(0f, 150f), deleteZoneTop = null)

        val order = ctrl.pendingOrder
        assertEquals(CardId.HRV, order[0])
        assertEquals(CardId.SLEEP_SCORE, order[1])
    }

    // -------------------------------------------------------------------------
    // Drag-handle isolation (Task 8): the long-press-drag gesture detector lives only on the
    // per-card drag handle icon now, not on the whole card body/root grid. A long press+drag
    // starting elsewhere on the card (e.g. the upper-right display-mode selector) must never
    // reach the DragController; the same gesture starting on the handle must.
    // -------------------------------------------------------------------------

    private fun fakeCardDataMap(): CardDataMap =
        CardDataMap(
            mapOf<CardId, @Composable (CardConfiguration) -> Unit>(
                CardId.SLEEP_SCORE to { _ ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Stand-in for the upper-right display-mode selector on a real metric card.
                        IconButton(
                            onClick = {},
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(48.dp)
                                    .testTag("fake_selector"),
                        ) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "selector")
                        }
                    }
                },
                CardId.HRV to { _ -> Box(modifier = Modifier.fillMaxSize()) { Text("HRV") } },
            ),
        )

    private fun fakeCardConfigurations(): CardConfigurationsList =
        CardConfigurationsList(
            listOf(
                CardConfiguration(cardId = CardId.SLEEP_SCORE, isVisible = true, position = 0),
                CardConfiguration(cardId = CardId.HRV, isVisible = true, position = 1),
            ),
        )

    @Test
    fun longPressDragOnSelectorArea_doesNotStartControllerDrag() {
        val gridController = DragController(listOf(CardId.SLEEP_SCORE, CardId.HRV))

        composeTestRule.setContent {
            ReorderableCardGrid(
                cardConfigurations = fakeCardConfigurations(),
                cardDataMap = fakeCardDataMap(),
                isEditing = true,
                onCardRemove = {},
                onCardReorder = {},
                controller = gridController,
            )
        }

        composeTestRule.onNodeWithTag("fake_selector").performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, 100f))
            up()
        }
        composeTestRule.waitForIdle()

        assertNull(gridController.draggedCardId)
    }

    @Test
    fun longPressDragOnHandle_startsControllerDragForThatCard() {
        val gridController = DragController(listOf(CardId.SLEEP_SCORE, CardId.HRV))

        composeTestRule.setContent {
            ReorderableCardGrid(
                cardConfigurations = fakeCardConfigurations(),
                cardDataMap = fakeCardDataMap(),
                isEditing = true,
                onCardRemove = {},
                onCardReorder = {},
                controller = gridController,
            )
        }

        // Both displayable cards render their own handle with the same content description;
        // the first in composition/display order belongs to SLEEP_SCORE (position 0).
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, 100f))
        }
        composeTestRule.waitForIdle()

        assertEquals(CardId.SLEEP_SCORE, gridController.draggedCardId)
    }

    @Test
    fun handleDragAfterDisplayModeChange_reordersCurrentConfigurations() {
        var configs by mutableStateOf(
            CardConfigurationsList(
                listOf(
                    CardConfiguration(CardId.SLEEP_SCORE, position = 0),
                    CardConfiguration(CardId.HRV, position = 1),
                ),
            ),
        )
        var reordered: List<CardConfiguration>? = null

        composeTestRule.setContent {
            ReorderableCardGrid(
                cardConfigurations = configs,
                cardDataMap = fakeCardDataMap(),
                isEditing = true,
                onCardRemove = {},
                onCardReorder = { reordered = it },
            )
        }
        // Keep this gesture active across recomposition. The pointer-input handler has already
        // captured its callbacks, so ending it after the mode mutation verifies that it reads the
        // current callback rather than its stale closure.
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, 20f))
        }
        composeTestRule.runOnIdle {
            configs =
                CardConfigurationsList(
                    configs.items.map {
                        if (it.cardId == CardId.HRV) {
                            it.copy(requestedDisplayMode = DashboardCardDisplayMode.BAR)
                        } else {
                            it
                        }
                    },
                )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            up()
        }

        assertEquals(
            DashboardCardDisplayMode.BAR,
            reordered!!.single { it.cardId == CardId.HRV }.requestedDisplayMode,
        )
    }

    // -------------------------------------------------------------------------
    // Regression: a live mid-drag reorder (pendingOrder swap while still dragging) must not
    // cancel the in-progress gesture. SLEEP_SCORE and HRV are paired side-by-side in one Row
    // (grid.kt's leftCard/rightCard Box templates). Dragging SLEEP_SCORE across into HRV's slot
    // swaps pendingOrder mid-gesture, which moves SLEEP_SCORE from the "left" Box template to
    // the "right" one (and vice versa for HRV) - a parent swap identical in kind to the one that
    // tears down a per-card pointerInput node if the gesture detector lives on the handle itself.
    // If that happens, detectDragGesturesAfterLongPress fires onDragCancel and the drag ends
    // after the single swap instead of continuing to respond to further movement.
    // -------------------------------------------------------------------------

    @Test
    fun handleDrag_continuesAfterMidDragReorderSwap() {
        val gridController = DragController(listOf(CardId.SLEEP_SCORE, CardId.HRV))

        composeTestRule.setContent {
            ReorderableCardGrid(
                cardConfigurations = fakeCardConfigurations(),
                cardDataMap = fakeCardDataMap(),
                isEditing = true,
                onCardRemove = {},
                onCardReorder = {},
                controller = gridController,
            )
        }
        composeTestRule.waitForIdle()

        // Read the real slots DragController hit-tests against, rather than re-deriving pixel
        // math independently - guarantees the computed delta lands exactly inside HRV's rect.
        val sleepCenter = gridController.slotBounds.getValue(CardId.SLEEP_SCORE).center
        val hrvCenter = gridController.slotBounds.getValue(CardId.HRV).center
        val deltaToHrv = Offset(hrvCenter.x - sleepCenter.x, hrvCenter.y - sleepCenter.y)

        // Drag SLEEP_SCORE across into HRV's slot (triggers the mid-drag swap), then keep
        // moving within the same continuous gesture. The drag must still be live afterwards.
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(deltaToHrv)
            advanceEventTime(50)
            moveBy(Offset(-20f, 0f))
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(CardId.HRV, CardId.SLEEP_SCORE), gridController.pendingOrder)
        assertEquals(CardId.SLEEP_SCORE, gridController.draggedCardId)
    }
}
