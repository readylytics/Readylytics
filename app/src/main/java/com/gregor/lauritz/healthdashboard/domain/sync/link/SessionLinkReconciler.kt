package com.gregor.lauritz.healthdashboard.domain.sync.link

import com.gregor.lauritz.healthdashboard.data.healthconnect.WorkoutMapper
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.HeartRateRecordEntity
import com.gregor.lauritz.healthdashboard.data.local.entity.HrvRecordEntity
import com.gregor.lauritz.healthdashboard.domain.repository.TransactionRunner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-tags every HR/HRV sample in [reconcile]'s range with its (recordType, sessionId) computed
 * via [SessionLinker] over the *complete* set of sleep + workout sessions in range, then
 * recomputes affected workout metrics ([WorkoutMapper.computeMetrics]).
 *
 * During a chunked resync ([com.gregor.lauritz.healthdashboard.domain.sync.HealthSyncUseCase.resyncRange]),
 * `HeartRateMapper`/`HrvMapper` only see the sleep/workout sessions present in the current
 * Health Connect fetch window. A night straddling a chunk boundary can therefore have its samples
 * split across two windows, each tagging only the subset it sees. Because chunk boundaries are
 * anchored to the resync start date (which itself depends on the user's retention setting), the
 * resulting tagging - and everything derived from it (RHR percentile, HRV mean, workout TRIMP) -
 * was retention-dependent.
 *
 * This pass runs once after all chunks are ingested and re-derives tagging from the full session
 * list, making the result a pure function of the data, independent of chunking.
 */
@Singleton
class SessionLinkReconciler
    @Inject
    constructor(
        private val sleepSessionDao: SleepSessionDao,
        private val workoutDao: WorkoutDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val transactionRunner: TransactionRunner,
    ) {
        suspend fun reconcile(
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
    }
