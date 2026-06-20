package app.readylytics.health.data.local

import app.readylytics.health.data.healthconnect.WorkoutMapper
import app.readylytics.health.data.local.dao.HeartRateDao
import app.readylytics.health.data.local.dao.HrvDao
import app.readylytics.health.data.local.dao.SleepSessionDao
import app.readylytics.health.data.local.dao.WorkoutDao
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.domain.repository.TransactionRunner
import app.readylytics.health.domain.sync.link.SampleLink
import app.readylytics.health.domain.sync.link.SessionLinkReconciler
import app.readylytics.health.domain.sync.link.SessionLinker
import app.readylytics.health.domain.sync.link.SessionSpan
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionLinkReconcilerImpl
    @Inject
    constructor(
        private val sleepSessionDao: SleepSessionDao,
        private val workoutDao: WorkoutDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val transactionRunner: TransactionRunner,
    ) : SessionLinkReconciler {
        override suspend fun reconcile(
            startMs: Long,
            endMs: Long,
            zoneThresholds: IntArray,
        ) {
            val sleepSpans =
                sleepSessionDao
                    .getOverlapping(startMs, endMs)
                    .map { SessionSpan(it.id, it.startTime, it.endTime) }
            val workoutSpans =
                workoutDao
                    .getOverlapping(startMs, endMs)
                    .map { SessionSpan(it.id, it.startTime, it.endTime) }

            transactionRunner.runInTransaction {
                relinkHeartRate(startMs, endMs, sleepSpans, workoutSpans)
                relinkHrv(startMs, endMs, sleepSpans)
                recomputeWorkouts(workoutSpans, zoneThresholds)
            }
        }

        private suspend fun relinkHeartRate(
            startMs: Long,
            endMs: Long,
            sleepSpans: List<SessionSpan>,
            workoutSpans: List<SessionSpan>,
        ) {
            val records = heartRateDao.getByTimeRange(startMs, endMs)
            val updated =
                records.mapNotNull { record ->
                    val link = SessionLinker.resolve(record.timestampMs, sleepSpans, workoutSpans)
                    record.relinkedOrNull(link)
                }
            if (updated.isNotEmpty()) heartRateDao.upsertAll(updated)
        }

        private suspend fun relinkHrv(
            startMs: Long,
            endMs: Long,
            sleepSpans: List<SessionSpan>,
        ) {
            val records = hrvDao.getByTimeRange(startMs, endMs)
            val updated =
                records.mapNotNull { record ->
                    val link = SessionLinker.resolve(record.timestampMs, sleepSpans, emptyList())
                    record.relinkedOrNull(link)
                }
            if (updated.isNotEmpty()) hrvDao.upsertAll(updated)
        }

        private suspend fun recomputeWorkouts(
            workoutSpans: List<SessionSpan>,
            zoneThresholds: IntArray,
        ) {
            for (span in workoutSpans) {
                val existing = workoutDao.getById(span.id) ?: continue
                val hrSamples = heartRateDao.getByTimeRange(existing.startTime, existing.endTime)
                val metrics =
                    WorkoutMapper.computeMetrics(
                        existing.startTime,
                        existing.endTime,
                        hrSamples,
                        zoneThresholds,
                    )
                workoutDao.upsertAll(
                    listOf(
                        existing.copy(
                            durationMinutes = metrics.durationMinutes,
                            zone1Minutes = metrics.zoneMinutes[0],
                            zone2Minutes = metrics.zoneMinutes[1],
                            zone3Minutes = metrics.zoneMinutes[2],
                            zone4Minutes = metrics.zoneMinutes[3],
                            zone5Minutes = metrics.zoneMinutes[4],
                            trimp = metrics.trimp,
                            avgHr = metrics.avgHr,
                        ),
                    ),
                )
            }
        }
    }

private fun HeartRateRecordEntity.relinkedOrNull(link: SampleLink): HeartRateRecordEntity? =
    if (recordType != link.recordType || sessionId != link.sessionId) {
        copy(recordType = link.recordType, sessionId = link.sessionId)
    } else {
        null
    }

private fun HrvRecordEntity.relinkedOrNull(link: SampleLink): HrvRecordEntity? =
    if (recordType != link.recordType || sessionId != link.sessionId) {
        copy(recordType = link.recordType, sessionId = link.sessionId)
    } else {
        null
    }
