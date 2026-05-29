package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.model.PhysiologyConstants
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the RHR baseline value for a given moment in time.
 * Centralizes the strategy for calculating or falling back to default RHR.
 */
interface RhrBaselineProvider {
    suspend fun getRhrBaseline(dayMidnight: Instant): Float
}

/**
 * Adaptive RHR baseline provider.
 * Returns user's calculated baseline if ≥7 days of data exist,
 * otherwise returns safe default (60 bpm).
 *
 * REF: ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION = 7
 */
@Singleton
class AdaptiveRhrBaselineProvider
    @Inject
    constructor(
        private val baselineComputer: BaselineComputer,
        private val settingsRepository: SettingsRepository,
    ) : RhrBaselineProvider {
        override suspend fun getRhrBaseline(dayMidnight: Instant): Float {
            // Get user preferences (flow → single value)
            val preferences = settingsRepository.userPreferences.first()

            // Check if user has override RHR baseline set
            val rhrOverride = preferences.rhrBaselineOverride
            if (rhrOverride != null && rhrOverride > 0f) {
                return rhrOverride
            }

            // Compute RHR history within baseline window
            val rhrValues = baselineComputer.rhrHistory(dayMidnight, preferences.restingHrPercentile)

            // Need at least 7 days of data for calibration; otherwise use default
            val minimumDaysMs = ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION * 24 * 60 * 60 * 1000L
            val windowStartMs = dayMidnight.minus(ScoringConstants.BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val hasEnoughData = (dayMidnight.toEpochMilli() - windowStartMs) >= minimumDaysMs && rhrValues.isNotEmpty()

            return if (hasEnoughData) {
                baselineComputer.resolveBaselineRhrBpm(rhrValues, rhrOverride)
            } else {
                PhysiologyConstants.DEFAULT_RHR_BPM.toFloat()
            }
        }
    }
