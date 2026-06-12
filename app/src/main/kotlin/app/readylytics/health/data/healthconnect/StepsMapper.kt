package app.readylytics.health.data.healthconnect

import androidx.health.connect.client.records.StepsRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Maps Health Connect [StepsRecord]s into lightweight, device-aware entries so
 * step totals can respect the user's per-data-type source-device selection.
 *
 * The aggregate steps API ([androidx.health.connect.client.HealthConnectClient.aggregate])
 * cannot be filtered by device, so when a specific device is selected we read raw
 * records and aggregate them here instead.
 */
object StepsMapper {
    data class StepEntry(
        val startTimeMs: Long,
        val deviceName: String,
        val count: Long,
    )

    fun toStepEntries(records: List<StepsRecord>): List<StepEntry> =
        records.map { record ->
            StepEntry(
                startTimeMs = record.startTime.toEpochMilli(),
                deviceName = DeviceLabel.from(record.metadata.device, record.metadata.dataOrigin),
                count = record.count,
            )
        }

    fun sumByDay(
        entries: List<StepEntry>,
        zoneId: ZoneId,
    ): Map<LocalDate, Long> =
        entries
            .groupBy { Instant.ofEpochMilli(it.startTimeMs).atZone(zoneId).toLocalDate() }
            .mapValues { (_, dayEntries) -> dayEntries.sumOf { it.count } }
}
