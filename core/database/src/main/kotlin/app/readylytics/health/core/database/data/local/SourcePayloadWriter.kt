package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.model.data.preferences.scoringZone
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.HeartRateInput
import app.readylytics.health.core.model.domain.sync.HrvInput
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.sync.SourceMetadata
import app.readylytics.health.core.model.domain.sync.SourcePayload
import app.readylytics.health.core.model.domain.sync.completeMinuteCutoff
import app.readylytics.health.core.model.domain.util.RetentionBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.yield
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourcePayloadWriter
    @Inject
    constructor(
        private val daos: HealthRecordDaos,
        private val transactionRunner: TransactionRunner,
        private val dirtyRangeStore: RoomDirtyRangeStore? = null,
        private val healthMutationStateDao: HealthMutationStateDao? = null,
        private val settingsRepo: SettingsRepository? = null,
        private val clock: Clock = Clock.systemDefaultZone(),
        private val warmRefresh: SourceHeartRateRefresh? = null,
    ) {
        companion object {
            const val PAGE_TRANSACTION_MAX_ROWS = 5_000
        }

        suspend fun replaceHeartRateSources(sources: List<SourcePayload<HeartRateInput>>) {
            if (sources.isEmpty()) return
            sources.groupedByRowBudget(PAGE_TRANSACTION_MAX_ROWS).forEach { group ->
                currentCoroutineContext().ensureActive()
                transactionRunner.runInTransaction {
                    val resolved = SourceRefResolver.resolveAll(daos.sourceRecordDao, group.map { it.source })
                    group.forEach { payload ->
                        replaceSingleHeartRateSource(payload, resolved.getValue(payload.source.sourceId))
                    }
                }
                yield()
            }
        }

        suspend fun replaceHrvSources(sources: List<SourcePayload<HrvInput>>) {
            if (sources.isEmpty()) return
            sources.groupedByRowBudget(PAGE_TRANSACTION_MAX_ROWS).forEach { group ->
                currentCoroutineContext().ensureActive()
                transactionRunner.runInTransaction {
                    val resolved = SourceRefResolver.resolveAll(daos.sourceRecordDao, group.map { it.source })
                    group.forEach { payload ->
                        replaceSingleHrvSource(payload, resolved.getValue(payload.source.sourceId))
                    }
                }
                yield()
            }
        }

        private suspend fun replaceSingleHeartRateSource(
            payload: SourcePayload<HeartRateInput>,
            resolved: ResolvedSource,
        ) {
            val source = payload.source
            val newRows = payload.rows

            val existingSource = resolved.existing
            val sourceRef = resolved.ref
            val oldTimestamps = daos.heartRateDao.readTimestampsKeyset(sourceRef)
            val warmContributions = warmRefresh?.contributionsFor(sourceRef).orEmpty()
            val warmMinutes = warmContributions.map { it.bucketStartMs }.toSet()
            val expectedRawRows = newRows.filter { completeMinuteCutoff(it.timestampMs) !in warmMinutes }

            if (!isMetadataChanged(existingSource, source) &&
                warmContributions.matchesHeartRatePayload(newRows) &&
                areHeartRateRowsIdentical(daos.heartRateDao, sourceRef, oldTimestamps, expectedRawRows)
            ) {
                return
            }

            if (newRows.isNotEmpty()) {
                upsertHeartRateRows(sourceRef, newRows)
            }
            daos.heartRateDao.deleteMissingRows(sourceRef, oldTimestamps, newRows)
            updateSourceMetadata(sourceRef, existingSource, source)

            recordDirtyRange(
                oldTimestamps = oldTimestamps + warmContributions.flatMap { listOf(it.firstSampleMs, it.lastSampleMs) },
                newTimestamps = newRows.map { it.timestampMs },
                startMs = source.startMs,
                endExclusiveMs = source.endExclusiveMs,
                sourceRef = sourceRef,
            )
            warmRefresh?.publish(sourceRef, warmContributions, newRows)
        }

        private suspend fun replaceSingleHrvSource(
            payload: SourcePayload<HrvInput>,
            resolved: ResolvedSource,
        ) {
            val source = payload.source
            val newRows = payload.rows

            val existingSource = resolved.existing
            val sourceRef = resolved.ref
            val oldTimestamps = daos.hrvDao.readTimestampsKeyset(sourceRef)

            if (!isMetadataChanged(existingSource, source) &&
                areHrvRowsIdentical(daos.hrvDao, sourceRef, oldTimestamps, newRows)
            ) {
                return
            }

            if (newRows.isNotEmpty()) {
                upsertHrvRows(sourceRef, newRows)
            }
            daos.hrvDao.deleteMissingRows(sourceRef, oldTimestamps, newRows)
            updateSourceMetadata(sourceRef, existingSource, source)

            recordDirtyRange(
                oldTimestamps = oldTimestamps,
                newTimestamps = newRows.map { it.timestampMs },
                startMs = source.startMs,
                endExclusiveMs = source.endExclusiveMs,
                sourceRef = sourceRef,
            )
        }

        private suspend fun updateSourceMetadata(
            sourceRef: Long,
            existingSource: HealthSourceRecordEntity?,
            source: SourceMetadata,
        ) {
            val nextRevision = (existingSource?.sourceRevision ?: 0L) + 1L
            daos.sourceRecordDao.updateAuthoritativeMetadata(
                id = sourceRef,
                originPackage = source.originPackage,
                recordStartMs = source.startMs,
                recordEndExclusiveMs = source.endExclusiveMs,
                lastModifiedMs = source.lastModifiedMs,
                metadataState = METADATA_STATE_AUTHORITATIVE,
                sourceRevision = nextRevision,
            )
        }

        private suspend fun upsertHeartRateRows(
            sourceRef: Long,
            rows: List<HeartRateInput>,
        ) {
            rows.chunked(BATCH_SIZE).forEach { chunk ->
                daos.heartRateDao.upsertAll(
                    chunk.map { row ->
                        HeartRateRecordEntity(
                            sourceRecordRef = sourceRef,
                            timestampMs = row.timestampMs,
                            beatsPerMinute = row.beatsPerMinute,
                            recordType = row.recordType,
                            sessionId = row.sessionId,
                            deviceName = row.deviceName,
                        )
                    },
                )
            }
        }

        private suspend fun upsertHrvRows(
            sourceRef: Long,
            rows: List<HrvInput>,
        ) {
            rows.chunked(BATCH_SIZE).forEach { chunk ->
                daos.hrvDao.upsertAll(
                    chunk.map { row ->
                        HrvRecordEntity(
                            sourceRecordRef = sourceRef,
                            timestampMs = row.timestampMs,
                            rmssdMs = row.rmssdMs,
                            recordType = row.recordType,
                            sessionId = row.sessionId,
                            deviceName = row.deviceName,
                        )
                    },
                )
            }
        }

        private suspend fun recordDirtyRange(
            oldTimestamps: List<Long>,
            newTimestamps: List<Long>,
            startMs: Long,
            endExclusiveMs: Long,
            sourceRef: Long,
        ) {
            val zoneId = resolveZoneId()
            val today = LocalDate.now(clock.withZone(zoneId))
            val affectedDates = mutableSetOf<LocalDate>()

            for (ts in oldTimestamps) {
                affectedDates.add(Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate())
            }
            for (ts in newTimestamps) {
                affectedDates.add(Instant.ofEpochMilli(ts).atZone(zoneId).toLocalDate())
            }
            affectedDates.add(Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate())
            val endInclusiveMs = maxOf(startMs, endExclusiveMs - 1L)
            affectedDates.add(Instant.ofEpochMilli(endInclusiveMs).atZone(zoneId).toLocalDate())

            // Resolve session dates for records
            val hrSessionIds = daos.heartRateDao.getBySourceRecordRef(sourceRef).mapNotNull { it.sessionId }
            val hrvSessionIds = daos.hrvDao.getBySourceRecordRef(sourceRef).mapNotNull { it.sessionId }
            val sessionIds = (hrSessionIds + hrvSessionIds).toSet()
            for (sessionId in sessionIds) {
                daos.sleepSessionDao.getById(sessionId)?.let { session ->
                    affectedDates.add(Instant.ofEpochMilli(session.startTime).atZone(zoneId).toLocalDate())
                }
                daos.workoutDao.getById(sessionId)?.let { workout ->
                    affectedDates.add(Instant.ofEpochMilli(workout.startTime).atZone(zoneId).toLocalDate())
                }
            }

            if (affectedDates.isNotEmpty() && healthMutationStateDao != null && dirtyRangeStore != null) {
                val earliest = affectedDates.minOrNull()!!
                val latest = affectedDates.maxOrNull()!!
                val prefs = settingsRepo?.userPreferences?.first()
                val retentionStart = RetentionBounds.resolveResyncStartDate(prefs ?: UserPreferences(), today)

                val closure =
                    ScoreInvalidation.dependencyClosure(
                        changed = ScoreInvalidation.AffectedRange(earliest, latest),
                        reason = ScoreInvalidation.reasonFromStored(REASON_AUTHORITATIVE_SOURCE_REPLACEMENT),
                        retentionStart = retentionStart,
                        today = today,
                    )
                
                if (closure != null) {
                    healthMutationStateDao.incrementGeneration()
                    dirtyRangeStore.append(
                        start = closure.start,
                        endInclusive = closure.endInclusive,
                        reason = REASON_AUTHORITATIVE_SOURCE_REPLACEMENT,
                        snapshotId = SNAPSHOT_ACTIVE,
                    )
                }
            }
        }

        private suspend fun resolveZoneId(): ZoneId =
            try {
                settingsRepo?.userPreferences?.first()?.scoringZone() ?: clock.zone
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                clock.zone
            }
    }

private const val BATCH_SIZE = 500
private const val DELETE_CHUNK_SIZE = 250
private const val METADATA_STATE_AUTHORITATIVE = "AUTHORITATIVE"
private const val REASON_AUTHORITATIVE_SOURCE_REPLACEMENT = "AUTHORITATIVE_SOURCE_REPLACEMENT"
private const val SNAPSHOT_ACTIVE = "ACTIVE"

private suspend fun HeartRateDao.deleteMissingRows(
    sourceRef: Long,
    oldTimestamps: List<Long>,
    newRows: List<HeartRateInput>,
) {
    if (newRows.isEmpty()) {
        deleteBySourceRecordRef(sourceRef)
        return
    }
    val newTimestamps = newRows.map { it.timestampMs }.toSet()
    val timestampsToDelete = oldTimestamps.filter { it !in newTimestamps }
    if (timestampsToDelete.isNotEmpty()) {
        timestampsToDelete.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
            deleteBySourceRecordRefAndTimestamps(sourceRef, chunk)
        }
    }
}

private suspend fun HrvDao.deleteMissingRows(
    sourceRef: Long,
    oldTimestamps: List<Long>,
    newRows: List<HrvInput>,
) {
    if (newRows.isEmpty()) {
        deleteBySourceRecordRef(sourceRef)
        return
    }
    val newTimestamps = newRows.map { it.timestampMs }.toSet()
    val timestampsToDelete = oldTimestamps.filter { it !in newTimestamps }
    if (timestampsToDelete.isNotEmpty()) {
        timestampsToDelete.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
            deleteBySourceRecordRefAndTimestamps(sourceRef, chunk)
        }
    }
}

private fun isMetadataChanged(
    existing: HealthSourceRecordEntity?,
    source: SourceMetadata,
): Boolean {
    if (existing == null) return true
    val boundsMatch =
        existing.recordStartMs == source.startMs &&
            existing.recordEndExclusiveMs == source.endExclusiveMs
    val detailsMatch =
        existing.originPackage == source.originPackage &&
            existing.lastModifiedMs == source.lastModifiedMs &&
            existing.metadataState == METADATA_STATE_AUTHORITATIVE
    return !(boundsMatch && detailsMatch)
}

private fun HeartRateRecordEntity.matchesPayload(input: HeartRateInput): Boolean {
    val coreMatches = beatsPerMinute == input.beatsPerMinute && recordType == input.recordType
    val metaMatches = sessionId == input.sessionId && deviceName == input.deviceName
    return coreMatches && metaMatches
}

private fun HrvRecordEntity.matchesPayload(input: HrvInput): Boolean {
    val coreMatches = rmssdMs == input.rmssdMs && recordType == input.recordType
    val metaMatches = sessionId == input.sessionId && deviceName == input.deviceName
    return coreMatches && metaMatches
}

private suspend fun HeartRateDao.readTimestampsKeyset(sourceRef: Long): List<Long> {
    val timestamps = mutableListOf<Long>()
    var afterTs = Long.MIN_VALUE
    var hasMore = true
    while (hasMore) {
        val page = getTimestampsBySourceRecordRef(sourceRef, afterTs, BATCH_SIZE)
        timestamps.addAll(page)
        hasMore = page.size == BATCH_SIZE
        if (hasMore) {
            afterTs = page.last()
        }
    }
    return timestamps
}

private suspend fun HrvDao.readTimestampsKeyset(sourceRef: Long): List<Long> {
    val timestamps = mutableListOf<Long>()
    var afterTs = Long.MIN_VALUE
    var hasMore = true
    while (hasMore) {
        val page = getTimestampsBySourceRecordRef(sourceRef, afterTs, BATCH_SIZE)
        timestamps.addAll(page)
        hasMore = page.size == BATCH_SIZE
        if (hasMore) {
            afterTs = page.last()
        }
    }
    return timestamps
}

private suspend fun areHeartRateRowsIdentical(
    dao: HeartRateDao,
    sourceRef: Long,
    oldTimestamps: List<Long>,
    newRows: List<HeartRateInput>,
): Boolean {
    val timestampsMatch =
        oldTimestamps.size == newRows.size &&
            oldTimestamps == newRows.map { it.timestampMs }.sorted()
    if (!timestampsMatch) return false

    return if (newRows.isEmpty()) {
        true
    } else {
        val oldRecords = dao.getBySourceRecordRef(sourceRef)
        val resolvedNew = newRows.associateBy { it.timestampMs }
        oldRecords.size == resolvedNew.size &&
            oldRecords.all { old ->
                resolvedNew[old.timestampMs]?.let { old.matchesPayload(it) } == true
            }
    }
}

private suspend fun areHrvRowsIdentical(
    dao: HrvDao,
    sourceRef: Long,
    oldTimestamps: List<Long>,
    newRows: List<HrvInput>,
): Boolean {
    val timestampsMatch =
        oldTimestamps.size == newRows.size &&
            oldTimestamps == newRows.map { it.timestampMs }.sorted()
    if (!timestampsMatch) return false

    return if (newRows.isEmpty()) {
        true
    } else {
        val oldRecords = dao.getBySourceRecordRef(sourceRef)
        val resolvedNew = newRows.associateBy { it.timestampMs }
        oldRecords.size == resolvedNew.size &&
            oldRecords.all { old ->
                resolvedNew[old.timestampMs]?.let { old.matchesPayload(it) } == true
            }
    }
}

private fun <T : SourcePayload<*>> List<T>.groupedByRowBudget(maxRows: Int): List<List<T>> {
    val groups = mutableListOf<List<T>>()
    var currentGroup = mutableListOf<T>()
    var currentRowCount = 0

    for (payload in this) {
        val payloadRowCount = payload.rows.size
        if (currentRowCount + payloadRowCount > maxRows && currentGroup.isNotEmpty()) {
            groups.add(currentGroup)
            currentGroup = mutableListOf()
            currentRowCount = 0
        }
        currentGroup.add(payload)
        currentRowCount += payloadRowCount
    }
    if (currentGroup.isNotEmpty()) {
        groups.add(currentGroup)
    }
    return groups
}
