package com.gregor.lauritz.healthdashboard.domain.display

import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import org.junit.Test
import kotlin.test.assertEquals

class MetricFormatterTest {
    // ── formatWeight ────────────────────────────────────────────────────────

    @Test
    fun `formatWeight metric returns value with kg suffix`() {
        assertEquals("72.3 kg", MetricFormatter.formatWeight(72.3f, UnitSystem.METRIC))
    }

    @Test
    fun `formatWeight metric single decimal place`() {
        assertEquals("100.0 kg", MetricFormatter.formatWeight(100f, UnitSystem.METRIC))
    }

    @Test
    fun `formatWeight imperial converts kg to lbs`() {
        // 70 kg * 2.20462 = 154.3234 → "154.3 lbs"
        val result = MetricFormatter.formatWeight(70f, UnitSystem.IMPERIAL)
        assertEquals("154.3 lbs", result)
    }

    @Test
    fun `formatWeight imperial precision rounds correctly`() {
        // 100 kg * 2.20462 = 220.462 → "220.5 lbs"
        val result = MetricFormatter.formatWeight(100f, UnitSystem.IMPERIAL)
        assertEquals("220.5 lbs", result)
    }

    @Test
    fun `formatWeight zero returns dash`() {
        assertEquals("—", MetricFormatter.formatWeight(0f, UnitSystem.METRIC))
        assertEquals("—", MetricFormatter.formatWeight(0f, UnitSystem.IMPERIAL))
    }

    @Test
    fun `formatWeight negative returns dash`() {
        assertEquals("—", MetricFormatter.formatWeight(-1f, UnitSystem.METRIC))
        assertEquals("—", MetricFormatter.formatWeight(-5f, UnitSystem.IMPERIAL))
    }

    // ── formatBodyFat ───────────────────────────────────────────────────────

    @Test
    fun `formatBodyFat returns one decimal with percent sign`() {
        assertEquals("18.5%", MetricFormatter.formatBodyFat(18.5f))
    }

    @Test
    fun `formatBodyFat rounds to one decimal`() {
        assertEquals("22.4%", MetricFormatter.formatBodyFat(22.44f))
        assertEquals("22.5%", MetricFormatter.formatBodyFat(22.45f))
    }

    @Test
    fun `formatBodyFat zero returns dash`() {
        assertEquals("—", MetricFormatter.formatBodyFat(0f))
    }

    @Test
    fun `formatBodyFat negative returns dash`() {
        assertEquals("—", MetricFormatter.formatBodyFat(-3.0f))
    }

    // ── formatBloodPressure ─────────────────────────────────────────────────

    @Test
    fun `formatBloodPressure returns systolic slash diastolic`() {
        assertEquals("120/80", MetricFormatter.formatBloodPressure(120, 80))
    }

    @Test
    fun `formatBloodPressure zero systolic returns dash`() {
        assertEquals("—", MetricFormatter.formatBloodPressure(0, 80))
    }

    @Test
    fun `formatBloodPressure zero diastolic returns dash`() {
        assertEquals("—", MetricFormatter.formatBloodPressure(120, 0))
    }

    @Test
    fun `formatBloodPressure both zero returns dash`() {
        assertEquals("—", MetricFormatter.formatBloodPressure(0, 0))
    }

    @Test
    fun `formatBloodPressure negative values return dash`() {
        assertEquals("—", MetricFormatter.formatBloodPressure(-1, 80))
        assertEquals("—", MetricFormatter.formatBloodPressure(120, -5))
    }

    // ── formatZonePercent ───────────────────────────────────────────────────

    @Test
    fun `formatZonePercent converts fraction to integer percent`() {
        assertEquals("34%", MetricFormatter.formatZonePercent(0.34f))
    }

    @Test
    fun `formatZonePercent zero fraction returns 0 percent`() {
        assertEquals("0%", MetricFormatter.formatZonePercent(0f))
    }

    @Test
    fun `formatZonePercent full fraction returns 100 percent`() {
        assertEquals("100%", MetricFormatter.formatZonePercent(1f))
    }

    @Test
    fun `formatZonePercent clamps above 1 to 100 percent`() {
        assertEquals("100%", MetricFormatter.formatZonePercent(1.5f))
    }

    @Test
    fun `formatZonePercent negative returns dash`() {
        assertEquals("—", MetricFormatter.formatZonePercent(-0.1f))
    }

    // ── formatBmi ───────────────────────────────────────────────────────────

    @Test
    fun `formatBmi returns one decimal place`() {
        assertEquals("22.4", MetricFormatter.formatBmi(22.4f))
    }

    @Test
    fun `formatBmi rounds correctly`() {
        assertEquals("18.5", MetricFormatter.formatBmi(18.54f))
        assertEquals("30.0", MetricFormatter.formatBmi(30.0f))
    }

    @Test
    fun `formatBmi zero returns dash`() {
        assertEquals("—", MetricFormatter.formatBmi(0f))
    }

    @Test
    fun `formatBmi negative returns dash`() {
        assertEquals("—", MetricFormatter.formatBmi(-1f))
    }

    @Test
    fun `formatStrain rounds half up to two decimals`() {
        assertEquals("0.37", MetricFormatter.formatStrain(0.365f))
    }

    @Test
    fun `formatStrain rounds down to two decimals`() {
        assertEquals("0.36", MetricFormatter.formatStrain(0.364f))
    }

    @Test
    fun `formatStrain null returns dash`() {
        assertEquals("—", MetricFormatter.formatStrain(null))
    }

    @Test
    fun `roundStrain returns rounded two decimal value`() {
        assertEquals(0.37f, MetricFormatter.roundStrain(0.365f))
    }

    @Test
    fun `formatTrimp rounds to nearest integer`() {
        assertEquals("116", MetricFormatter.formatTrimp(115.6f))
    }
}
