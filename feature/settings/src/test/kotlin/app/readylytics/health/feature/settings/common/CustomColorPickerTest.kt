package app.readylytics.health.feature.settings.common

import androidx.compose.ui.graphics.Color
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CustomColorPickerTest {
    @Before
    fun setUp() {
        mockkStatic(android.graphics.Color::class)
        every { android.graphics.Color.parseColor(any()) } answers {
            val colorString = firstArg<String>()
            require(colorString.startsWith("#")) { "Unknown color" }
            val hex = colorString.substring(1)
            require(hex.length == 6 || hex.length == 8) { "Unknown color" }
            try {
                val color = hex.toLong(16)
                if (hex.length == 6) {
                    (color or 0xFF000000).toInt()
                } else {
                    color.toInt()
                }
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Unknown color", e)
            }
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(android.graphics.Color::class)
    }

    @Test
    fun `parseHexColor parses valid 6-char hex string`() {
        val color = parseHexColor("#FF0000")
        assertEquals(Color(0xFFFF0000), color)
    }

    @Test
    fun `parseHexColor parses valid 8-char hex string`() {
        val color = parseHexColor("#80FF0000")
        assertEquals(Color(0x80FF0000), color)
    }

    @Test
    fun `parseHexColor returns null on invalid hex characters`() {
        val color = parseHexColor("#ZZZZZZ")
        assertNull(color)
    }

    @Test
    fun `parseHexColor returns null on unexpected length`() {
        val color = parseHexColor("#12345")
        assertNull(color)
    }
}
