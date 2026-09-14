package app.readylytics.health.core.healthconnect.domain.sync

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
    ): HistoricalRunIdentity =
        if (isCompatible(existing, requested)) existing!! else requested

    private fun isCompatible(existing: HistoricalRunIdentity?, requested: HistoricalRunIdentity): Boolean =
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

    private fun canPreservePhases(oldRun: HistoricalRunIdentity, newRun: HistoricalRunIdentity): Boolean =
        oldRun.protocolVersion == newRun.protocolVersion &&
            oldRun.mode == newRun.mode &&
            oldRun.startEpochDay == newRun.startEpochDay &&
            oldRun.endEpochDayInclusive == newRun.endEpochDayInclusive &&
            oldRun.zoneId == newRun.zoneId &&
            oldRun.sourceSelectionId == newRun.sourceSelectionId

    private fun remapCheckpointForNewSettings(
        checkpoint: ResyncCheckpoint,
        runIdentity: HistoricalRunIdentity,
        runStartDate: LocalDate,
    ): ResyncCheckpoint {
        val oldSnapshot = checkpoint.runIdentity?.decodeScoringSnapshot()
        val newSnapshot = runIdentity.decodeScoringSnapshot()
        val hrZonesChanged =
            oldSnapshot == null || newSnapshot == null || hasHrZonesOrLinkPolicyChanged(oldSnapshot, newSnapshot)

        return if (hrZonesChanged) {
            val nextPhase =
                if (checkpoint.phase == ResyncPhase.RECONCILE || checkpoint.phase == ResyncPhase.RECOMPUTE) {
                    ResyncPhase.RECONCILE
                } else {
                    checkpoint.phase
                }
            checkpoint.copy(
                phase = nextPhase,
                nextDate = if (nextPhase == ResyncPhase.RECONCILE) runStartDate else checkpoint.nextDate,
                runIdentity = runIdentity,
                selectionHash = runIdentity.scoringSnapshotId,
            )
        } else {
            checkpoint.copy(
                nextDate = if (checkpoint.phase == ResyncPhase.RECOMPUTE) runStartDate else checkpoint.nextDate,
                runIdentity = runIdentity,
                selectionHash = runIdentity.scoringSnapshotId,
            )
        }
    }

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
