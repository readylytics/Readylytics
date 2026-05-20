package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import com.gregor.lauritz.healthdashboard.ui.components.reorder.DragController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests verifying that ReorderableCardGrid wires bounds → DragController
 * → center-cross detection correctly.
 *
 * Detailed algorithm unit tests live in DragControllerTest. These tests focus on
 * the integration contract: correct bounds registration leads to correct detection.
 */
class ReorderableCardGridThresholdTest {
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
}
