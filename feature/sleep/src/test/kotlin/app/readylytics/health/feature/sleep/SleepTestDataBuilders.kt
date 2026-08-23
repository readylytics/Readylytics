package app.readylytics.health.feature.sleep

import app.readylytics.health.core.model.domain.repository.HeartRateRecordData
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import java.time.LocalDate
import java.time.ZoneId

/**
 * Test data builders for sleep feature tests.
 * Extracts builder helpers to reduce method length and improve testability.
 */

/**
 * Builds a standard sleep session with optional customization of sleep metrics.
 */
fun sleepSession(
    id: String,
    startTime: Long,
    endTime: Long,
    durationMinutes: Int,
    deviceName: String = "SmartRing",
    efficiency: Float = 0.93f,
    deepSleepMinutes: Int = 90,
    lightSleepMinutes: Int = 300,
    remSleepMinutes: Int = 90,
    awakeMinutes: Int = 0,
    sleepScore: Float? = null,
) = SleepSessionData(
    id = id,
    deviceName = deviceName,
    startTime = startTime,
    endTime = endTime,
    durationMinutes = durationMinutes,
    efficiency = efficiency,
    deepSleepMinutes = deepSleepMinutes,
    lightSleepMinutes = lightSleepMinutes,
    remSleepMinutes = remSleepMinutes,
    awakeMinutes = awakeMinutes,
    sleepScore = sleepScore,
)

/**
 * Builds a heart rate sample for a sleep session.
 */
fun buildHeartRateSample(
    id: String,
    sessionId: String,
    timestampMs: Long,
    beatsPerMinute: Int,
) = HeartRateRecordData(
    id = id,
    timestampMs = timestampMs,
    beatsPerMinute = beatsPerMinute,
    recordType = "SLEEP",
    sessionId = sessionId,
)

/**
 * Builds a standard sleep session with heart rate data.
 * Used when testing HR timeline integration with sleep sessions.
 */
fun buildSleepSessionWithHr(
    selectedDate: LocalDate,
    zoneId: ZoneId,
) = sleepSession(
    id = "session_1",
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
    awakeMinutes = 30,
)

/**
 * Builds two overlapping sleep sessions to test tie-breaking logic.
 * Returns: (stable ID winner without source) to (source winner)
 */
fun buildOverlapTiebreakerSessions(
    selectedDate: LocalDate,
    zoneId: ZoneId,
): Pair<SleepSessionData, SleepSessionData> {
    val earlierStart = selectedDate.minusDays(1).atTime(22, 0).atZone(zoneId).toInstant().toEpochMilli()
    val laterStart = selectedDate.minusDays(1).atTime(22, 30).atZone(zoneId).toInstant().toEpochMilli()
    val stableIdWinnerWithoutSource = sleepSession(
        id = "a-stable-id",
        startTime = earlierStart,
        endTime = earlierStart + 480 * 60_000L,
        durationMinutes = 480,
        deviceName = "z-source",
    )
    val sourceWinner = sleepSession(
        id = "z-stable-id",
        startTime = laterStart,
        endTime = laterStart + 480 * 60_000L,
        durationMinutes = 480,
        deviceName = "a-source",
    )
    return stableIdWinnerWithoutSource to sourceWinner
}

/**
 * Builds session data for testing scoring zone assignment.
 * Returns: (scoring zone ID, session start time in millis)
 */
fun buildScoringZoneSessionData(
    selectedDate: LocalDate,
    deviceZoneId: ZoneId,
): Pair<ZoneId, Long> {
    val cutoffMinutes = 20 * 60
    val referenceInstant = selectedDate.minusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant()
    val deviceOffsetSeconds = deviceZoneId.rules.getOffset(referenceInstant).totalSeconds
    val scoringZoneId =
        listOf("America/New_York", "Pacific/Kiritimati", "Pacific/Pago_Pago")
            .asSequence()
            .map(ZoneId::of)
            .first { zone ->
                zone.rules.getOffset(referenceInstant).totalSeconds != deviceOffsetSeconds
            }
    val sessionStart =
        (0..(48 * 60))
            .asSequence()
            .map { minuteOffset ->
                selectedDate
                    .minusDays(1)
                    .atStartOfDay(ZoneId.of("UTC"))
                    .plusMinutes(minuteOffset.toLong())
                    .toInstant()
            }.first { instant ->
                scoreDayFor(instant, scoringZoneId, cutoffMinutes) == selectedDate &&
                    scoreDayFor(instant, deviceZoneId, cutoffMinutes) != selectedDate
            }.toEpochMilli()
    return scoringZoneId to sessionStart
}

/**
 * Calculates the score day for a given instant, zone, and cutoff time.
 * Used by scoring zone testing to determine which day a session belongs to.
 */
fun scoreDayFor(
    instant: java.time.Instant,
    zoneId: ZoneId,
    cutoffMinutes: Int,
): LocalDate {
    val localTime = instant.atZone(zoneId)
    val minutesOfDay = localTime.hour * 60 + localTime.minute
    return if (minutesOfDay < cutoffMinutes) localTime.toLocalDate() else localTime.toLocalDate().plusDays(1)
}
