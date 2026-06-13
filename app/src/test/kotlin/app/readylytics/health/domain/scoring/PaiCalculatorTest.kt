package app.readylytics.health.domain.scoring

import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PaiCalculatorTest {
    private val duration = 40f
    private val rhr = 60f
    private val hrMax = 190f
    private val hrAvg = 160f
    private val gender = Gender.fromString("Male")

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
        // HR = 64 (less than 60 + 5)
        val result = PaiCalculator.calculateDailyTrimp(duration, 64f, rhr, hrMax, gender)
        assertEquals(0f, result, 0.001f)
        // HR = 66 (more than 60 + 5)
        val resultAbove = PaiCalculator.calculateDailyTrimp(duration, 66f, rhr, hrMax, gender)
        assert(resultAbove > 0f)
    }

    @Test
    fun `calculateDailyTrimp matches Banister model for Men`() {
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
        val genderFemale = Gender.fromString("Female")
        // HRR = 130, HRr = 0.7692
        // a = 0.86, b = 1.67
        // Td = 40 * 0.7692 * 0.86 * exp(1.67 * 0.7692)
        // Td = 30.769 * 0.86 * exp(1.2845)
        // Td = 26.461 * 3.612 = 95.58
        val result = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, genderFemale)
        assertEquals(95.58f, result, 0.1f)
    }

    @Test
    fun `calculateDailyTrimp defaults to Male if gender is not Female`() {
        val resultNull = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, null)
        val resultOther = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, Gender.fromString("Other"))
        val resultMale = PaiCalculator.calculateDailyTrimp(duration, hrAvg, rhr, hrMax, Gender.fromString("Male"))
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
        val itrimB = 2.1f
        // HRr = 0.7692 — no PAI calibration factor in TRIMP (canonical Manzi 2009)
        // Td = 40 * 0.7692 * exp(2.1 * 0.7692) = 30.769 * 5.030 = 154.74
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
        assertEquals(154.74f, result, 0.5f)
    }

    @Test
    fun `calculateDailyTrimp matches Cheng model below LT`() {
        // hrAvg=160 < ltBpm=170 (below LT)
        // weight = 0.5 * (160-60) / (170-60) = 0.5 * 100/110 = 0.4545
        // Td = 40 * 0.4545 = 18.18
        val ltBpm = 170f
        val result =
            PaiCalculator.calculateDailyTrimp(
                duration,
                hrAvg,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.CHENG,
                ltBpm = ltBpm,
            )
        assertEquals(18.18f, result, 0.1f)
    }

    @Test
    fun `calculateDailyTrimp matches Cheng model above LT`() {
        // hrAvg=180 > ltBpm=170; sexFactor=0.64 (male)
        // f = (180-170)/(190-170) = 10/20 = 0.5
        // weight = 0.5 + 0.64 * 0.5 * exp(0.09 * 0.5) = 0.5 + 0.32 * exp(0.045) ≈ 0.8347
        // Td = 40 * 0.8347 = 33.39
        val hrAvgAboveLT = 180f
        val ltBpm = 170f
        val chengBeta = 0.09f
        val result =
            PaiCalculator.calculateDailyTrimp(
                duration,
                hrAvgAboveLT,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.CHENG,
                chengBeta = chengBeta,
                ltBpm = ltBpm,
            )
        assertEquals(33.39f, result, 0.1f)
    }

    @Test
    fun `calculateDailyTrimp Cheng is continuous at LT`() {
        // At hrAvg == ltBpm both branches must yield weight == 0.5
        val ltBpm = 170f
        val atLt =
            PaiCalculator.calculateDailyTrimp(
                duration,
                ltBpm,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.CHENG,
                ltBpm = ltBpm,
            )
        // expected: 40 * 0.5 = 20.0
        assertEquals(20.0f, atLt, 0.05f)
    }

    @Test
    fun `calculateDailyTrimp Cheng returns 0 when ltBpm not configured`() {
        val result =
            PaiCalculator.calculateDailyTrimp(
                duration,
                hrAvg,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.CHENG,
                ltBpm = 0f,
            )
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `calculateAllDayTrimp returns 0 for fewer than 2 samples`() {
        val empty = emptyList<ComputeWorkoutTrimpUseCase.HeartRateSample>()
        val single = listOf(sample(0, 160))
        assertEquals(0f, PaiCalculator.calculateAllDayTrimp(empty, hrMax, rhr, gender), 0.001f)
        assertEquals(0f, PaiCalculator.calculateAllDayTrimp(single, hrMax, rhr, gender), 0.001f)
    }

    @Test
    fun `calculateAllDayTrimp sums contiguous intervals within gap cap`() {
        // Two 4-minute intervals at 160 bpm, each interval contributes
        // calculateDailyTrimp(durationMinutes = 4, hrAvg = 160, ...)
        val samples =
            listOf(
                sample(0, 160),
                sample(4, 160),
                sample(8, 160),
            )
        val expectedPerInterval = PaiCalculator.calculateDailyTrimp(4f, 160f, rhr, hrMax, gender)
        val result = PaiCalculator.calculateAllDayTrimp(samples, hrMax, rhr, gender)
        assertEquals(expectedPerInterval * 2f, result, 0.01f)
    }

    @Test
    fun `calculateAllDayTrimp excludes intervals longer than the gap cap`() {
        // First interval is 4 minutes (counts), second is 10 minutes (off-wrist, excluded)
        val samples =
            listOf(
                sample(0, 160),
                sample(4, 160),
                sample(14, 160),
            )
        val expectedPerInterval = PaiCalculator.calculateDailyTrimp(4f, 160f, rhr, hrMax, gender)
        val result = PaiCalculator.calculateAllDayTrimp(samples, hrMax, rhr, gender)
        assertEquals(expectedPerInterval, result, 0.01f)
    }

    private fun sample(
        minuteOffset: Long,
        bpm: Int,
    ): ComputeWorkoutTrimpUseCase.HeartRateSample =
        ComputeWorkoutTrimpUseCase.HeartRateSample(
            timestamp = Instant.EPOCH.plusSeconds(minuteOffset * 60),
            bpm = bpm,
        )

    @Test
    fun `calculateDailyTrimp HRr clamped to 1 when HR exceeds HRmax`() {
        // hrAvg=230 > hrMax=190 → hrR should clamp to 1.0, not 2.08
        val result =
            PaiCalculator.calculateDailyTrimp(
                duration,
                230f,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.BANISTER,
            )
        val resultAtHrMax =
            PaiCalculator.calculateDailyTrimp(
                duration,
                hrMax,
                rhr,
                hrMax,
                gender,
                trimpModel = TrimpModel.BANISTER,
            )
        // Both should equal the result at HR=HRmax (clamped)
        assertEquals(resultAtHrMax, result, 0.001f)
    }
}
