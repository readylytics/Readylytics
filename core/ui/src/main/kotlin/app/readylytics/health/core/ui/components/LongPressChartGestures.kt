package app.readylytics.health.core.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction

/**
 * Vico marker controller that opens chart tooltips on long press instead of on touch-down.
 *
 * Vico's default `showOnPress` controller shows the marker the moment a finger lands, so every
 * page scroll or chart pan that starts on a chart flashes a tooltip. This controller:
 * - selects the point under the finger on long press (calling [onLongPress], e.g. for haptics)
 *   and starts a scrub;
 * - follows the finger while scrubbing — pair it with [consumeGesturesWhileScrubbing] so chart
 *   pan and parent scroll cannot steal the gesture;
 * - hides Vico's internal marker on release. The app tooltip stays visible because the chart
 *   listeners ignore `onHidden`, and the next long press always fires a fresh `onShown`;
 * - forwards a short tap to [onTap], which returns `true` when it dismissed a tooltip. Only then
 *   is the tap consumed, so a tap on a chart with no open tooltip still reaches a clickable parent.
 */
class LongPressMarkerController(
    private val onLongPress: () -> Unit,
    private val onTap: () -> Boolean,
) : CartesianMarkerController {
    /** `true` from the long press until the finger lifts. */
    var isScrubbing: Boolean = false
        private set

    override val acceptsLongPress: Boolean = true

    // The marker is hidden after every release, so there is nothing to re-anchor on viewport
    // changes. Lock.Position would replay the last interaction on every scroll and re-fire onTap.
    override val lock: CartesianMarkerController.Lock = CartesianMarkerController.Lock.X

    override fun shouldAcceptInteraction(
        interaction: Interaction,
        targets: List<CartesianMarker.Target>,
    ): Boolean =
        when (interaction) {
            is Interaction.Press -> {
                // A new gesture always starts idle, even if a cancelled one never sent Release.
                isScrubbing = false
                false
            }
            is Interaction.LongPress -> targets.isNotEmpty()
            is Interaction.Move, is Interaction.Release -> isScrubbing
            is Interaction.Tap -> onTap()
            else -> false
        }

    override fun shouldShowMarker(
        interaction: Interaction,
        targets: List<CartesianMarker.Target>,
    ): Boolean =
        when (interaction) {
            is Interaction.LongPress -> {
                isScrubbing = true
                onLongPress()
                true
            }
            is Interaction.Move -> true
            is Interaction.Release -> {
                isScrubbing = false
                false
            }
            else -> false
        }
}

/**
 * Remembers a [LongPressMarkerController] that performs long-press haptic feedback when a
 * tooltip opens. A short tap calls [onDismiss] when [isVisible] reports an open tooltip.
 */
@Composable
fun rememberLongPressMarkerController(
    isVisible: () -> Boolean,
    onDismiss: () -> Unit,
): LongPressMarkerController {
    val hapticFeedback = LocalHapticFeedback.current
    val currentIsVisible by rememberUpdatedState(isVisible)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    return remember(hapticFeedback) {
        LongPressMarkerController(
            onLongPress = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) },
            onTap = { dismissIfVisible(currentIsVisible, currentOnDismiss) },
        )
    }
}

/**
 * Consumes every pointer change in the [PointerEventPass.Initial] pass while [controller] is
 * scrubbing. Apply it to the `CartesianChartHost` modifier or to a container around it: as an
 * ancestor of Vico's gesture handlers it runs first, so Vico's horizontal scroll and any parent
 * scroll container see the moves as consumed and stay put, and a clickable parent does not fire
 * on release. Vico's own marker tracking reads events regardless of consumption, so the scrub
 * keeps updating.
 */
fun Modifier.consumeGesturesWhileScrubbing(controller: LongPressMarkerController): Modifier =
    pointerInput(controller) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (controller.isScrubbing) event.changes.forEach { it.consume() }
            }
        }
    }

/**
 * Long-press-to-select gesture for custom Canvas charts, mirroring [LongPressMarkerController].
 *
 * - Short tap: calls [onDismiss] when [isVisible] reports an open tooltip. Only then is the tap
 *   consumed, so it otherwise still reaches a clickable parent.
 * - Long press: [onSelect] with the press position, plus long-press haptic feedback when it
 *   selected something, then [onSelect] again for every move until the finger lifts. All changes
 *   after the long press are consumed so a parent scroll or a sibling transform detector does
 *   not move the chart mid-scrub.
 *
 * A drag that starts before the long-press timeout is left unconsumed, so it scrolls normally.
 * [onSelect] returns whether the position resolved to a data point.
 */
suspend fun PointerInputScope.detectLongPressSelection(
    hapticFeedback: HapticFeedback,
    isVisible: () -> Boolean,
    onDismiss: () -> Unit,
    onSelect: (Offset) -> Boolean,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id)
        if (longPress == null) {
            // Released before the timeout (a tap) or cancelled because a scroll consumed the
            // pointer. Only an unconsumed release within touch slop counts as a tap.
            val up = currentEvent.changes.firstOrNull { it.id == down.id }
            val isTap = up?.isTapRelease(down, viewConfiguration.touchSlop) == true
            if (isTap && dismissIfVisible(isVisible, onDismiss)) up?.consume()
            return@awaitEachGesture
        }
        longPress.consume()
        if (onSelect(longPress.position)) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        drag(longPress.id) { change ->
            change.consume()
            onSelect(change.position)
        }
        currentEvent.changes.forEach { it.consume() }
    }
}

/**
 * Short tap that dismisses a tooltip a container shows on behalf of its child charts, such as a
 * split chart whose children only report their selection upward. A long-press scrub or a scroll
 * in a child consumes the pointer and cancels it, so it never fires on the release that ends a
 * long press. The tap is consumed only when it dismissed a tooltip.
 */
suspend fun PointerInputScope.detectTapToDismiss(
    isVisible: () -> Boolean,
    onDismiss: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        val isQuick = up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
        if (isQuick && up.isTapRelease(down, viewConfiguration.touchSlop) && dismissIfVisible(isVisible, onDismiss)) {
            up.consume()
        }
    }
}

/** Calls [onDismiss] when [isVisible]; returns whether a tooltip was dismissed. */
internal fun dismissIfVisible(
    isVisible: () -> Boolean,
    onDismiss: () -> Unit,
): Boolean {
    val wasVisible = isVisible()
    if (wasVisible) onDismiss()
    return wasVisible
}

private fun PointerInputChange.isTapRelease(
    down: PointerInputChange,
    touchSlop: Float,
): Boolean = changedToUp() && (position - down.position).getDistance() < touchSlop
