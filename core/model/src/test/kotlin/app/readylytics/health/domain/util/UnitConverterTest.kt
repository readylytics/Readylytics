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
}
