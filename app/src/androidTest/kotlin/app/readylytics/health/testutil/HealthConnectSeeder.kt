package app.readylytics.health.testutil

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

enum class SeedPeriod(
    val days: Int,
) {
    DAYS_5(5),
    DAYS_14(14),
    DAYS_42(42),
}

private const val INSERT_BATCH_SIZE = 1000

/**
 * Seeds deterministic nocturnal RHR and HRV data into Health Connect.
 *
 * Per night:
 *   - [SeedConstants.RHR_PER_HOUR] RestingHeartRateRecords per hour of sleep (6-min intervals)
 *   - [SeedConstants.HRV_PER_HOUR] HeartRateVariabilityRmssdRecords per hour of sleep
 *
 * All records carry a stable [clientRecordId] → re-insertion is idempotent (upsert).
 *
 * @param today used as the anchor date; capture once per test to avoid midnight boundary issues.
 */
suspend fun HealthConnectClient.seedNocturnalData(
    period: SeedPeriod = SeedPeriod.DAYS_42,
    avgSleepHours: Double = SeedConstants.DEFAULT_AVG_SLEEP_HOURS,
    sleepVariationHours: Double = SeedConstants.DEFAULT_VARIATION_HOURS,
    today: LocalDate = LocalDate.now(ZoneOffset.UTC),
) {
    val allRecords: List<Record> =
        buildList {
            for (dayIndex in 0 until period.days) {
                val sleepStart = SeedConstants.sleepStartForDay(dayIndex, today)
                val sleepDurationHours = SeedConstants.sleepDurationForDay(dayIndex, avgSleepHours, sleepVariationHours)

                addAll(buildRhrRecords(dayIndex, sleepStart, sleepDurationHours))
                addAll(buildHrvRecords(dayIndex, sleepStart, sleepDurationHours))
            }
        }

    for (chunk in allRecords.chunked(INSERT_BATCH_SIZE)) {
        insertRecords(chunk)
    }
}

private fun buildRhrRecords(
    dayIndex: Int,
    sleepStart: Instant,
    sleepDurationHours: Double,
): List<RestingHeartRateRecord> {
    val totalSlots = floor(sleepDurationHours).toInt() * SeedConstants.RHR_PER_HOUR
    val intervalSeconds = 6 * 60L // 10 records per hour = one every 6 minutes

    val nadirBpm = SeedConstants.rhrNadirForDay(dayIndex)

    // Target: 3:00 AM UTC of the morning following sleepStart's calendar date
    val nadirTarget: Instant =
        sleepStart
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .plusDays(1)
            .atTime(SeedConstants.NADIR_TARGET_HOUR_UTC, 0)
            .toInstant(ZoneOffset.UTC)

    val nadirSlotIndex: Int =
        (0 until totalSlots).minByOrNull { i ->
            abs(sleepStart.plusSeconds(i * intervalSeconds).epochSecond - nadirTarget.epochSecond)
        } ?: 0

    return (0 until totalSlots).map { i ->
        val slotTime = sleepStart.plusSeconds(i * intervalSeconds)
        val bpm =
            if (i == nadirSlotIndex) {
                nadirBpm
            } else {
                // U-shaped curve: bpm rises with distance from nadir, capped at nadir + 14
                nadirBpm + min(abs(i - nadirSlotIndex).toLong(), 14L)
            }
        RestingHeartRateRecord(
            time = slotTime,
            zoneOffset = ZoneOffset.UTC,
            beatsPerMinute = bpm,
            metadata = Metadata.manualEntry(clientRecordId = "rhr_seeded_day${dayIndex}_s$i"),
        )
    }
}

private fun buildHrvRecords(
    dayIndex: Int,
    sleepStart: Instant,
    sleepDurationHours: Double,
): List<HeartRateVariabilityRmssdRecord> {
    val totalHrvs = floor(sleepDurationHours).toInt() * SeedConstants.HRV_PER_HOUR
    val baseRmssd = SeedConstants.hrvRmssdForDay(dayIndex)

    return (0 until totalHrvs).map { h ->
        val recordTime = sleepStart.plusSeconds(h * 3600L)
        // Arch-shaped: HRV rises through first half of sleep, falls in second half
        val rmssd = baseRmssd + min(h, totalHrvs - 1 - h).toDouble() * 2.5
        HeartRateVariabilityRmssdRecord(
            time = recordTime,
            zoneOffset = ZoneOffset.UTC,
            heartRateVariabilityMillis = rmssd,
            metadata = Metadata.manualEntry(clientRecordId = "hrv_seeded_day${dayIndex}_h$h"),
        )
    }
}
