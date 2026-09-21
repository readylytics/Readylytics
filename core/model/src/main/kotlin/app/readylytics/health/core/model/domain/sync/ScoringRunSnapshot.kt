package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.model.domain.preferences.PhysiologyProfile
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.scoringZone
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import kotlinx.serialization.Serializable

const val PRIMARY_DEVICE_KEY = "__PRIMARY_DEVICE__"

@Serializable
data class ScoringRunSnapshot(
    val part1: ScoringSnapshotPart1,
    val part2: ScoringSnapshotPart2,
    val part3: ScoringSnapshotPart3,
    val part4: ScoringSnapshotPart4,
    val part5: ScoringSnapshotPart5,
    val part6: ScoringSnapshotPart6,
    val part7: ScoringSnapshotPart7,
    val part8: ScoringSnapshotPart8,
    val resolvedHrMax: Float,
    val sourceSelection: Map<String, String>,
) {
    fun toPreferences(): UserPreferences {
        val primaryDevice = sourceSelection[PRIMARY_DEVICE_KEY]
        val deviceByDataType =
            sourceSelection
                .filterKeys { it != PRIMARY_DEVICE_KEY }
                .toSortedMap()

        val base = UserPreferences(primaryDeviceName = primaryDevice, deviceByDataType = deviceByDataType)
        val withParts1To4 = applyPart1To4(base)
        return applyPart5To8(withParts1To4)
    }

    private fun applyPart1To4(base: UserPreferences): UserPreferences =
        base.copy(
            goalSleepHours = part1.goalSleepHours,
            hrvBaselineOverride = part1.hrvBaselineOverride,
            rhrBaselineOverride = part1.rhrBaselineOverride,
            maxHeartRate = part1.maxHeartRate,
            autoCalculateMaxHr = part1.autoCalculateMaxHr,
            manualZoneEditing = part1.manualZoneEditing,
            zone1MinPercent = part1.zone1MinPercent,
            zone1MaxPercent = part2.zone1MaxPercent,
            zone2MaxPercent = part2.zone2MaxPercent,
            zone3MaxPercent = part2.zone3MaxPercent,
            zone4MaxPercent = part2.zone4MaxPercent,
            zone1MinBpm = part2.zone1MinBpm,
            zone1MaxBpm = part2.zone1MaxBpm,
            zone2MaxBpm = part2.zone2MaxBpm,
            zone3MaxBpm = part3.zone3MaxBpm,
            zone4MaxBpm = part3.zone4MaxBpm,
            age = part3.age,
            birthDate = part3.birthDate,
            gender = part3.gender?.let { Gender.valueOf(it) },
            heightCm = part3.heightCm,
            hrvOptimalThreshold = part3.hrvOptimalThreshold,
            hrvWarningThreshold = part4.hrvWarningThreshold,
            rhrOptimalThreshold = part4.rhrOptimalThreshold,
            rhrWarningThreshold = part4.rhrWarningThreshold,
            restingHrPercentile = part4.restingHrPercentile,
            consistencyThresholdMinutes = part4.consistencyThresholdMinutes,
            consistencyEvaluationDays = part4.consistencyEvaluationDays,
            consistencyBaselineDays = part4.consistencyBaselineDays,
        )

    private fun applyPart5To8(base: UserPreferences): UserPreferences =
        base.copy(
            hrrToleranceSeconds = part5.hrrToleranceSeconds,
            rasScalingFactor = part5.rasScalingFactor,
            stepGoal = part5.stepGoal,
            physiologyProfile = PhysiologyProfile.valueOf(part5.physiologyProfile),
            installDate = part5.installDate,
            circadianThresholdOverride = part5.circadianThresholdOverride,
            trimpModel = TrimpModel.valueOf(part5.trimpModel),
            banisterMultiplier = part6.banisterMultiplier,
            chengBeta = part6.chengBeta,
            itrimB = part6.itrimB,
            scoringZoneId = part6.scoringZoneId,
            strainLoadSourceMode = LoadSourceMode.valueOf(part6.strainLoadSourceMode),
            rasSourceMode = LoadSourceMode.valueOf(part6.rasSourceMode),
            coreMergeGapMinutes = part6.coreMergeGapMinutes,
            supplementalCutoffMinutesOfDay = part7.supplementalCutoffMinutesOfDay,
            minimumCountedSleepSegmentMinutes = part7.minimumCountedSleepSegmentMinutes,
            supplementalArchitectureCoveragePercent = part7.supplementalArchitectureCoveragePercent,
            bodyTempElevatedThresholdCelsius = part7.bodyTempElevatedThresholdCelsius,
            sleepScoreWeightProfile = SleepScoreWeightProfile.valueOf(part7.sleepScoreWeightProfile),
            hypersomniaOnsetPercent = part7.hypersomniaOnsetPercent,
            residualFatigueHalfLifeHours = part7.residualFatigueHalfLifeHours,
            residualFatigueGain = part8.residualFatigueGain,
            trainingReadinessResidualFatigueScale = part8.trainingReadinessResidualFatigueScale,
            trainingReadinessLoadBalanceWeight = part8.trainingReadinessLoadBalanceWeight,
            lastAppliedTrainingReadinessResidualFatigueScale =
                part8.lastAppliedTrainingReadinessResidualFatigueScale,
            lastAppliedTrainingReadinessLoadBalanceWeight =
                part8.lastAppliedTrainingReadinessLoadBalanceWeight,
            vo2MaxSourceMode = Vo2MaxSourceMode.valueOf(part8.vo2MaxSourceMode),
            vo2MaxEstimationMethod = Vo2MaxEstimationMethod.valueOf(part8.vo2MaxEstimationMethod),
        )

    fun toPreferencesOrNull(): UserPreferences? = runCatching { toPreferences() }.getOrNull()

    companion object {
        fun capture(
            prefs: UserPreferences,
            resolvedHrMax: Float,
        ): ScoringRunSnapshot =
            ScoringRunSnapshot(
                part1 = capturePart1(prefs),
                part2 = capturePart2(prefs),
                part3 = capturePart3(prefs),
                part4 = capturePart4(prefs),
                part5 = capturePart5(prefs),
                part6 = capturePart6(prefs),
                part7 = capturePart7(prefs),
                part8 = capturePart8(prefs),
                resolvedHrMax = resolvedHrMax,
                sourceSelection = captureSourceSelection(prefs),
            )

        private fun captureSourceSelection(prefs: UserPreferences): Map<String, String> =
            buildMap<String, String> {
                prefs.deviceByDataType.toSortedMap().forEach { (k, v) -> put(k, v) }
                prefs.primaryDeviceName?.let { put(PRIMARY_DEVICE_KEY, it) }
            }.toSortedMap()

        private fun capturePart1(prefs: UserPreferences) = ScoringSnapshotPart1(
            goalSleepHours = prefs.goalSleepHours,
            hrvBaselineOverride = prefs.hrvBaselineOverride,
            rhrBaselineOverride = prefs.rhrBaselineOverride,
            maxHeartRate = prefs.maxHeartRate,
            autoCalculateMaxHr = prefs.autoCalculateMaxHr,
            manualZoneEditing = prefs.manualZoneEditing,
            zone1MinPercent = prefs.zone1MinPercent,
        )

        private fun capturePart2(prefs: UserPreferences) = ScoringSnapshotPart2(
            zone1MaxPercent = prefs.zone1MaxPercent,
            zone2MaxPercent = prefs.zone2MaxPercent,
            zone3MaxPercent = prefs.zone3MaxPercent,
            zone4MaxPercent = prefs.zone4MaxPercent,
            zone1MinBpm = prefs.zone1MinBpm,
            zone1MaxBpm = prefs.zone1MaxBpm,
            zone2MaxBpm = prefs.zone2MaxBpm,
        )

        private fun capturePart3(prefs: UserPreferences) = ScoringSnapshotPart3(
            zone3MaxBpm = prefs.zone3MaxBpm,
            zone4MaxBpm = prefs.zone4MaxBpm,
            age = prefs.age,
            birthDate = prefs.birthDate,
            gender = prefs.gender?.name,
            heightCm = prefs.heightCm,
            hrvOptimalThreshold = prefs.hrvOptimalThreshold,
        )

        private fun capturePart4(prefs: UserPreferences) = ScoringSnapshotPart4(
            hrvWarningThreshold = prefs.hrvWarningThreshold,
            rhrOptimalThreshold = prefs.rhrOptimalThreshold,
            rhrWarningThreshold = prefs.rhrWarningThreshold,
            restingHrPercentile = prefs.restingHrPercentile,
            consistencyThresholdMinutes = prefs.consistencyThresholdMinutes,
            consistencyEvaluationDays = prefs.consistencyEvaluationDays,
            consistencyBaselineDays = prefs.consistencyBaselineDays,
        )

        private fun capturePart5(prefs: UserPreferences) = ScoringSnapshotPart5(
            hrrToleranceSeconds = prefs.hrrToleranceSeconds,
            rasScalingFactor = prefs.rasScalingFactor,
            stepGoal = prefs.stepGoal,
            physiologyProfile = prefs.physiologyProfile.name,
            installDate = prefs.installDate,
            circadianThresholdOverride = prefs.circadianThresholdOverride,
            trimpModel = prefs.trimpModel.name,
        )

        private fun capturePart6(prefs: UserPreferences) = ScoringSnapshotPart6(
            banisterMultiplier = prefs.banisterMultiplier,
            chengBeta = prefs.chengBeta,
            itrimB = prefs.itrimB,
            scoringZoneId = prefs.scoringZone().id,
            strainLoadSourceMode = prefs.strainLoadSourceMode.name,
            rasSourceMode = prefs.rasSourceMode.name,
            coreMergeGapMinutes = prefs.coreMergeGapMinutes,
        )

        private fun capturePart7(prefs: UserPreferences) = ScoringSnapshotPart7(
            supplementalCutoffMinutesOfDay = prefs.supplementalCutoffMinutesOfDay,
            minimumCountedSleepSegmentMinutes = prefs.minimumCountedSleepSegmentMinutes,
            supplementalArchitectureCoveragePercent = prefs.supplementalArchitectureCoveragePercent,
            bodyTempElevatedThresholdCelsius = prefs.bodyTempElevatedThresholdCelsius,
            sleepScoreWeightProfile = prefs.sleepScoreWeightProfile.name,
            hypersomniaOnsetPercent = prefs.hypersomniaOnsetPercent,
            residualFatigueHalfLifeHours = prefs.residualFatigueHalfLifeHours,
        )

        private fun capturePart8(prefs: UserPreferences) = ScoringSnapshotPart8(
            residualFatigueGain = prefs.residualFatigueGain,
            trainingReadinessResidualFatigueScale = prefs.trainingReadinessResidualFatigueScale,
            trainingReadinessLoadBalanceWeight = prefs.trainingReadinessLoadBalanceWeight,
            lastAppliedTrainingReadinessResidualFatigueScale =
                prefs.lastAppliedTrainingReadinessResidualFatigueScale,
            lastAppliedTrainingReadinessLoadBalanceWeight =
                prefs.lastAppliedTrainingReadinessLoadBalanceWeight,
            vo2MaxSourceMode = prefs.vo2MaxSourceMode.name,
            vo2MaxEstimationMethod = prefs.vo2MaxEstimationMethod.name,
        )
    }
}
