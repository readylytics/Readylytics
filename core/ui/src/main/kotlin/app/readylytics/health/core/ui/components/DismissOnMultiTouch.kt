package app.readylytics.health.core.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Calls [onMultiTouch] once per gesture when a second finger joins, so a pinch or two-finger pan
 * does not leave a chart tooltip behind. It never consumes pointer changes.
 *
 * The modifier is keyed on `Unit` so a gesture in progress survives recomposition, which means
 * [onMultiTouch] is captured once: it must only touch state holders that outlive recomposition.
 */
fun Modifier.dismissOnMultiTouch(onMultiTouch: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var isMultiTouch = false
            while (!isMultiTouch) {
                val event = awaitPointerEvent()
                // Stop polling once all fingers are lifted
                if (event.changes.none { it.pressed }) break
                if (event.changes.size > 1) {
                    isMultiTouch = true
                    onMultiTouch()
                }
            }
        }
    }
