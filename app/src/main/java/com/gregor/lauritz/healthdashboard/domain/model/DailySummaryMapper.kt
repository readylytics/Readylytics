package com.gregor.lauritz.healthdashboard.domain.model

import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
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
            nocturnalRhr = entity.nocturnalRhr,
            nocturnalHrv = entity.nocturnalHrv,
            sleepDurationMinutes = entity.sleepDurationMinutes,
            deepSleepPercent = entity.deepSleepPercent,
            remSleepPercent = entity.remSleepPercent,
            totalTrimp = entity.totalTrimp,
            rhrRatio = entity.rhrRatio,
            hrvBaseline = entity.hrvBaseline,
            restingHeartRate = entity.restingHeartRate,
            restingHrRatio = entity.restingHrRatio,
            restingHrBaseline = entity.restingHrBaseline,
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
            nocturnalRhr = domain.nocturnalRhr,
            nocturnalHrv = domain.nocturnalHrv,
            sleepDurationMinutes = domain.sleepDurationMinutes,
            deepSleepPercent = domain.deepSleepPercent,
            remSleepPercent = domain.remSleepPercent,
            totalTrimp = domain.totalTrimp,
            rhrRatio = domain.rhrRatio,
            hrvBaseline = domain.hrvBaseline,
            restingHeartRate = domain.restingHeartRate,
            restingHrRatio = domain.restingHrRatio,
            restingHrBaseline = domain.restingHrBaseline,
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
        )
    }
}
