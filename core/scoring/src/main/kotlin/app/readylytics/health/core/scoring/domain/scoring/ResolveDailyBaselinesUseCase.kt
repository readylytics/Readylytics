package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.scoring.domain.scoring.BaselineComputer
import app.readylytics.health.core.scoring.domain.scoring.ResolveDailyBaselinesUseCase

import app.readylytics.health.core.model.domain.scoring.ScoringConstants

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.model.SleepSession
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepDayPolicy
import app.readylytics.health.core.scoring.domain.util.HeartRateFormulas
import java.time.LocalDate
import javax.inject.Inject

class ResolveDailyBaselinesUseCase
    @Inject
    constructor(
        private val baselineComputer: BaselineComputer,
    ) {
        data class InitialBaselines(
            val hrMax: Float,
            val frozenHrMax: Float?,
            val frozenRasScalingFactor: Float?,
            val rhrBaselineValue: Float,
            val frozenSnapshot: DailySummary?,
        )

        data class FinalBaselines(
            val hrvMuMssd: Float?,
            val hrvSigmaMssd: Float?,
            val rhrBpm: Float,
            val rhrSigma: Float?,
        )

        suspend fun resolveInitialBaselines(
            dayMidnightMs: Long,
            nextDayMidnightMs: Long,
            prefs: UserPreferences,
            dailySummary: DailySummary?,
            sleepDayPolicy: SleepDayPolicy,
            prefetchedSessions: List<SleepSession>?,
        ): InitialBaselines {
            val frozenSnapshot = dailySummary?.takeIf { it.baselineCalculatedAtDate != null }
            val frozenHrMax = frozenSnapshot?.hrMax
            val frozenRasScalingFactor = frozenSnapshot?.rasScalingFactor
            val hrMax = frozenHrMax ?: HeartRateFormulas.resolveMaxHeartRate(prefs)

            val rhrBaselineValue =
                (if (dailySummary?.baselineCalculatedAtDate != null) dailySummary.rhrBpm else null)
                    ?: prefs.rhrBaselineOverride
                    ?: baselineComputer.computeAdaptiveBaselineRhrBpmBetween(
                        fromMs = dayMidnightMs,
                        toMs = nextDayMidnightMs,
                        percentile = prefs.restingHrPercentile,
                        sleepDayPolicy = sleepDayPolicy,
                        prefetchedSessions = prefetchedSessions,
                    )
                    ?: ScoringConstants.DEFAULT_RHR_BPM

            check(hrMax > 0f) { "HR Max is missing or invalid" }
            check(rhrBaselineValue > 0f) { "RHR Baseline is missing or invalid" }

            return InitialBaselines(
                hrMax = hrMax,
                frozenHrMax = frozenHrMax,
                frozenRasScalingFactor = frozenRasScalingFactor,
                rhrBaselineValue = rhrBaselineValue,
                frozenSnapshot = frozenSnapshot,
            )
        }

        fun resolveFinalBaselines(
            frozenSnapshot: DailySummary?,
            summaryHrvMuMssd: Float?,
            summaryHrvSigmaMssd: Float?,
            summaryRhrSigma: Float?,
            rhrBaselineValue: Float,
        ): FinalBaselines {
            val hrvMuMssd = frozenSnapshot?.hrvMuMssd ?: summaryHrvMuMssd
            val hrvSigmaMssd = frozenSnapshot?.hrvSigmaMssd ?: summaryHrvSigmaMssd
            val rhrBpm = frozenSnapshot?.rhrBpm ?: rhrBaselineValue
            val rhrSigma = frozenSnapshot?.rhrSigma ?: summaryRhrSigma

            return FinalBaselines(
                hrvMuMssd = hrvMuMssd,
                hrvSigmaMssd = hrvSigmaMssd,
                rhrBpm = rhrBpm,
                rhrSigma = rhrSigma,
            )
        }
    }
