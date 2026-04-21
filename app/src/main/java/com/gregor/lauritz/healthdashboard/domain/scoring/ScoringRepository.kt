package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.local.dao.DailySummaryDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HeartRateDao
import com.gregor.lauritz.healthdashboard.data.local.dao.HrvDao
import com.gregor.lauritz.healthdashboard.data.local.dao.SleepSessionDao
import com.gregor.lauritz.healthdashboard.data.local.dao.WorkoutDao
import com.gregor.lauritz.healthdashboard.data.local.entity.DailySummaryEntity
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

private const val MIN_SESSIONS_FOR_CALIBRATION = 7
private const val ACUTE_DAYS = 7L
private const val CHRONIC_DAYS = 42L
private const val BASELINE_DAYS = 30L

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
        suspend fun computeAndPersistDailySummary() {
            val now = Instant.now()
            val todayMidnight =
                now
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
            val todayMidnightMs = todayMidnight.toEpochMilli()
            val nowMs = now.toEpochMilli()

            val calibrationFrom = todayMidnight.minus(CHRONIC_DAYS, ChronoUnit.DAYS).toEpochMilli()
            if (sleepSessionDao.countSince(calibrationFrom) < MIN_SESSIONS_FOR_CALIBRATION) return

            val prefs = prefsRepo.userPreferences.first()

            val acuteFrom = todayMidnight.minus(ACUTE_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val chronicFrom = todayMidnight.minus(CHRONIC_DAYS, ChronoUnit.DAYS).toEpochMilli()
            val acuteSum = workoutDao.getTotalTrimp(acuteFrom, nowMs) ?: 0f
            val chronicSum = workoutDao.getTotalTrimp(chronicFrom, nowMs) ?: 0f
            val sr = computeStrainRatio(acuteSum, chronicSum)
            val loadScore = computeLoadScore(sr)

            val todayTrimp = workoutDao.getTotalTrimp(todayMidnightMs, nowMs) ?: 0f

            val session = sleepSessionDao.getLatest()
            var sleepScore: Float? = null
            var nocturnalRhr: Float? = null
            var nocturnalHrv: Float? = null
            var deepSleepPercent: Float? = null
            var remSleepPercent: Float? = null
            var rhrRatio: Float? = null
            var hrvZScore: Float? = null

            if (session != null) {
                val baselineFrom = todayMidnight.minus(BASELINE_DAYS, ChronoUnit.DAYS).toEpochMilli()
                val hrvValues = hrvDao.getSleepRmssdValues(baselineFrom)
                val rhrValues = heartRateDao.getMinSleepHrPerSession(baselineFrom)
                val sessionHrvSamples = hrvDao.getSleepRmssdForSession(session.id)
                val currentHrvMean =
                    if (sessionHrvSamples.isNotEmpty()) sessionHrvSamples.average().toFloat() else 0f
                val currentNocturnalRhr = heartRateDao.getMinSleepHr(session.id)?.toFloat()

                nocturnalRhr = currentNocturnalRhr
                nocturnalHrv = currentHrvMean.takeIf { sessionHrvSamples.isNotEmpty() }

                if (session.durationMinutes > 0) {
                    deepSleepPercent = session.deepSleepMinutes / session.durationMinutes.toFloat() * 100f
                    remSleepPercent = session.remSleepMinutes / session.durationMinutes.toFloat() * 100f
                }

                if (currentNocturnalRhr != null) {
                    val baselineRhr = prefs.rhrBaselineOverride ?: medianInt(rhrValues)
                    rhrRatio = currentNocturnalRhr / (baselineRhr + 0.001f)
                    sleepScore =
                        computeSleepScore(
                            durationMinutes = session.durationMinutes,
                            efficiency = session.efficiency,
                            deepSleepMinutes = session.deepSleepMinutes,
                            remSleepMinutes = session.remSleepMinutes,
                            goalSleepHours = prefs.goalSleepHours,
                            currentHrvMean = currentHrvMean,
                            hrvValues = hrvValues,
                            currentNocturnalRhr = currentNocturnalRhr,
                            rhrValues = rhrValues,
                            rhrBaselineOverride = prefs.rhrBaselineOverride,
                            hrvBaselineOverride = prefs.hrvBaselineOverride,
                        )
                }

                if (sessionHrvSamples.isNotEmpty()) {
                    val baselineHrv = prefs.hrvBaselineOverride ?: median(hrvValues)
                    val stdHrv = if (prefs.hrvBaselineOverride != null) 1f else stdev(hrvValues)
                    hrvZScore = (currentHrvMean - baselineHrv) / (stdHrv + 0.001f)
                }
            }

            val existing = dailySummaryDao.getByDate(todayMidnightMs)
            val updated =
                (existing ?: DailySummaryEntity(dateMidnightMs = todayMidnightMs)).copy(
                    sleepScore = sleepScore,
                    loadScore = loadScore,
                    strainRatio = sr,
                    nocturnalRhr = nocturnalRhr,
                    nocturnalHrv = nocturnalHrv,
                    sleepDurationMinutes = session?.durationMinutes,
                    deepSleepPercent = deepSleepPercent,
                    remSleepPercent = remSleepPercent,
                    totalTrimp = todayTrimp,
                    rhrRatio = rhrRatio,
                    hrvZScore = hrvZScore,
                )
            dailySummaryDao.upsert(updated)
        }
    }

// ---------------------------------------------------------------------------
// Pure-math scoring functions — package-level so they can be unit-tested
// without instantiating the repository or its dependencies.
// ---------------------------------------------------------------------------

internal fun computeStrainRatio(
    acuteSum: Float,
    chronicSum: Float,
): Float {
    val acuteDailyAvg = acuteSum / ACUTE_DAYS
    val chronicDailyAvg = chronicSum / CHRONIC_DAYS
    return if (chronicDailyAvg > 0f) acuteDailyAvg / chronicDailyAvg else 0f
}

internal fun computeLoadScore(sr: Float): Float =
    when {
        sr <= 0f -> 80f
        sr < 0.8f -> 80f
        sr <= 1.2f -> 100f
        sr <= 1.5f -> 100f - (sr - 1.2f) * 200f
        else -> 40f
    }

internal fun computeDurationSubScore(
    durationMinutes: Int,
    efficiency: Float,
    goalSleepHours: Float,
): Float {
    val ratio = (durationMinutes / 60f / goalSleepHours).coerceIn(0f, 1f)
    return 0.7f * ratio * 100f + 0.3f * efficiency
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
    val baselineHrv = hrvBaselineOverride ?: median(hrvValues)
    val stdHrv = if (hrvBaselineOverride != null) 1f else stdev(hrvValues)
    val zHrv = (currentHrvMean - baselineHrv) / (stdHrv + 0.001f)
    val hrvScore = ((zHrv + 2f) / 4f * 100f).coerceIn(0f, 100f)

    val baselineRhr = rhrBaselineOverride ?: medianInt(rhrValues)
    val ratio = currentNocturnalRhr / (baselineRhr + 0.001f)
    val rhrScore = ((1.1f - ratio) / 0.1f * 100f).coerceIn(0f, 100f)

    return 0.5f * hrvScore + 0.5f * rhrScore
}

internal fun computeSleepScore(
    durationMinutes: Int,
    efficiency: Float,
    deepSleepMinutes: Int,
    remSleepMinutes: Int,
    goalSleepHours: Float,
    currentHrvMean: Float,
    hrvValues: List<Float>,
    currentNocturnalRhr: Float,
    rhrValues: List<Int>,
    rhrBaselineOverride: Float?,
    hrvBaselineOverride: Float?,
): Float {
    val sDur = computeDurationSubScore(durationMinutes, efficiency, goalSleepHours)
    val sArch = computeArchSubScore(deepSleepMinutes, remSleepMinutes, durationMinutes)
    val sRest =
        computeRestorationSubScore(
            currentHrvMean,
            hrvValues,
            currentNocturnalRhr,
            rhrValues,
            rhrBaselineOverride,
            hrvBaselineOverride,
        )
    return 0.50f * sDur + 0.25f * sArch + 0.25f * sRest
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
