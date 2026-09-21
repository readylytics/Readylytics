package app.readylytics.health.core.scoring.domain.scoring.sleep

import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepFragmentationCalculator
import app.readylytics.health.core.scoring.domain.scoring.sleep.SleepModifierResolver

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.SleepSessionData
import app.readylytics.health.core.model.domain.repository.SleepSessionRepository
import app.readylytics.health.core.model.domain.repository.SleepStageData
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
            coreSessionIds: Set<String>,
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
                        // WP-14/C4: fetches exactly the core cluster's canonical segment IDs -- never
                        // a supplemental nap's stages, and never just one of several merged core
                        // segments (the old single-ID call could miss stages from a merged core's
                        // other segments entirely).
                        val stages = sleepSessionRepository.getSessionStages(coreSessionIds.toList())
                        val canonical = canonicalizeStages(stages)
                        if (canonical.isEmpty()) null else SleepFragmentationCalculator.compute(canonical)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logE(TAG, e) { "Fragmentation resolution failed for $coreSessionIds" }
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

        /**
         * Deduplicates by (session, stage type, start, end) -- a core cluster's segments can each
         * contribute an identical stage row when the same underlying record was synced under more
         * than one session ID -- then orders by (start, end, session ID) before fragmentation math
         * runs, per WP-14/C4's canonicalization contract.
         */
        private fun canonicalizeStages(stages: List<SleepStageData>): List<SleepStageData> =
            stages
                .distinctBy { StageDedupKey(it.sessionId, it.stageType, it.startTime, it.endTime) }
                .sortedWith(compareBy({ it.startTime }, { it.endTime }, { it.sessionId.orEmpty() }))

        private data class StageDedupKey(
            val sessionId: String?,
            val stageType: String,
            val startTime: Long,
            val endTime: Long,
        )

        private companion object {
            const val TAG = "SleepModifierResolver"
        }
    }
