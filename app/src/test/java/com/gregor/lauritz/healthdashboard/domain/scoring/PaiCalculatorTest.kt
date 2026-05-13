package com.gregor.lauritz.healthdashboard.domain.scoring

import com.gregor.lauritz.healthdashboard.data.preferences.PhysiologyProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class PaiCalculatorTest {
    @Test
    fun `getDefaultPaiScalingFactor returns correct values for all profiles`() {
        assertEquals(0.15f, PaiCalculator.getDefaultPaiScalingFactor(PhysiologyProfile.ATHLETE), 0.001f)
        assertEquals(0.18f, PaiCalculator.getDefaultPaiScalingFactor(PhysiologyProfile.ACTIVE), 0.001f)
        assertEquals(0.20f, PaiCalculator.getDefaultPaiScalingFactor(PhysiologyProfile.GENERAL), 0.001f)
        assertEquals(0.25f, PaiCalculator.getDefaultPaiScalingFactor(PhysiologyProfile.SEDENTARY), 0.001f)
        assertEquals(0.20f, PaiCalculator.getDefaultPaiScalingFactor(PhysiologyProfile.SHIFT_WORKER), 0.001f)
    }

    @Test
    fun `calculateDailyTrimp excludes samples below RHR plus 5`() {
        val duration = 40f
        val rhr = 60f
        val hrMax = 190f
        val gender = "Male"

        // HR = 64 (less than 60 + 5)
        val result = PaiCalculator.calculateDailyTrimp(duration, 64f, rhr, hrMax, gender)
        assertEquals(0f, result, 0.001f)

        // HR = 66 (more than 60 + 5)
        val resultAbove = PaiCalculator.calculateDailyTrimp(duration, 66f, rhr, hrMax, gender)
        assert(resultAbove > 0f)
    }

    @Test
    fun `calculateDailyTrimp matches Banister model for Men`() {
        val duration = 40f
        val hrAvg = 160f
        val rhr = 60f
        val hrMax = 190f
        val gender = "Male"

        // HRR = 190 - 60 = 130
        // HRr = (160 - 60) / 130 = 100 / 130 = 0.7692
        // a = 0.64, b = 1.92
        // Td = 40 * 0.7692 * 0.64 * exp(1.92 * 0.7692)
        // Td = 30.769 * 0.64 * exp(1.4768)
        // Td = 19.692 * 4.3789 = 86.23

        val result = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, gender)
        assertEquals(86.23f, result, 0.1f)
    }

    @Test
    fun `calculateDailyTrimp matches Banister model for Women`() {
        val duration = 40f
        val hrAvg = 160f
        val rhr = 60f
        val hrMax = 190f
        val gender = "Female"

        // HRR = 130, HRr = 0.7692
        // a = 0.86, b = 1.67
        // Td = 40 * 0.7692 * 0.86 * exp(1.67 * 0.7692)
        // Td = 30.769 * 0.86 * exp(1.2845)
        // Td = 26.461 * 3.612 = 95.58

        val result = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, gender)
        assertEquals(95.58f, result, 0.1f)
    }

    @Test
    fun `calculateDailyTrimp defaults to Male if gender is not Female`() {
        val duration = 40f
        val hrAvg = 160f
        val rhr = 60f
        val hrMax = 190f

        val resultNull = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, null)
        val resultOther = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, "Other")
        val resultMale = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, "Male")

        assertEquals(resultMale, resultNull, 0.001f)
        assertEquals(resultMale, resultOther, 0.001f)
    }

    @Test
    fun `calculateDailyPai respects daily cap`() {
        val trimp = 1000f
        val scalingFactor = 0.2f
        // 1000 * 0.2 = 200 -> capped at 75

        val result = PaiCalculator.calculateDailyPai(trimp, scalingFactor)
        assertEquals(75f, result, 0.001f)
    }

    @Test
    fun `applyAccumulationMultiplier applies correct multipliers within a tier`() {
        val dailyPai = 10f

        assertEquals(10f, PaiCalculator.applyAccumulationMultiplier(dailyPai, 40f), 0.001f) // tier 1: 10×1.0
        assertEquals(5f, PaiCalculator.applyAccumulationMultiplier(dailyPai, 60f), 0.001f) // tier 2: 10×0.5
        assertEquals(2.5f, PaiCalculator.applyAccumulationMultiplier(dailyPai, 110f), 0.001f) // tier 3: 10×0.25
    }

    @Test
    fun `applyAccumulationMultiplier splits PAI across tier boundaries`() {
        // Start=45, daily=30: 5 in tier1(×1.0)=5, 25 in tier2(×0.5)=12.5 → 17.5
        assertEquals(17.5f, PaiCalculator.applyAccumulationMultiplier(30f, 45f), 0.001f)

        // Start=90, daily=30: 10 in tier2(×0.5)=5, 20 in tier3(×0.25)=5 → 10.0
        assertEquals(10f, PaiCalculator.applyAccumulationMultiplier(30f, 90f), 0.001f)

        // Start=45, daily=60: 5 in tier1=5, 50 in tier2=25, 5 in tier3=1.25 → 31.25
        assertEquals(31.25f, PaiCalculator.applyAccumulationMultiplier(60f, 45f), 0.001f)
    }

    @Test
    fun `calculateDailyTrimp handles mixed heart rate sources correctly`() {
        val duration = 40f
        val hrMax = 190f
        val rhr = 60f
        val gender = "Male"

        // Scenario: Some samples are high intensity (exercise), some are low (resting)
        // High intensity: 160 bpm -> TRIMP = 86.23 (as seen in other test)
        val exerciseTrimp = PaiCalculator.calculateDailyTrimp(duration, 160f, rhr, hrMax, gender)

        // Low intensity: 64 bpm -> TRIMP = 0 (below RHR + 5 threshold)
        val restingTrimp = PaiCalculator.calculateDailyTrimp(duration, 64f, rhr, hrMax, gender)

        assertEquals(0f, restingTrimp, 0.001f)
        assert(exerciseTrimp > 0f)
    }

    @Test
    fun `calculateDailyTrimp matches iTRIMP model`() {
        val duration = 40f
        val hrAvg = 160f
        val rhr = 60f
        val hrMax = 190f
        val gender = "Male"
        val itrimB = 2.1f

        // HRr = 0.7692
        // Td = 40 * 0.7692 * exp(2.1 * 0.7692) * 0.48
        // Td = 30.769 * 5.0295 * 0.48 = 74.28

        val result =
            PaiCalculator.calculateDailyTrimp(
                duration,
                hrAvg,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.I_TRIMP,
                itrimB = itrimB,
            )
        assertEquals(74.28f, result, 0.1f)
    }

    @Test
    fun `calculateDailyTrimp matches Cheng model below LT`() {
        val duration = 40f
        val hrAvg = 160f
        val rhr = 60f
        val hrMax = 190f
        val gender = "Male"

        // HRr = 0.7692 (Below LT=0.85)
        // Td = 40 * 0.7692 * 0.36 * 3.2 = 35.45

        val result =
            PaiCalculator.calculateDailyTrimp(
                duration,
                hrAvg,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.CHENG,
            )
        assertEquals(35.45f, result, 0.1f)
    }

    @Test
    fun `calculateDailyTrimp matches Cheng model above LT`() {
        val duration = 40f
        val hrAvg = 180f
        val rhr = 60f
        val hrMax = 190f
        val gender = "Male"
        val chengBeta = 0.09f

        // HRr = 0.9231 (Above LT=0.85)
        // Td = 40 * 0.9231 * 0.72 * exp(0.09 * 0.9231) * 3.2
        // Td = 26.585 * 1.0866 * 3.2 = 92.44

        val result =
            PaiCalculator.calculateDailyTrimp(
                duration,
                hrAvg,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.CHENG,
                chengBeta = chengBeta,
            )
        assertEquals(92.44f, result, 0.1f)
    }
}
