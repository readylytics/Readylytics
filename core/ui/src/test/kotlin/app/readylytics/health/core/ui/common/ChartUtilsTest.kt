package app.readylytics.health.core.ui.common

import org.junit.Test
import java.util.Locale
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ChartUtilsTest {
    @Test
    fun `tooltip date formatter is reused for the same locale`() {
        assertSame(
            ChartUtils.getTooltipDateFormatter(Locale.US),
            ChartUtils.getTooltipDateFormatter(Locale.US),
        )
    }

    @Test
    fun `tooltip date formatter is distinct for different locales`() {
        assertNotSame(
            ChartUtils.getTooltipDateFormatter(Locale.US),
            ChartUtils.getTooltipDateFormatter(Locale.GERMANY),
        )
    }
}
