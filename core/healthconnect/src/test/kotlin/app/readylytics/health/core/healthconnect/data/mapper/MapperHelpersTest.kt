package app.readylytics.health.core.healthconnect.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapperHelpersTest {
    @Test
    fun `extractDeviceName returns null for null, empty, or blank strings`() {
        assertNull(MapperHelpers.extractDeviceName(null))
        assertNull(MapperHelpers.extractDeviceName(""))
        assertNull(MapperHelpers.extractDeviceName("   "))
        assertNull(MapperHelpers.extractDeviceName("\t\n"))
    }

    @Test
    fun `extractDeviceName returns trimmed non-blank device name`() {
        assertEquals("Pixel Watch", MapperHelpers.extractDeviceName("Pixel Watch"))
        assertEquals("Oura Ring", MapperHelpers.extractDeviceName("Oura Ring"))
    }
}
