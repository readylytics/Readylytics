package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
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
 * - follows the finger while scrubbing — apply [scrubGate] to the chart host so chart pan and
 *   parent scroll cannot steal the gesture;
 * - hides Vico's internal marker on release. The app tooltip stays visible because the chart
 *   listeners ignore `onHidden`, and the next long press always fires a fresh `onShown`;
 * - ignores taps. The tooltip `Popup` dismisses itself on the touch-down outside it, so a chart
 *   needs no dismiss path of its own.
 */
class LongPressMarkerController(
    private val onLongPress: () -> Unit,
) : CartesianMarkerController {
    /** `true` from the long press until the finger lifts. */
    var isScrubbing: Boolean = false
        private set

    /**
     * Consumes every pointer change in the [PointerEventPass.Initial] pass while [isScrubbing].
     * Apply it to the `CartesianChartHost` modifier or to a container around it: as an ancestor of
     * Vico's gesture handlers it runs first, so Vico's horizontal scroll and any parent scroll
     * container see the moves as consumed and stay put, and a clickable parent does not fire on
     * release. Vico's own marker tracking reads events regardless of consumption, so the scrub
     * keeps updating.
     *
     * Built once per controller: a modifier created on every call never equals its predecessor,
     * so the chart host could not be skipped when the tooltip state changes during a scrub.
     */
    val scrubGate: Modifier =
        Modifier.pointerInput(this) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (isScrubbing) event.changes.forEach { it.consume() }
                }
            }
        }

    override val acceptsLongPress: Boolean = true

    // The marker is hidden after every release, so there is nothing to re-anchor on viewport
    // changes. Lock.Position would replay the last interaction on every scroll.
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
 * Remembers a [LongPressMarkerController] that performs long-press haptic feedback when a tooltip
 * opens. Pass it to `rememberCartesianChart(markerController = …)` and add its
 * [LongPressMarkerController.scrubGate] to the `CartesianChartHost` modifier.
 */
@Composable
fun rememberLongPressMarkerController(): LongPressMarkerController {
    val hapticFeedback = LocalHapticFeedback.current
    return remember(hapticFeedback) {
        LongPressMarkerController(
            onLongPress = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) },
        )
    }
}
