package com.gregor.lauritz.healthdashboard.domain.scoring.sleep

import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.entity.SleepSessionEntity
import com.gregor.lauritz.healthdashboard.domain.util.mean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentNightHrvResolver
    @Inject
    constructor(
        private val hrvDao: HrvDao,
    ) {
        data class HrvResult(
            val samples: List<Float>,
            val mean: Float,
        )

        suspend fun resolve(session: SleepSessionEntity): HrvResult {
            var samples = hrvDao.getSleepRmssdForSession(session.id)
            if (samples.isEmpty()) {
                samples = hrvDao.getRmssdInTimeRange(session.startTime, session.endTime)
            }
            val mean = if (samples.isNotEmpty()) samples.mean() else 0f
            return HrvResult(samples, mean)
        }
    }
