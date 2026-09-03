package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BodyMetricsDataLoader
    @Inject
    constructor(
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        private val bodyTemperatureRecordDao: BodyTemperatureRecordDao,
        private val vo2MaxRecordDao: Vo2MaxRecordDao,
    ) {
        suspend fun loadAvgSpo2(session: SleepSessionEntity?): Float? {
            if (session == null) return null
            val spo2Samples = oxygenSaturationRecordDao.getByTimeRange(session.startTime, session.endTime)
            return if (spo2Samples.isNotEmpty()) {
                spo2Samples.asSequence().map { it.percentage }.average().toFloat()
            } else {
                null
            }
        }

        suspend fun loadAvgBodyTemp(session: SleepSessionEntity?): Float? {
            if (session == null) return null
            val bodyTempSamples = bodyTemperatureRecordDao.getByTimeRange(session.startTime, session.endTime)
            return if (bodyTempSamples.isNotEmpty()) {
                bodyTempSamples.asSequence().map { it.celsius }.average().toFloat()
            } else {
                null
            }
        }

        data class LatestBodyMetrics(
            val weightKg: Float?,
            val bodyFatPercent: Float?,
            val bloodPressureSystolic: Int?,
            val bloodPressureDiastolic: Int?,
        )

        suspend fun loadLatestBodyMetrics(nextDayMidnightMs: Long): LatestBodyMetrics {
            val weight = weightRecordDao.getLatestUpTo(nextDayMidnightMs)
            val bodyFat = bodyFatRecordDao.getLatestUpTo(nextDayMidnightMs)
            val bp = bloodPressureRecordDao.getLatestUpTo(nextDayMidnightMs)
            return LatestBodyMetrics(
                weightKg = weight?.weightKg,
                bodyFatPercent = bodyFat?.bodyFatPercent,
                bloodPressureSystolic = bp?.systolicMmHg,
                bloodPressureDiastolic = bp?.diastolicMmHg,
            )
        }

        suspend fun loadLatestVo2Max(nextDayMidnightMs: Long): Vo2MaxRecordEntity? =
            vo2MaxRecordDao.getLatestUpTo(nextDayMidnightMs)
    }
