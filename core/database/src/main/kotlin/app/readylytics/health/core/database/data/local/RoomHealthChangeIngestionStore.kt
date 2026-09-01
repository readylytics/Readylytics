package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.model.domain.model.DomainHeartRateSample
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.HealthChangeIngestionStore
import app.readylytics.health.core.model.domain.sync.SessionSpans
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomHealthChangeIngestionStore
    @Inject
    constructor(
        private val daos: HealthRecordDaos,
    ) : HealthChangeIngestionStore {
        override suspend fun affectedDatesForRecord(
            type: HealthDataType,
            hcRecordId: String,
            zoneId: ZoneId,
        ): Set<LocalDate> =
            when (type) {
                HealthDataType.SLEEP ->
                    daos.sleepSessionDao.getById(hcRecordId)?.let {
                        datesBetween(it.startTime, it.endTime, zoneId)
                    } ?: emptySet()
                HealthDataType.HEART_RATE ->
                    daos.sourceRecordDao.getSourceRef(hcRecordId)?.let { ref ->
                        daos.heartRateDao.getBySourceRecordRef(ref)
                            .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                    } ?: emptySet()
                HealthDataType.HRV ->
                    daos.sourceRecordDao.getSourceRef(hcRecordId)?.let { ref ->
                        daos.hrvDao.getBySourceRecordRef(ref)
                            .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                    } ?: emptySet()
                HealthDataType.EXERCISE ->
                    daos.workoutDao.getById(hcRecordId)?.let {
                        datesBetween(it.startTime, it.endTime, zoneId)
                    } ?: emptySet()
                HealthDataType.WEIGHT ->
                    daos.weightRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.BODY_FAT ->
                    daos.bodyFatRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.BLOOD_PRESSURE ->
                    daos.bloodPressureRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.OXYGEN_SATURATION ->
                    daos.oxygenSaturationRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.BODY_TEMPERATURE ->
                    daos.bodyTemperatureRecordDao.getBySourceRecordId(hcRecordId)
                        .mapTo(mutableSetOf()) { dateFor(it.timestampMs, zoneId) }
                HealthDataType.STEPS ->
                    daos.stepRecordDao.getById(hcRecordId)?.let {
                        datesBetween(it.startTime, it.endTime, zoneId)
                    } ?: emptySet()
            }

        override suspend fun deleteRecord(type: HealthDataType, hcRecordId: String) {
            when (type) {
                HealthDataType.SLEEP -> daos.sleepSessionDao.deleteById(hcRecordId)
                HealthDataType.HEART_RATE -> {
                    daos.sourceRecordDao.getSourceRef(hcRecordId)
                        ?.let { daos.heartRateDao.deleteBySourceRecordRef(it) }
                    daos.sourceRecordDao.deleteBySourceRecordId(hcRecordId)
                }
                HealthDataType.HRV -> {
                    daos.sourceRecordDao.getSourceRef(hcRecordId)
                        ?.let { daos.hrvDao.deleteBySourceRecordRef(it) }
                    daos.sourceRecordDao.deleteBySourceRecordId(hcRecordId)
                }
                HealthDataType.EXERCISE -> daos.workoutDao.deleteById(hcRecordId)
                HealthDataType.WEIGHT -> daos.weightRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.BODY_FAT -> daos.bodyFatRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.BLOOD_PRESSURE -> daos.bloodPressureRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.OXYGEN_SATURATION ->
                    daos.oxygenSaturationRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.BODY_TEMPERATURE ->
                    daos.bodyTemperatureRecordDao.deleteBySourceRecordId(hcRecordId)
                HealthDataType.STEPS -> daos.stepRecordDao.deleteById(hcRecordId)
            }
        }

        override suspend fun sessionSpansOverlapping(startMs: Long, endMs: Long): SessionSpans =
            SessionSpans(
                sleepSessions = daos.sleepSessionDao.getOverlapping(startMs, endMs).map { it.toInput() },
                workouts = daos.workoutDao.getOverlapping(startMs, endMs).map { it.toInput() },
            )

        override suspend fun heartRateSamplesForMetrics(
            recordType: String,
            startMs: Long,
            endMs: Long,
        ): List<DomainHeartRateSample> =
            daos.heartRateDao.getByTypeAndTimeRange(recordType, startMs, endMs).map {
                DomainHeartRateSample(time = Instant.ofEpochMilli(it.timestampMs), beatsPerMinute = it.beatsPerMinute)
            }

        private fun datesBetween(startMs: Long, endMs: Long, zoneId: ZoneId): Set<LocalDate> {
            val startDate = Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate()
            val endDate = Instant.ofEpochMilli(endMs).atZone(zoneId).toLocalDate()
            val dates = mutableSetOf<LocalDate>()
            var current = startDate
            while (!current.isAfter(endDate)) {
                dates.add(current)
                current = current.plusDays(1)
            }
            return dates
        }

        private fun dateFor(timestampMs: Long, zoneId: ZoneId): LocalDate =
            Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate()
    }
