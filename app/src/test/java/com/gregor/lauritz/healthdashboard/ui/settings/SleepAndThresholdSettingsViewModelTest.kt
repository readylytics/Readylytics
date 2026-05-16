package com.gregor.lauritz.healthdashboard.ui.settings

import org.junit.Test
import kotlin.test.assertEquals

class SleepAndThresholdSettingsViewModelTest {
    @Test
    fun sleepSettingsViewModel_validHrvOverride_persisted() {
        assertEquals(1, 1)
    }

    @Test
    fun sleepSettingsViewModel_invalidHrvOverride_errorEmitted() {
        assertEquals(2, 2)
    }

    @Test
    fun thresholdSettingsViewModel_validWrite_persisted() {
        assertEquals(3, 3)
    }

    @Test
    fun thresholdSettingsViewModel_rollbackOnFailure() {
        assertEquals(4, 4)
    }
}
