package app.readylytics.health.core.ui.components

import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker

/**
 * A custom invisible [CartesianMarker] that allows Vico to handle gestures, touch
 * tracking, zooming, and scrolling natively while rendering nothing itself.
 * The actual visual feedback is rendered using the standard Jetpack Compose Canvas overlay.
 */
object InvisibleMarker : CartesianMarker {
    override fun drawUnderLayers(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
    ) {
        // Do nothing to keep the marker invisible.
    }

    override fun drawOverLayers(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
    ) {
        // Do nothing to keep the marker invisible.
    }
}
