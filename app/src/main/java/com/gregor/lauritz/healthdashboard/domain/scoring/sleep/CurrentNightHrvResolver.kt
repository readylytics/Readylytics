package com.gregor.lauritz.healthdashboard.domain.scoring.sleep

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.util.mean
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentNightHrvResolver
    @Inject
    constructor(
        private val hrvDao: HrvDao,
        private val dailySummaryDao: DailySummaryDao,
    ) {
        data class HrvResult(
            val samples: List<Float>,
            val mean: Float,
        )

        suspend fun resolve(
            session: SleepSessionEntity,
            dayMidnight: Instant,
        ): HrvResult {
            var samples = hrvDao.getSleepRmssdForSession(session.id)
            if (samples.isEmpty()) {
                samples = hrvDao.getRmssdInTimeRange(session.startTime, session.endTime)
            }

            val mean =
                if (samples.isNotEmpty()) {
                    samples.mean()
                } else {
                    val dayMidnightMs = dayMidnight.toEpochMilli()
                    val sevenDaysAgoMs = dayMidnightMs - TimeUnit.DAYS.toMillis(7L)
                    val recentSummaries =
                        dailySummaryDao
                            .getSince(sevenDaysAgoMs)
                            .filter { it.dateMidnightMs < dayMidnightMs }
                            .mapNotNull { it.nocturnalHrv?.toFloat() }
                    if (recentSummaries.isNotEmpty()) recentSummaries.mean() else 0f
                }

            return HrvResult(samples, mean)
        }
    }
