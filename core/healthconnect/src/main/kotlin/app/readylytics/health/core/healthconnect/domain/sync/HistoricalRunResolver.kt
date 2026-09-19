package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScoringRunSnapshot
import java.time.LocalDate

object HistoricalRunResolver {
    fun resolve(
        existing: HistoricalRunIdentity?,
        requested: HistoricalRunIdentity,
    ): HistoricalRunIdentity = if (isCompatible(existing, requested)) existing!! else requested

    private fun isCompatible(
        existing: HistoricalRunIdentity?,
        requested: HistoricalRunIdentity,
    ): Boolean =
        existing != null &&
            existing.protocolVersion == HistoricalRunIdentity.CURRENT_PROTOCOL_VERSION &&
            existing.protocolVersion == requested.protocolVersion &&
            existing.mode == requested.mode &&
            existing.zoneId == requested.zoneId &&
            existing.algorithmRevision == requested.algorithmRevision &&
            existing.sourceSelectionId == requested.sourceSelectionId &&
            existing.scoringSnapshotId == requested.scoringSnapshotId &&
            existing.startEpochDay == requested.startEpochDay &&
            requested.endEpochDayInclusive >= existing.endEpochDayInclusive

    fun resolveEffectiveCheckpoint(
        savedCheckpoint: ResyncCheckpoint?,
        runIdentity: HistoricalRunIdentity,
        isSameRun: Boolean,
        skipIngestAndPrune: Boolean,
        runStartDate: LocalDate,
    ): ResyncCheckpoint? {
        val checkpoint = savedCheckpoint ?: return null
        return if (isSameRun) {
            checkpoint.takeIf { skipIngestAndPrune || it.baselineChangeTokens.isNotEmpty() }
        } else {
            checkpoint.runIdentity
                ?.takeIf { canPreservePhases(it, runIdentity) }
                ?.let { remapCheckpointForNewSettings(checkpoint, runIdentity, runStartDate) }
        }
    }

    /**
     * WP-10 review fix: dropped the exact `sourceSelectionId` match this used to require -- a
     * source-selection change is now handled by its own narrower [remapForSourceSelectionRestart]
     * branch in [remapCheckpointForNewSettings] instead of forcing every phase back to a full
     * restart (see [affectedSourceTypes]).
     */
    private fun canPreservePhases(
        oldRun: HistoricalRunIdentity,
        newRun: HistoricalRunIdentity,
    ): Boolean =
        oldRun.protocolVersion == newRun.protocolVersion &&
            oldRun.mode == newRun.mode &&
            oldRun.startEpochDay == newRun.startEpochDay &&
            oldRun.endEpochDayInclusive == newRun.endEpochDayInclusive &&
            oldRun.zoneId == newRun.zoneId

    /** Choose the earliest invalidated phase so combined settings changes cannot skip ingestion. */
    private fun remapCheckpointForNewSettings(
        checkpoint: ResyncCheckpoint,
        runIdentity: HistoricalRunIdentity,
        runStartDate: LocalDate,
    ): ResyncCheckpoint {
        val oldSnapshot = checkpoint.runIdentity?.decodeScoringSnapshot()
        val newSnapshot = runIdentity.decodeScoringSnapshot()
        val hrZonesChanged =
            oldSnapshot == null || newSnapshot == null || hasHrZonesOrLinkPolicyChanged(oldSnapshot, newSnapshot)
        val affectedTypes =
            if (oldSnapshot != null && newSnapshot != null) {
                affectedSourceTypes(oldSnapshot, newSnapshot)
            } else {
                emptySet()
            }

        return when {
            affectedTypes.isNotEmpty() || oldSnapshot == null || newSnapshot == null ->
                remapForSourceSelectionRestart(checkpoint, runIdentity, runStartDate)
            hrZonesChanged -> remapForReconcileRestart(checkpoint, runIdentity, runStartDate)
            else -> remapForRecomputeRestart(checkpoint, runIdentity)
        }
    }

    private fun remapForReconcileRestart(
        checkpoint: ResyncCheckpoint,
        runIdentity: HistoricalRunIdentity,
        runStartDate: LocalDate,
    ): ResyncCheckpoint {
        val nextPhase =
            if (checkpoint.phase == ResyncPhase.RECONCILE || checkpoint.phase == ResyncPhase.RECOMPUTE) {
                ResyncPhase.RECONCILE
            } else {
                checkpoint.phase
            }
        return checkpoint.copy(
            phase = nextPhase,
            nextDate = if (nextPhase == ResyncPhase.RECONCILE) runStartDate else checkpoint.nextDate,
            runIdentity = runIdentity,
            selectionHash = runIdentity.scoringSnapshotId,
        )
    }

    private fun remapForRecomputeRestart(
        checkpoint: ResyncCheckpoint,
        runIdentity: HistoricalRunIdentity,
    ): ResyncCheckpoint =
        checkpoint.copy(
            nextDate = if (checkpoint.phase == ResyncPhase.RECOMPUTE) checkpoint.startDate else checkpoint.nextDate,
            runIdentity = runIdentity,
            selectionHash = runIdentity.scoringSnapshotId,
        )

    /**
     * WP-10 review fix: a device/source-selection change, including simultaneous HR-zone changes,
     * invalidates only the INGEST phase (and whatever naturally follows it: PRUNE, then RECONCILE,
     * then RECOMPUTE), rather than collapsing into the same full-restart-from-scratch path a
     * protocol-version or date-range mismatch takes. The run is rewound to [ResyncPhase.INGEST] at
     * `runStartDate` because ingestion filters records to the selected device *before persisting*
     * (`HealthIngestionCoordinator.fetchAndPersistBulkRecords`), so a newly selected device's
     * records were never persisted under the old selection and must be re-fetched from Health
     * Connect across the full range -- there is no per-type ingest cursor to resume mid-range from.
     *
     * Deliberately preserves, rather than resets, [ResyncCheckpoint.completedTypes] and
     * [ResyncCheckpoint.baselineChangeTokens] from the old checkpoint: unlike a full restart (fresh
     * baseline tokens captured, `completedTypes` reset to that fresh baseline), this keeps the run's
     * original Changes-API baseline anchored and lets the ingest loop's own
     * `runCompletedTypes.intersect(...)` bookkeeping re-derive accurate per-type completion as the
     * re-scan proceeds -- removing the affected type(s) here instead would incorrectly and
     * permanently exclude them forever, since that intersect can only shrink a type set, never
     * re-admit a removed one.
     *
     * KNOWN LIMITATION: because [HistoricalIngestPhase]/`HealthIngestionCoordinator.ingestWindow`
     * ingest every [HealthDataType] together per chunk (no `typesToIngest` filter exists), this
     * re-scan still re-fetches Health Connect data for *every* type across the full range, not only
     * [affectedSourceTypes] -- idempotent (upsert-keyed, immediately corrected by the following
     * [HistoricalPrunePhase]) but not scoped at the network-I/O level. Scoping the re-fetch itself
     * to just the affected type(s) would require threading a type filter through
     * `IngestWindowParams`/`HealthIngestionCoordinator` and reworking how `completedTypes` are
     * intersected per chunk (which currently assumes every chunk scans every type) -- a larger
     * restructure than this checkpoint-resolution fix.
     */
    private fun remapForSourceSelectionRestart(
        checkpoint: ResyncCheckpoint,
        runIdentity: HistoricalRunIdentity,
        runStartDate: LocalDate,
    ): ResyncCheckpoint =
        checkpoint.copy(
            phase = ResyncPhase.INGEST,
            nextDate = runStartDate,
            runIdentity = runIdentity,
            selectionHash = runIdentity.scoringSnapshotId,
            chunkDaysOverride = null,
            hrPageToken = null,
            hrvPageToken = null,
        )

    private fun hasHrZonesOrLinkPolicyChanged(
        old: ScoringRunSnapshot,
        new: ScoringRunSnapshot,
    ): Boolean =
        old.part1.manualZoneEditing != new.part1.manualZoneEditing ||
            old.part1.zone1MinPercent != new.part1.zone1MinPercent ||
            old.part2 != new.part2 ||
            old.part3.zone3MaxBpm != new.part3.zone3MaxBpm ||
            old.part3.zone4MaxBpm != new.part3.zone4MaxBpm ||
            old.part6.strainLoadSourceMode != new.part6.strainLoadSourceMode ||
            old.part6.rasSourceMode != new.part6.rasSourceMode

    /**
     * WP-10 review fix: the [HealthDataType]s whose device selection actually differs between
     * [old] and [new]'s [ScoringRunSnapshot.sourceSelection] maps. Keys that aren't a valid
     * [HealthDataType] name (namely `PRIMARY_DEVICE_KEY`) are dropped -- ingestion's per-type device
     * filter (`HealthIngestionCoordinator`'s private `deviceFor(type)`) reads only
     * `UserPreferences.deviceByDataType`, never the primary-device fallback, so a primary-device-only
     * change has no effect on what gets ingested/pruned and correctly yields an empty set here.
     */
    private fun affectedSourceTypes(
        old: ScoringRunSnapshot,
        new: ScoringRunSnapshot,
    ): Set<HealthDataType> {
        val oldSelection = old.sourceSelection
        val newSelection = new.sourceSelection
        val changedKeys = (oldSelection.keys + newSelection.keys).filter { oldSelection[it] != newSelection[it] }
        return changedKeys.mapNotNullTo(mutableSetOf()) { key ->
            runCatching { HealthDataType.valueOf(key) }.getOrNull()
        }
    }
}

internal fun UserPreferences.scoringCheckpointIdentity(): String =
    listOf(
        "goalSleepHours=$goalSleepHours",
        "hrvBaselineOverride=$hrvBaselineOverride",
        "rhrBaselineOverride=$rhrBaselineOverride",
        "maxHeartRate=$maxHeartRate",
        "autoCalculateMaxHr=$autoCalculateMaxHr",
        "zone1MinBpm=$zone1MinBpm",
        "zone1MaxBpm=$zone1MaxBpm",
        "zone2MaxBpm=$zone2MaxBpm",
        "zone3MaxBpm=$zone3MaxBpm",
        "zone4MaxBpm=$zone4MaxBpm",
        "age=$age",
        "gender=${gender?.name}",
        "hrvOptimalThreshold=$hrvOptimalThreshold",
        "rhrOptimalThreshold=$rhrOptimalThreshold",
        "restingHrPercentile=$restingHrPercentile",
        "consistencyThresholdMinutes=$consistencyThresholdMinutes",
        "consistencyEvaluationDays=$consistencyEvaluationDays",
        "consistencyBaselineDays=$consistencyBaselineDays",
        "rasScalingFactor=$rasScalingFactor",
        "physiologyProfile=${physiologyProfile.name}",
        "installDate=$installDate",
        "circadianThresholdOverride=$circadianThresholdOverride",
        "trimpModel=${trimpModel.name}",
        "banisterMultiplier=$banisterMultiplier",
        "chengBeta=$chengBeta",
        "itrimB=$itrimB",
        "scoringZone=${scoringZone().id}",
        "strainLoadSourceMode=${strainLoadSourceMode.name}",
        "rasSourceMode=${rasSourceMode.name}",
        "coreMergeGapMinutes=$coreMergeGapMinutes",
        "supplementalCutoffMinutesOfDay=$supplementalCutoffMinutesOfDay",
        "minimumCountedSleepSegmentMinutes=$minimumCountedSleepSegmentMinutes",
        "supplementalArchitectureCoveragePercent=$supplementalArchitectureCoveragePercent",
        "residualFatigueHalfLifeHours=$residualFatigueHalfLifeHours",
        "residualFatigueGain=$residualFatigueGain",
    ).joinToString("|")
