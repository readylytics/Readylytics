package app.readylytics.health.domain.model

import app.readylytics.health.data.local.entity.DailySummaryEntity
import java.time.Instant
import java.time.ZoneId

object DailySummaryMapper {
    fun toDomain(entity: DailySummaryEntity): DailySummary {
        val date =
            Instant
                .ofEpochMilli(entity.dateMidnightMs)
                .atZone(ZoneId.systemDefault())
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
            paiScore = entity.paiScore,
            totalPai = entity.totalPai,
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
            paiScalingFactor = entity.paiScalingFactor,
            baselineObservationCount = entity.baselineObservationCount,
            dailyHrTrimp = entity.dailyHrTrimp,
            dailyHrPai = entity.dailyHrPai,
            dailyHrAtl = entity.dailyHrAtl,
            dailyHrCtl = entity.dailyHrCtl,
            dailyHrLoadScore = entity.dailyHrLoadScore,
            dailyHrReadinessScore = entity.dailyHrReadinessScore,
        )
    }

    fun toEntity(domain: DailySummary): DailySummaryEntity {
        val midnightMs =
            domain.date
                .atStartOfDay(ZoneId.systemDefault())
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
            paiScore = domain.paiScore,
            totalPai = domain.totalPai,
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
            paiScalingFactor = domain.paiScalingFactor,
            baselineObservationCount = domain.baselineObservationCount,
            dailyHrTrimp = domain.dailyHrTrimp,
            dailyHrPai = domain.dailyHrPai,
            dailyHrAtl = domain.dailyHrAtl,
            dailyHrCtl = domain.dailyHrCtl,
            dailyHrLoadScore = domain.dailyHrLoadScore,
            dailyHrReadinessScore = domain.dailyHrReadinessScore,
        )
    }
}
