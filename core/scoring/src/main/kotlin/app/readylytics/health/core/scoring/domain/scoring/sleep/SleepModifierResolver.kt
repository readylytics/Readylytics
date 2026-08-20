package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepFragmentationCalculator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver

import app.readylytics.health.domain.preferences.UserPreferences
import app.readylytics.health.domain.repository.SleepSessionData
import app.readylytics.health.domain.repository.SleepSessionRepository
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyRepository
import app.readylytics.health.core.model.domain.util.logE
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class SleepModifiers(
    val fragmentation: SleepFragmentation?,
    val regularityScore: Float?,
)

/**
 * Resolves the per-night modifiers the sleep score needs beyond the session row itself.
 * Neither modifier may fail the scoring pass: both degrade to null, which the strategy reads
 * as "unavailable" (degraded weights / neutral multiplier).
 */
@Singleton
class SleepModifierResolver
    @Inject
    constructor(
        private val sleepSessionRepository: SleepSessionRepository,
        private val circadianConsistencyRepository: CircadianConsistencyRepository,
    ) {
        suspend fun resolve(
            sessionId: String,
            targetDate: LocalDate,
            prefs: UserPreferences,
            stagesSuspicious: Boolean,
            prefetchedSessions: List<SleepSessionData>? = null,
        ): SleepModifiers {
            val fragmentation =
                if (stagesSuspicious) {
                    null
                } else {
                    try {
                        val stages = sleepSessionRepository.getSessionStages(sessionId)
                        if (stages.isEmpty()) null else SleepFragmentationCalculator.compute(stages)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logE(TAG, e) { "Fragmentation resolution failed for $sessionId" }
                        null
                    }
                }

            val regularity =
                try {
                    if (prefetchedSessions != null) {
                        circadianConsistencyRepository.scoreFor(targetDate, prefs, prefetchedSessions)
                    } else {
                        circadianConsistencyRepository.scoreFor(targetDate, prefs)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logE(TAG, e) { "Regularity resolution failed for $targetDate" }
                    null
                }

            return SleepModifiers(fragmentation = fragmentation, regularityScore = regularity)
        }

        private companion object {
            const val TAG = "SleepModifierResolver"
        }
    }
