package app.readylytics.health.domain.util

import app.readylytics.health.domain.preferences.UnitSystem
import org.junit.Test
import kotlin.test.assertEquals

class UnitConverterTest {
    @Test
    fun `height display returns placeholder for null height`() {
        assertEquals(
            UnitConverter.HeightDisplay(value = "—", unit = ""),
            UnitConverter.heightCmToDisplay(null, UnitSystem.METRIC),
        )
    }

    @Test
    fun `height display returns metric centimeters`() {
        assertEquals(
            UnitConverter.HeightDisplay(value = "182", unit = "unit_metric_cm"),
            UnitConverter.heightCmToDisplay(182.9f, UnitSystem.METRIC),
        )
    }

    @Test
    fun `height display converts to feet and inches`() {
        assertEquals(
            UnitConverter.HeightDisplay(value = "510", unit = "height_imperial_format"),
            UnitConverter.heightCmToDisplay(177.8f, UnitSystem.IMPERIAL),
        )
    }

    @Test
    fun `weight display returns placeholder for null weight`() {
        assertEquals(
            UnitConverter.WeightDisplay(value = "—", unit = ""),
            UnitConverter.weightKgToDisplay(null, UnitSystem.METRIC),
        )
    }

    @Test
    fun `weight display keeps metric precision`() {
        assertEquals(
            UnitConverter.WeightDisplay(value = "72.3", unit = "unit_metric_kg"),
            UnitConverter.weightKgToDisplay(72.34f, UnitSystem.METRIC),
        )
    }

    @Test
    fun `weight display converts kilograms to pounds`() {
        assertEquals(
            UnitConverter.WeightDisplay(value = "159.5", unit = "unit_imperial_lbs"),
            UnitConverter.weightKgToDisplay(72.34f, UnitSystem.IMPERIAL),
        )
    }

    @Test
    fun `short weight display truncates decimal output`() {
        assertEquals(
            UnitConverter.WeightDisplay(value = "159", unit = "unit_imperial_lbs"),
            UnitConverter.weightKgToDisplayShort(72.34f, UnitSystem.IMPERIAL),
        )
    }

    @Test
    fun `celsiusToDisplayTemperature passes through unchanged for metric`() {
        assertEquals(36.6f, UnitConverter.celsiusToDisplayTemperature(36.6f, UnitSystem.METRIC), 0.01f)
    }

    @Test
    fun `celsiusToDisplayTemperature converts to fahrenheit for imperial`() {
        assertEquals(97.88f, UnitConverter.celsiusToDisplayTemperature(36.6f, UnitSystem.IMPERIAL), 0.01f)
    }

    @Test
    fun `celsiusDeltaToDisplayDelta scales without the 32-degree offset`() {
        assertEquals(1.0f, UnitConverter.celsiusDeltaToDisplayDelta(1.0f, UnitSystem.METRIC), 0.01f)
        assertEquals(1.8f, UnitConverter.celsiusDeltaToDisplayDelta(1.0f, UnitSystem.IMPERIAL), 0.01f)
    }

    @Test
    fun `formatDistance uses km for large metric distances`() {
        assertEquals("5.2 km", UnitConverter.formatDistance(5200f, UnitSystem.METRIC))
        assertEquals("5.2 km", UnitConverter.formatDistance(5160f, UnitSystem.METRIC))
    }

    @Test
    fun `formatDistance uses meters below one kilometer`() {
        assertEquals("820 m", UnitConverter.formatDistance(820f, UnitSystem.METRIC))
    }

    @Test
    fun `formatDistance converts to miles for imperial`() {
        assertEquals("1.0 mi", UnitConverter.formatDistance(1609.344f, UnitSystem.IMPERIAL))
        assertEquals("0.4 mi", UnitConverter.formatDistance(644f, UnitSystem.IMPERIAL))
    }

    @Test
    fun `formatDistance returns dash for non-positive values`() {
        assertEquals("—", UnitConverter.formatDistance(0f, UnitSystem.METRIC))
        assertEquals("—", UnitConverter.formatDistance(-5f, UnitSystem.IMPERIAL))
    }

    @Test
    fun `formatSpeed shows kmh for metric and mph for imperial`() {
        assertEquals("36.0 km/h", UnitConverter.formatSpeed(36f, UnitSystem.METRIC))
        assertEquals("22.4 mph", UnitConverter.formatSpeed(36f, UnitSystem.IMPERIAL))
    }

    @Test
    fun `formatPace renders minutes per km for metric`() {
        assertEquals("5:30 min/km", UnitConverter.formatPace(5.5f, UnitSystem.METRIC))
    }

    @Test
    fun `formatPace converts to minutes per mile for imperial`() {
        assertEquals("8:03 min/mi", UnitConverter.formatPace(5f, UnitSystem.IMPERIAL))
    }

    @Test
    fun `formatPace caps at 20 minutes per km`() {
        assertEquals("20:00 min/km", UnitConverter.formatPace(20f, UnitSystem.METRIC))
        assertEquals("20:00 min/km", UnitConverter.formatPace(45f, UnitSystem.METRIC))
    }

    @Test
    fun `formatElevation shows meters for metric and feet for imperial`() {
        assertEquals("120 m", UnitConverter.formatElevation(120f, UnitSystem.METRIC))
        assertEquals("394 ft", UnitConverter.formatElevation(120f, UnitSystem.IMPERIAL))
    }

    @Test
    fun `distanceParts splits value from unit token`() {
        assertEquals(
            UnitConverter.MetricParts(value = "5.2", unit = "km"),
            UnitConverter.distanceParts(5200f, UnitSystem.METRIC),
        )
        assertEquals(
            UnitConverter.MetricParts(value = "820", unit = "m"),
            UnitConverter.distanceParts(820f, UnitSystem.METRIC),
        )
        assertEquals(
            UnitConverter.MetricParts(value = "1.0", unit = "mi"),
            UnitConverter.distanceParts(1609.344f, UnitSystem.IMPERIAL),
        )
        assertEquals(null, UnitConverter.distanceParts(0f, UnitSystem.METRIC))
    }

    @Test
    fun `speedParts splits value from unit token`() {
        assertEquals(
            UnitConverter.MetricParts(value = "36.0", unit = "km/h"),
            UnitConverter.speedParts(36f, UnitSystem.METRIC),
        )
        assertEquals(
            UnitConverter.MetricParts(value = "22.4", unit = "mph"),
            UnitConverter.speedParts(36f, UnitSystem.IMPERIAL),
        )
        assertEquals(null, UnitConverter.speedParts(0f, UnitSystem.METRIC))
    }

    @Test
    fun `paceParts splits value from unit token`() {
        assertEquals(
            UnitConverter.MetricParts(value = "5:30", unit = "min/km"),
            UnitConverter.paceParts(5.5f, UnitSystem.METRIC),
        )
        assertEquals(
            UnitConverter.MetricParts(value = "8:03", unit = "min/mi"),
            UnitConverter.paceParts(5f, UnitSystem.IMPERIAL),
        )
        assertEquals(null, UnitConverter.paceParts(0f, UnitSystem.METRIC))
    }

    @Test
    fun `elevationParts splits value from unit token and clamps absurd input`() {
        assertEquals(
            UnitConverter.MetricParts(value = "120", unit = "m"),
            UnitConverter.elevationParts(120f, UnitSystem.METRIC),
        )
        assertEquals(
            UnitConverter.MetricParts(value = "394", unit = "ft"),
            UnitConverter.elevationParts(120f, UnitSystem.IMPERIAL),
        )
        assertEquals(
            UnitConverter.MetricParts(value = "15000", unit = "m"),
            UnitConverter.elevationParts(1_000_000f, UnitSystem.METRIC),
        )
        assertEquals(null, UnitConverter.elevationParts(Float.NaN, UnitSystem.METRIC))
        assertEquals(null, UnitConverter.elevationParts(-5f, UnitSystem.METRIC))
    }
}
