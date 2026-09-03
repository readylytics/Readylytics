package app.readylytics.health.core.database.data.mapper

import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.model.domain.model.ReadinessResult
import app.readylytics.health.core.model.domain.model.RecoveryFlag
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object DailySummaryMapper {
    fun toDomain(
        entity: DailySummaryEntity,
        zoneId: ZoneId,
    ): DailySummary {
        val date = resolveDate(entity, zoneId)
        val flags = resolveFlags(entity)

        return DailySummary(
            date = date,
            sleepScore = entity.sleepScore,
            nocturnalHrv = entity.nocturnalHrv,
            sleepDurationMinutes = entity.sleepDurationMinutes,
            deepSleepPercent = entity.deepSleepPercent,
            remSleepPercent = entity.remSleepPercent,
            hrvBaseline = entity.hrvBaseline,
            restingHeartRate = entity.restingHeartRate,
            restingHrRatio = entity.restingHrRatio,
            stepCount = entity.stepCount,
            zLnHrv = entity.zLnHrv,
            zRhr = entity.zRhr,
            recoveryFlags = flags,
            hrvSigma = entity.hrvSigma,
            readinessResult =
                ReadinessResult(
                    recoveryFlags = flags,
                    contributors = entity.contributors,
                    diagnostics = entity.diagnostics,
                ),
            sRest = entity.sRest,
            isCalibrating = entity.isCalibrating ?: false,
        ).withBodyMetrics(entity).withBaselines(entity).withLoadFields(entity)
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
            nocturnalHrv = domain.nocturnalHrv,
            sleepDurationMinutes = domain.sleepDurationMinutes,
            deepSleepPercent = domain.deepSleepPercent,
            remSleepPercent = domain.remSleepPercent,
            hrvBaseline = domain.hrvBaseline,
            restingHeartRate = domain.restingHeartRate,
            restingHrRatio = domain.restingHrRatio,
            stepCount = domain.stepCount,
            zLnHrv = domain.zLnHrv,
            zRhr = domain.zRhr,
            recoveryFlags =
                if (domain.readinessResult.recoveryFlags.isNotEmpty()) {
                    domain.readinessResult.recoveryFlags.joinToString(",") { it.name }
                } else {
                    null
                },
            hrvSigma = domain.hrvSigma,
            diagnosticsEmbedded = domain.readinessResult.diagnostics,
            contributorsEmbedded = domain.readinessResult.contributors,
            sRest = domain.sRest,
            isCalibrating = domain.isCalibrating,
        ).withBodyMetrics(domain).withBaselines(domain).withLoadFields(domain)
    }

    private fun resolveDate(
        entity: DailySummaryEntity,
        zoneId: ZoneId,
    ): LocalDate =
        Instant
            .ofEpochMilli(entity.dateMidnightMs)
            .atZone(zoneId)
            .toLocalDate()

    private fun resolveFlags(entity: DailySummaryEntity): Set<RecoveryFlag> =
        entity.recoveryFlags
            ?.split(',')
            ?.mapNotNull { token ->
                runCatching { RecoveryFlag.valueOf(token.trim()) }.getOrNull()
            }?.toSet()
            ?: emptySet()

    private fun DailySummary.withBodyMetrics(entity: DailySummaryEntity): DailySummary =
        copy(
            weightKg = entity.weightKg,
            bodyFatPercent = entity.bodyFatPercent,
            bloodPressureSystolic = entity.bloodPressureSystolic,
            bloodPressureDiastolic = entity.bloodPressureDiastolic,
            avgSleepingSpo2 = entity.avgSleepingSpo2,
            avgSleepingBodyTemp = entity.avgSleepingBodyTemp,
        )

    private fun DailySummary.withBaselines(entity: DailySummaryEntity): DailySummary =
        copy(
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
        )

    private fun DailySummary.withLoadFields(entity: DailySummaryEntity): DailySummary =
        copy(
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
            supplementalSleepDurationMinutes = entity.supplementalSleepDurationMinutes,
            napCount = entity.napCount,
            residualFatigue = entity.residualFatigue,
            acuteLoadRecovery = entity.acuteLoadRecovery,
            trainingLoadReadinessWorkoutOnly = entity.trainingLoadReadinessWorkoutOnly,
            trainingLoadReadinessEverydayHr = entity.trainingLoadReadinessEverydayHr,
            trainingReadinessWorkoutOnly = entity.trainingReadinessWorkoutOnly,
            trainingReadinessEverydayHr = entity.trainingReadinessEverydayHr,
            vo2Max = entity.vo2Max,
            vo2MaxSource = entity.vo2MaxSource,
        )

    private fun DailySummaryEntity.withBodyMetrics(domain: DailySummary): DailySummaryEntity =
        copy(
            weightKg = domain.weightKg,
            bodyFatPercent = domain.bodyFatPercent,
            bloodPressureSystolic = domain.bloodPressureSystolic,
            bloodPressureDiastolic = domain.bloodPressureDiastolic,
            avgSleepingSpo2 = domain.avgSleepingSpo2,
            avgSleepingBodyTemp = domain.avgSleepingBodyTemp,
        )

    private fun DailySummaryEntity.withBaselines(domain: DailySummary): DailySummaryEntity =
        copy(
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
        )

    private fun DailySummaryEntity.withLoadFields(domain: DailySummary): DailySummaryEntity =
        copy(
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
            supplementalSleepDurationMinutes = domain.supplementalSleepDurationMinutes,
            napCount = domain.napCount,
            residualFatigue = domain.residualFatigue,
            acuteLoadRecovery = domain.acuteLoadRecovery,
            trainingLoadReadinessWorkoutOnly = domain.trainingLoadReadinessWorkoutOnly,
            trainingLoadReadinessEverydayHr = domain.trainingLoadReadinessEverydayHr,
            trainingReadinessWorkoutOnly = domain.trainingReadinessWorkoutOnly,
            trainingReadinessEverydayHr = domain.trainingReadinessEverydayHr,
            vo2Max = domain.vo2Max,
            vo2MaxSource = domain.vo2MaxSource,
        )
}
