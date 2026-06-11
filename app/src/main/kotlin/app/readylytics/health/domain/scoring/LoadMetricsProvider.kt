package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.domain.display.MetricFormatter
import app.readylytics.health.domain.util.toMidnightEpochMilli
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
            return dao.getPreciseStrainRatio(dateMs)?.toFloat()?.let(MetricFormatter::roundStrain)
        }
    }
