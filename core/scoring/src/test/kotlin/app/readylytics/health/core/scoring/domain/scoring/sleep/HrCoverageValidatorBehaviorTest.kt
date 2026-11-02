package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.model.domain.model.HeartRateRecord
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HrCoverageValidatorBehaviorTest {
    private val validator = HrCoverageValidator()

    private fun hr(timestampMs: Long) = HeartRateRecord(
        id = "hr_$timestampMs",
        timestampMs = timestampMs,
        beatsPerMinute = 60,
        recordType = "SLEEP",
    )

    @Test
    fun `two boundary samples across an 8-hour sleep fail 70 percent coverage`() {
        val start = 0L
        val end = 8 * 3600 * 1000L // 8 hours
        // 2 samples separated by 7 hours 58 minutes
        val records = listOf(
            hr(60 * 1000L),
            hr(end - 60 * 1000L),
        )

        val isValid = validator.isValid(start, end, records)

        assertFalse(isValid, "Two boundary samples must not count as 100% coverage")
    }

    @Test
    fun `continuous sampling every 2 minutes passes 70 percent coverage`() {
        val start = 0L
        val end = 8 * 3600 * 1000L
        val records = (0..(8 * 30)).map { hr(it * 2 * 60 * 1000L) }

        val isValid = validator.isValid(start, end, records)

        assertTrue(isValid)
    }
}
