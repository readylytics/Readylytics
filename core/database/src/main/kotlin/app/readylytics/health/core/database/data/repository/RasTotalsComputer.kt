package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.scoring.domain.scoring.RasCalculator
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round

@Singleton
class RasTotalsComputer
    @Inject
    constructor(
        private val seriesLoader: ScoringSeriesLoader,
    ) {
        data class RasTotals(
            val dailyRas: Float,
            val dailyRasEverydayHr: Float,
            val totalRasWorkoutOnly: Float,
            val totalRasEverydayHr: Float,
            val last6DaysRasWorkoutOnly: Float,
        )

        suspend fun compute(
            dailyTrimpRaw: Float,
            trimpEverydayHr: Float,
            scalingFactor: Float,
            targetDate: LocalDate,
            zoneId: ZoneId,
        ): RasTotals {
            val dailyRas = round(RasCalculator.calculateDailyRas(dailyTrimpRaw, scalingFactor) * 10f) / 10f
            val dailyRasEverydayHr = round(RasCalculator.calculateDailyRas(trimpEverydayHr, scalingFactor) * 10f) / 10f
            val last6DaysRasWorkoutOnly = sumRasLastSixDays(targetDate, zoneId) { it.rasWorkoutOnly }
            val last6DaysRasEverydayHr = sumRasLastSixDays(targetDate, zoneId) { it.rasEverydayHr }
            return RasTotals(
                dailyRas = dailyRas,
                dailyRasEverydayHr = dailyRasEverydayHr,
                totalRasWorkoutOnly = round(dailyRas + last6DaysRasWorkoutOnly),
                totalRasEverydayHr = round(dailyRasEverydayHr + last6DaysRasEverydayHr),
                last6DaysRasWorkoutOnly = last6DaysRasWorkoutOnly,
            )
        }

        private suspend fun sumRasLastSixDays(
            targetDate: LocalDate,
            zoneId: ZoneId,
            selector: (DailySummaryEntity) -> Float?,
        ): Float {
            val previousDaysMs =
                (1..6).map { i ->
                    targetDate.minusDays(i.toLong()).atStartOfDay(zoneId).toInstant().toEpochMilli()
                }
            return seriesLoader.loadPreviousDaysSummaries(previousDaysMs).mapNotNull(selector).sum()
        }
    }
