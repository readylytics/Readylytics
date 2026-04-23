package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MIN_SESSIONS_FOR_CALIBRATION = 7
private const val ACUTE_DAYS = 7L
private const val CHRONIC_DAYS = 30L
private const val CTL_FETCH_DAYS = 30L
private const val BASELINE_DAYS = 30L
private const val DEFAULT_FITNESS_LEVEL = 35f

@Singleton
class ScoringRepository
    @Inject
    constructor(
        private val workoutDao: WorkoutDao,
        private val sleepSessionDao: SleepSessionDao,
        private val heartRateDao: HeartRateDao,
        private val hrvDao: HrvDao,
        private val dailySummaryDao: DailySummaryDao,
        private val prefsRepo: UserPreferencesRepository,
    ) {
        suspend fun computeAndPersistDailySummary(targetDate: LocalDate = LocalDate.now()) {
            val zoneId = ZoneId.systemDefault()
            val dayMidnight = targetDate.atStartOfDay(zoneId).toInstant()
            val nextDayMidnight = targetDate.plusDays(1).atStartOfDay(zoneId).toInstant()
            val dayMidnightMs = dayMidnight.toEpochMilli()
            val nextDayMidnightMs = nextDayMidnight.toEpochMilli()

            val calibrationFrom = dayMidnight.minus(CHRONIC_DAYS, ChronoUnit.DAYS).toEpochMilli()
            if (sleepSessionDao.countSince(calibrationFrom) < MIN_SESSIONS_FOR_CALIBRATION) return

            val prefs = prefsRepo.userPreferences.first()

            val ctlFetchFrom = dayMidnight.minus(CTL_FETCH_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val earliestWorkout = workoutDao.getEarliestWorkoutTimestamp()
            val dataTenureDays =
                if (earliestWorkout != null) {
                    ChronoUnit
                        .DAYS
                        .between(
                            java.time.Instant
                                .ofEpochMilli(earliestWorkout)
                                .atZone(zoneId)
                                .toLocalDate(),
                            targetDate,
                        ).toInt()
                        .coerceAtLeast(1)
                } else {
                    0
                }

            val ctlTotal = workoutDao.getTotalTrimp(ctlFetchFrom, nextDayMidnightMs) ?: 0f
            val ctl = computeCtl(ctlTotal, CTL_FETCH_DAYS, dataTenureDays)

            val acuteFrom = dayMidnight.minus(ACUTE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val acuteTotal = workoutDao.getTotalTrimp(acuteFrom, nextDayMidnightMs) ?: 0f
            val atl = acuteTotal / ACUTE_DAYS.toFloat()

            val sr = computeStrainRatio(atl, ctl)
            val loadScore = computeLoadScore(sr)

            val todayTrimp = workoutDao.getTotalTrimp(dayMidnightMs, nextDayMidnightMs) ?: 0f

            val session = sleepSessionDao.getSessionEndingInRange(dayMidnightMs, nextDayMidnightMs)
            var sleepScore: Float? = null
            var readinessScore: Float? = null
            var nocturnalRhr: Int? = null
            var nocturnalHrv: Int? = null
            var deepSleepPercent: Float? = null
            var remSleepPercent: Float? = null
            var rhrRatio: Float? = null
            var hrvBaseline: Int? = null
            var restingHeartRate: Int? = null
            var restingHrRatio: Float? = null
            var restingHrBaseline: Int? = null

            if (session != null) {
                val baselineFrom = dayMidnight.minus(BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val hrvValues = hrvDao.getSleepRmssdValues(baselineFrom)
                val rhrValues = heartRateDao.getAvgSleepHrPerSession(baselineFrom)
                val sessionHrvSamples = hrvDao.getSleepRmssdForSession(session.id)
                val currentHrvMean =
                    if (sessionHrvSamples.isNotEmpty()) sessionHrvSamples.average().toFloat() else 0f
                val currentNocturnalRhr = heartRateDao.getAvgSleepHr(session.id)

                nocturnalRhr = currentNocturnalRhr
                nocturnalHrv = if (sessionHrvSamples.isNotEmpty()) currentHrvMean.roundToInt() else null

                if (session.durationMinutes > 0) {
                    deepSleepPercent = session.deepSleepMinutes / session.durationMinutes.toFloat() * 100f
                    remSleepPercent = session.remSleepMinutes / session.durationMinutes.toFloat() * 100f
                }

                // Median is used as the displayed user-facing baseline; mean is used in Z-score (μ_hrv).
                hrvBaseline = (prefs.hrvBaselineOverride ?: median(hrvValues)).roundToInt()

                val baselineRhr = (prefs.rhrBaselineOverride ?: medianInt(rhrValues)).roundToInt()

                val minHrStartTime = session.endTime - prefs.restingHrBeforeMinutes * 60 * 1000L
                val minHrEndTime = session.endTime + prefs.restingHrAfterMinutes * 60 * 1000L
                val currentRestingHr = heartRateDao.getMinHrInRange(minHrStartTime, minHrEndTime)
                restingHeartRate = currentRestingHr

                // Calculate resting HR baseline specifically from wake-up windows of previous sessions
                val sessions = sleepSessionDao.getSince(baselineFrom)
                val historicRestingHrs = mutableListOf<Int>()
                for (s in sessions) {
                    if (s.id == session.id) continue
                    val start = s.endTime - prefs.restingHrBeforeMinutes * 60 * 1000L
                    val end = s.endTime + prefs.restingHrAfterMinutes * 60 * 1000L
                    heartRateDao.getMinHrInRange(start, end)?.let { historicRestingHrs.add(it) }
                }
                restingHrBaseline = if (historicRestingHrs.isNotEmpty()) medianInt(historicRestingHrs).roundToInt() else null

                if (currentRestingHr != null && restingHrBaseline != null && restingHrBaseline > 0) {
                    restingHrRatio = currentRestingHr.toFloat() / restingHrBaseline
                }

                if (currentNocturnalRhr != null) {
                    rhrRatio = currentNocturnalRhr.toFloat() / (baselineRhr + 0.001f)

                    val minHrTimestamp = heartRateDao.getMinHrTimestamp(session.id)
                    val isLateNadir =
                        if (minHrTimestamp != null && session.durationMinutes > 0) {
                            val sessionDurationMs = session.durationMinutes * 60 * 1000L
                            val relativeNadirMs = minHrTimestamp - session.startTime
                            // Late = last 15% of session
                            relativeNadirMs > (sessionDurationMs * 0.85f)
                        } else {
                            false
                        }

                    var sRest =
                        computeRestorationSubScore(
                            currentHrvMean = currentHrvMean,
                            hrvValues = hrvValues,
                            currentNocturnalRhr = currentNocturnalRhr.toFloat(),
                            rhrValues = rhrValues,
                            rhrBaselineOverride = prefs.rhrBaselineOverride,
                            hrvBaselineOverride = prefs.hrvBaselineOverride,
                        )

                    if (isLateNadir) {
                        sRest *= 0.9f
                    }

                    sleepScore =
                        computeSleepScore(
                            durationMinutes = session.durationMinutes,
                            efficiency = session.efficiency,
                            deepSleepMinutes = session.deepSleepMinutes,
                            remSleepMinutes = session.remSleepMinutes,
                            goalSleepHours = prefs.goalSleepHours,
                            sRest = sRest,
                        )

                    val zHrv =
                        if (sessionHrvSamples.isNotEmpty() && hrvValues.isNotEmpty()) {
                            val mu = prefs.hrvBaselineOverride ?: mean(hrvValues)
                            (currentHrvMean - mu) / hrvSigma(hrvValues)
                        } else {
                            null
                        }

                    readinessScore =
                        computeReadinessScore(
                            sRest = sRest,
                            sleepScore = sleepScore,
                            loadScore = loadScore,
                            zHrv = zHrv,
                            rhrRatio = rhrRatio,
                        )
                }
            }

            val existing = dailySummaryDao.getByDate(dayMidnightMs)
            val updated =
                (existing ?: DailySummaryEntity(dateMidnightMs = dayMidnightMs)).copy(
                    sleepScore = sleepScore,
                    loadScore = loadScore,
                    readinessScore = readinessScore,
                    strainRatio = sr,
                    nocturnalRhr = nocturnalRhr,
                    nocturnalHrv = nocturnalHrv,
                    sleepDurationMinutes = session?.durationMinutes,
                    deepSleepPercent = deepSleepPercent,
                    remSleepPercent = remSleepPercent,
                    totalTrimp = todayTrimp,
                    rhrRatio = rhrRatio,
                    hrvBaseline = hrvBaseline,
                    restingHeartRate = restingHeartRate,
                    restingHrRatio = restingHrRatio,
                    restingHrBaseline = restingHrBaseline,
                )
            dailySummaryDao.upsert(updated)
        }
    }

// ---------------------------------------------------------------------------
// Pure-math scoring functions — package-level so they can be unit-tested
// without instantiating the repository or its dependencies.
// ---------------------------------------------------------------------------

internal fun computeStrainRatio(
    atl: Float,
    ctl: Float,
): Float = if (ctl > 0f) atl / ctl else 0f

// CTL = per-calendar-day average over the chronic window, seeded from a fitness level
// when not enough workout history exists. Both ATL and CTL are in the same unit
// (TRIMP per calendar day) so their ratio (SR) reaches ≈1.0 at steady training load.
internal fun computeCtl(
    totalTrimp: Float,
    windowDays: Long,
    dataTenureDays: Int,
    seedFitnessLevel: Float = DEFAULT_FITNESS_LEVEL,
): Float =
    when {
        dataTenureDays < 7 -> seedFitnessLevel
        dataTenureDays < 21 -> totalTrimp / dataTenureDays.toFloat()
        else -> totalTrimp / windowDays.toFloat()
    }

internal fun computeLoadScore(sr: Float): Float =
    when {
        sr <= 0f -> 85f // Optimal (maintenance/low load)
        sr < 0.8f -> 50f // Neutral (Under-training)
        sr <= 1.2f -> 100f // Optimal (Sweet spot)
        sr <= 1.5f -> 100f - (sr - 1.2f) * 100f // 1.2 -> 100, 1.5 -> 70 (Warning starts at 60 in M3ScoreDial, let's aim for 70)
        else -> 30f // Poor
    }

internal fun computeDurationSubScore(
    durationMinutes: Int,
    efficiency: Float,
    goalSleepHours: Float,
): Float {
    val ratio = (durationMinutes / 60f / goalSleepHours).coerceIn(0f, 1f)
    return ratio * 100f * (efficiency / 100f)
}

internal fun computeArchSubScore(
    deepSleepMinutes: Int,
    remSleepMinutes: Int,
    durationMinutes: Int,
): Float {
    if (durationMinutes == 0) return 0f
    val deepPct = deepSleepMinutes / durationMinutes.toFloat() * 100f
    val remPct = remSleepMinutes / durationMinutes.toFloat() * 100f
    val deepComponent = (deepPct / 20f).coerceAtMost(1f) * 100f
    val remComponent = (remPct / 20f).coerceAtMost(1f) * 100f
    return 0.5f * deepComponent + 0.5f * remComponent
}

internal fun computeRestorationSubScore(
    currentHrvMean: Float,
    hrvValues: List<Float>,
    currentNocturnalRhr: Float,
    rhrValues: List<Int>,
    rhrBaselineOverride: Float?,
    hrvBaselineOverride: Float?,
): Float {
    // μ_hrv is the rolling mean (not median) per the Z-score formula definition.
    val muHrv = hrvBaselineOverride ?: mean(hrvValues)
    val sigmaHrv = hrvSigma(hrvValues)
    val zHrv = (currentHrvMean - muHrv) / sigmaHrv
    val hrvScore = (50f + 25f * zHrv).coerceIn(0f, 100f)

    // RHR score: min(100, baseline / night × 100) per spec.
    val baselineRhr = rhrBaselineOverride ?: medianInt(rhrValues)
    val rhrScore = (baselineRhr / (currentNocturnalRhr + 0.001f) * 100f).coerceIn(0f, 100f)

    return 0.5f * hrvScore + 0.5f * rhrScore
}

internal fun computeSleepScore(
    durationMinutes: Int,
    efficiency: Float,
    deepSleepMinutes: Int,
    remSleepMinutes: Int,
    goalSleepHours: Float,
    sRest: Float,
): Float {
    val sDur = computeDurationSubScore(durationMinutes, efficiency, goalSleepHours)
    val sArch = computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes)
    return 0.50f * sDur + 0.25f * sArch + 0.25f * sRest
}

internal fun computeReadinessScore(
    sRest: Float,
    sleepScore: Float,
    loadScore: Float,
    zHrv: Float? = null,
    rhrRatio: Float? = null,
): Float {
    var rs = 0.4f * sRest + 0.3f * sleepScore + 0.3f * loadScore
    // Paradoxical High: elevated HRV Z-score alongside raised RHR signals illness, not peak readiness.
    if (zHrv != null && rhrRatio != null && zHrv > 2.0f && rhrRatio > 1.05f) {
        rs = rs.coerceAtMost(60f)
    }
    return rs.coerceIn(0f, 100f)
}

internal fun mean(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    return values.average().toFloat()
}

internal fun median(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
}

internal fun medianInt(values: List<Int>): Float {
    if (values.isEmpty()) return 0f
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid].toFloat()
}

internal fun stdev(values: List<Float>): Float {
    if (values.size < 2) return 0f
    val mean = values.average().toFloat()
    val variance = values.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / values.size
    return sqrt(variance)
}

// Phase-aware HRV sigma: provisional phase (<21 samples) uses a fixed 15% CV rule to
// avoid noisy stdev estimates; mature phase uses empirical stdev.
internal fun hrvSigma(hrvValues: List<Float>): Float =
    if (hrvValues.size < 21) {
        maxOf(mean(hrvValues) * 0.15f, 1e-6f)
    } else {
        stdev(hrvValues).takeIf { it > 0f } ?: maxOf(mean(hrvValues) * 0.15f, 1e-6f)
    }
