package app.readylytics.health.domain.circadian

import app.readylytics.health.data.preferences.PhysiologyProfile
import org.junit.Test
import kotlin.test.assertEquals

class CircadianStrategyTest {
    private val regularStrategy = RegularUserCircadianStrategy()
    private val shiftWorkerStrategy = ShiftWorkerCircadianStrategy()

    // RegularUserCircadianStrategy tests

    @Test
    fun regularStrategy_athleteProfile_returns20() {
        val threshold = regularStrategy.determineThreshold(PhysiologyProfile.ATHLETE, null)
        assertEquals(20, threshold)
    }

    @Test
    fun regularStrategy_activeProfile_returns30() {
        val threshold = regularStrategy.determineThreshold(PhysiologyProfile.ACTIVE, null)
        assertEquals(30, threshold)
    }

    @Test
    fun regularStrategy_generalProfile_returns30() {
        val threshold = regularStrategy.determineThreshold(PhysiologyProfile.GENERAL, null)
        assertEquals(30, threshold)
    }

    @Test
    fun regularStrategy_sedentaryProfile_returns45() {
        val threshold = regularStrategy.determineThreshold(PhysiologyProfile.SEDENTARY, null)
        assertEquals(45, threshold)
    }

    @Test
    fun regularStrategy_withOverride_ignoresProfile() {
        val threshold = regularStrategy.determineThreshold(PhysiologyProfile.ATHLETE, 60)
        assertEquals(60, threshold)
    }

    // ShiftWorkerCircadianStrategy tests

    @Test
    fun shiftWorkerStrategy_noOverride_returnsMaxValue() {
        val threshold = shiftWorkerStrategy.determineThreshold(PhysiologyProfile.SHIFT_WORKER, null)
        assertEquals(Int.MAX_VALUE, threshold)
    }

    @Test
    fun shiftWorkerStrategy_withOverride_returnsOverride() {
        val threshold = shiftWorkerStrategy.determineThreshold(PhysiologyProfile.SHIFT_WORKER, 45)
        assertEquals(45, threshold)
    }
}
