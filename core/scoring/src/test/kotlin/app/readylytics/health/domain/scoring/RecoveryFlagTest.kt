package app.readylytics.health.domain.scoring

import app.readylytics.health.domain.model.RecoveryFlag
import app.readylytics.health.domain.scoring.strategies.LoadScoringStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// computeRecoveryFlags: 2-night consecutive confirmation for strong recovery and ILLNESS_ONSET.
// Single-night anomalies are noise; both nights must show the same pattern before a flag fires.
// REF: Le Meur 2013 Med Sci Sports Exerc; Mishra 2020 Nat Biomed Eng
class RecoveryFlagTest {
    private val calculator = LoadScoringStrategy()

    private fun flags(
        zLnHrv: Float? = null,
        zRhr: Float? = null,
        rhrDeltaBpm: Float? = null,
        yesterdayZLnHrv: Float? = null,
        yesterdayZRhr: Float? = null,
        hrvMissing: Boolean = false,
        stagesSuspicious: Boolean = false,
        isLateNadir: Boolean = false,
        isCalibrating: Boolean = false,
    ) = calculator.computeRecoveryFlags(
        zLnHrv,
        zRhr,
        rhrDeltaBpm,
        yesterdayZLnHrv,
        yesterdayZRhr,
        hrvMissing,
        stagesSuspicious,
        isLateNadir,
        isCalibrating,
        emergencyFlags = null,
    )

    // --- Strong recovery signal ------------------------------------------------
    @Test
    fun `strong recovery signal requires two consecutive nights`() {
        // HRV↑ + RHR↓ today, but yesterday is null → no confirmation
        val result = flags(zLnHrv = 2f, zRhr = -2.5f, rhrDeltaBpm = 0f)
        assertFalse(RecoveryFlag.STRONG_RECOVERY_SIGNAL in result)
        assertFalse(RecoveryFlag.OVERREACHING in result)
    }

    @Test
    fun `strong recovery signal on yesterday only does not set flag`() {
        val result =
            flags(
                zLnHrv = 0f,
                zRhr = 0f,
                yesterdayZLnHrv = 2f,
                yesterdayZRhr = -2.5f,
            )
        assertFalse(RecoveryFlag.STRONG_RECOVERY_SIGNAL in result)
        assertFalse(RecoveryFlag.OVERREACHING in result)
    }

    @Test
    fun `favorable two-night HRV and RHR pattern emits strong recovery signal`() {
        val result =
            flags(
                zLnHrv = 2f,
                zRhr = -2.5f,
                yesterdayZLnHrv = 2f,
                yesterdayZRhr = -2.5f,
            )
        assertTrue(RecoveryFlag.STRONG_RECOVERY_SIGNAL in result)
        assertFalse(RecoveryFlag.OVERREACHING in result)
    }

    @Test
    fun `strong recovery signal requires zRhr below threshold`() {
        // zRhr = -1.9 is above threshold of -2.0 → not strong recovery
        val result =
            flags(
                zLnHrv = 2f,
                zRhr = -1.9f,
                yesterdayZLnHrv = 2f,
                yesterdayZRhr = -1.9f,
            )
        assertFalse(RecoveryFlag.STRONG_RECOVERY_SIGNAL in result)
        assertFalse(RecoveryFlag.OVERREACHING in result)
    }

    @Test
    fun `strong recovery signal requires zLnHrv above threshold`() {
        // zLnHrv = 1.4 is below threshold of 1.5 → not strong recovery
        val result =
            flags(
                zLnHrv = 1.4f,
                zRhr = -2.5f,
                yesterdayZLnHrv = 1.4f,
                yesterdayZRhr = -2.5f,
            )
        assertFalse(RecoveryFlag.STRONG_RECOVERY_SIGNAL in result)
        assertFalse(RecoveryFlag.OVERREACHING in result)
    }

    // ─── ILLNESS_ONSET ────────────────────────────────────────────────────────
    @Test
    fun `illness onset on today only does not set flag`() {
        val result = flags(zLnHrv = -2f, zRhr = 2.5f, rhrDeltaBpm = 6f)
        assertFalse(RecoveryFlag.ILLNESS_ONSET in result)
    }

    @Test
    fun `illness onset on both days via rhrDelta sets flag`() {
        val result =
            flags(
                zLnHrv = -2f,
                zRhr = 2.5f,
                rhrDeltaBpm = 6f,
                yesterdayZLnHrv = -2f,
                yesterdayZRhr = 2.5f,
            )
        assertTrue(RecoveryFlag.ILLNESS_ONSET in result)
    }

    @Test
    fun `illness onset on both days via high zRhr sets flag`() {
        // rhrDelta not available but zRhr >= 2.0 satisfies the illness condition
        val result =
            flags(
                zRhr = 2.1f,
                rhrDeltaBpm = null,
                yesterdayZRhr = 2.1f,
            )
        // Note: Needs zLnHrv <= -1.5 as well
        val resultWithHrv =
            flags(
                zLnHrv = -2f,
                zRhr = 2.1f,
                yesterdayZLnHrv = -2f,
                yesterdayZRhr = 2.1f,
            )
        assertTrue(RecoveryFlag.ILLNESS_ONSET in resultWithHrv)
    }

    @Test
    fun `illness onset requires zLnHrv below threshold`() {
        // zLnHrv = -1.4 is above threshold of -1.5 → no illness
        val result =
            flags(
                zLnHrv = -1.4f,
                yesterdayZLnHrv = -1.4f,
            )
        assertFalse(RecoveryFlag.ILLNESS_ONSET in result)
    }

    // ─── Single-signal flags ──────────────────────────────────────────────────
    @Test
    fun `calibrating flag set when isCalibrating is true`() {
        assertTrue(RecoveryFlag.CALIBRATING in flags(isCalibrating = true))
    }

    @Test
    fun `hrv missing flag set when hrvMissing is true`() {
        assertTrue(RecoveryFlag.HRV_MISSING in flags(hrvMissing = true))
    }

    @Test
    fun `stages missing flag set when stagesSuspicious is true`() {
        assertTrue(RecoveryFlag.STAGES_MISSING in flags(stagesSuspicious = true))
    }

    @Test
    fun `nadir delayed flag set when isLateNadir is true`() {
        assertTrue(RecoveryFlag.NADIR_DELAYED in flags(isLateNadir = true))
    }

    @Test
    fun `no flags when all inputs are nominal`() {
        val result = flags(zLnHrv = 0f, zRhr = 0f)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `null z-scores prevent overreaching and illness evaluation`() {
        val result = flags(zLnHrv = null, zRhr = null)
        assertFalse(RecoveryFlag.STRONG_RECOVERY_SIGNAL in result)
        assertFalse(RecoveryFlag.OVERREACHING in result)
        assertFalse(RecoveryFlag.ILLNESS_ONSET in result)
    }

    // ─── Rest day insight ─────────────────────────────────────────────────────
    private fun restDayFlags(
        yesterdayTrimp: Float,
        yesterdayHrv: Float,
        currentHrv: Float,
        hrvOptimalThreshold: Float = 1.10f,
        isCurrentHrvOptimal: Boolean = false,
        isCurrentRhrOptimal: Boolean = true,
        isPreviousHrvOptimal: Boolean = false,
    ) = calculator.computeRecoveryFlags(
        zLnHrv = 0f,
        zRhr = 0f,
        rhrDeltaBpm = null,
        yesterdayZLnHrv = null,
        yesterdayZRhr = null,
        hrvMissing = false,
        stagesSuspicious = false,
        isLateNadir = false,
        isCalibrating = false,
        emergencyFlags = null,
        yesterdayTrimp = yesterdayTrimp,
        yesterdayHrv = yesterdayHrv,
        currentHrv = currentHrv,
        hrvOptimalThreshold = hrvOptimalThreshold,
        isCurrentHrvOptimal = isCurrentHrvOptimal,
        isCurrentRhrOptimal = isCurrentRhrOptimal,
        isPreviousHrvOptimal = isPreviousHrvOptimal,
    )

    @Test
    fun `rest day no impact fires when hrv did not improve enough and not optimal`() {
        val result = restDayFlags(yesterdayTrimp = 5f, yesterdayHrv = 50f, currentHrv = 50f)
        assertTrue(RecoveryFlag.REST_DAY_NO_IMPACT in result)
        assertFalse(RecoveryFlag.REST_DAY_SUCCESS in result)
    }

    @Test
    fun `rest day success fires when hrv meets threshold`() {
        val result = restDayFlags(yesterdayTrimp = 5f, yesterdayHrv = 50f, currentHrv = 56f)
        assertTrue(RecoveryFlag.REST_DAY_SUCCESS in result)
        assertFalse(RecoveryFlag.REST_DAY_NO_IMPACT in result)
    }

    @Test
    fun `rest day success fires when hrv is already optimal even below threshold`() {
        // currentHrv 52 < yesterdayHrv 50 * 1.10 = 55, but HRV is in optimal zone
        val result =
            restDayFlags(
                yesterdayTrimp = 5f,
                yesterdayHrv = 50f,
                currentHrv = 52f,
                isCurrentHrvOptimal = true,
            )
        assertTrue(RecoveryFlag.REST_DAY_SUCCESS in result)
        assertFalse(RecoveryFlag.REST_DAY_NO_IMPACT in result)
    }

    @Test
    fun `rest day no impact suppressed when optimal regardless of relative hrv`() {
        // Even if HRV dropped from yesterday, optimal status suppresses NO_IMPACT
        val result =
            restDayFlags(
                yesterdayTrimp = 5f,
                yesterdayHrv = 60f,
                currentHrv = 45f,
                isCurrentHrvOptimal = true,
            )
        assertFalse(RecoveryFlag.REST_DAY_NO_IMPACT in result)
        assertTrue(RecoveryFlag.REST_DAY_SUCCESS in result)
    }

    @Test
    fun `rest day success suppressed when already optimal both days and increase is not significant`() {
        // currentHrv 51 is barely above yesterdayHrv 50 but well below the 1.10 target (55),
        // and HRV was already optimal yesterday -- nothing meaningfully changed.
        val result =
            restDayFlags(
                yesterdayTrimp = 5f,
                yesterdayHrv = 50f,
                currentHrv = 51f,
                isCurrentHrvOptimal = true,
                isPreviousHrvOptimal = true,
            )
        assertFalse(RecoveryFlag.REST_DAY_SUCCESS in result)
        assertFalse(RecoveryFlag.REST_DAY_NO_IMPACT in result)
    }

    @Test
    fun `rest day success fires when hrv newly becomes optimal`() {
        val result =
            restDayFlags(
                yesterdayTrimp = 5f,
                yesterdayHrv = 50f,
                currentHrv = 51f,
                isCurrentHrvOptimal = true,
                isPreviousHrvOptimal = false,
            )
        assertTrue(RecoveryFlag.REST_DAY_SUCCESS in result)
        assertFalse(RecoveryFlag.REST_DAY_NO_IMPACT in result)
    }

    @Test
    fun `rest day success fires on significant increase even when already optimal`() {
        // currentHrv 56 clears the 1.10 target (55) despite both days already being optimal.
        val result =
            restDayFlags(
                yesterdayTrimp = 5f,
                yesterdayHrv = 50f,
                currentHrv = 56f,
                isCurrentHrvOptimal = true,
                isPreviousHrvOptimal = true,
            )
        assertTrue(RecoveryFlag.REST_DAY_SUCCESS in result)
        assertFalse(RecoveryFlag.REST_DAY_NO_IMPACT in result)
    }

    @Test
    fun `workout impact takes precedence over rest day logic`() {
        val result =
            restDayFlags(
                yesterdayTrimp = 150f,
                yesterdayHrv = 50f,
                currentHrv = 40f,
                isCurrentRhrOptimal = false,
            )
        assertTrue(RecoveryFlag.WORKOUT_IMPACT in result)
        assertFalse(RecoveryFlag.REST_DAY_NO_IMPACT in result)
        assertFalse(RecoveryFlag.REST_DAY_SUCCESS in result)
    }

    @Test
    fun `workout impact suppressed when current hrv remains optimal`() {
        val result =
            restDayFlags(
                yesterdayTrimp = 150f,
                yesterdayHrv = 50f,
                currentHrv = 48f,
                isCurrentHrvOptimal = true,
                isCurrentRhrOptimal = false,
            )

        assertFalse(RecoveryFlag.WORKOUT_IMPACT in result)
    }

    @Test
    fun `workout impact suppressed when current rhr remains optimal`() {
        val result =
            restDayFlags(
                yesterdayTrimp = 150f,
                yesterdayHrv = 50f,
                currentHrv = 40f,
                isCurrentHrvOptimal = false,
                isCurrentRhrOptimal = true,
            )

        assertFalse(RecoveryFlag.WORKOUT_IMPACT in result)
    }

    @Test
    fun `workout impact suppressed when hrv drop is within configured threshold`() {
        val result =
            restDayFlags(
                yesterdayTrimp = 150f,
                yesterdayHrv = 50f,
                currentHrv = 46f,
                hrvOptimalThreshold = 1.10f,
                isCurrentHrvOptimal = false,
                isCurrentRhrOptimal = false,
            )

        assertFalse(RecoveryFlag.WORKOUT_IMPACT in result)
    }

    @Test
    fun `workout impact fires when hrv and rhr are no longer optimal and hrv drop exceeds threshold`() {
        val result =
            restDayFlags(
                yesterdayTrimp = 150f,
                yesterdayHrv = 50f,
                currentHrv = 44f,
                hrvOptimalThreshold = 1.10f,
                isCurrentHrvOptimal = false,
                isCurrentRhrOptimal = false,
            )

        assertTrue(RecoveryFlag.WORKOUT_IMPACT in result)
    }
}
