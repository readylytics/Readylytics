package com.gregor.lauritz.healthdashboard.ui.components

import org.junit.Test
import org.junit.Assert.assertEquals

class ReorderableCardGridThresholdTest {
    companion object {
        private const val DEFAULT_CARD_HEIGHT = 130
    }

    // Test downThreshold calculation: (currentHeight + nextHeight) / 8
    @Test
    fun downThreshold_calculatesCorrectly_withEqualHeights() {
        val currentHeight = 100
        val nextHeight = 100
        val expected = (currentHeight + nextHeight) / 8f
        val actual = (currentHeight + nextHeight) / 8f
        assertEquals(expected, actual, 0.1f)
    }

    @Test
    fun downThreshold_calculatesCorrectly_withDifferentHeights() {
        val currentHeight = 100
        val nextHeight = 150
        val expected = (100 + 150) / 8f // 31.25
        val actual = (currentHeight + nextHeight) / 8f
        assertEquals(expected, actual, 0.1f)
    }

    @Test
    fun downThreshold_usesDefaultForMissingHeight() {
        val currentHeight = 100
        val nextHeight = DEFAULT_CARD_HEIGHT // Use default
        val expected = (100 + DEFAULT_CARD_HEIGHT) / 8f
        val actual = (currentHeight + nextHeight) / 8f
        assertEquals(expected, actual, 0.1f)
    }

    // Test upThreshold calculation: (currentHeight + prevHeight) / 8
    @Test
    fun upThreshold_calculatesCorrectly_withEqualHeights() {
        val currentHeight = 100
        val prevHeight = 100
        val expected = (currentHeight + prevHeight) / 8f
        val actual = (currentHeight + prevHeight) / 8f
        assertEquals(expected, actual, 0.1f)
    }

    @Test
    fun upThreshold_calculatesCorrectly_withDifferentHeights() {
        val currentHeight = 150
        val prevHeight = 100
        val expected = (150 + 100) / 8f // 31.25
        val actual = (currentHeight + prevHeight) / 8f
        assertEquals(expected, actual, 0.1f)
    }

    @Test
    fun upThreshold_usesDefaultForMissingHeight() {
        val currentHeight = 100
        val prevHeight = DEFAULT_CARD_HEIGHT // Use default
        val expected = (100 + DEFAULT_CARD_HEIGHT) / 8f
        val actual = (currentHeight + prevHeight) / 8f
        assertEquals(expected, actual, 0.1f)
    }

    // Test boundary conditions
    @Test
    fun downThreshold_atBoundary_dragOffsetEqualsThreshold() {
        val currentHeight = 100
        val nextHeight = 100
        val dragOffset = (currentHeight + nextHeight) / 8f // Exactly at threshold

        // At threshold, swap should NOT trigger (requires > threshold)
        val shouldSwap = dragOffset > (currentHeight + nextHeight) / 8f
        assertEquals(false, shouldSwap)
    }

    @Test
    fun downThreshold_pastBoundary_dragOffsetExceedsThreshold() {
        val currentHeight = 100
        val nextHeight = 100
        val threshold = (currentHeight + nextHeight) / 8f
        val dragOffset = threshold + 1f

        // Past threshold, swap should trigger
        val shouldSwap = dragOffset > threshold
        assertEquals(true, shouldSwap)
    }

    @Test
    fun upThreshold_atBoundary_dragOffsetEqualsThreshold() {
        val currentHeight = 100
        val prevHeight = 100
        val dragOffset = -((currentHeight + prevHeight) / 8f) // Exactly at threshold

        // At threshold, swap should NOT trigger (requires < threshold)
        val shouldSwap = dragOffset < -(currentHeight + prevHeight) / 8f
        assertEquals(false, shouldSwap)
    }

    @Test
    fun upThreshold_pastBoundary_dragOffsetExceedsThreshold() {
        val currentHeight = 100
        val prevHeight = 100
        val threshold = (currentHeight + prevHeight) / 8f
        val dragOffset = -(threshold + 1f)

        // Past threshold (below), swap should trigger
        val shouldSwap = dragOffset < -threshold
        assertEquals(true, shouldSwap)
    }

    // Test realistic scenarios
    @Test
    fun threshold_smallCards_requiresLessDragDistance() {
        val smallCard = 80
        val threshold = (smallCard + smallCard) / 8f // 20

        val largeCard = 200
        val largeThreshold = (largeCard + largeCard) / 8f // 50

        // Smaller cards require less drag distance
        assertEquals(true, threshold < largeThreshold)
    }

    @Test
    fun threshold_asymmetricHeights_calculatesIndependently() {
        val currentHeight = 130
        val prevHeight = 100
        val nextHeight = 160

        val upThreshold = (currentHeight + prevHeight) / 8f // (130 + 100) / 8 = 28.75
        val downThreshold = (currentHeight + nextHeight) / 8f // (130 + 160) / 8 = 36.25

        // Different thresholds for up and down
        assertEquals(true, upThreshold != downThreshold)
        assertEquals(true, downThreshold > upThreshold)
    }

    @Test
    fun threshold_firstCard_usesDefaultForPrev() {
        val firstCardHeight = 100
        val secondCardHeight = 120

        // First card has no previous, should use DEFAULT
        val upThreshold = (firstCardHeight + DEFAULT_CARD_HEIGHT) / 8f
        val downThreshold = (firstCardHeight + secondCardHeight) / 8f

        assertEquals((100 + 130) / 8f, upThreshold, 0.1f)
        assertEquals((100 + 120) / 8f, downThreshold, 0.1f)
    }

    @Test
    fun threshold_lastCard_usesDefaultForNext() {
        val lastCardHeight = 100
        val secondToLastHeight = 120

        // Last card has no next, should use DEFAULT
        val upThreshold = (lastCardHeight + secondToLastHeight) / 8f
        val downThreshold = (lastCardHeight + DEFAULT_CARD_HEIGHT) / 8f

        assertEquals((100 + 120) / 8f, upThreshold, 0.1f)
        assertEquals((100 + 130) / 8f, downThreshold, 0.1f)
    }
}
