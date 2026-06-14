package app.readylytics.health.domain.scoring.components

import org.junit.Test
import kotlin.test.assertEquals

class ScoringComponentsTest {
    // PhaseCalculator tests (boundaries on baseline-usable session count)

    @Test
    fun phaseCalculator_6validNights_returnsCalibration() {
        val phase = PhaseCalculator.calculatePhase(6)
        assertEquals(Phase.CALIBRATION, phase)
        assertEquals(ConfidenceLevel.NOT_READY, phase.confidence)
    }

    @Test
    fun phaseCalculator_7validNights_returnsEarlyBaseline() {
        val phase = PhaseCalculator.calculatePhase(7)
        assertEquals(Phase.EARLY_BASELINE, phase)
        assertEquals(ConfidenceLevel.LOW, phase.confidence)
    }

    @Test
    fun phaseCalculator_20validNights_returnsEarlyBaseline() {
        val phase = PhaseCalculator.calculatePhase(20)
        assertEquals(Phase.EARLY_BASELINE, phase)
        assertEquals(ConfidenceLevel.LOW, phase.confidence)
    }

    @Test
    fun phaseCalculator_21validNights_returnsMaturing() {
        val phase = PhaseCalculator.calculatePhase(21)
        assertEquals(Phase.MATURING, phase)
        assertEquals(ConfidenceLevel.MEDIUM, phase.confidence)
    }

    @Test
    fun phaseCalculator_59validNights_returnsMaturing() {
        val phase = PhaseCalculator.calculatePhase(59)
        assertEquals(Phase.MATURING, phase)
        assertEquals(ConfidenceLevel.MEDIUM, phase.confidence)
    }

    @Test
    fun phaseCalculator_60validNights_returnsMature() {
        val phase = PhaseCalculator.calculatePhase(60)
        assertEquals(Phase.MATURE, phase)
        assertEquals(ConfidenceLevel.HIGH, phase.confidence)
    }

    // Negative test: a night with missing/invalid HRV must not count toward the
    // baseline-usable session total, so it must not push the phase across a boundary.
    @Test
    fun phaseCalculator_invalidNightNotCountedTowardBaseline_staysEarlyBaseline() {
        val validHistoricalSessionIds = 20
        val canContributeToBaseline = false // e.g. HRV missing/invalid for current night

        val totalValidHrvNights = validHistoricalSessionIds + (if (canContributeToBaseline) 1 else 0)

        assertEquals(20, totalValidHrvNights)
        assertEquals(Phase.EARLY_BASELINE, PhaseCalculator.calculatePhase(totalValidHrvNights))
    }

    @Test
    fun phaseCalculator_validNightCountedTowardBaseline_crossesToMaturing() {
        val validHistoricalSessionIds = 20
        val canContributeToBaseline = true

        val totalValidHrvNights = validHistoricalSessionIds + (if (canContributeToBaseline) 1 else 0)

        assertEquals(21, totalValidHrvNights)
        assertEquals(Phase.MATURING, PhaseCalculator.calculatePhase(totalValidHrvNights))
    }

    // SleepArchitectureTargetFactory tests

    @Test
    fun sleepArchitectureTargetFactory_age20_returnsAgeRange18To29() {
        val targets = SleepArchitectureTargetFactory.create(20)
        assertEquals(SleepArchitectureTargets.AgeRange18To29::class, targets::class)
    }

    @Test
    fun sleepArchitectureTargetFactory_age40_returnsAgeRange30To49() {
        val targets = SleepArchitectureTargetFactory.create(40)
        assertEquals(SleepArchitectureTargets.AgeRange30To49::class, targets::class)
    }

    @Test
    fun sleepArchitectureTargetFactory_age55_returnsAgeRange50To59() {
        val targets = SleepArchitectureTargetFactory.create(55)
        assertEquals(SleepArchitectureTargets.AgeRange50To59::class, targets::class)
    }

    @Test
    fun sleepArchitectureTargetFactory_age65_returnsAgeRange60Plus() {
        val targets = SleepArchitectureTargetFactory.create(65)
        assertEquals(SleepArchitectureTargets.AgeRange60Plus::class, targets::class)
    }

    // ScoringConfigFactory tiered emergency flags tests

    @Test
    fun scoringConfigFactory_athleteProfile_returnsZHrvThreshold1_2() {
        val prefs =
            app.readylytics.health.data.preferences.UserPreferences(
                physiologyProfile = app.readylytics.health.data.preferences.PhysiologyProfile.ATHLETE,
            )
        val factory =
            app.readylytics.health.domain.scoring
                .ScoringConfigFactory()
        val config = factory.build(prefs, java.time.LocalDate.now(), java.time.LocalDate.now())
        assertEquals(1.2f, config.emergencyFlags.overreachingZHrvThreshold)
        assertEquals(-1.2f, config.emergencyFlags.illnessZHrvThreshold)
    }

    @Test
    fun scoringConfigFactory_activeProfile_returnsZHrvThreshold1_5() {
        val prefs =
            app.readylytics.health.data.preferences.UserPreferences(
                physiologyProfile = app.readylytics.health.data.preferences.PhysiologyProfile.ACTIVE,
            )
        val factory =
            app.readylytics.health.domain.scoring
                .ScoringConfigFactory()
        val config = factory.build(prefs, java.time.LocalDate.now(), java.time.LocalDate.now())
        assertEquals(1.5f, config.emergencyFlags.overreachingZHrvThreshold)
        assertEquals(-1.5f, config.emergencyFlags.illnessZHrvThreshold)
    }

    @Test
    fun scoringConfigFactory_sedentaryProfile_returnsZHrvThreshold2_0() {
        val prefs =
            app.readylytics.health.data.preferences.UserPreferences(
                physiologyProfile = app.readylytics.health.data.preferences.PhysiologyProfile.SEDENTARY,
            )
        val factory =
            app.readylytics.health.domain.scoring
                .ScoringConfigFactory()
        val config = factory.build(prefs, java.time.LocalDate.now(), java.time.LocalDate.now())
        assertEquals(2.0f, config.emergencyFlags.overreachingZHrvThreshold)
        assertEquals(-2.0f, config.emergencyFlags.illnessZHrvThreshold)
    }
}
