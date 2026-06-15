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
}
