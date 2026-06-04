package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.domain.util.toMidnightEpochMilli
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoadMetricsProvider
    @Inject
    constructor(
        private val dao: DailySummaryDao,
    ) {
        suspend fun getPreciseStrainRatio(date: LocalDate): Double? {
            val dateMs = date.toMidnightEpochMilli()
            return dao.getPreciseStrainRatio(dateMs)
        }

        suspend fun getRoundedStrainRatio(date: LocalDate): Float? {
            val dateMs = date.toMidnightEpochMilli()
            return dao.getPreciseStrainRatio(dateMs)?.toFloat()
        }
    }
