package app.readylytics.health.core.ui.components

import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction
import com.patrykandpatrick.vico.compose.common.Point
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LongPressMarkerControllerTest {
    private val point = Point(10f, 20f)
    private val targets: List<CartesianMarker.Target> =
        listOf(
            object : CartesianMarker.Target {
                override val x: Double = 1.0
                override val canvasX: Float = 10f
            },
        )

    private var longPressCount = 0

    private val controller = LongPressMarkerController(onLongPress = { longPressCount++ })

    /** Mirrors Vico's host: shouldShowMarker is only consulted after the interaction is accepted. */
    private fun dispatch(
        interaction: Interaction,
        markerTargets: List<CartesianMarker.Target> = targets,
    ): Boolean? =
        if (controller.shouldAcceptInteraction(interaction, markerTargets)) {
            controller.shouldShowMarker(interaction, markerTargets)
        } else {
            null
        }

    @Test
    fun `press does not show the marker`() {
        assertEquals(null, dispatch(Interaction.Press(point)))
        assertFalse(controller.isScrubbing)
    }

    @Test
    fun `move without a long press is ignored so scrolling never selects`() {
        dispatch(Interaction.Press(point))

        assertEquals(null, dispatch(Interaction.Move(point)))
        assertEquals(null, dispatch(Interaction.Release(point)))
        assertEquals(0, longPressCount)
    }

    @Test
    fun `long press shows the marker, starts scrubbing and fires haptics once`() {
        dispatch(Interaction.Press(point))

        assertEquals(true, dispatch(Interaction.LongPress(point)))
        assertTrue(controller.isScrubbing)
        assertEquals(1, longPressCount)
    }

    @Test
    fun `long press without targets is ignored`() {
        assertEquals(null, dispatch(Interaction.LongPress(point), markerTargets = emptyList()))
        assertFalse(controller.isScrubbing)
        assertEquals(0, longPressCount)
    }

    @Test
    fun `moves while scrubbing keep the marker on the finger`() {
        dispatch(Interaction.Press(point))
        dispatch(Interaction.LongPress(point))

        assertEquals(true, dispatch(Interaction.Move(Point(30f, 20f))))
        assertEquals(true, dispatch(Interaction.Move(Point(50f, 20f))))
        assertEquals(1, longPressCount)
    }

    @Test
    fun `release ends the scrub and hides the Vico marker`() {
        dispatch(Interaction.Press(point))
        dispatch(Interaction.LongPress(point))

        assertEquals(false, dispatch(Interaction.Release(point)))
        assertFalse(controller.isScrubbing)
    }

    @Test
    fun `press resets a scrub whose release was never delivered`() {
        dispatch(Interaction.Press(point))
        dispatch(Interaction.LongPress(point))

        dispatch(Interaction.Press(point))

        assertFalse(controller.isScrubbing)
        assertEquals(null, dispatch(Interaction.Move(point)))
    }

    @Test
    fun `tap is ignored because the tooltip popup dismisses itself`() {
        assertEquals(null, dispatch(Interaction.Tap(point)))
        assertFalse(controller.isScrubbing)
        assertEquals(0, longPressCount)
    }

    @Test
    fun `controller accepts long presses and locks to x`() {
        assertTrue(controller.acceptsLongPress)
        assertEquals(CartesianMarkerController.Lock.X, controller.lock)
    }

    @Test
    fun `scrub gate is one stable modifier per controller`() {
        assertSame(controller.scrubGate, controller.scrubGate)
    }
}
