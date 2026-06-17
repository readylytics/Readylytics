package app.readylytics.health.domain.model

import app.readylytics.health.data.local.entity.DailySummaryEntity
import java.time.Instant
import java.time.ZoneId

object DailySummaryMapper {
    fun toDomain(
        entity: DailySummaryEntity,
        zoneId: ZoneId,
    ): DailySummary {
        val date =
            Instant
                .ofEpochMilli(entity.dateMidnightMs)
                .atZone(zoneId)
                .toLocalDate()

        val flags: Set<RecoveryFlag> =
            entity.recoveryFlags
                ?.split(',')
                ?.mapNotNull { token ->
                    runCatching { RecoveryFlag.valueOf(token.trim()) }.getOrNull()
                }?.toSet()
                ?: emptySet()

        return DailySummary(
            date = date,
            sleepScore = entity.sleepScore,
            loadScore = entity.loadScore,
            readinessScore = entity.readinessScore,
            strainRatio = entity.strainRatio,
            nocturnalHrv = entity.nocturnalHrv,
            sleepDurationMinutes = entity.sleepDurationMinutes,
            deepSleepPercent = entity.deepSleepPercent,
            remSleepPercent = entity.remSleepPercent,
            totalTrimp = entity.totalTrimp,
            hrvBaseline = entity.hrvBaseline,
            restingHeartRate = entity.restingHeartRate,
            restingHrRatio = entity.restingHrRatio,
            legacyRasScore = entity.legacyRasScore,
            legacyTotalRas = entity.legacyTotalRas,
            stepCount = entity.stepCount,
            zLnHrv = entity.zLnHrv,
            zRhr = entity.zRhr,
            recoveryFlags = flags,
            hrvSigma = entity.hrvSigma,
            readinessResult =
                ReadinessResult(
                    readinessScore = entity.readinessScore,
                    sleepScore = entity.sleepScore,
                    loadScore = entity.loadScore,
                    sRest = entity.sRest,
                    recoveryFlags = flags,
                    contributors = entity.contributors,
                    diagnostics = entity.diagnostics,
                ),
            sRest = entity.sRest,
            isCalibrating = entity.isCalibrating ?: false,
            weightKg = entity.weightKg,
            bodyFatPercent = entity.bodyFatPercent,
            bloodPressureSystolic = entity.bloodPressureSystolic,
            bloodPressureDiastolic = entity.bloodPressureDiastolic,
            avgSleepingSpo2 = entity.avgSleepingSpo2,
            hrvMuMssd = entity.hrvMuMssd,
            hrvSigmaMssd = entity.hrvSigmaMssd,
            rhrBpm = entity.rhrBpm,
            rhrSigma = entity.rhrSigma,
            baselineCalculatedAtDate = entity.baselineCalculatedAtDate,
            hrMax = entity.hrMax,
            snapshotProfile = entity.snapshotProfile,
            snapshotCalibrationPhase = entity.snapshotCalibrationPhase,
            hrvSigmaPrior = entity.hrvSigmaPrior,
            rasScalingFactor = entity.rasScalingFactor,
            baselineObservationCount = entity.baselineObservationCount,
            trimpWorkoutOnly = entity.trimpWorkoutOnly,
            trimpEverydayHr = entity.trimpEverydayHr,
            rasWorkoutOnly = entity.rasWorkoutOnly,
            rasEverydayHr = entity.rasEverydayHr,
            totalRasWorkoutOnly = entity.totalRasWorkoutOnly,
            totalRasEverydayHr = entity.totalRasEverydayHr,
            atlWorkoutOnly = entity.atlWorkoutOnly,
            atlEverydayHr = entity.atlEverydayHr,
            ctlWorkoutOnly = entity.ctlWorkoutOnly,
            ctlEverydayHr = entity.ctlEverydayHr,
            strainRatioWorkoutOnly = entity.strainRatioWorkoutOnly,
            strainRatioEverydayHr = entity.strainRatioEverydayHr,
            loadScoreWorkoutOnly = entity.loadScoreWorkoutOnly,
            loadScoreEverydayHr = entity.loadScoreEverydayHr,
            readinessWorkoutOnly = entity.readinessWorkoutOnly,
            readinessEverydayHr = entity.readinessEverydayHr,
            everydayCoverageMinutes = entity.everydayCoverageMinutes,
            everydayLoadConfidence = entity.everydayLoadConfidence,
        )
    }

    fun toEntity(
        domain: DailySummary,
        zoneId: ZoneId,
    ): DailySummaryEntity {
        val midnightMs =
            domain.date
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()

        return DailySummaryEntity(
            dateMidnightMs = midnightMs,
            sleepScore = domain.sleepScore,
            loadScore = domain.loadScore,
            readinessScore = domain.readinessScore,
            strainRatio = domain.strainRatio,
            nocturnalHrv = domain.nocturnalHrv,
            sleepDurationMinutes = domain.sleepDurationMinutes,
            deepSleepPercent = domain.deepSleepPercent,
            remSleepPercent = domain.remSleepPercent,
            totalTrimp = domain.totalTrimp,
            hrvBaseline = domain.hrvBaseline,
            restingHeartRate = domain.restingHeartRate,
            restingHrRatio = domain.restingHrRatio,
            legacyRasScore = domain.legacyRasScore,
            legacyTotalRas = domain.legacyTotalRas,
            stepCount = domain.stepCount,
            zLnHrv = domain.zLnHrv,
            zRhr = domain.zRhr,
            recoveryFlags =
                if (domain.recoveryFlags.isNotEmpty()) {
                    domain.recoveryFlags.joinToString(",") { it.name }
                } else {
                    null
                },
            hrvSigma = domain.hrvSigma,
            diagnostics = domain.readinessResult.diagnostics,
            contributors = domain.readinessResult.contributors,
            sRest = domain.sRest,
            weightKg = domain.weightKg,
            bodyFatPercent = domain.bodyFatPercent,
            bloodPressureSystolic = domain.bloodPressureSystolic,
            bloodPressureDiastolic = domain.bloodPressureDiastolic,
            avgSleepingSpo2 = domain.avgSleepingSpo2,
            hrvMuMssd = domain.hrvMuMssd,
            hrvSigmaMssd = domain.hrvSigmaMssd,
            rhrBpm = domain.rhrBpm,
            rhrSigma = domain.rhrSigma,
            baselineCalculatedAtDate = domain.baselineCalculatedAtDate,
            hrMax = domain.hrMax,
            snapshotProfile = domain.snapshotProfile,
            snapshotCalibrationPhase = domain.snapshotCalibrationPhase,
            hrvSigmaPrior = domain.hrvSigmaPrior,
            rasScalingFactor = domain.rasScalingFactor,
            baselineObservationCount = domain.baselineObservationCount,
            trimpWorkoutOnly = domain.trimpWorkoutOnly,
            trimpEverydayHr = domain.trimpEverydayHr,
            rasWorkoutOnly = domain.rasWorkoutOnly,
            rasEverydayHr = domain.rasEverydayHr,
            totalRasWorkoutOnly = domain.totalRasWorkoutOnly,
            totalRasEverydayHr = domain.totalRasEverydayHr,
            atlWorkoutOnly = domain.atlWorkoutOnly,
            atlEverydayHr = domain.atlEverydayHr,
            ctlWorkoutOnly = domain.ctlWorkoutOnly,
            ctlEverydayHr = domain.ctlEverydayHr,
            strainRatioWorkoutOnly = domain.strainRatioWorkoutOnly,
            strainRatioEverydayHr = domain.strainRatioEverydayHr,
            loadScoreWorkoutOnly = domain.loadScoreWorkoutOnly,
            loadScoreEverydayHr = domain.loadScoreEverydayHr,
            readinessWorkoutOnly = domain.readinessWorkoutOnly,
            readinessEverydayHr = domain.readinessEverydayHr,
            everydayCoverageMinutes = domain.everydayCoverageMinutes,
            everydayLoadConfidence = domain.everydayLoadConfidence,
        )
    }
}
