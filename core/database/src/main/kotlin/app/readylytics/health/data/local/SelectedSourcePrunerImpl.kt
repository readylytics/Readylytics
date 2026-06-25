package app.readylytics.health.data.local

import app.readylytics.health.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.data.local.dao.BodyFatRecordDao
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WeightRecordDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.domain.model.HealthDataType
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.sync.SelectedSourcePruner
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SelectedSourcePrunerImpl
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val sleepSessionDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val workoutDao: WorkoutDao,
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
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
                                sleepSessionDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.HEART_RATE ->
                                heartRateDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.HRV ->
                                hrvDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.EXERCISE ->
                                workoutDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.WEIGHT ->
                                weightRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.BODY_FAT ->
                                bodyFatRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.BLOOD_PRESSURE ->
                                bloodPressureRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.OXYGEN_SATURATION ->
                                oxygenSaturationRecordDao.deleteRecordsNotMatchingDevice(fromMs, toMs, deviceName)
                            HealthDataType.STEPS -> {
                                // Steps are in daily_summaries
                            }
                        }
                    }
                }
            }
        }
    }
