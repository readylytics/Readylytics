package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker

class BoundsTrackingMarker(
    private val onBoundsChanged: (Rect) -> Unit,
) : CartesianMarker {
    override fun drawUnderLayers(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
    ) {
        onBoundsChanged(context.layerBounds)
    }

    override fun drawOverLayers(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
    ) {
        onBoundsChanged(context.layerBounds)
    }
}

@Composable
fun rememberBoundsTrackingMarker(onBoundsChanged: (Rect) -> Unit): CartesianMarker =
    remember { BoundsTrackingMarker(onBoundsChanged) }
