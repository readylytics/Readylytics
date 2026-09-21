package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao
import app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageDao
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.completeMinuteCutoff
import app.readylytics.health.core.model.domain.sync.link.SessionLinker
import app.readylytics.health.core.model.domain.sync.link.SessionSpan
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaces one authoritative source inside already published, source-backed minutes. The caller
 * owns the source replacement transaction, including metadata and dirty work. Other sources retain
 * their measured evidence; only the refreshed source is replaced. Legacy minutes remain quarantined
 * because one source payload does not establish complete interval coverage for unknown lineage.
 */
@Singleton
class SourceHeartRateRefresh
    @Inject
    constructor(
        private val daos: HealthRecordDaos,
        private val coverageDao: MinuteCoverageDao,
        private val bucketDao: MinuteBucketDao,
        private val publisher: MinuteCoveragePublisher,
        private val mutationStateDao: HealthMutationStateDao? = null,
    ) {
        suspend fun contributionsFor(sourceRef: Long): List<HrSourceMinuteContributionEntity> =
            coverageDao.getVisibleContributionsForSource(sourceRef)

        suspend fun publish(
            sourceRef: Long,
            previous: List<HrSourceMinuteContributionEntity>,
            rows: List<HeartRateInput>,
        ) {
            val rowsByMinute = rows.groupBy { completeMinuteCutoff(it.timestampMs) }
            val affectedMinutes = previous.map { it.bucketStartMs }.toSet() + rowsByMinute.keys
            for (minute in affectedMinutes.sorted()) {
                val coverage = coverageDao.getCoverageInRange(minute, minute + MINUTE_MS).singleOrNull()
                if (coverage?.tier == TIER_WARM && coverage.quality == QUALITY_SOURCE_BACKED) {
                    publishMinute(sourceRef, coverage, rowsByMinute[minute].orEmpty())
                }
            }
        }

        private suspend fun publishMinute(
            sourceRef: Long,
            coverage: MinuteCoverageEntity,
            rows: List<HeartRateInput>,
        ) {
            val minute = coverage.bucketStartMs
            val generation = mutationStateDao?.current()?.sourceGeneration ?: (coverage.visibleGeneration + 1L)
            val retained =
                coverageDao
                    .getContributionsForMinute(minute)
                    .filter { it.generation == coverage.visibleGeneration && it.sourceRecordRef != sourceRef }
                    .map { it.copy(generation = generation) }
            val refreshed = rows.toContribution(sourceRef, minute, generation)
            val contributions = retained + listOfNotNull(refreshed)
            if (contributions.isEmpty()) {
                bucketDao.deleteBucketsForMinutes(listOf(minute))
                coverageDao.deleteContributionsForMinutes(listOf(minute))
                coverageDao.deleteCoverageInRange(minute, minute + MINUTE_MS)
            } else {
                val samples =
                    retained.flatMap { contribution ->
                        contribution.reconstructEvidence().map { sample ->
                            HeartRateRecordEntity(
                                contribution.sourceRecordRef,
                                sample.timestampMs,
                                sample.beatsPerMinute,
                                "RESTING",
                                deviceName = sample.deviceName,
                            )
                        }
                    } +
                        rows.filter { it.beatsPerMinute in PLAUSIBLE_BPM }.map {
                            HeartRateRecordEntity(
                                sourceRef,
                                it.timestampMs,
                                it.beatsPerMinute,
                                it.recordType,
                                it.sessionId,
                                it.deviceName,
                            )
                        }
                publishProjection(coverage.copy(visibleGeneration = generation), contributions, samples)
            }
            // Do not leave a partial raw minute for a later rollup to publish over the retained
            // contributions. These rows have now been consumed by this complete visibility switch.
            rows.map { it.timestampMs }.chunked(DELETE_CHUNK_SIZE).forEach {
                daos.heartRateDao.deleteBySourceRecordRefAndTimestamps(sourceRef, it)
            }
        }

        private suspend fun publishProjection(
            coverage: MinuteCoverageEntity,
            contributions: List<HrSourceMinuteContributionEntity>,
            samples: List<HeartRateRecordEntity>,
        ) {
            val minute = coverage.bucketStartMs
            val sleeps =
                daos.sleepSessionDao
                    .getOverlapping(minute, minute + MINUTE_MS - 1)
                    .map { SessionSpan(it.id, it.startTime, it.endTime) }
            val workouts =
                daos.workoutDao
                    .getOverlapping(minute, minute + MINUTE_MS - 1)
                    .map { SessionSpan(it.id, it.startTime, it.endTime) }
            val buckets =
                samples
                    .map { sample ->
                        val link = SessionLinker.resolve(sample.timestampMs, sleeps, workouts)
                        sample.copy(recordType = link.recordType, sessionId = link.sessionId)
                    }.aggregateIntoMinuteBuckets()
                    .map { it.copy(generation = coverage.visibleGeneration) }
            publisher.publish(
                MinutePublicationRequest(
                    rangeStartMs = minute,
                    rangeEndExclusiveMs = minute + MINUTE_MS,
                    capturedGeneration = coverage.visibleGeneration,
                    coverage = listOf(coverage),
                    contributions = contributions,
                    buckets = buckets,
                ),
            )
        }
    }

/** Compares the measured warm representation, avoiding a revision bump on an identical retry. */
internal fun List<HrSourceMinuteContributionEntity>.matchesHeartRatePayload(rows: List<HeartRateInput>): Boolean {
    val rowsByMinute = rows.groupBy { completeMinuteCutoff(it.timestampMs) }
    return all { previous ->
        rowsByMinute[previous.bucketStartMs].orEmpty().toContribution(
            previous.sourceRecordRef,
            previous.bucketStartMs,
            previous.generation,
        ) == previous
    }
}

private fun List<HeartRateInput>.toContribution(
    sourceRef: Long,
    minute: Long,
    generation: Long,
): HrSourceMinuteContributionEntity? {
    val plausible = filter { it.beatsPerMinute in PLAUSIBLE_BPM }
    if (plausible.isEmpty()) return null
    return HrSourceMinuteContributionEntity(
        sourceRecordRef = sourceRef,
        bucketStartMs = minute,
        generation = generation,
        firstSampleMs = plausible.minOf { it.timestampMs },
        lastSampleMs = plausible.maxOf { it.timestampMs },
        deviceName = plausible.firstOrNull { !it.deviceName.isNullOrBlank() }?.deviceName.orEmpty(),
        bpmHistogram = BpmHistogram(plausible.groupingBy { it.beatsPerMinute }.eachCount()).encode(),
    )
}

private const val MINUTE_MS = 60_000L
private const val DELETE_CHUNK_SIZE = 250
private val PLAUSIBLE_BPM = 30..230
