package app.readylytics.health.data.logcat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class LogcatCaptureStoreImplTest {
    @Test
    fun formatSinceTimeMatchesLogcatExpectedPattern() {
        val formatted = LogcatCaptureStoreImpl.formatSinceTime(15)

        assertTrue(formatted.matches(Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""")))
    }

    @Test
    fun formatSinceTimeIsEarlierForLargerDurations() {
        val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        val shorter = format.parse(LogcatCaptureStoreImpl.formatSinceTime(15))!!
        val longer = format.parse(LogcatCaptureStoreImpl.formatSinceTime(120))!!

        assertTrue(longer.time <= shorter.time)
    }
}
