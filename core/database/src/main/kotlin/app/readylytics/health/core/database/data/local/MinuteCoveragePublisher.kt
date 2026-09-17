package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageDao
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Tier values persisted in `minute_coverage.tier`. */
const val TIER_WARM: String = "WARM"

/** Quality values persisted in `minute_coverage.quality`. */
const val QUALITY_SOURCE_BACKED: String = "SOURCE_BACKED"

/** Pre-v22 approximate warm projections, initialized by migration 21→22 and by old-archive restore. */
const val QUALITY_LEGACY_UNKNOWN: String = "LEGACY_UNKNOWN"

/**
 * One complete publication unit: the full set of minutes whose visible generation is being
 * switched together, plus the evidence and projections that back it.
 */
data class MinutePublicationRequest(
    val rangeStartMs: Long,
    val rangeEndExclusiveMs: Long,
    val capturedGeneration: Long,
    val coverage: List<MinuteCoverageEntity>,
    val contributions: List<HrSourceMinuteContributionEntity>,
    val buckets: List<HrMinuteBucketEntity>,
    val dirtyRange: DirtyRangeEntity? = null,
)

/**
 * What the publisher actually committed. [quarantinedMinutes] are the requested minutes it refused
 * to touch because their visible coverage is still legacy/approximate (OD-1): their raw evidence
 * stays in place pending an authorized complete refresh.
 */
data class MinutePublicationOutcome(
    val publishedMinutes: Set<Long>,
    val quarantinedMinutes: Set<Long>,
)

/**
 * Typed failure raised when the source generation captured before the publish no longer matches
 * the live one, i.e. another writer mutated sources while this unit was being assembled. Aborts
 * the caller's transaction rather than committing a mixed-generation projection.
 */
class SourceGenerationConflictException(
    val capturedGeneration: Long,
    val currentGeneration: Long,
) : IllegalStateException(
        "Source generation changed during publication: captured=$capturedGeneration, " +
            "current=$currentGeneration",
    )

/**
 * WP-17 Step 3/4 visibility switch for one complete minute/coverage unit.
 *
 * **Must be called inside a transaction owned by the caller** (`TransactionRunner` /
 * `withTransaction`). It deliberately does not open its own transaction, so that assembling the
 * unit, switching visibility and consuming the raw evidence stay in one all-or-nothing commit --
 * a throw anywhere before the caller's commit leaves the previously visible data untouched.
 *
 * Within that transaction it:
 * 1. re-checks the captured source generation ([SourceGenerationConflictException] otherwise),
 * 2. drops requested minutes whose visible coverage is still `LEGACY_UNKNOWN` (OD-1: an ordinary
 *    rollup is not an authorized complete refresh, and mixing source-backed buckets into a legacy
 *    minute is the concatenated state OD-1 forbids),
 * 3. deletes the superseded contributions and bucket slices of exactly the minutes it republishes,
 * 4. installs the new contributions and bucket projections,
 * 5. switches `minute_coverage` to the new generation, and
 * 6. appends P2 dirty work.
 */
@Singleton
class MinuteCoveragePublisher
    @Inject
    constructor(
        private val minuteBucketDao: MinuteBucketDao,
        private val minuteCoverageDao: MinuteCoverageDao,
        private val dirtyRangeDao: DirtyRangeDao? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
    ) {
        suspend fun publish(request: MinutePublicationRequest): MinutePublicationOutcome {
            requireGenerationUnchanged(request.capturedGeneration)

            val legacyMinutes =
                minuteCoverageDao
                    .getLegacyMinutesInRange(request.rangeStartMs, request.rangeEndExclusiveMs)
                    .toSet()
            val requestedMinutes = request.coverage.mapTo(mutableSetOf()) { it.bucketStartMs }
            val publishableCoverage = request.coverage.filterNot { it.bucketStartMs in legacyMinutes }
            val publishedMinutes = publishableCoverage.mapTo(mutableSetOf()) { it.bucketStartMs }
            val outcome =
                MinutePublicationOutcome(
                    publishedMinutes = publishedMinutes,
                    quarantinedMinutes = requestedMinutes.intersect(legacyMinutes),
                )
            if (publishedMinutes.isEmpty()) return outcome

            removeSupersededRows(publishedMinutes)
            minuteCoverageDao.upsertContributions(
                request.contributions.filter { it.bucketStartMs in publishedMinutes },
            )
            minuteBucketDao.upsertBuckets(request.buckets.filter { it.bucketStartMs in publishedMinutes })
            minuteCoverageDao.upsertCoverage(publishableCoverage)
            request.dirtyRange?.let { dirtyRangeDao?.insert(it) }
            return outcome
        }

        private suspend fun removeSupersededRows(publishedMinutes: Set<Long>) {
            publishedMinutes.chunked(MINUTE_KEY_CHUNK).forEach { chunk ->
                minuteCoverageDao.deleteContributionsForMinutes(chunk)
                minuteBucketDao.deleteBucketsForMinutes(chunk)
            }
        }

        private suspend fun requireGenerationUnchanged(capturedGeneration: Long) {
            val current = healthMutationStateDao?.current()?.sourceGeneration ?: return
            if (current != capturedGeneration) {
                throw SourceGenerationConflictException(capturedGeneration, current)
            }
        }

        private companion object {
            // Bounded so a day-chunk's worth of minute keys never approaches SQLite's
            // SQLITE_MAX_VARIABLE_NUMBER (999 on the oldest supported API levels).
            const val MINUTE_KEY_CHUNK = 500
        }
    }
