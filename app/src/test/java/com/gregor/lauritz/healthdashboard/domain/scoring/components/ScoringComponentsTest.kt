package com.gregor.lauritz.healthdashboard.domain.scoring.components

import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import org.junit.Test
import kotlin.test.assertEquals

class ScoringComponentsTest {
    // PhaseCalculator tests

    @Test
    fun phaseCalculator_0daysSinceInstall_returnsCalibration() {
        val phase = PhaseCalculator.calculatePhase(0)
        assertEquals(Phase.CALIBRATION, phase)
    }

    @Test
    fun phaseCalculator_7daysSinceInstall_returnsProvisional() {
        val phase = PhaseCalculator.calculatePhase(7)
        assertEquals(Phase.PROVISIONAL, phase)
    }

    @Test
    fun phaseCalculator_42daysSinceInstall_returnsMature() {
        val phase = PhaseCalculator.calculatePhase(42)
        assertEquals(Phase.MATURE, phase)
    }

    // SleepArchitectureTargetFactory tests

    @Test
    fun sleepArchitectureTargetFactory_age20_returnsAgeRange18To29() {
        val targets = SleepArchitectureTargetFactory.create(20, Gender.MALE)
        assertEquals(SleepArchitectureTargets.AgeRange18To29::class, targets::class)
    }

    @Test
    fun sleepArchitectureTargetFactory_age40Female_returnsAgeRange30To49() {
        val targets = SleepArchitectureTargetFactory.create(40, Gender.FEMALE)
        assertEquals(SleepArchitectureTargets.AgeRange30To49::class, targets::class)
    }

    @Test
    fun sleepArchitectureTargetFactory_age65_returnsAgeRange50To69() {
        val targets = SleepArchitectureTargetFactory.create(65, null)
        assertEquals(SleepArchitectureTargets.AgeRange50To69::class, targets::class)
    }
}
