package app.readylytics.health.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ThemeTest {
    @Test
    fun testPaletteCalculations() {
        val primary = Color(0xFFAA78FF)
        val secondary = calculateSecondarySeedColor(primary)
        val tertiary = calculateTertiarySeedColor(primary)

        val primaryHsl = FloatArray(3)
        primary.toHsl(primaryHsl)

        val secondaryHsl = FloatArray(3)
        secondary.toHsl(secondaryHsl)

        val tertiaryHsl = FloatArray(3)
        tertiary.toHsl(tertiaryHsl)

        println("Primary HSL: ${primaryHsl.joinToString()}")
        println("Secondary HSL: ${secondaryHsl.joinToString()}")
        println("Tertiary HSL: ${tertiaryHsl.joinToString()}")

        try {
            // Verify Hue
            assertEquals("Hue secondary", primaryHsl[0], secondaryHsl[0], 1.0f)
            assertEquals("Hue tertiary", (primaryHsl[0] + 60f) % 360f, tertiaryHsl[0], 1.0f)

            // Verify Saturation
            val expectedSecondarySat = maxOf(0.16f, primaryHsl[1] * 0.35f)
            assertEquals("Saturation secondary", expectedSecondarySat, secondaryHsl[1], 0.01f)

            val expectedTertiarySat = maxOf(0.24f, primaryHsl[1] * 0.5f)
            assertEquals("Saturation tertiary", expectedTertiarySat, tertiaryHsl[1], 0.01f)

            // Verify Lightness (should be preserved)
            assertEquals("Lightness secondary", primaryHsl[2], secondaryHsl[2], 0.01f)
            assertEquals("Lightness tertiary", primaryHsl[2], tertiaryHsl[2], 0.01f)
        } catch (e: AssertionError) {
            println("Assertion failed: ${e.message}")
            throw e
        }
    }
}
