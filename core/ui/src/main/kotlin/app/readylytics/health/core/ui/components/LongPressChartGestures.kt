package app.readylytics.health.core.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Long-press-to-select gesture for custom Canvas charts, mirroring [LongPressMarkerController].
 *
 * A long press resolves the point under the finger with [resolve] and writes it to [selection],
 * with long-press haptic feedback when it resolved one. The selection then follows the finger
 * until it lifts; a position that resolves to nothing leaves it as it was.
 *
 * Every change from the long press on is consumed in the [PointerEventPass.Initial] pass, like
 * [LongPressMarkerController.scrubGate], so a parent scroll or a sibling transform detector cannot
 * pick the gesture up mid-scrub — even when the finger pauses and the touch controller keeps
 * sending moves without a position change.
 *
 * A tap, or a drag that starts before the long-press timeout, is left unconsumed and scrolls
 * normally. The tooltip `Popup` dismisses itself on the touch-down outside it, so there is no
 * tap-to-dismiss here.
 */
suspend fun <T : Any> PointerInputScope.detectLongPressSelection(
    hapticFeedback: HapticFeedback,
    selection: MutableState<T?>,
    resolve: (Offset) -> T?,
) {
    fun select(position: Offset): Boolean {
        val item = resolve(position) ?: return false
        selection.value = item
        return true
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (select(longPress.position)) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        var lastPosition = longPress.position
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            val finger = event.changes.firstOrNull { it.id == longPress.id }
            if (finger != null && finger.pressed && finger.position != lastPosition) {
                lastPosition = finger.position
                select(lastPosition)
            }
        } while (event.changes.any { it.pressed })
    }
}
