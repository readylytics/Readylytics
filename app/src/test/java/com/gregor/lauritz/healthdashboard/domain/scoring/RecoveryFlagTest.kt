package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// computeRecoveryFlags: 2-night consecutive confirmation for OVERREACHING and ILLNESS_ONSET.
// Single-night anomalies are noise; both nights must show the same pattern before a flag fires.
// REF: Le Meur 2013 Med Sci Sports Exerc; Mishra 2020 Nat Biomed Eng
class RecoveryFlagTest {
    private val calculator = ScoringCalculatorImpl()

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
        zLnHrv, zRhr, rhrDeltaBpm, yesterdayZLnHrv, yesterdayZRhr,
        hrvMissing, stagesSuspicious, isLateNadir, isCalibrating,
    )

    // ─── OVERREACHING ─────────────────────────────────────────────────────────

    @Test
    fun `overreaching on today only does not set flag`() {
        // HRV↑ + RHR↓ today, but yesterday is null → no confirmation
        val result = flags(zLnHrv = 2f, zRhr = -2.5f, rhrDeltaBpm = 0f)
        assertFalse(RecoveryFlag.OVERREACHING in result)
    }

    @Test
    fun `overreaching on yesterday only does not set flag`() {
        val result = flags(
            zLnHrv = 0f, zRhr = 0f,
            yesterdayZLnHrv = 2f, yesterdayZRhr = -2.5f,
        )
        assertFalse(RecoveryFlag.OVERREACHING in result)
    }

    @Test
    fun `overreaching on both days sets flag`() {
        val result = flags(
            zLnHrv = 2f, zRhr = -2.5f,
            yesterdayZLnHrv = 2f, yesterdayZRhr = -2.5f,
        )
        assertTrue(RecoveryFlag.OVERREACHING in result)
    }

    @Test
    fun `overreaching requires zRhr below threshold`() {
        // zRhr = -1.9 is above threshold of -2.0 → not overreaching
        val result = flags(
            zLnHrv = 2f, zRhr = -1.9f,
            yesterdayZLnHrv = 2f, yesterdayZRhr = -1.9f,
        )
        assertFalse(RecoveryFlag.OVERREACHING in result)
    }

    @Test
    fun `overreaching requires zLnHrv above threshold`() {
        // zLnHrv = 1.4 is below threshold of 1.5 → not overreaching
        val result = flags(
            zLnHrv = 1.4f, zRhr = -2.5f,
            yesterdayZLnHrv = 1.4f, yesterdayZRhr = -2.5f,
        )
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
        val result = flags(
            zLnHrv = -2f, zRhr = 2.5f, rhrDeltaBpm = 6f,
            yesterdayZLnHrv = -2f, yesterdayZRhr = 2.5f,
        )
        assertTrue(RecoveryFlag.ILLNESS_ONSET in result)
    }

    @Test
    fun `illness onset on both days via high zRhr sets flag`() {
        // rhrDelta not available but zRhr >= 2.0 satisfies the illness condition
        val result = flags(
            zLnHrv = -2f, zRhr = 2.1f, rhrDeltaBpm = null,
            yesterdayZLnHrv = -2f, yesterdayZRhr = 2.1f,
        )
        assertTrue(RecoveryFlag.ILLNESS_ONSET in result)
    }

    @Test
    fun `illness onset requires zLnHrv below threshold`() {
        // zLnHrv = -1.4 is above threshold of -1.5 → no illness
        val result = flags(
            zLnHrv = -1.4f, zRhr = 2.5f, rhrDeltaBpm = 6f,
            yesterdayZLnHrv = -1.4f, yesterdayZRhr = 2.5f,
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
        assertFalse(RecoveryFlag.OVERREACHING in result)
        assertFalse(RecoveryFlag.ILLNESS_ONSET in result)
    }
}
