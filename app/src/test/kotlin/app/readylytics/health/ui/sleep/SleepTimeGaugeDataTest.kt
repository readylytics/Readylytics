package app.readylytics.health.ui.sleep

import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.repository.SleepSessionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SleepTimeGaugeDataTest {
    @Test
    fun `actual sleep subtracts awake minutes`() {
        val session = sleepSession(durationMinutes = 510, awakeMinutes = 45)

        assertEquals(465, actualSleepMinutes(session))
    }

    @Test
    fun `goal sleep maps to half fill`() {
        val data =
            buildSleepTimeGaugeData(
                session = sleepSession(durationMinutes = 510, awakeMinutes = 30),
                summary = DailySummary(date = LocalDate.of(2026, 6, 11), sleepDurationMinutes = 480),
                goalSleepHours = 8f,
            )

        assertEquals(0.5f, data.progress!!, 0.001f)
        assertEquals("8h", data.displayText)
        assertEquals(MetricStatus.OPTIMAL, data.status)
    }

    @Test
    fun `double goal sleep maps to full fill`() {
        val data =
            buildSleepTimeGaugeData(
                session = sleepSession(durationMinutes = 960, awakeMinutes = 0),
                summary = DailySummary(date = LocalDate.of(2026, 6, 11), sleepDurationMinutes = 960),
                goalSleepHours = 8f,
            )

        assertEquals(1f, data.progress!!, 0.001f)
    }

    @Test
    fun `over double goal sleep clamps to full fill`() {
        val data =
            buildSleepTimeGaugeData(
                session = sleepSession(durationMinutes = 1_000, awakeMinutes = 0),
                summary = DailySummary(date = LocalDate.of(2026, 6, 11), sleepDurationMinutes = 1_000),
                goalSleepHours = 8f,
            )

        assertEquals(1f, data.progress!!, 0.001f)
    }

    @Test
    fun `missing sleep session returns unavailable calibrated state`() {
        val data =
            buildSleepTimeGaugeData(
                session = null,
                summary = null,
                goalSleepHours = 8f,
            )

        assertNull(data.progress)
        assertEquals("—", data.displayText)
        assertEquals(MetricStatus.CALIBRATING, data.status)
    }

    private fun sleepSession(
        durationMinutes: Int,
        awakeMinutes: Int,
    ) = SleepSessionData(
        id = "sleep_1",
        deviceName = "Test Ring",
        startTime = 0L,
        endTime = durationMinutes * 60_000L,
        durationMinutes = durationMinutes,
        efficiency = 0.9f,
        deepSleepMinutes = 90,
        lightSleepMinutes = 300,
        remSleepMinutes = 90,
        awakeMinutes = awakeMinutes,
        sleepScore = 85f,
    )
}
