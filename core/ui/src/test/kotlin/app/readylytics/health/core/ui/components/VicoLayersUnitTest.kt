package app.readylytics.health.core.ui.components

import androidx.compose.ui.geometry.Rect
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class VicoLayersUnitTest {
    @Test
    fun `bounds tracking marker updates layer bounds on draw`() {
        var observedBounds: Rect? = null
        val marker = BoundsTrackingMarker { observedBounds = it }

        val mockContext = mockk<CartesianDrawingContext>()
        val expectedRect = Rect(0f, 0f, 100f, 200f)
        every { mockContext.layerBounds } returns expectedRect

        marker.drawUnderLayers(mockContext, emptyList())
        assertEquals(expectedRect, observedBounds)

        val updatedRect = Rect(10f, 20f, 110f, 220f)
        every { mockContext.layerBounds } returns updatedRect
        marker.drawOverLayers(mockContext, emptyList())
        assertEquals(updatedRect, observedBounds)
    }
}
