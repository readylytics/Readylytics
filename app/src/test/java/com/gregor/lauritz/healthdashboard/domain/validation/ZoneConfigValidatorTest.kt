package com.gregor.lauritz.healthdashboard.domain.validation

import com.gregor.lauritz.healthdashboard.domain.validation.ZoneConfigValidator.ZoneConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneConfigValidatorTest {
    private val validator = ZoneConfigValidator()

    private fun typicalConfig(
        z1Min: Int = 95,
        z1Max: Int = 114,
        z2Max: Int = 133,
        z3Max: Int = 152,
        z4Max: Int = 171,
        z5Max: Int? = null,
        age: Int = 30,
        rhr: Int? = 60,
        hrMax: Int? = null,
    ) = ZoneConfig(
        zone1MinBpm = z1Min,
        zone1MaxBpm = z1Max,
        zone2MaxBpm = z2Max,
        zone3MaxBpm = z3Max,
        zone4MaxBpm = z4Max,
        zone5MaxBpm = z5Max,
        ageYears = age,
        rhrBaselineBpm = rhr,
        explicitHrMax = hrMax,
    )

    @Test
    fun `typical configuration passes`() {
        val result = validator.validate(typicalConfig(age = 30, hrMax = 190))
        assertTrue("Typical config should be valid: ${result.issues}", result.isValid)
    }

    @Test
    fun `inverted zone1 caught`() {
        val result = validator.validate(typicalConfig(z1Min = 120, z1Max = 114))
        assertFalse(result.isValid)
        val issue = result.issues.first { it.field == "zone1MinBpm" }
        assertNotNull(issue.suggestedValue)
    }

    @Test
    fun `zone1 below RHR caught`() {
        val result = validator.validate(typicalConfig(z1Min = 50, rhr = 60))
        assertFalse(result.isValid)
        val issue = result.issues.first { it.field == "zone1MinBpm" }
        assertEquals(60, issue.suggestedValue)
    }

    @Test
    fun `non-monotonic zones caught`() {
        // zone3Max ≤ zone2Max
        val result = validator.validate(typicalConfig(z2Max = 150, z3Max = 150))
        assertFalse(result.isValid)
        assertTrue(result.issues.any { it.field == "zone3MaxBpm" })
    }

    @Test
    fun `zone5 above 95pct of HR max caught with correction`() {
        // hrMax 190 → max allowed = 181; zone5 195 should fail
        val result = validator.validate(typicalConfig(z5Max = 195, hrMax = 190))
        assertFalse(result.isValid)
        val issue = result.issues.first { it.field == "zone5MaxBpm" }
        assertEquals(181, issue.suggestedValue) // round(190 * 0.95)
    }

    @Test
    fun `zone4 above HR max caught with correction`() {
        // hrMax 165, zone4Max 168 → fail
        val result = validator.validate(typicalConfig(z4Max = 168, hrMax = 165))
        assertFalse(result.isValid)
        val issue = result.issues.first { it.field == "zone4MaxBpm" }
        assertTrue(issue.message.contains("HR max"))
    }

    @Test
    fun `estimateHrMax uses Karvonen for under 40`() {
        // 220 - 25 = 195
        assertEquals(195, validator.estimateHrMax(25))
    }

    @Test
    fun `estimateHrMax uses Tanaka for 40 plus`() {
        // 208 - 0.7 * 60 = 208 - 42 = 166
        assertEquals(166, validator.estimateHrMax(60))
    }

    @Test
    fun `revalidate on age change re-runs validation against new HR max`() {
        // 30yo config valid; bump to 70yo → zone4Max 171 likely exceeds Tanaka(70) = 208-49 = 159
        val cfg = typicalConfig(age = 30, z4Max = 171, z5Max = 180)
        val initially = validator.validate(cfg)
        assertTrue(initially.isValid)
        val after = validator.revalidateOnAgeChange(cfg, newAge = 70)
        assertFalse("After age bump, zones should fail: ${after.issues}", after.isValid)
    }

    @Test
    fun `effective HR max reflects explicit override`() {
        val result = validator.validate(typicalConfig(hrMax = 200))
        assertEquals(200, result.effectiveHrMax)
    }

    @Test
    fun `effective HR max falls back to estimate`() {
        val result = validator.validate(typicalConfig(age = 30, hrMax = null))
        assertEquals(190, result.effectiveHrMax)
    }

    @Test
    fun `validation timestamp is set`() {
        val before = System.currentTimeMillis()
        val result = validator.validate(typicalConfig())
        val after = System.currentTimeMillis()
        assertTrue(result.timestampMs in before..after)
    }

    @Test
    fun `multiple issues collected together`() {
        // Inverted zone1 + non-monotonic zones + zone5 too high
        val result =
            validator.validate(
                typicalConfig(
                    z1Min = 130,
                    z1Max = 114,
                    z2Max = 110,
                    z5Max = 220,
                    hrMax = 190,
                ),
            )
        assertFalse(result.isValid)
        assertTrue("Multiple issues expected: ${result.issues.size}", result.issues.size >= 3)
    }

    @Test
    fun `no RHR baseline skips zone1 floor check`() {
        // No RHR provided → zone1Min=10 should not trigger an RHR-related issue
        val result = validator.validate(typicalConfig(z1Min = 10, z1Max = 30, rhr = null))
        // No issue from RHR floor; other issues like non-monotonic may exist
        assertFalse(
            "Should not contain RHR-related zone1Min issue",
            result.issues.any { it.field == "zone1MinBpm" && it.message.contains("resting heart rate") },
        )
    }
}
