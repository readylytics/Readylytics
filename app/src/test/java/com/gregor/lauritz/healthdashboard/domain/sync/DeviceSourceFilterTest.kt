package com.gregor.lauritz.healthdashboard.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSourceFilterTest {
    private data class Sample(
        val deviceName: String?,
        val value: Int,
    )

    private val records =
        listOf(
            Sample("Pixel Watch", 1),
            Sample("This Phone", 2),
            Sample("Pixel Watch", 3),
            Sample(null, 4),
        )

    @Test
    fun `null selection keeps all records`() {
        val result = DeviceSourceFilter.filterToDevice(records, null) { it.deviceName }
        assertEquals(records, result)
    }

    @Test
    fun `blank selection keeps all records`() {
        val result = DeviceSourceFilter.filterToDevice(records, "  ") { it.deviceName }
        assertEquals(records, result)
    }

    @Test
    fun `specific device keeps only matching records`() {
        val result = DeviceSourceFilter.filterToDevice(records, "Pixel Watch") { it.deviceName }
        assertEquals(listOf(1, 3), result.map { it.value })
    }

    @Test
    fun `unknown device yields empty list`() {
        val result = DeviceSourceFilter.filterToDevice(records, "Garmin") { it.deviceName }
        assertEquals(emptyList<Sample>(), result)
    }

    @Test
    fun `empty input returns empty regardless of selection`() {
        val result = DeviceSourceFilter.filterToDevice(emptyList<Sample>(), "Pixel Watch") { it.deviceName }
        assertEquals(emptyList<Sample>(), result)
    }
}
