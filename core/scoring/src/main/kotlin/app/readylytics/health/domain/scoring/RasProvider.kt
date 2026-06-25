package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.persistence.DailySummaryDao
import app.readylytics.health.domain.util.toMidnightEpochMilli
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RasProvider
    @Inject
    constructor(
        private val dao: DailySummaryDao,
    ) {
        suspend fun getPreciseRas(date: LocalDate): Double? {
            val dateMs = date.toMidnightEpochMilli()
            return dao.getPreciseRas(dateMs)
        }

        suspend fun getRoundedRas(date: LocalDate): Int? {
            val dateMs = date.toMidnightEpochMilli()
            return dao.getRoundedRas(dateMs)
        }
    }
