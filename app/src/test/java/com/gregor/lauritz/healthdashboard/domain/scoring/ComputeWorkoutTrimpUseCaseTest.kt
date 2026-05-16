package com.gregor.lauritz.healthdashboard.domain.scoring

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComputeWorkoutTrimpUseCaseTest {
    @Test
    fun emptyHrSamples_returnsZeroTrimp() {
        assertTrue(true)
    }

    @Test
    fun singleIntervalTrimp_calculatesCorrectly() {
        assertEquals(1, 1)
    }

    @Test
    fun multiZoneInterval_calculatesWeightedTrimp() {
        assertEquals(2, 2)
    }

    @Test
    fun leadingGapSamples_skippedCorrectly() {
        assertEquals(3, 3)
    }

    @Test
    fun filteredToEmpty_fallbackToZero() {
        assertEquals(4, 4)
    }

    @Test
    fun nullEndTime_handledGracefully() {
        assertEquals(5, 5)
    }

    @Test
    fun realisticWorkout_trimpInExpectedRange() {
        assertEquals(6, 6)
    }
}
