package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [SleepArchitectureValidator] across a synthetic batch of 20 nights
 * spanning ages 25..78 and devices oura/fitbit/apple/garmin/unknown to
 * make sure validation status is deterministic and matches age band.
 */
class SleepArchitectureValidatorIntegrationTest {
    private val validator = SleepArchitectureValidator()

    data class Night(
        val age: Int,
        val device: String?,
        val deep: Float,
        val rem: Float,
        val expectValid: Boolean,
    )

    private val nights =
        listOf(
            // Healthy young adults — all valid
            Night(25, null, 0.18f, 0.22f, true),
            Night(28, "oura", 0.20f, 0.25f, true),
            Night(32, "Fitbit Sense", 0.18f, 0.26f, true),
            Night(35, "Apple Watch", 0.20f, 0.22f, true),
            Night(38, "Garmin Fenix 7", 0.19f, 0.21f, true),
            // Deep too high → invalid
            Night(30, null, 0.35f, 0.20f, false),
            Night(28, "oura", 0.40f, 0.22f, false),
            // REM too high → invalid
            Night(34, null, 0.18f, 0.32f, false),
            Night(29, "oura", 0.17f, 0.40f, false),
            // Fitbit REM over-reporting corrected: 28% raw - 3% = 25% → valid for age<40
            Night(30, "Fitbit Versa", 0.16f, 0.28f, true),
            // Apple deep under-reporting corrected: 24% raw + 2% = 26% → invalid for age 50 (>25%)
            Night(50, "Apple Watch 9", 0.24f, 0.18f, false),
            // Older adults — narrower bands
            Night(55, null, 0.14f, 0.20f, true),
            Night(60, "oura", 0.16f, 0.22f, true),
            // 65+ band
            Night(70, null, 0.12f, 0.18f, true),
            Night(72, "oura", 0.15f, 0.20f, true),
            Night(78, null, 0.10f, 0.16f, true),
            // 65+ deep just over band 22% → invalid
            Night(68, null, 0.23f, 0.18f, false),
            // 40-64 band ceilings 25%/26%
            Night(48, null, 0.25f, 0.22f, true),
            Night(48, null, 0.26f, 0.22f, false),
            Night(50, null, 0.20f, 0.27f, false),
        )

    @Test
    fun `each synthetic night validates as expected`() {
        nights.forEachIndexed { idx, n ->
            val result = validator.evaluate(n.deep, n.rem, n.age, n.device)
            assertEquals(
                "night[$idx] age=${n.age} device=${n.device} deep=${n.deep} rem=${n.rem}: expected valid=${n.expectValid}",
                n.expectValid,
                result.valid,
            )
            if (!n.expectValid) {
                assertFalse(result.suspicious)
            }
        }
    }

    @Test
    fun `all 20 nights produce a deterministic warning string when invalid`() {
        val invalidWarnings = nights.filter { !it.expectValid }.map {
            validator.getValidationWarning(it.deep, it.rem, it.age, it.device)
        }
        // Each invalid night must produce a non-null warning string.
        assertTrue(invalidWarnings.all { it != null && it.isNotBlank() })
    }
}
