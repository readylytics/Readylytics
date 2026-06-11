package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.domain.util.toMidnightEpochMilli
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaiProvider
    @Inject
    constructor(
        private val dao: DailySummaryDao,
    ) {
        suspend fun getPrecisePai(date: LocalDate): Double? {
            val dateMs = date.toMidnightEpochMilli()
            return dao.getPrecisePai(dateMs)
        }

        suspend fun getRoundedPai(date: LocalDate): Int? {
            val dateMs = date.toMidnightEpochMilli()
            return dao.getRoundedPai(dateMs)
        }
    }
