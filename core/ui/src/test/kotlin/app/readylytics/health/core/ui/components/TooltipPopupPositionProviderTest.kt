package app.readylytics.health.core.ui.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Test
import kotlin.test.assertEquals

class TooltipPopupPositionProviderTest {
    private val provider =
        TooltipPopupPositionProvider(
            tapOffset = IntOffset(120, 90),
            yOffsetPx = 0,
        )
    private val anchor = IntRect(16, 300, 16 + 320, 300 + 180)
    private val window = IntSize(400, 800)
    private val popupSize = IntSize(120, 60)

    @Test
    fun `popup centers horizontally on the tap point`() {
        val position =
            provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popupSize)
        assertEquals(16 + 120 - 60, position.x)
    }

    @Test
    fun `popup floats above the top of the anchored chart`() {
        val position =
            provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popupSize)
        assertEquals(300 - 60, position.y)
    }

    @Test
    fun `popup is clamped inside the window`() {
        val edgeProvider =
            TooltipPopupPositionProvider(
                tapOffset = IntOffset(4, 4),
                yOffsetPx = 0,
            )
        val position =
            edgeProvider.calculatePosition(anchor, window, LayoutDirection.Ltr, popupSize)
        assertEquals(0, position.x)
        assertEquals(300 - 60, position.y)
    }
}
