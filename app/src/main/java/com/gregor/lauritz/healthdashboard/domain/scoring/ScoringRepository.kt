package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.domain.model.ReadinessResult
import com.gregor.lauritz.healthdashboard.domain.model.RecoveryFlag
import com.gregor.lauritz.healthdashboard.domain.util.HeartRateFormulas
import com.gregor.lauritz.healthdashboard.domain.util.logD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScoringRepository
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val sleepSessionDao: SleepSessionDao,
        private val dailySummaryDao: DailySummaryDao,
        private val prefsRepo: UserPreferencesRepository,
        private val scoringCalculator: ScoringCalculator,
        private val baselineComputer: BaselineComputer,
        private val computeSleepMetricsUseCase: ComputeSleepMetricsUseCase,
    ) {
        suspend fun computeAndPersistDailySummary(targetDate: LocalDate = LocalDate.now()) {
            val summary = computeDailySummary(targetDate)
            dailySummaryDao.upsert(summary)
        }

        suspend fun computeDailySummary(targetDate: LocalDate = LocalDate.now()): DailySummaryEntity =
            withContext(Dispatchers.Default) {
                val zoneId = ZoneId.systemDefault()
                val dayMidnight = targetDate.atStartOfDay(zoneId).toInstant()
                val nextDayMidnight = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()
                val dayMidnightMs = dayMidnight.toEpochMilli()
                val nextDayMidnightMs = nextDayMidnight.toEpochMilli()

                val prefs = prefsRepo.userPreferences.first()
                val hrMax = if (prefs.autoCalculateMaxHr) HeartRateFormulas.estimateMaxHr(prefs.age).toFloat() else prefs.maxHeartRate.toFloat()

                val rhrBaselineValues = baselineComputer.rhrHistory(dayMidnight)
                val rhrBaselineValue = baselineComputer.resolveBaselineRhrBpm(
                    rhrValues = rhrBaselineValues,
                    rhrBaselineOverride = prefs.rhrBaselineOverride,
                )

                logD("ScoringRepository") { "PAI CALC START [$targetDate]" }

                // Calculate Daily PAI points from workouts (no sleep calibration needed)
                val totalDuration = workoutDao.getTotalDurationMinutes(dayMidnightMs, nextDayMidnightMs) ?: 0
                val weightedAvgHr = workoutDao.getWeightedAvgHr(dayMidnightMs, nextDayMidnightMs) ?: 0f

                logD("ScoringRepository") { "Workout Data - Duration: $totalDuration, WeightedAvgHr: $weightedAvgHr, RHR Baseline: $rhrBaselineValue, HR Max: $hrMax" }

                val dailyTrimpRaw = PaiCalculator.calculateDailyTrimp(
                    durationMinutes = totalDuration.toFloat(),
                    hrAvg = weightedAvgHr,
                    rhrBaseline = rhrBaselineValue,
                    hrMax = hrMax,
                    gender = prefs.gender
                )
                val dailyPaiRaw = PaiCalculator.calculateDailyPai(dailyTrimpRaw, prefs.paiScalingFactor)

                logD("ScoringRepository") { "Result - DailyTrimpRaw: $dailyTrimpRaw, DailyPaiRaw: $dailyPaiRaw" }

                var summary = (dailySummaryDao.getByDate(dayMidnightMs) ?: DailySummaryEntity(dateMidnightMs = dayMidnightMs))
                    .copy(paiScore = dailyPaiRaw)

                // Sleep calibration check — guard strain/sleep/readiness metrics only
                val calibrationFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val isCalibrated = sleepSessionDao.countSince(calibrationFrom) >= ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION

                if (!isCalibrated) {
                    // Persist PAI-only partial summary and skip sleep/strain scoring
                    val totalPaiSoFar = fetchPreviousDaysPaiTotal(targetDate)
                    val finalDailyPai = PaiCalculator.applyAccumulationMultiplier(dailyPaiRaw, totalPaiSoFar)
                    summary = summary.copy(paiScore = finalDailyPai, totalPai = totalPaiSoFar + finalDailyPai)
                    logD("ScoringRepository") { "PAI FINAL (uncalibrated) - FinalDaily: $finalDailyPai, Total: ${totalPaiSoFar + finalDailyPai}" }
                    return@withContext summary
                }

                val tzOffsetMs = zoneId.rules.getOffset(dayMidnight).totalSeconds * 1000L
                // ACWR uncoupling: CTL uses days 8–42, ATL uses days 1–7 (non-overlapping)
                // Fetch days 8-42 for CTL (skip last 7 days)
                val ctlFetchFrom = dayMidnight.minus(ScoringConstants.CHRONIC_DAYS - 1, ChronoUnit.DAYS)
                    .plus(ScoringConstants.ACUTE_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val dailyTrimpList = workoutDao.getDailyTrimp(ctlFetchFrom, nextDayMidnightMs, tzOffsetMs)

                val ctl = scoringCalculator.computeCtlEma(dailyTrimpList = dailyTrimpList)
                val acuteFrom = dayMidnight.minus(ScoringConstants.ACUTE_DAYS - 1, ChronoUnit.DAYS).toEpochMilli()
                val acuteTotal = workoutDao.getTotalTrimp(acuteFrom, nextDayMidnightMs) ?: 0f
                // Seed ATL = CTL during cold-start so SR initialises at 1.0 — REF: A.15 review
                val atl = if (dailyTrimpList.size < ScoringConstants.MIN_SESSIONS_FOR_CALIBRATION) ctl
                          else acuteTotal / ScoringConstants.ACUTE_DAYS.toFloat()

                val sr = scoringCalculator.computeStrainRatio(atl, ctl)
                val loadScore = scoringCalculator.computeLoadScore(sr)
                val todayTrimp = workoutDao.getTotalTrimp(dayMidnightMs, nextDayMidnightMs) ?: 0f

                summary = summary.copy(loadScore = loadScore, strainRatio = sr, totalTrimp = todayTrimp)

                // Always compute and persist HRV baseline regardless of sleep session availability
                // Only include samples from valid nights to avoid baseline pollution — REF: A.5 review
                val computedHrvBaseline = baselineComputer.computeHrvBaseline(
                    dayMidnight = dayMidnight,
                    hrvBaselineOverride = prefs.hrvBaselineOverride,
                )
                summary = summary.copy(hrvBaseline = computedHrvBaseline)

                val session = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
                if (session != null) {
                    summary = computeSleepMetricsUseCase(session, dayMidnight, targetDate, prefs, summary, loadScore, zoneId)
                }

                // After readiness is calculated, adjust PAI and calculate rolling sum
                val adjustedDailyPai = PaiCalculator.adjustForReadiness(summary.paiScore ?: 0f, summary.readinessScore)

                val totalPaiSoFar = fetchPreviousDaysPaiTotal(targetDate)
                val finalDailyPai = PaiCalculator.applyAccumulationMultiplier(adjustedDailyPai, totalPaiSoFar)

                summary = summary.copy(
                    paiScore = finalDailyPai,
                    totalPai = totalPaiSoFar + finalDailyPai
                )

                logD("ScoringRepository") { "PAI FINAL - Adjusted: $adjustedDailyPai, PrevTotal: $totalPaiSoFar, FinalDaily: $finalDailyPai, Total: ${totalPaiSoFar + finalDailyPai}" }

                summary
            }

        /**
         * Reconstruct a [ReadinessResult] from a persisted [DailySummaryEntity] for
         * downstream consumers (UI, debug overlay, exports).
         * REF: plan_scoring.md §3
         */
        fun toReadinessResult(summary: DailySummaryEntity): ReadinessResult {
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

        private suspend fun fetchPreviousDaysPaiTotal(targetDate: LocalDate): Float {
            val zoneIdSystem = ZoneId.systemDefault()
            val previousDays = (1..6).map { i ->
                targetDate.minusDays(i.toLong()).atStartOfDay(zoneIdSystem).toInstant().toEpochMilli()
                    .let { dailySummaryDao.getByDate(it)?.paiScore ?: 0f }
            }
            return previousDays.sum()
        }

    }

