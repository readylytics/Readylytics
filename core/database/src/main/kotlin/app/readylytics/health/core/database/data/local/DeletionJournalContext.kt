package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.SettingsRepository
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.ScoreInvalidation
import app.readylytics.health.core.model.domain.util.RetentionBounds
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/**
 * Dependencies [deleteRecordsAndJournal] needs, bundled into one holder so the function stays
 * under detekt's LongParameterList threshold instead of taking each dependency as its own
 * parameter alongside [HealthDataType]/`ids`/`zoneId`/`today`/`reason`/`snapshotId`.
 */
data class DeletionJournalContext(
    val daos: HealthRecordDaos,
    val vo2MaxRecordDao: Vo2MaxRecordDao?,
    val dirtyRangeStore: RoomDirtyRangeStore?,
    val healthMutationStateDao: HealthMutationStateDao?,
    val settingsRepo: SettingsRepository?,
    val transactionRunner: TransactionRunner?,
)

/**
 * Deletes a batch of HC records of [type] and journals a durable dirty-range ticket covering
 * their pre-delete affected dates, in one Room transaction (bind-safe 500-item chunks). A
 * top-level function (rather than a [RoomHealthChangeIngestionStore] member) taking its
 * dependencies via [context], so the class's `deleteRecord`/`deleteRecords` overrides can share
 * this one implementation without the class itself crossing detekt's TooManyFunctions threshold.
 * [RoomDirtyRangeRetentionTest] also calls this directly to pin a custom [reason]/[snapshotId]/
 * [today] for determinism, which the public `deleteRecord`/`deleteRecords` API doesn't expose.
 */
suspend fun deleteRecordsAndJournal(
    context: DeletionJournalContext,
    type: HealthDataType,
    ids: List<String>,
    zoneId: ZoneId,
    today: LocalDate,
    reason: String = "RECORD_DELETION",
    snapshotId: String = "ACTIVE",
): Set<LocalDate> {
    if (ids.isEmpty()) return emptySet()
    val daos = context.daos
    val vo2MaxRecordDao = context.vo2MaxRecordDao
    return context.transactionRunner.runOrDirect {
        val affected = mutableSetOf<LocalDate>()
        ids.chunked(500).forEach { chunk ->
            affected.addAll(datesForChunk(daos, vo2MaxRecordDao, type, chunk, zoneId))
        }
        val dirtyRangeStore = context.dirtyRangeStore
        val healthMutationStateDao = context.healthMutationStateDao
        if (affected.isNotEmpty() && dirtyRangeStore != null && healthMutationStateDao != null) {
            journalAffectedDates(
                affected, dirtyRangeStore, healthMutationStateDao, context.settingsRepo, today, reason, snapshotId,
            )
        }
        ids.chunked(500).forEach { chunk -> deleteFromDaosPlural(daos, vo2MaxRecordDao, type, chunk) }
        affected
    }
}

private suspend fun journalAffectedDates(
    affected: Set<LocalDate>,
    dirtyRangeStore: RoomDirtyRangeStore,
    healthMutationStateDao: HealthMutationStateDao,
    settingsRepo: SettingsRepository?,
    today: LocalDate,
    reason: String,
    snapshotId: String,
) {
    val earliest = affected.minOrNull()!!
    val latest = affected.maxOrNull()!!
    val prefs = settingsRepo?.userPreferences?.first()
    val retentionStart = RetentionBounds.resolveResyncStartDate(prefs ?: UserPreferences(), today)
    val closure =
        ScoreInvalidation.dependencyClosure(
            changed = ScoreInvalidation.AffectedRange(earliest, latest),
            reason = ScoreInvalidation.reasonFromStored(reason),
            retentionStart = retentionStart,
            today = today,
        )
    if (closure != null) {
        healthMutationStateDao.incrementGeneration()
        dirtyRangeStore.append(
            start = closure.start,
            endInclusive = closure.endInclusive,
            reason = reason,
            snapshotId = snapshotId,
        )
    }
}
