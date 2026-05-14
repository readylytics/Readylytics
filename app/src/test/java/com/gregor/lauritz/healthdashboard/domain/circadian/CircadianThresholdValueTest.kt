package com.gregor.lauritz.healthdashboard.domain.circadian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CircadianThresholdValueTest {
    @Test
    fun testValidRange() {
        // Min value
        val min = CircadianThresholdValue(0)
        assertEquals(0, min.minutes)

        // Max value
        val max = CircadianThresholdValue(90)
        assertEquals(90, max.minutes)

        // Mid value
        val mid = CircadianThresholdValue(45)
        assertEquals(45, mid.minutes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testBelowMinimum() {
        CircadianThresholdValue(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testAboveMaximum() {
        CircadianThresholdValue(91)
    }

    @Test
    fun testTryCreateValid() {
        val result = CircadianThresholdValue.tryCreate(30)
        assertTrue(result.isSuccess)
        assertEquals(30, result.getOrNull()?.minutes)
    }

    @Test
    fun testTryCreateNull() {
        val result = CircadianThresholdValue.tryCreate(null)
        assertTrue(result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun testTryCreateInvalid() {
        val result = CircadianThresholdValue.tryCreate(150)
        assertTrue(result.isFailure)
    }

    @Test
    fun testClampBelow() {
        val clamped = CircadianThresholdValue.createOrClamp(-10)
        assertEquals(0, clamped?.minutes)
    }

    @Test
    fun testClampAbove() {
        val clamped = CircadianThresholdValue.createOrClamp(200)
        assertEquals(90, clamped?.minutes)
    }
}
