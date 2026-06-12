package app.readylytics.health.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.preferences.FallbackThemeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun testPresetThemesExplicitMapping() {
        // Preset theme 1: Green Performance
        val gp = FallbackThemeColor.GREEN_PERFORMANCE
        assertEquals(0xFF2ECC71L, gp.primaryColor)
        assertEquals(0xFF3498DBL, gp.secondaryColor)
        assertEquals(0xFFF1C40FL, gp.tertiaryColor)

        // Preset theme 2: Blue Trust
        val bt = FallbackThemeColor.BLUE_TRUST
        assertEquals(0xFF4A90E2L, bt.primaryColor)
        assertEquals(0xFFF5A623L, bt.secondaryColor)
        assertEquals(0xFF50E3C2L, bt.tertiaryColor)

        // Preset theme 3: Purple Insight
        val pi = FallbackThemeColor.PURPLE_INSIGHT
        assertEquals(0xFF8E44ADL, pi.primaryColor)
        assertEquals(0xFFF39C12L, pi.secondaryColor)
        assertEquals(0xFFE91E63L, pi.tertiaryColor)

        // Preset theme 4: Icon Signature
        val isig = FallbackThemeColor.ICON_SIGNATURE
        assertEquals(0xFF9D6FFFL, isig.primaryColor)
        assertEquals(0xFF409FFFL, isig.secondaryColor)
        assertEquals(0xFFC1A2F5L, isig.tertiaryColor)

        // Preset theme 5: Icon Elements
        val ielem = FallbackThemeColor.ICON_ELEMENTS
        assertEquals(0xFF409FFFL, ielem.primaryColor)
        assertEquals(0xFF9D6FFFL, ielem.secondaryColor)
        assertEquals(0xFFFFB74DL, ielem.tertiaryColor)
    }

    @Test
    fun testMcuDerivation() {
        val seedColor = Color(0xFF2ECC71)
        val hct =
            com.materialkolor.hct.Hct
                .fromInt(seedColor.toArgb())
        val scheme = com.materialkolor.scheme.SchemeTonalSpot(hct, false, 0.0)

        // Ensure MCU derived colors are valid and not empty/zero
        assertNotEquals(0, scheme.primary)
        assertNotEquals(0, scheme.secondary)
        assertNotEquals(0, scheme.tertiary)
    }
}
