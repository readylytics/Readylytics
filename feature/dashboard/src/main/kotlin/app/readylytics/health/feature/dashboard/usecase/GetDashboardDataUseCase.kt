package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.core.ui.model.HeartRateDaySummary
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.LoadSourceSelector
import app.readylytics.health.domain.model.Result
import app.readylytics.health.domain.model.SleepSessionSummary
import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.scoring.CircadianConsistencyResult
import app.readylytics.health.domain.util.logE
import app.readylytics.health.feature.dashboard.DashboardMetricPresentation
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetDashboardDataUseCase
    @Inject
    constructor(
        private val factory: DashboardMetricPresentationFactory,
    ) {
        data class DashboardCards(
            val cardDataMap: Map<CardId, DashboardMetricPresentation>,
            val rasDailyBreakdown: List<Pair<String, Float>>,
        )

        operator fun invoke(
            summary: DailySummary?,
            prefs: UserPreferences,
            date: LocalDate,
            lastSleepSession: SleepSessionSummary?,
            rasSummaries: List<DailySummary>,
            circadianResult: CircadianConsistencyResult? = null,
            heartRateSummary: HeartRateDaySummary? = null,
        ): Result<DashboardCards> =
            try {
                val cardDataMap =
                    factory.build(
                        summary,
                        prefs,
                        date,
                        lastSleepSession,
                        circadianResult,
                        heartRateSummary,
                    )
                val rasDailyBreakdown = buildRasBreakdown(date, rasSummaries, prefs)

                Result.success(DashboardCards(cardDataMap, rasDailyBreakdown))
            } catch (e: Exception) {
                logE("GetDashboardDataUseCase", e) { "Failed to build dashboard data" }
                Result.failure("Failed to build dashboard data", "CARD_GENERATION_ERROR")
            }

        private fun buildRasBreakdown(
            endDate: LocalDate,
            summaries: List<DailySummary>,
            prefs: UserPreferences,
        ): List<Pair<String, Float>> {
            val fmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
            return (6 downTo 0).map { daysBack ->
                val day = endDate.minusDays(daysBack.toLong())
                val entry = summaries.firstOrNull { it.date == day }
                val ras = entry?.let { LoadSourceSelector.selectDailyRas(it, prefs.rasSourceMode) }
                day.format(fmt) to (ras ?: 0f)
            }
        }
    }
