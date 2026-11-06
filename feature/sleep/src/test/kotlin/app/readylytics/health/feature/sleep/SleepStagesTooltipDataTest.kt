package app.readylytics.health.feature.sleep

import androidx.compose.ui.unit.IntOffset
import app.readylytics.health.core.model.domain.repository.SleepStageData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class SleepStagesTooltipDataTest {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
    private val stage = SleepStageData("deep", 0L, 3_600_000L, 60)
    private val segment = SelectedSegmentState(stage, segmentCenterXPx = 500f, segmentCenterYPx = 10f)

    private fun data(
        selected: SelectedSegmentState? = segment,
        scroll: Int,
    ) = sleepStagesTooltipData(
        selectedSegment = selected,
        viewportWidthPx = 400,
        scrollOffsetPx = scroll,
        selectedStageName = "Deep",
        selectedDurationStr = "1h",
        timeFormatter = formatter,
    )

    @Test
    fun `returns null when no selection`() {
        assertNull(data(selected = null, scroll = 0))
    }

    @Test
    fun `hides when segment left of viewport`() {
        assertNull(data(scroll = 600))
    }

    @Test
    fun `hides when segment right of viewport`() {
        assertNull(data(scroll = 0))
    }

    @Test
    fun `shows at left edge`() {
        assertEquals(IntOffset(0, 0), data(scroll = 500)?.offset)
    }

    @Test
    fun `shows at right edge`() {
        val result = data(scroll = 100)
        assertNotNull(result)
        assertEquals(IntOffset(400, 0), result?.offset)
        assertEquals("Deep", result?.valueText)
        assertEquals("1h", result?.extraLine)
    }
}
