package app.readylytics.health.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.SleepSessionDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.data.local.reconstructTimestampedSamples
import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.HrMinuteBucketRow
import app.readylytics.health.domain.model.RecordType
import app.readylytics.health.domain.model.TimestampedTrimp
import app.readylytics.health.domain.scoring.ComputeDailyTrimpUseCase
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoringDayDataLoader
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val sleepSessionDao: SleepSessionDao,
        private val dailySummaryDao: DailySummaryDao,
        private val heartRateDao: HeartRateDao,
        private val minuteBucketDao: MinuteBucketDao,
        private val weightRecordDao: WeightRecordDao,
        private val bodyFatRecordDao: BodyFatRecordDao,
        private val bloodPressureRecordDao: BloodPressureRecordDao,
        private val oxygenSaturationRecordDao: OxygenSaturationRecordDao,
        private val bodyTemperatureRecordDao: BodyTemperatureRecordDao,
    ) {
        // from processWorkouts L298
        suspend fun loadWorkouts(dayMidnightMs: Long, nextDayMidnightMs: Long): List<WorkoutRecordEntity> =
            workoutDao.getWorkoutsInRange(dayMidnightMs, nextDayMidnightMs)

        // from processWorkouts L299-306
        suspend fun loadExerciseHrSamples(workouts: List<WorkoutRecordEntity>): List<HeartRateRecordEntity> {
            if (workouts.isEmpty()) return emptyList()
            return heartRateDao
                .getByTimeRange(workouts.minOf { it.startTime }, workouts.maxOf { it.endTime })
                .filter { it.recordType == RecordType.EXERCISE.name }
                .sortedBy { it.timestampMs }
        }

        // from exerciseSamplesForWorkout L655-673
        suspend fun loadWorkoutSamples(
            workout: WorkoutRecordEntity,
            hotSamples: List<HeartRateRecordEntity>,
        ): List<HeartRateRecordEntity> {
            val hot = hotSamples.filter { it.timestampMs in workout.startTime..workout.endTime }
            if (hot.isNotEmpty()) return hot
            return minuteBucketDao
                .getBucketsForSession("EXERCISE", workout.id)
                .reconstructTimestampedSamples()
                .map { (timestampMs, bpm) ->
                    HeartRateRecordEntity(
                        sourceRecordRef = 0L,
                        timestampMs = timestampMs,
                        beatsPerMinute = bpm,
                        recordType = RecordType.EXERCISE.name,
                        sessionId = workout.id,
                    )
                }
        }

        // from processWorkouts L325-328
        suspend fun persistModelTrimp(
            workouts: List<WorkoutRecordEntity>,
            updates: List<ComputeDailyTrimpUseCase.WorkoutModelTrimpUpdate>,
        ) {
            if (updates.isEmpty()) return
            val updateMap = updates.associate { it.workoutId to it.modelTrimp }
            workoutDao.upsertAll(workouts.filter { it.id in updateMap }.map { it.copy(modelTrimp = updateMap[it.id]) })
        }

        // from mergedMinuteBuckets L631-653
        suspend fun loadMergedMinuteBuckets(dayStartMs: Long, dayEndMs: Long): List<HrMinuteBucketRow> {
            val hot = heartRateDao.getMinuteBuckets(dayStartMs, dayEndMs)
            val warm = minuteBucketDao.getMinuteBuckets(dayStartMs, dayEndMs)
            if (warm.isEmpty()) return hot
            if (hot.isEmpty()) return warm
            val acc = LinkedHashMap<Int, Pair<Double, Int>>()
            fun add(row: HrMinuteBucketRow) {
                val prev = acc[row.bucketIndex]
                acc[row.bucketIndex] = if (prev == null) {
                    row.avgBpm * row.sampleCount to row.sampleCount
                } else {
                    (prev.first + row.avgBpm * row.sampleCount) to (prev.second + row.sampleCount)
                }
            }
            hot.forEach(::add)
            warm.forEach(::add)
            return acc.entries
                .sortedBy { it.key }
                .map { (idx, value) -> HrMinuteBucketRow(idx, value.first / value.second, value.second) }
        }

        // from resolveSleepAggregation L682
        suspend fun loadOverlappingSessions(fetchStartMs: Long, fetchEndMs: Long): List<SleepSessionEntity> =
            sleepSessionDao.getOverlapping(fetchStartMs, fetchEndMs)

        // from computeDailySummary L197
        suspend fun loadSessionEndingInRange(dayMidnightMs: Long, nextDayMidnightMs: Long): SleepSessionEntity? =
            sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)

        // from resolveAvgSpo2 L453-457
        suspend fun loadAvgSpo2(session: SleepSessionEntity?): Float? {
            if (session == null) return null
            val spo2Samples = oxygenSaturationRecordDao.getByTimeRange(session.startTime, session.endTime)
            return if (spo2Samples.isNotEmpty()) spo2Samples.asSequence().map { it.percentage }.average().toFloat() else null
        }

        // from resolveAvgBodyTemp L459-463
        suspend fun loadAvgBodyTemp(session: SleepSessionEntity?): Float? {
            if (session == null) return null
            val bodyTempSamples = bodyTemperatureRecordDao.getByTimeRange(session.startTime, session.endTime)
            return if (bodyTempSamples.isNotEmpty()) bodyTempSamples.asSequence().map { it.celsius }.average().toFloat() else null
        }

        data class LatestBodyMetrics(
            val weightKg: Float?,
            val bodyFatPercent: Float?,
            val bloodPressureSystolic: Int?,
            val bloodPressureDiastolic: Int?,
        )

        // from buildBaseSummary L411-413
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

        // from sumRasLastSixDays L765
        suspend fun loadPreviousDaysSummaries(previousDaysMs: List<Long>): List<DailySummaryEntity> =
            dailySummaryDao.getByDates(previousDaysMs)

        // from fetchWalkForwardTrimpContext L145 / computeCalibratedSummary L541
        suspend fun loadWorkoutTrimpPoints(fromMs: Long, toMs: Long): List<TimestampedTrimp> =
            workoutDao.getTrimpPoints(fromMs, toMs)

        // from fetchWalkForwardTrimpContext L146 / computeCalibratedSummary L546
        suspend fun loadEverydayTrimpPoints(fromMs: Long, toMs: Long): List<TimestampedTrimp> =
            dailySummaryDao.getEverydayTrimpPoints(fromMs, toMs)

        // from persist L626
        suspend fun persistDailySummary(summary: DailySummary, zoneId: ZoneId) {
            dailySummaryDao.upsert(DailySummaryMapper.toEntity(summary, zoneId))
        }
    }
