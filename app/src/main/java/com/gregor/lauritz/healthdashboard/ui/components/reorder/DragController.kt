package com.gregor.lauritz.healthdashboard.ui.components.reorder

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId

/**
 * Pure state holder for drag-and-drop reordering logic.
 *
 * Invariants:
 *  V1 — upstream cardConfigurations are ignored while a drag is active (syncFromUpstream no-ops).
 *  V2 — slotBounds are written by the UI in a single shared coordinate space (root-local).
 *  V3 — the dragged card stays anchored to the finger across mid-drag layout shifts:
 *       on swap, dragOffset is pre-compensated by (oldOrigin - newOrigin) so that
 *       after the layout pass re-runs onGloballyPositioned, origin.center + dragOffset
 *       still resolves to the same pointer position.
 *  V4 — exactly one onCardReorder (or onCardRemove) call is emitted, on onDragEnd.
 */
@Stable
class DragController(
    initialOrder: List<CardId>,
) {
    /** Working order during drag. Authoritative for rendering while draggedCardId != null. */
    var pendingOrder: List<CardId> by mutableStateOf(initialOrder)
        private set

    /** Card currently being dragged, null when idle. */
    var draggedCardId: CardId? by mutableStateOf(null)
        private set

    /** Pointer offset from the drag-start origin, in root-local pixels. */
    var dragOffset: Offset by mutableStateOf(Offset.Zero)
        private set

    /** Slot bounds keyed by CardId. MUST be written in a single coord space (root-local). */
    val slotBounds: SnapshotStateMap<CardId, Rect> = mutableStateMapOf()

    /** True when the dragged card's center has crossed the delete-zone top edge. */
    var hoveringDeleteZone: Boolean by mutableStateOf(false)
        private set

    fun updateSlotBounds(
        id: CardId,
        rect: Rect,
    ) {
        slotBounds[id] = rect
    }

    fun onDragStart(id: CardId) {
        draggedCardId = id
        dragOffset = Offset.Zero
        hoveringDeleteZone = false
    }

    /**
     * Process one drag delta.
     *
     *  1. Accumulate [delta] into dragOffset.
     *  2. Compute dragged center in root-local coords = origin.center + dragOffset.
     *  3. Flip hoveringDeleteZone if the center has crossed [deleteZoneTop].
     *     (Flag only — does NOT short-circuit; the user can still pull back into the grid.)
     *  4. Find any non-dragged slot whose root-local rect contains the dragged center (2-D hit test).
     *  5. Pre-compensate dragOffset by (origin.center - target.center) so the finger
     *     stays anchored to the same pixel after the upcoming swap & layout pass (V3).
     *  6. Move dragged card to target's index in pendingOrder.
     */
    fun onDrag(
        delta: Offset,
        deleteZoneTop: Float?,
    ) {
        val id = draggedCardId ?: return
        dragOffset += delta

        val origin = slotBounds[id] ?: return
        val draggedCenter = origin.center + dragOffset

        hoveringDeleteZone = deleteZoneTop != null && draggedCenter.y >= deleteZoneTop

        // 2-D hit test: which slot is the finger currently over?
        val targetId =
            pendingOrder.firstOrNull { otherId ->
                if (otherId == id) return@firstOrNull false
                slotBounds[otherId]?.contains(draggedCenter) == true
            } ?: return

        val from = pendingOrder.indexOf(id)
        val to = pendingOrder.indexOf(targetId)
        if (from < 0 || to < 0 || from == to) return

        val targetBounds = slotBounds[targetId] ?: return
        // Pre-compensate offset for the layout shift that will happen on next compose.
        // After swap, slotBounds[id] will be re-measured to approximately targetBounds,
        // so we adjust dragOffset by the delta between the two centers.
        dragOffset += origin.center - targetBounds.center

        val newOrder = pendingOrder.toMutableList()
        newOrder.removeAt(from)
        newOrder.add(to, id)
        pendingOrder = newOrder
    }

    fun onDragEnd(): DragEndResult {
        val id = draggedCardId
        val deletion = hoveringDeleteZone
        val finalOrder = pendingOrder
        draggedCardId = null
        dragOffset = Offset.Zero
        hoveringDeleteZone = false
        return DragEndResult(
            draggedId = id,
            finalOrder = finalOrder,
            delete = deletion,
        )
    }

    /**
     * Sync pendingOrder from an upstream emission.
     * No-op while a drag is active (V1).
     */
    fun syncFromUpstream(newOrder: List<CardId>) {
        if (draggedCardId == null) {
            pendingOrder = newOrder
        }
    }
}

/** Result returned by DragController.onDragEnd. */
data class DragEndResult(
    val draggedId: CardId?,
    val finalOrder: List<CardId>,
    val delete: Boolean,
)
