package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepArchitectureValidatorTest {
    private val validator = SleepArchitectureValidator()

    // ─── Age bands ──────────────────────────────────────────────────────────

    @Test
    fun `age under 40 uses 28 percent deep ceiling`() {
        val b = validator.boundsForAge(30)
        assertEquals(0.28f, b.deepMaxFraction, 1e-3f)
        assertEquals(0.28f, b.remMaxFraction, 1e-3f)
    }

    @Test
    fun `age 40 to 64 uses 25 percent deep ceiling`() {
        val b = validator.boundsForAge(55)
        assertEquals(0.25f, b.deepMaxFraction, 1e-3f)
        assertEquals(0.26f, b.remMaxFraction, 1e-3f)
    }

    @Test
    fun `age 65 plus uses 22 percent deep ceiling`() {
        val b = validator.boundsForAge(70)
        assertEquals(0.22f, b.deepMaxFraction, 1e-3f)
        assertEquals(0.24f, b.remMaxFraction, 1e-3f)
    }

    // ─── Published PSG ranges are accepted ──────────────────────────────────

    @Test
    fun `Ohayon 2004 typical young adult (deep 18 percent rem 22 percent) is valid`() {
        assertTrue(validator.isValidArchitecture(0.18f, 0.22f, age = 28, deviceSource = null))
    }

    @Test
    fun `typical 50 year old (deep 14 percent rem 20 percent) is valid`() {
        assertTrue(validator.isValidArchitecture(0.14f, 0.20f, age = 50, deviceSource = null))
    }

    @Test
    fun `typical 70 year old (deep 12 percent rem 18 percent) is valid`() {
        assertTrue(validator.isValidArchitecture(0.12f, 0.18f, age = 70, deviceSource = null))
    }

    // ─── Edge cases at age boundaries ───────────────────────────────────────

    @Test
    fun `deep at age band ceiling is still valid`() {
        // age 30 → ceiling 28% inclusive
        assertTrue(validator.isValidArchitecture(0.28f, 0.20f, age = 30, deviceSource = null))
    }

    @Test
    fun `deep just above age band ceiling is invalid`() {
        // age 30 → 28.1% is over
        assertFalse(validator.isValidArchitecture(0.281f, 0.20f, age = 30, deviceSource = null))
    }

    @Test
    fun `older adult deep at 25 percent is invalid for 65 plus`() {
        // 25% > 22% age band ceiling for 70-year-old
        assertFalse(validator.isValidArchitecture(0.25f, 0.18f, age = 70, deviceSource = null))
    }

    // ─── Device corrections ─────────────────────────────────────────────────

    @Test
    fun `Fitbit REM 28 percent corrected back below 26 percent ceiling for age 50`() {
        // Fitbit -3% REM correction: 28% - 3% = 25% < 26% → valid
        assertTrue(validator.isValidArchitecture(0.18f, 0.28f, age = 50, deviceSource = "Fitbit Versa 4"))
    }

    @Test
    fun `Apple Watch deep 24 percent corrected up to 26 percent for age 50 is invalid`() {
        // Apple +2% deep correction: 24% + 2% = 26% > 25% → invalid
        assertFalse(
            validator.isValidArchitecture(0.24f, 0.18f, age = 50, deviceSource = "Apple Watch Series 9"),
        )
    }

    @Test
    fun `Oura applies no correction`() {
        // Same reading, no adjustment.
        assertTrue(validator.isValidArchitecture(0.20f, 0.20f, age = 30, deviceSource = "Oura Ring Gen3"))
    }

    @Test
    fun `unknown device source applies no correction`() {
        assertTrue(validator.isValidArchitecture(0.20f, 0.20f, age = 30, deviceSource = "Mystery Tracker 1"))
    }

    // ─── Suspicious flag ────────────────────────────────────────────────────

    @Test
    fun `mid-range stages are not suspicious`() {
        // deep=18%, rem=22% — both well below ceiling and 85% threshold
        val res = validator.evaluate(0.18f, 0.22f, age = 30, deviceSource = null)
        assertTrue(res.valid)
        assertFalse(res.suspicious)
    }

    @Test
    fun `deep within suspicious band flags suspicious without rejecting night`() {
        // For age<40 ceiling is 28%; 85% of 28% = 23.8%. Deep=25% > 23.8% → suspicious.
        val res = validator.evaluate(0.25f, 0.20f, age = 30, deviceSource = null)
        assertTrue(res.valid)
        assertTrue(res.suspicious)
    }

    @Test
    fun `REM within suspicious band flags suspicious`() {
        // 85% of 28% rem ceiling = 23.8%. Rem=25% → suspicious.
        val res = validator.evaluate(0.15f, 0.25f, age = 30, deviceSource = null)
        assertTrue(res.valid)
        assertTrue(res.suspicious)
    }

    @Test
    fun `invalid night is not also flagged suspicious`() {
        val res = validator.evaluate(0.30f, 0.40f, age = 30, deviceSource = null)
        assertFalse(res.valid)
        assertFalse(res.suspicious)
    }

    // ─── Warning messages ──────────────────────────────────────────────────

    @Test
    fun `warning is null when night is healthy`() {
        assertNull(validator.getValidationWarning(0.18f, 0.22f, age = 30, deviceSource = null))
    }

    @Test
    fun `warning mentions REM when REM is over band`() {
        val warning = validator.getValidationWarning(0.20f, 0.40f, age = 30, deviceSource = null)
        assertNotNull(warning)
        assertTrue(warning!!.contains("REM"))
    }

    @Test
    fun `warning mentions Deep when deep is over band`() {
        val warning = validator.getValidationWarning(0.40f, 0.20f, age = 30, deviceSource = null)
        assertNotNull(warning)
        assertTrue(warning!!.contains("Deep"))
    }
}
