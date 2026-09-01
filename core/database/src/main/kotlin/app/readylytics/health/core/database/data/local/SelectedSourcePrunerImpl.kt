package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.SelectedSourcePruner
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SelectedSourcePrunerImpl
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val daos: HealthRecordDaos,
    ) : SelectedSourcePruner {
        override suspend fun prune(
            start: LocalDate,
            endInclusive: LocalDate,
            selections: Map<HealthDataType, String?>,
            zoneId: ZoneId,
        ) {
            val fromMs = start.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val toMs =
                endInclusive
                    .plusDays(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            transactionRunner.runInTransaction {
                selections.forEach { (type, deviceName) ->
                    if (!deviceName.isNullOrBlank()) {
                        when (type) {
                            HealthDataType.SLEEP ->
                                daos.sleepSessionDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.HEART_RATE -> {
                                daos.heartRateDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                                daos.minuteBucketMaintenanceDao.deleteBucketsNotMatchingDevice(fromMs, toMs, deviceName)
                            }
                            HealthDataType.HRV ->
                                daos.hrvDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.EXERCISE ->
                                daos.workoutDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.WEIGHT ->
                                daos.weightRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.BODY_FAT ->
                                daos.bodyFatRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.BLOOD_PRESSURE ->
                                daos.bloodPressureRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.OXYGEN_SATURATION ->
                                daos.oxygenSaturationRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.BODY_TEMPERATURE ->
                                daos.bodyTemperatureRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.STEPS -> {
                                // Steps are in daily_summaries
                            }
                        }
                    }
                }
            }
        }
    }
