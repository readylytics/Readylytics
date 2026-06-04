package com.gregor.lauritz.healthdashboard.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthDataTypeTest {
    @Test
    fun `all nine data types are present`() {
        assertEquals(9, HealthDataType.entries.size)
    }

    @Test
    fun `data types are grouped under the expected categories`() {
        val byCategory = HealthDataType.entries.groupBy { it.category }

        assertEquals(
            setOf(HealthDataType.EXERCISE, HealthDataType.STEPS),
            byCategory[HealthDataCategory.ACTIVITY]?.toSet(),
        )
        assertEquals(
            setOf(HealthDataType.BODY_FAT, HealthDataType.WEIGHT),
            byCategory[HealthDataCategory.BODY_MEASUREMENTS]?.toSet(),
        )
        assertEquals(
            setOf(HealthDataType.SLEEP),
            byCategory[HealthDataCategory.SLEEP]?.toSet(),
        )
        assertEquals(
            setOf(
                HealthDataType.BLOOD_PRESSURE,
                HealthDataType.HEART_RATE,
                HealthDataType.HRV,
                HealthDataType.OXYGEN_SATURATION,
            ),
            byCategory[HealthDataCategory.VITALS]?.toSet(),
        )
    }
}
