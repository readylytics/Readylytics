package app.readylytics.health.domain.model

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.scoring.ScoringConstants
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DailyMetricsMapperTest {
    private val date = LocalDate.of(2026, 6, 2)
    private val prefs = UserPreferences()

    // --- H1: rounding (roundToInt half toward +∞) ---

    @Test
    fun `rhr baseline 60_7 rounds to 61`() {
        val summary = DailySummary(date = date, rhrBpm = 60.7f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(61, metrics.rhrBaselineRounded)
    }

    @Test
    fun `rhr baseline 72_4 rounds to 72`() {
        val summary = DailySummary(date = date, rhrBpm = 72.4f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(72, metrics.rhrBaselineRounded)
    }

    @Test
    fun `pai 74_6 rounds to 75 (not truncated)`() {
        // Default paiSourceMode is EVERYDAY_HEART_RATE.
        val summary = DailySummary(date = date, totalPaiEverydayHr = 74.6f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(75, metrics.paiRounded)
    }

    @Test
    fun `spo2 96_5 rounds to 97`() {
        val summary = DailySummary(date = date, avgSleepingSpo2 = 96.5f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(97, metrics.spo2Rounded)
    }

    @Test
    fun `readiness is rounded to integer`() {
        // Default strainLoadSourceMode is WORKOUT_ONLY.
        val summary = DailySummary(date = date, readinessWorkoutOnly = 81.5f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(82, metrics.readinessRounded)
    }

    @Test
    fun `strain ratio display uses canonical two decimal formatting`() {
        // Default strainLoadSourceMode is WORKOUT_ONLY.
        val summary = DailySummary(date = date, strainRatioWorkoutOnly = 0.365f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals("0.37", metrics.strainRatioDisplay)
    }

    // --- H4: frozen baseline columns pass through verbatim (no recompute) ---

    @Test
    fun `frozen baseline snapshot columns pass through unchanged`() {
        val summary =
            DailySummary(
                date = date,
                hrvMuMssd = 42.1234f,
                hrvSigmaMssd = 3.5678f,
                rhrBpm = 58.9f,
            )
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(42.1234f, metrics.hrvBaselineMeanRaw)
        assertEquals(3.5678f, metrics.hrvBaselineSdRaw)
        assertEquals(58.9f, metrics.rhrSnapshotRaw)
    }

    // --- H5: cross-call determinism ---

    @Test
    fun `mapping the same summary twice yields equal objects`() {
        val summary =
            DailySummary(
                date = date,
                restingHeartRate = 50,
                nocturnalHrv = 65,
                rhrBpm = 52.6f,
                totalPai = 74.6f,
                avgSleepingSpo2 = 96.5f,
                weightKg = 80.25f,
            )
        val first = DailyMetricsMapper.toMetrics(summary, prefs)
        val second = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(first, second)
    }

    // --- rhrBaseline derivation + fallback chain ---

    @Test
    fun `rhr baseline reads directly from rhrBpm snapshot`() {
        val summary = DailySummary(date = date, rhrBpm = 50f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(50f, metrics.rhrBaselineRaw)
        assertEquals(50, metrics.rhrBaselineRounded)
    }

    @Test
    fun `rhr baseline falls back to prefs override when rhrBpm null`() {
        val overridePrefs = UserPreferences(rhrBaselineOverride = 55f)
        val summary = DailySummary(date = date, rhrBpm = null)
        val metrics = DailyMetricsMapper.toMetrics(summary, overridePrefs)
        assertEquals(55f, metrics.rhrBaselineRaw)
    }

    @Test
    fun `rhr baseline falls back to prefs override when no rhrBpm`() {
        val overridePrefs = UserPreferences(rhrBaselineOverride = 55f)
        val summary = DailySummary(date = date)
        val metrics = DailyMetricsMapper.toMetrics(summary, overridePrefs)
        assertEquals(55f, metrics.rhrBaselineRaw)
    }

    @Test
    fun `rhr baseline falls back to DEFAULT_RHR_BPM when nothing else available`() {
        val noOverridePrefs = UserPreferences(rhrBaselineOverride = null)
        val summary = DailySummary(date = date)
        val metrics = DailyMetricsMapper.toMetrics(summary, noOverridePrefs)
        assertEquals(ScoringConstants.DEFAULT_RHR_BPM, metrics.rhrBaselineRaw)
    }

    // --- null safety + display formatting ---

    @Test
    fun `null metric values produce null rounded and display fields`() {
        val summary = DailySummary(date = date)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertNull(metrics.sleepScoreRounded)
        assertNull(metrics.spo2Rounded)
        assertNull(metrics.weightKgDisplay)
        assertNull(metrics.bodyFatDisplay)
        assertNull(metrics.bloodPressureDisplay)
        assertNull(metrics.sleepDurationDisplay)
        assertNull(metrics.deepSleepPercentDisplay)
        assertNull(metrics.remSleepPercentDisplay)
    }

    @Test
    fun `deep and rem sleep percent display round to integer percent`() {
        val summary = DailySummary(date = date, deepSleepPercent = 18.6f, remSleepPercent = 22.4f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals("19%", metrics.deepSleepPercentDisplay)
        assertEquals("22%", metrics.remSleepPercentDisplay)
    }

    @Test
    fun `weight display emits both kg and lbs`() {
        val summary = DailySummary(date = date, weightKg = 80.0f)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals("80.0", metrics.weightKgDisplay)
        // 80 kg * 2.20462 = 176.37 -> "176.4"
        assertEquals("176.4", metrics.weightLbsDisplay)
    }

    @Test
    fun `blood pressure display formats systolic over diastolic`() {
        val summary = DailySummary(date = date, bloodPressureSystolic = 118, bloodPressureDiastolic = 76)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals("118/76", metrics.bloodPressureDisplay)
    }

    @Test
    fun `baseline arrow and diff reflect measured value relative to baseline`() {
        val above = DailySummary(date = date, restingHeartRate = 60, rhrBpm = 55f)
        val metrics = DailyMetricsMapper.toMetrics(above, prefs)
        assertEquals(BaselineArrow.UP, metrics.rhrBaselineArrow)
        assertEquals(5, metrics.rhrBaselineDiff)
    }

    // --- Phase 5: HRV baseline display alignment (geometric exp(mu), not arithmetic median) ---

    @Test
    fun `hrv baseline rounded uses geometric mean exp(mu), not the arithmetic median`() {
        // ln(40) ~= 3.6889 -> exp(mu) ~= 40, while the arithmetic median is a different value (45)
        val mu = ln(40.0).toFloat()
        val summary = DailySummary(date = date, hrvMuMssd = mu, hrvBaseline = 45)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(exp(mu.toDouble()).roundToInt(), metrics.hrvBaselineRounded)
        assertEquals(40, metrics.hrvBaselineRounded)
    }

    @Test
    fun `hrv baseline rounded falls back to prefs override when hrvMuMssd is null`() {
        val overridePrefs = UserPreferences(hrvBaselineOverride = 35f)
        val summary = DailySummary(date = date, hrvMuMssd = null, hrvBaseline = 45)
        val metrics = DailyMetricsMapper.toMetrics(summary, overridePrefs)
        assertEquals(35, metrics.hrvBaselineRounded)
    }

    @Test
    fun `hrv baseline rounded falls back to arithmetic baseline when no mu and no override`() {
        val summary = DailySummary(date = date, hrvMuMssd = null, hrvBaseline = 45)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertEquals(45, metrics.hrvBaselineRounded)
    }

    @Test
    fun `hrv baseline rounded is null when no mu, override, or arithmetic baseline available`() {
        val summary = DailySummary(date = date, hrvMuMssd = null, hrvBaseline = null)
        val metrics = DailyMetricsMapper.toMetrics(summary, prefs)
        assertNull(metrics.hrvBaselineRounded)
    }
}
