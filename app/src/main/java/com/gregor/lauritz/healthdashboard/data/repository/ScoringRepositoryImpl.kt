package com.gregor.lauritz.healthdashboard.data.repository

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessResult
import com.gregor.lauritz.healthdashboard.domain.model.RecordType
import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.repository.ScoringRepository
import com.gregor.lauritz.healthdashboard.domain.scoring.BaselineComputer
import com.gregor.lauritz.healthdashboard.domain.scoring.ComputeSleepMetricsUseCase
import com.gregor.lauritz.healthdashboard.domain.scoring.PaiCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringCalculator
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConfigFactory
import com.gregor.lauritz.healthdashboard.domain.scoring.ScoringConstants
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import com.gregor.lauritz.healthdashboard.domain.util.logD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.round
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoringRepositoryImpl
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val sleepSessionDao: SleepSessionDao,
        private val dailySummaryDao: DailySummaryDao,
        private val settingsRepo: SettingsRepository,
        private val scoringCalculator: ScoringCalculator,
        private val baselineComputer: BaselineComputer,
        private val computeSleepMetricsUseCase: ComputeSleepMetricsUseCase,
        private val scoringConfigFactory: ScoringConfigFactory,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
    ) : ScoringRepository {
        override suspend fun computeAndPersistDailySummary(targetDate: LocalDate) {
            val summary = computeDailySummary(targetDate)
            dailySummaryDao.upsert(summary)
        }

        override suspend fun computeDailySummary(targetDate: LocalDate): DailySummaryEntity =
            withContext(Dispatchers.Default) {
                val zoneId = ZoneId.systemDefault()
                val dayMidnight = targetDate.atStartOfDay(zoneId).toInstant()
                val nextDayMidnight = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()
                val dayMidnightMs = dayMidnight.toEpochMilli()
                val nextDayMidnightMs = nextDayMidnight.toEpochMilli()

                val prefs = settingsRepo.userPreferences.first()
                val hrMax = if (prefs.autoCalculateMaxHr) {
                    HeartRateFormulas.estimateMaxHr(prefs.age).toFloat()
                } else {
                    prefs.maxHeartRate.toFloat()
                }

                val rhrBaselineValue = baselineComputer.computeAdaptiveBaselineRhrBpm(
                    dayMidnight = dayMidnight,
                    rhrBaselineOverride = prefs.rhrBaselineOverride,
                )

                if (hrMax <= 0f) throw IllegalStateException("HR Max is missing or invalid")
                if (rhrBaselineValue <= 0f) throw IllegalStateException("RHR Baseline is missing or invalid")

                val installDateFromPrefs = LocalDate.ofEpochDay(prefs.installDate / 86400000)
                val scoringConfig = scoringConfigFactory.build(
                    userPreferences = prefs,
                    installDate = installDateFromPrefs,
                    currentDate = targetDate
                )

                logD("ScoringRepository") { "PAI CALC START [$targetDate]" }

                val workouts = workoutDao.getWorkoutsInRange(dayMidnightMs, nextDayMidnightMs)
                var dailyTrimpRaw = 0f
                val updatedWorkouts = mutableListOf<com.gregor.lauritz.healthdashboard.data.local.entity.WorkoutRecordEntity>()

                workouts.forEach { workout ->
                    val workoutHrSamples = heartRateDao.getByTimeRange(workout.startTime, workout.endTime)
                        .filter { it.recordType == RecordType.EXERCISE.name }
                        .sortedBy { it.timestampMs }

                    if (workoutHrSamples.isNotEmpty()) {
                        var workoutTrimp = 0f
                        workoutHrSamples.forEachIndexed { index, sample ->
                            val nextMs = if (index < workoutHrSamples.lastIndex) {
                                workoutHrSamples[index + 1].timestampMs
                            } else {
                                workout.endTime
                            }
                            val durationMinutes = (nextMs - sample.timestampMs) / 60_000f

                            if (durationMinutes > 0f) {
                                workoutTrimp += PaiCalculator.calculateDailyTrimp(
                                    durationMinutes = durationMinutes,
                                    hrAvg = sample.beatsPerMinute.toFloat(),
                                    rhrBaseline = rhrBaselineValue,
                                    hrMax = hrMax,
                                    gender = prefs.gender,
                                    trimpModel = scoringConfig.trimpModel,
                                    banisterMultiplier = scoringConfig.banisterMultiplier,
                                    chengBeta = scoringConfig.chengBeta,
                                    itrimB = scoringConfig.itrimB,
                                )
                            }
                        }
                        dailyTrimpRaw += workoutTrimp
                        updatedWorkouts.add(workout.copy(trimp = workoutTrimp))
                    } else {
                        dailyTrimpRaw += workout.trimp
                    }
                }

                if (updatedWorkouts.isNotEmpty()) {
                    workoutDao.upsertAll(updatedWorkouts)
                }

                // Enforce 75-point daily cap. Standard PAI is pure load, no readiness penalty.
                // Round daily PAI to 1 decimal place to ensure display consistency.
                val dailyPaiRaw = PaiCalculator.calculateDailyPai(dailyTrimpRaw, scoringConfig.paiScalingFactor)
                val dailyPai = round(dailyPaiRaw * 10f) / 10f

                val last6DaysPai = sumPaiScoreLastSixDays(targetDate)
                // Total PAI is rounded to nearest integer to match the UI's simple sum of rounded daily values.
                val totalPai7d = round(dailyPai + last6DaysPai)

                logD("ScoringRepository") { "Result - DailyTrimp: $dailyTrimpRaw, DailyPai: $dailyPai, Last6d: $last6DaysPai, Total7d: $totalPai7d" }

                var summary = (dailySummaryDao.getByDate(dayMidnightMs) ?: DailySummaryEntity(dateMidnightMs = dayMidnightMs))
                    .copy(paiScore = dailyPai, totalPai = totalPai7d)

                val calibrationFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val isCalibrated = sleepSessionDao.countSince(calibrationFrom) >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION

                if (!isCalibrated) {
                    val session = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
                    if (session != null) {
                        val hrvValues = hrvDao.getSleepRmssdForSession(session.id)
                        val avgHrv = if (hrvValues.isNotEmpty()) {
                            (hrvValues.sum() / hrvValues.size).toInt()
                        } else null
                        val avgRhr = heartRateDao.getAvgSleepHr(session.id)
                        summary = summary.copy(
                            nocturnalHrv = avgHrv,
                            nocturnalRhr = avgRhr,
                            sleepDurationMinutes = session.durationMinutes,
                            deepSleepPercent = if (session.durationMinutes > 0)
                                session.deepSleepMinutes / session.durationMinutes.toFloat() * 100f else null,
                            remSleepPercent = if (session.durationMinutes > 0)
                                session.remSleepMinutes / session.durationMinutes.toFloat() * 100f else null,
                            isCalibrating = true,
                        )
                    } else {
                        summary = summary.copy(isCalibrating = true)
                    }

                    val updatedAudit = scoringConfig.auditTrail.copy(
                        appliedSf = scoringConfig.paiScalingFactor,
                        physiologyProfile = prefs.physiologyProfile.name,
                        paiTotalPre = last6DaysPai,
                        paiTotalPost = summary.totalPai
                    )
                    logD("ScoringConfig") { "Telemetry: $updatedAudit" }

                    return@withContext summary
                }

                val tzOffsetMs = zoneId.rules.getOffset(dayMidnight).totalSeconds * 1000L
                val ctlFetchFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS - 1, ChronoUnit.DAYS).toEpochMilli()
                val epochDayToTrimp = workoutDao.getDailyTrmpByEpochDay(ctlFetchFrom, nextDayMidnightMs, tzOffsetMs)
                val dailyTrimpByDate = epochDayToTrimp.mapKeys { (epochDay, _) -> LocalDate.ofEpochDay(epochDay) }

                val ctl = scoringCalculator.computeCtlEmaWithDecay(dailyTrimpByDate, targetDate)
                val atl = scoringCalculator.computeAtlEmaWithDecay(dailyTrimpByDate, targetDate)

                val sr = scoringCalculator.computeStrainRatio(atl, ctl)
                val loadScore = scoringCalculator.computeLoadScore(sr)

                summary = summary.copy(loadScore = loadScore, strainRatio = sr, totalTrimp = dailyTrimpRaw)

                val computedHrvBaseline = baselineComputer.computeHrvBaseline(
                    dayMidnight = dayMidnight,
                    hrvBaselineOverride = prefs.hrvBaselineOverride,
                )
                summary = summary.copy(hrvBaseline = computedHrvBaseline)

                val session = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
                if (session != null) {
                    summary = computeSleepMetricsUseCase(session, dayMidnight, targetDate, prefs, summary, loadScore, zoneId)
                }

                // Final summary remains consistent with the pre-calculated dailyPai and totalPai7d.
                // Readiness adjustment is excluded from PAI storage to match standard PAI models and manual sums.

                val updatedAudit = scoringConfig.auditTrail.copy(
                    appliedSf = scoringConfig.paiScalingFactor,
                    physiologyProfile = prefs.physiologyProfile.name,
                    paiTotalPre = last6DaysPai,
                    paiTotalPost = summary.totalPai
                )
                logD("ScoringConfig") { "Telemetry: $updatedAudit" }

                summary
            }

        override fun toReadinessResult(summary: DailySummaryEntity): ReadinessResult {
            val flags: Set<RecoveryFlag> = summary.recoveryFlags
                ?.split(',')
                ?.mapNotNull { token ->
                    runCatching { RecoveryFlag.valueOf(token.trim()) }.getOrNull()
                }
                ?.toSet()
                ?: emptySet()
            return ReadinessResult(
                readinessScore = summary.readinessScore,
                sleepScore     = summary.sleepScore,
                loadScore      = summary.loadScore,
                sRest          = summary.sRest,
                recoveryFlags  = flags,
                contributors   = summary.contributors,
                diagnostics    = summary.diagnostics,
            )
        }

        private suspend fun sumPaiScoreLastSixDays(targetDate: LocalDate): Float {
            val zoneIdSystem = ZoneId.systemDefault()
            val previousDays = (1..6).map { i ->
                targetDate.minusDays(i.toLong()).atStartOfDay(zoneIdSystem).toInstant().toEpochMilli()
                    .let { dailySummaryDao.getByDate(it)?.paiScore ?: 0f }
            }
            return previousDays.sum()
        }
    }
