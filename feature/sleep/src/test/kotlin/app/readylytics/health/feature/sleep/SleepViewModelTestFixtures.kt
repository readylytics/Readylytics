package app.readylytics.health.feature.sleep

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.model.DailyMetrics
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import java.time.LocalDate
import java.time.ZoneId

// Reusable test fixtures for SleepViewModel tests.
// Provides factory methods for common test data scenarios.

/**
 * Builds a prior session (3 days in the past from selected date).
 * Used for testing historical data in trend charts.
 */
fun buildPriorSleepSession(
    selectedDate: LocalDate,
    zoneId: ZoneId,
): SleepSessionData =
    SleepSessionData(
        id = "session_prior",
        deviceName = "SmartRing",
        startTime =
            selectedDate
                .minusDays(3)
                .atTime(22, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        endTime =
            selectedDate
                .minusDays(2)
                .atTime(6, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        durationMinutes = 480,
        efficiency = 0.93f,
        deepSleepMinutes = 90,
        lightSleepMinutes = 300,
        remSleepMinutes = 90,
        awakeMinutes = 30,
        sleepScore = 85f,
    )

/**
 * Builds a core sleep session spanning midnight.
 * Used for core/main night sleep testing.
 */
fun buildCoreSleepSession(
    selectedDate: LocalDate,
    zoneId: ZoneId,
): SleepSessionData =
    sleepSession(
        id = "core",
        startTime =
            selectedDate
                .minusDays(1)
                .atTime(22, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        endTime =
            selectedDate
                .atTime(6, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        durationMinutes = 480,
    )

/**
 * Builds a nap session (short afternoon sleep).
 * Used for testing nap handling in trend calculations.
 */
fun buildNapSession(
    selectedDate: LocalDate,
    zoneId: ZoneId,
): SleepSessionData =
    sleepSession(
        id = "nap",
        startTime =
            selectedDate
                .atTime(13, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        endTime =
            selectedDate
                .atTime(13, 30)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        durationMinutes = 30,
    )

/**
 * Builds a short cutoff-boundary session (ends before 20-minute cutoff).
 * Used for testing session assignment at scoring day boundaries.
 */
fun buildCutoffBoundarySession(
    selectedDate: LocalDate,
    zoneId: ZoneId,
): SleepSessionData =
    sleepSession(
        id = "cutoff",
        startTime =
            selectedDate
                .minusDays(1)
                .atTime(20, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        endTime =
            selectedDate
                .minusDays(1)
                .atTime(20, 30)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        durationMinutes = 30,
    )

/**
 * Builds a legacy zero-duration session that needs computation.
 * Used for testing duration derivation from start/end times.
 */
fun buildLegacyZeroDurationSession(
    selectedDate: LocalDate,
    zoneId: ZoneId,
): SleepSessionData =
    sleepSession(
        id = "legacy-zero-duration",
        startTime =
            selectedDate
                .minusDays(1)
                .atTime(22, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli(),
        endTime =
            selectedDate
                .minusDays(1)
                .atTime(22, 0)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli() + 8 * 60 * 60_000L,
        durationMinutes = 0,
    )

/**
 * Builds a standard daily summary for the selected date.
 */
fun buildDailySummary(
    selectedDate: LocalDate,
    sleepDurationMinutes: Int = 480,
    sleepScore: Float? = null,
): DailySummary =
    DailySummary(
        date = selectedDate,
        sleepDurationMinutes = sleepDurationMinutes,
        sleepScore = sleepScore,
    )

/**
 * Builds a standard daily metrics for the selected date.
 */
fun buildDailyMetrics(
    selectedDate: LocalDate,
    sleepScoreRounded: Int = 80,
): DailyMetrics =
    DailyMetrics(
        date = selectedDate,
        sleepScoreRounded = sleepScoreRounded,
    )

/**
 * Gets default sleep layout configurations for testing.
 * Mirrors production defaults.
 */
fun getDefaultSleepLayoutConfigs() =
    Triple(
        SettingsDefaults.DEFAULT_SLEEP_TOP_CARDS,
        SettingsDefaults.DEFAULT_SLEEP_CHARTS,
        SettingsDefaults.DEFAULT_SLEEP_METRIC_CARDS,
    )
