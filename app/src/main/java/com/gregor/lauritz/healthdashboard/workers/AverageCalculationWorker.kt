package com.gregor.lauritz.healthdashboard.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gregor.lauritz.healthdashboard.data.local.dao.DailyHrvAverageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.DailyRestingHeartRateAverageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.DailySleepAverageDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailyHrvAverageEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.DailyRestingHeartRateAverageEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySleepAverageEntity
import com.gregor.lauritz.healthdashboard.domain.metrics.MovingAverageCalculator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

@HiltWorker
class AverageCalculationWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted workerParams: WorkerParameters,
        private val hrvDao: HrvDao,
        private val heartRateDao: HeartRateDao,
        private val sleepDao: SleepSessionDao,
        private val hrvAverageDao: DailyHrvAverageDao,
        private val rhrAverageDao: DailyRestingHeartRateAverageDao,
        private val sleepAverageDao: DailySleepAverageDao,
    ) : CoroutineWorker(appContext, workerParams) {
        override suspend fun doWork(): Result =
            runCatching {
                val zoneId = ZoneId.systemDefault()
                val today = LocalDate.now(zoneId)
                val todayMs = today.atStartOfDay(zoneId).toInstant().toEpochMilli()

                val lookbackMs = 30 * 24 * 60 * 60 * 1000L
                val startMs = todayMs - lookbackMs

                calculateAndStoreHrvAverages(startMs, todayMs, zoneId)
                calculateAndStoreRhrAverages(startMs, todayMs, zoneId)
                calculateAndStoreSleepAverages(startMs, todayMs, zoneId)

                Result.success()
            }.getOrElse {
                Result.retry()
            }

        private suspend fun calculateAndStoreHrvAverages(
            startMs: Long,
            todayMs: Long,
            zoneId: ZoneId,
        ) {
            val records = hrvDao._observeSleepHrvSince(startMs).first()

            val dailyValues = mutableMapOf<LocalDate, MutableList<Float>>()
            for (record in records) {
                val date = LocalDate.ofEpochDay(record.timestampMs / (24 * 60 * 60 * 1000L))
                dailyValues.getOrPut(date) { mutableListOf() }.add(record.rmssdMs)
            }

            for ((date, values) in dailyValues) {
                val dateMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

                val valuesByDate = dailyValues
                    .filter { (d, _) -> d.isBefore(date) || d.isEqual(date) }
                    .mapKeys { (d, _) -> d.atStartOfDay(zoneId).toInstant().toEpochMilli() }
                    .mapValues { (_, vs) -> MovingAverageCalculator.median(vs) }

                val (avg7d, avg30d) = MovingAverageCalculator.calculateMovingAverages(valuesByDate, dateMs)

                hrvAverageDao.upsert(DailyHrvAverageEntity(dateMs, avg7d, avg30d))
            }
        }

        private suspend fun calculateAndStoreRhrAverages(
            startMs: Long,
            todayMs: Long,
            zoneId: ZoneId,
        ) {
            val records = heartRateDao.getByTimeRange(startMs, todayMs)
            val restingRecords = records.filter { it.recordType == "RESTING" }

            val dailyValues = mutableMapOf<LocalDate, MutableList<Int>>()
            for (record in restingRecords) {
                val date = LocalDate.ofEpochDay(record.timestampMs / (24 * 60 * 60 * 1000L))
                dailyValues.getOrPut(date) { mutableListOf() }.add(record.beatsPerMinute)
            }

            for ((date, values) in dailyValues) {
                val dateMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

                val valuesByDate = dailyValues
                    .filter { (d, _) -> d.isBefore(date) || d.isEqual(date) }
                    .mapKeys { (d, _) -> d.atStartOfDay(zoneId).toInstant().toEpochMilli() }
                    .mapValues { (_, vs) -> MovingAverageCalculator.median(vs.map { it.toFloat() }) }

                val (avg7d, avg30d) = MovingAverageCalculator.calculateMovingAverages(valuesByDate, dateMs)

                rhrAverageDao.upsert(DailyRestingHeartRateAverageEntity(dateMs, avg7d, avg30d))
            }
        }

        private suspend fun calculateAndStoreSleepAverages(
            startMs: Long,
            todayMs: Long,
            zoneId: ZoneId,
        ) {
            val sessions = sleepDao.getSince(startMs)

            val dailyDurations = mutableMapOf<LocalDate, MutableList<Float>>()
            val dailyScores = mutableMapOf<LocalDate, MutableList<Float>>()

            for (session in sessions) {
                val date = LocalDate.ofEpochDay(session.startTime / (24 * 60 * 60 * 1000L))

                val durationMinutes = (session.endTime - session.startTime) / (60 * 1000f)
                dailyDurations.getOrPut(date) { mutableListOf() }.add(durationMinutes)

                session.sleepScore?.let {
                    dailyScores.getOrPut(date) { mutableListOf() }.add(it)
                }
            }

            val allDates = (dailyDurations.keys + dailyScores.keys).toSet()
            for (date in allDates) {
                val dateMs = date.atStartOfDay(zoneId).toInstant().toEpochMilli()

                val durationValuesByDate = dailyDurations
                    .filter { (d, _) -> d.isBefore(date) || d.isEqual(date) }
                    .mapKeys { (d, _) -> d.atStartOfDay(zoneId).toInstant().toEpochMilli() }
                    .mapValues { (_, vs) -> MovingAverageCalculator.median(vs) }

                val scoreValuesByDate = dailyScores
                    .filter { (d, _) -> d.isBefore(date) || d.isEqual(date) }
                    .mapKeys { (d, _) -> d.atStartOfDay(zoneId).toInstant().toEpochMilli() }
                    .mapValues { (_, vs) -> MovingAverageCalculator.median(vs) }

                val (durationAvg7d, durationAvg30d) = MovingAverageCalculator.calculateMovingAverages(durationValuesByDate, dateMs)
                val (scoreAvg7d, scoreAvg30d) = MovingAverageCalculator.calculateMovingAverages(scoreValuesByDate, dateMs)

                sleepAverageDao.upsert(
                    DailySleepAverageEntity(
                        dateMs,
                        durationAvg7d,
                        durationAvg30d,
                        scoreAvg7d,
                        scoreAvg30d,
                    ),
                )
            }
        }
    }
