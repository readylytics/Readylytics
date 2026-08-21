package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringHistoryRepository
import app.readylytics.health.core.model.domain.repository.WalkForwardBaselineContext
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfig
import app.readylytics.health.core.scoring.domain.scoring.ScoringConfigFactory
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import java.time.LocalDate
import java.time.ZoneId

data class ScoringDayContext(
    val targetDate: LocalDate,
    val zoneId: ZoneId,
    val dayMidnightMs: Long,
    val nextDayMidnightMs: Long,
    val sleepDayPolicy: SleepDayPolicy,
    val dailySummary: DailySummary?,
    val initialBaselines: ResolveDailyBaselinesUseCase.InitialBaselines,
    val scoringConfig: ScoringConfig,
    val prefs: UserPreferences,
)

class ScoringDayContextResolver(
    private val scoringConfigFactory: ScoringConfigFactory,
    private val resolveDailyBaselinesUseCase: ResolveDailyBaselinesUseCase,
    private val scoringHistoryRepository: ScoringHistoryRepository,
) {
    fun createSleepDayPolicy(prefs: UserPreferences, zoneId: ZoneId): SleepDayPolicy =
        SleepDayPolicy(
            coreMergeGapMinutes = prefs.coreMergeGapMinutes,
            supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
            minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
            supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
            scoringZoneId = zoneId,
        )

    suspend fun resolveScoringDayContext(
        targetDate: LocalDate,
        prefs: UserPreferences,
        baselineContext: WalkForwardBaselineContext?,
    ): ScoringDayContext {
        val zoneId = prefs.scoringZone()
        val dayMidnightMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val nextDayMidnightMs = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val sleepDayPolicy = createSleepDayPolicy(prefs, zoneId)
        val dailySummary = scoringHistoryRepository.getDailySummaryByDate(dayMidnightMs, zoneId)

        val initialBaselines =
            resolveDailyBaselinesUseCase.resolveInitialBaselines(
                dayMidnightMs = dayMidnightMs,
                nextDayMidnightMs = nextDayMidnightMs,
                prefs = prefs,
                dailySummary = dailySummary,
                sleepDayPolicy = sleepDayPolicy,
                prefetchedSessions = baselineContext?.sessions,
            )
        val scoringConfig =
            scoringConfigFactory.build(
                userPreferences = prefs,
                installDate = LocalDate.ofEpochDay(prefs.installDate / 86400000),
                currentDate = targetDate,
            )
        return ScoringDayContext(
            targetDate = targetDate,
            zoneId = zoneId,
            dayMidnightMs = dayMidnightMs,
            nextDayMidnightMs = nextDayMidnightMs,
            sleepDayPolicy = sleepDayPolicy,
            dailySummary = dailySummary,
            initialBaselines = initialBaselines,
            scoringConfig = scoringConfig,
            prefs = prefs,
        )
    }
}
