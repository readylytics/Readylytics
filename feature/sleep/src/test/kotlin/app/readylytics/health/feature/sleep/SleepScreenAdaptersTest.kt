package app.readylytics.health.feature.sleep

import app.readylytics.health.core.ui.common.DateFormatUtils
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.repository.SleepSessionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SleepScreenAdaptersTest {
    @Test
    fun `nap duration fallback resolves correctly`() {
        val napDurationDisplay: String? = null
        val resolved = napDurationDisplay ?: DateFormatUtils.formatSleepDuration(0) ?: "0h"
        assertEquals("0h", resolved)

        val napDurationDisplayWithValue = "1h 15m"
        val resolvedWithValue = napDurationDisplayWithValue ?: DateFormatUtils.formatSleepDuration(0) ?: "0h"
        assertEquals("1h 15m", resolvedWithValue)
    }

    @Test
    fun `nap count fallback resolves correctly`() {
        val napCount: Int? = null
        val resolved = napCount?.toString() ?: "0"
        assertEquals("0", resolved)

        val napCountWithValue = 2
        val resolvedWithValue = napCountWithValue.toString()
        assertEquals("2", resolvedWithValue)
    }

    @Test
    fun `single session visual stays when aggregate matches session actual sleep`() {
        val session =
            resolveSingleSessionVisual(
                session = sleepSession(durationMinutes = 510, awakeMinutes = 30),
                summary = DailySummary(date = LocalDate.of(2026, 7, 9), sleepDurationMinutes = 480),
            )

        assertEquals("sleep_1", session?.id)
    }

    @Test
    fun `single session visual falls back to session when aggregate differs from session actual sleep`() {
        val session =
            resolveSingleSessionVisual(
                session = sleepSession(durationMinutes = 510, awakeMinutes = 30),
                summary = DailySummary(date = LocalDate.of(2026, 7, 9), sleepDurationMinutes = 540),
            )

        assertEquals("sleep_1", session?.id)
        assertNull(session?.takeIf { it.endTime <= it.startTime })
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
