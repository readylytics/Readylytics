package com.gregor.lauritz.healthdashboard.ui.components.reorder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DragController.
 *
 * Tests are pure JVM (no Android framework, no Compose UI).
 * mutableStateOf / mutableStateMapOf run on the JVM snapshot system without a host.
 *
 * Arrange-Act-Assert pattern throughout.
 */
class DragControllerTest {
    // Three-card order used as base in most tests
    private val initialOrder = listOf(CardId.SLEEP_SCORE, CardId.HRV, CardId.STEPS)

    private lateinit var controller: DragController

    @Before
    fun setUp() {
        controller = DragController(initialOrder)
        // Register slot bounds for all three cards (200x100 each, stacked vertically).
        // SLEEP_SCORE: y 0..100, HRV: y 100..200, STEPS: y 200..300
        controller.updateSlotBounds(CardId.SLEEP_SCORE, Rect(0f, 0f, 200f, 100f))
        controller.updateSlotBounds(CardId.HRV, Rect(0f, 100f, 200f, 200f))
        controller.updateSlotBounds(CardId.STEPS, Rect(0f, 200f, 200f, 300f))
    }

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun initialState_pendingOrderMatchesConstructorArg() {
        assertEquals(initialOrder, controller.pendingOrder)
    }

    @Test
    fun initialState_noDraggedCard() {
        assertNull(controller.draggedCardId)
    }

    @Test
    fun initialState_dragOffsetIsZero() {
        assertEquals(Offset.Zero, controller.dragOffset)
    }

    @Test
    fun initialState_notHoveringDeleteZone() {
        assertFalse(controller.hoveringDeleteZone)
    }

    // -------------------------------------------------------------------------
    // onDragStart
    // -------------------------------------------------------------------------

    @Test
    fun onDragStart_setsDraggedCardId() {
        controller.onDragStart(CardId.HRV)
        assertEquals(CardId.HRV, controller.draggedCardId)
    }

    @Test
    fun onDragStart_resetsDragOffset() {
        // Arrange: simulate leftover offset from a previous drag
        controller.onDragStart(CardId.HRV)
        controller.onDrag(Offset(10f, 10f), null)
        controller.onDragEnd()

        // Act
        controller.onDragStart(CardId.SLEEP_SCORE)

        // Assert
        assertEquals(Offset.Zero, controller.dragOffset)
    }

    @Test
    fun onDragStart_resetsHoveringDeleteZone() {
        // Arrange: get into delete-zone state
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 50f), deleteZoneTop = 10f)
        assertTrue(controller.hoveringDeleteZone)
        controller.onDragEnd()

        // Act
        controller.onDragStart(CardId.HRV)

        // Assert
        assertFalse(controller.hoveringDeleteZone)
    }

    // -------------------------------------------------------------------------
    // onDrag — delta accumulation
    // -------------------------------------------------------------------------

    @Test
    fun onDrag_accumulatesOffset() {
        // Arrange
        controller.onDragStart(CardId.SLEEP_SCORE)

        // Act
        controller.onDrag(Offset(10f, 5f), null)
        controller.onDrag(Offset(3f, 2f), null)

        // Assert: offset is cumulative
        assertEquals(Offset(13f, 7f), controller.dragOffset)
    }

    @Test
    fun onDrag_withoutDragStart_isNoOp() {
        // No onDragStart called — draggedCardId is null
        controller.onDrag(Offset(100f, 100f), null)
        assertEquals(Offset.Zero, controller.dragOffset)
        assertEquals(initialOrder, controller.pendingOrder)
    }

    // -------------------------------------------------------------------------
    // Center-cross detection — reorder fires when center enters neighbor bounds
    // -------------------------------------------------------------------------

    @Test
    fun onDrag_centerCrossesNeighbor_reorderFires() {
        // Arrange: SLEEP_SCORE bounds y 0..100, center.y = 50.
        // Drag down 110px → draggedCenter.y = 50 + 110 = 160, inside HRV (y 100..200).
        controller.onDragStart(CardId.SLEEP_SCORE)

        // Act
        controller.onDrag(Offset(0f, 110f), null)

        // Assert: SLEEP_SCORE swapped with HRV
        val order = controller.pendingOrder
        assertEquals(CardId.HRV, order[0])
        assertEquals(CardId.SLEEP_SCORE, order[1])
        assertEquals(CardId.STEPS, order[2])
    }

    @Test
    fun onDrag_centerDoesNotCrossNeighbor_noReorder() {
        // Arrange: drag only 10px — center.y = 50 + 10 = 60, still inside SLEEP_SCORE (0..100).
        controller.onDragStart(CardId.SLEEP_SCORE)

        // Act
        controller.onDrag(Offset(0f, 10f), null)

        // Assert: order unchanged
        assertEquals(initialOrder, controller.pendingOrder)
    }

    @Test
    fun onDrag_centerCrossesSecondSlot_cardMovesToCorrectIndex() {
        // Arrange: drag SLEEP_SCORE so center.y = 50 + 210 = 260, inside STEPS (y 200..300).
        controller.onDragStart(CardId.SLEEP_SCORE)

        // Act
        controller.onDrag(Offset(0f, 210f), null)

        // Assert: SLEEP_SCORE moved past index 0
        val order = controller.pendingOrder
        assertTrue(order.indexOf(CardId.SLEEP_SCORE) > 0)
    }

    @Test
    fun onDrag_reorder_draggedIdRemainsTracked() {
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 110f), null)

        assertEquals(CardId.SLEEP_SCORE, controller.draggedCardId)
    }

    @Test
    fun onDrag_reorderFromIndex2ToIndex0_correctIndices() {
        // Arrange: 5-card stack, drag STEPS (index 2, center y=250) up into SLEEP_SCORE (y 0..100).
        val fiveCards =
            listOf(
                CardId.SLEEP_SCORE,
                CardId.HRV,
                CardId.STEPS,
                CardId.RESTING_HR,
                CardId.READINESS,
            )
        val ctrl = DragController(fiveCards)
        ctrl.updateSlotBounds(CardId.SLEEP_SCORE, Rect(0f, 0f, 200f, 100f))
        ctrl.updateSlotBounds(CardId.HRV, Rect(0f, 100f, 200f, 200f))
        ctrl.updateSlotBounds(CardId.STEPS, Rect(0f, 200f, 200f, 300f))
        ctrl.updateSlotBounds(CardId.RESTING_HR, Rect(0f, 300f, 200f, 400f))
        ctrl.updateSlotBounds(CardId.READINESS, Rect(0f, 400f, 200f, 500f))

        // STEPS center = y 250; drag up -160 → center.y = 90, inside SLEEP_SCORE (0..100).
        ctrl.onDragStart(CardId.STEPS)
        ctrl.onDrag(Offset(0f, -160f), null)

        val order = ctrl.pendingOrder
        assertEquals(CardId.STEPS, order[0])
        assertEquals(CardId.SLEEP_SCORE, order[1])
        assertEquals(CardId.HRV, order[2])
    }

    // -------------------------------------------------------------------------
    // Delete zone
    // -------------------------------------------------------------------------

    @Test
    fun onDrag_centerCrossesDeleteZoneTop_hoveringDeleteZoneTrue() {
        // Arrange: SLEEP_SCORE center starts at y=50; deleteZoneTop=100.
        controller.onDragStart(CardId.SLEEP_SCORE)

        // Act: move 60px down → center.y = 110 >= 100
        controller.onDrag(Offset(0f, 60f), deleteZoneTop = 100f)

        // Assert
        assertTrue(controller.hoveringDeleteZone)
    }

    @Test
    fun onDrag_hoveringDeleteZone_noReorderAttempted() {
        // Arrange
        controller.onDragStart(CardId.SLEEP_SCORE)

        // Act: center crosses delete zone — return-early prevents reorder
        controller.onDrag(Offset(0f, 110f), deleteZoneTop = 100f)

        // Assert: order unchanged
        assertEquals(initialOrder, controller.pendingOrder)
    }

    @Test
    fun onDrag_centerAboveDeleteZoneTop_hoveringDeleteZoneFalse() {
        // Arrange: first enter the delete zone
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 60f), deleteZoneTop = 100f)
        assertTrue(controller.hoveringDeleteZone)

        // Act: drag back up so center.y = 50 + 60 - 70 = 40 < 100
        controller.onDrag(Offset(0f, -70f), deleteZoneTop = 100f)

        // Assert
        assertFalse(controller.hoveringDeleteZone)
    }

    @Test
    fun onDrag_deleteZoneTopNull_hoveringDeleteZoneAlwaysFalse() {
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 500f), deleteZoneTop = null)
        assertFalse(controller.hoveringDeleteZone)
    }

    // -------------------------------------------------------------------------
    // onDragEnd
    // -------------------------------------------------------------------------

    @Test
    fun onDragEnd_returnsDraggedId() {
        controller.onDragStart(CardId.HRV)
        val result = controller.onDragEnd()
        assertEquals(CardId.HRV, result.draggedId)
    }

    @Test
    fun onDragEnd_returnsFinalOrder() {
        // Arrange: perform a reorder then capture pendingOrder before end
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 110f), null)
        val expectedOrder = controller.pendingOrder

        // Act
        val result = controller.onDragEnd()

        // Assert
        assertEquals(expectedOrder, result.finalOrder)
    }

    @Test
    fun onDragEnd_deleteTrue_whenHoveringDeleteZone() {
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 60f), deleteZoneTop = 100f)
        val result = controller.onDragEnd()
        assertTrue(result.delete)
    }

    @Test
    fun onDragEnd_deleteFalse_whenNotHoveringDeleteZone() {
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 10f), null)
        val result = controller.onDragEnd()
        assertFalse(result.delete)
    }

    @Test
    fun onDragEnd_clearsDraggedCardId() {
        controller.onDragStart(CardId.HRV)
        controller.onDragEnd()
        assertNull(controller.draggedCardId)
    }

    @Test
    fun onDragEnd_clearsDragOffset() {
        controller.onDragStart(CardId.HRV)
        controller.onDrag(Offset(50f, 80f), null)
        controller.onDragEnd()
        assertEquals(Offset.Zero, controller.dragOffset)
    }

    @Test
    fun onDragEnd_clearsHoveringDeleteZone() {
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 60f), deleteZoneTop = 100f)
        controller.onDragEnd()
        assertFalse(controller.hoveringDeleteZone)
    }

    @Test
    fun onDragEnd_withNoDragStart_returnsNullId() {
        val result = controller.onDragEnd()
        assertNull(result.draggedId)
        assertFalse(result.delete)
    }

    // -------------------------------------------------------------------------
    // syncFromUpstream gating
    // -------------------------------------------------------------------------

    @Test
    fun syncFromUpstream_whenIdle_updatesPendingOrder() {
        val newOrder = listOf(CardId.STEPS, CardId.HRV, CardId.SLEEP_SCORE)
        controller.syncFromUpstream(newOrder)
        assertEquals(newOrder, controller.pendingOrder)
    }

    @Test
    fun syncFromUpstream_duringDrag_isIgnored() {
        // Arrange: start drag
        controller.onDragStart(CardId.SLEEP_SCORE)
        val orderDuringDrag = controller.pendingOrder

        // Act: upstream tries to push a new order mid-drag
        val upstreamOrder = listOf(CardId.STEPS, CardId.HRV, CardId.SLEEP_SCORE)
        controller.syncFromUpstream(upstreamOrder)

        // Assert: pendingOrder unchanged (V1 invariant)
        assertEquals(orderDuringDrag, controller.pendingOrder)
    }

    @Test
    fun syncFromUpstream_afterDragEnd_updatesOrder() {
        // Arrange
        controller.onDragStart(CardId.HRV)
        controller.onDragEnd()

        // Act
        val newOrder = listOf(CardId.STEPS, CardId.HRV, CardId.SLEEP_SCORE)
        controller.syncFromUpstream(newOrder)

        // Assert
        assertEquals(newOrder, controller.pendingOrder)
    }

    // -------------------------------------------------------------------------
    // Multiple drag/end cycles — state resets correctly
    // -------------------------------------------------------------------------

    @Test
    fun multipleDragCycles_stateResetsAfterEachEnd() {
        // First cycle
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 110f), null)
        controller.onDragEnd()
        assertNull(controller.draggedCardId)
        assertEquals(Offset.Zero, controller.dragOffset)
        assertFalse(controller.hoveringDeleteZone)

        // Second cycle — different card
        controller.onDragStart(CardId.HRV)
        assertEquals(CardId.HRV, controller.draggedCardId)
        assertEquals(Offset.Zero, controller.dragOffset)
        assertFalse(controller.hoveringDeleteZone)

        controller.onDrag(Offset(5f, 5f), null)
        val result = controller.onDragEnd()

        assertEquals(CardId.HRV, result.draggedId)
        assertNull(controller.draggedCardId)
        assertEquals(Offset.Zero, controller.dragOffset)
    }

    @Test
    fun multipleDragCycles_pendingOrderReflectsEachCycle() {
        // Cycle 1: move SLEEP_SCORE to index 1
        controller.onDragStart(CardId.SLEEP_SCORE)
        controller.onDrag(Offset(0f, 110f), null)
        val result1 = controller.onDragEnd()
        assertEquals(listOf(CardId.HRV, CardId.SLEEP_SCORE, CardId.STEPS), result1.finalOrder)

        // Cycle 2: update bounds to reflect new layout, move HRV (now index 0) down one slot
        controller.updateSlotBounds(CardId.HRV, Rect(0f, 0f, 200f, 100f))
        controller.updateSlotBounds(CardId.SLEEP_SCORE, Rect(0f, 100f, 200f, 200f))
        controller.updateSlotBounds(CardId.STEPS, Rect(0f, 200f, 200f, 300f))

        controller.onDragStart(CardId.HRV)
        controller.onDrag(Offset(0f, 110f), null) // center.y = 50 + 110 = 160, inside SLEEP_SCORE
        val result2 = controller.onDragEnd()

        // HRV should now be at index 1
        assertEquals(1, result2.finalOrder.indexOf(CardId.HRV))
    }

    // -------------------------------------------------------------------------
    // updateSlotBounds
    // -------------------------------------------------------------------------

    @Test
    fun updateSlotBounds_storesBoundsForCard() {
        val rect = Rect(10f, 20f, 110f, 120f)
        controller.updateSlotBounds(CardId.READINESS, rect)
        assertEquals(rect, controller.slotBounds[CardId.READINESS])
    }

    @Test
    fun updateSlotBounds_overwritesPreviousBounds() {
        val rect1 = Rect(0f, 0f, 100f, 100f)
        val rect2 = Rect(5f, 5f, 105f, 105f)
        controller.updateSlotBounds(CardId.HRV, rect1)
        controller.updateSlotBounds(CardId.HRV, rect2)
        assertEquals(rect2, controller.slotBounds[CardId.HRV])
    }
}
