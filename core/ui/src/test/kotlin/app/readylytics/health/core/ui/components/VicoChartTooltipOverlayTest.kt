package app.readylytics.health.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VicoChartTooltipOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `idle overlay does not compose animated halo`() {
        composeRule.setContent {
            VicoChartTooltipOverlay(
                selectedPointOffset = null,
                modifier = Modifier.size(200.dp),
            )
        }

        assertEquals(0, composeRule.onAllNodesWithTag(VICO_POINT_HALO_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun `selected point composes animated halo`() {
        composeRule.setContent {
            VicoChartTooltipOverlay(
                selectedPointOffset = Offset(50f, 60f),
                modifier = Modifier.size(200.dp),
            )
        }

        assertEquals(1, composeRule.onAllNodesWithTag(VICO_POINT_HALO_TAG).fetchSemanticsNodes().size)
    }
}
