package com.gregor.lauritz.healthdashboard.domain.dashboard

import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferences
import com.gregor.lauritz.healthdashboard.domain.model.DailySummary
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.SleepSessionSummary
import com.gregor.lauritz.healthdashboard.domain.util.ResourceProvider
import com.gregor.lauritz.healthdashboard.ui.dashboard.DashboardAction
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class GetDashboardDataUseCaseTest {
    private lateinit var resourceProvider: ResourceProvider
    private lateinit var workoutMetricsUseCase: GetWorkoutMetricsUseCase
    private lateinit var useCase: GetDashboardDataUseCase

    private val today: LocalDate = LocalDate.of(2025, 1, 15)

    private val basePrefs =
        UserPreferences(
            goalSleepHours = 8f,
            hrvOptimalThreshold = 0.95f,
            hrvWarningThreshold = 0.85f,
            rhrOptimalThreshold = 1.0f,
            rhrWarningThreshold = 1.1f,
            heightCm = 180f,
            age = 30,
            gender = Gender.MALE,
            unitSystem = UnitSystem.METRIC,
        )

    @Before
    fun setUp() {
        resourceProvider =
            mockk(relaxed = true) {
                every { getString(any()) } returns "tooltip"
                every { getString(any(), *anyVararg()) } returns "tooltip-formatted"
            }
        workoutMetricsUseCase = mockk(relaxed = true)
        every { workoutMetricsUseCase.invoke(any()) } returns
            GetWorkoutMetricsUseCase.WorkoutMetrics(strainRatioCard = null)
        useCase = GetDashboardDataUseCase(resourceProvider, workoutMetricsUseCase)
    }

    // Helpers
    private fun fullSummary(): DailySummary =
        DailySummary(
            date = today,
            nocturnalRhr = 55,
            nocturnalHrv = 70,
            hrvBaseline = 65,
            rhrRatio = 0.95f,
            restingHeartRate = 58,
            restingHrRatio = 0.97f,
            restingHrBaseline = 60,
            totalPai = 110f,
            sleepDurationMinutes = 480,
            weightKg = 75f,
            bodyFatPercent = 18f,
            bloodPressureSystolic = 118,
            bloodPressureDiastolic = 75,
            paiScore = 50f,
        )

    private fun sampleSleep(): SleepSessionSummary =
        SleepSessionSummary(
            efficiency = 90f,
            startTime = 1_700_000_000_000L,
            endTime = 1_700_028_000_000L,
        )

    // --- Empty data ---

    @Test
    fun returnsEmptyMapWhenSummaryNull() {
        val result = useCase(null, basePrefs, today, null, emptyList())
        assertTrue(result.cardDataMap.isEmpty())
    }

    @Test
    fun returnsSevenDayPaiBreakdownWhenSummaryNull() {
        val result = useCase(null, basePrefs, today, null, emptyList())
        assertEquals(7, result.paiDailyBreakdown.size)
    }

    @Test
    fun paiBreakdownAllZeroWhenNoSummaries() {
        val result = useCase(null, basePrefs, today, null, emptyList())
        assertTrue(result.paiDailyBreakdown.all { it.second == 0f })
    }

    @Test
    fun paiBreakdownReflectsProvidedSummaries() {
        val summaries =
            listOf(
                DailySummary(date = today, paiScore = 42f),
                DailySummary(date = today.minusDays(1), paiScore = 30f),
            )
        val result = useCase(null, basePrefs, today, null, summaries)
        assertEquals(42f, result.paiDailyBreakdown.last().second)
        assertEquals(30f, result.paiDailyBreakdown[result.paiDailyBreakdown.size - 2].second)
    }

    @Test
    fun paiBreakdownZeroForMissingDays() {
        val summaries = listOf(DailySummary(date = today, paiScore = 80f))
        val result = useCase(null, basePrefs, today, null, summaries)
        // Last is today (80), all others zero
        assertTrue(result.paiDailyBreakdown.dropLast(1).all { it.second == 0f })
    }

    // --- Happy path ---

    @Test
    fun emitsAllCardsWhenSummaryAndPrefsProvided() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertTrue(result.cardDataMap.containsKey(CardId.SLEEP_RHR))
        assertTrue(result.cardDataMap.containsKey(CardId.HRV))
        assertTrue(result.cardDataMap.containsKey(CardId.PAI_DAILY))
        assertTrue(result.cardDataMap.containsKey(CardId.SLEEP_DURATION))
        assertTrue(result.cardDataMap.containsKey(CardId.RESTING_HR))
        assertTrue(result.cardDataMap.containsKey(CardId.SLEEP_EFFICIENCY))
        assertTrue(result.cardDataMap.containsKey(CardId.WEIGHT))
        assertTrue(result.cardDataMap.containsKey(CardId.BODY_FAT))
        assertTrue(result.cardDataMap.containsKey(CardId.BLOOD_PRESSURE))
    }

    @Test
    fun sleepRhrCardShowsBpmUnit() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals("bpm", result.cardDataMap[CardId.SLEEP_RHR]?.unit)
    }

    @Test
    fun hrvCardShowsMsUnit() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals("ms", result.cardDataMap[CardId.HRV]?.unit)
    }

    @Test
    fun sleepRhrCardActionNavigatesSleep() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals(DashboardAction.NAVIGATE_SLEEP, result.cardDataMap[CardId.SLEEP_RHR]?.action)
    }

    @Test
    fun restingHrCardActionNavigatesRhr() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals(DashboardAction.NAVIGATE_RHR, result.cardDataMap[CardId.RESTING_HR]?.action)
    }

    @Test
    fun weightCardActionNavigatesWeight() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals(DashboardAction.NAVIGATE_WEIGHT, result.cardDataMap[CardId.WEIGHT]?.action)
    }

    @Test
    fun bodyFatCardActionNavigatesBodyFat() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals(DashboardAction.NAVIGATE_BODY_FAT, result.cardDataMap[CardId.BODY_FAT]?.action)
    }

    @Test
    fun bloodPressureCardActionNavigatesBloodPressure() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals(
            DashboardAction.NAVIGATE_BLOOD_PRESSURE,
            result.cardDataMap[CardId.BLOOD_PRESSURE]?.action,
        )
    }

    // --- Strain ratio inclusion ---

    @Test
    fun includesStrainRatioCardWhenWorkoutUseCaseProvidesOne() {
        every { workoutMetricsUseCase.invoke(any()) } returns
            GetWorkoutMetricsUseCase.WorkoutMetrics(
                strainRatioCard =
                    com.gregor.lauritz.healthdashboard.ui.dashboard.CardData(
                        title = "Strain Ratio",
                        value = "1.20",
                        unit = "",
                        status = MetricStatus.OPTIMAL,
                        tooltip = "x",
                    ),
            )
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertTrue(result.cardDataMap.containsKey(CardId.STRAIN_RATIO))
    }

    @Test
    fun omitsStrainRatioCardWhenNull() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertFalse(result.cardDataMap.containsKey(CardId.STRAIN_RATIO))
    }

    // --- Null / missing data on summary fields ---

    @Test
    fun sleepRhrCardShowsDashWhenNocturnalRhrNull() {
        val summary = fullSummary().copy(nocturnalRhr = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("—", result.cardDataMap[CardId.SLEEP_RHR]?.value)
    }

    @Test
    fun hrvCardShowsDashWhenNocturnalHrvNull() {
        val summary = fullSummary().copy(nocturnalHrv = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("—", result.cardDataMap[CardId.HRV]?.value)
    }

    @Test
    fun paiCardShowsDashWhenTotalPaiNull() {
        val summary = fullSummary().copy(totalPai = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("—", result.cardDataMap[CardId.PAI_DAILY]?.value)
    }

    @Test
    fun paiCardCalibratingWhenTotalPaiNull() {
        val summary = fullSummary().copy(totalPai = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.CALIBRATING, result.cardDataMap[CardId.PAI_DAILY]?.status)
    }

    @Test
    fun sleepDurationCardShowsDashWhenSleepDurationNull() {
        val summary = fullSummary().copy(sleepDurationMinutes = null)
        val result = useCase(summary, basePrefs, today, null, emptyList())
        assertEquals("—", result.cardDataMap[CardId.SLEEP_DURATION]?.value)
    }

    @Test
    fun sleepDurationCardFormatsHoursAndMinutes() {
        val summary = fullSummary().copy(sleepDurationMinutes = 465)
        val result = useCase(summary, basePrefs, today, null, emptyList())
        assertEquals("7h 45m", result.cardDataMap[CardId.SLEEP_DURATION]?.value)
    }

    @Test
    fun sleepDurationCardFormatsWholeHours() {
        val summary = fullSummary().copy(sleepDurationMinutes = 480)
        val result = useCase(summary, basePrefs, today, null, emptyList())
        assertEquals("8h", result.cardDataMap[CardId.SLEEP_DURATION]?.value)
    }

    @Test
    fun sleepDurationSecondaryTextSetWhenLastSessionPresent() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertNotNull(result.cardDataMap[CardId.SLEEP_DURATION]?.secondaryText)
    }

    @Test
    fun sleepDurationSecondaryTextNullWhenNoLastSession() {
        val result = useCase(fullSummary(), basePrefs, today, null, emptyList())
        assertNull(result.cardDataMap[CardId.SLEEP_DURATION]?.secondaryText)
    }

    // --- Sleep efficiency ---

    @Test
    fun sleepEfficiencyCalibratingWhenSessionNull() {
        val result = useCase(fullSummary(), basePrefs, today, null, emptyList())
        assertEquals(MetricStatus.CALIBRATING, result.cardDataMap[CardId.SLEEP_EFFICIENCY]?.status)
    }

    @Test
    fun sleepEfficiencyShowsDashWhenEfficiencyNull() {
        val session = sampleSleep().copy(efficiency = null)
        val result = useCase(fullSummary(), basePrefs, today, session, emptyList())
        assertEquals("—", result.cardDataMap[CardId.SLEEP_EFFICIENCY]?.value)
    }

    @Test
    fun sleepEfficiencyOptimalAt90Percent() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.OPTIMAL, result.cardDataMap[CardId.SLEEP_EFFICIENCY]?.status)
    }

    @Test
    fun sleepEfficiencyShowsPercentValue() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals("90%", result.cardDataMap[CardId.SLEEP_EFFICIENCY]?.value)
    }

    @Test
    fun sleepEfficiencyPoorBelow70() {
        val session = sampleSleep().copy(efficiency = 60f)
        val result = useCase(fullSummary(), basePrefs, today, session, emptyList())
        assertEquals(MetricStatus.POOR, result.cardDataMap[CardId.SLEEP_EFFICIENCY]?.status)
    }

    // --- Resting HR fallback ---

    @Test
    fun restingHrFallsBackToNocturnalRhrWhenRestingHrNull() {
        val summary = fullSummary().copy(restingHeartRate = null, nocturnalRhr = 52)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("52", result.cardDataMap[CardId.RESTING_HR]?.value)
    }

    @Test
    fun restingHrShowsDashWhenBothNull() {
        val summary = fullSummary().copy(restingHeartRate = null, nocturnalRhr = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("—", result.cardDataMap[CardId.RESTING_HR]?.value)
    }

    // --- Weight ---

    @Test
    fun weightCardShowsDashWhenWeightKgNull() {
        val summary = fullSummary().copy(weightKg = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("—", result.cardDataMap[CardId.WEIGHT]?.value)
    }

    @Test
    fun weightCardUsesKgWhenMetric() {
        val result =
            useCase(fullSummary(), basePrefs.copy(unitSystem = UnitSystem.METRIC), today, null, emptyList())
        assertEquals("kg", result.cardDataMap[CardId.WEIGHT]?.unit)
    }

    @Test
    fun weightCardUsesLbsWhenImperial() {
        val result =
            useCase(fullSummary(), basePrefs.copy(unitSystem = UnitSystem.IMPERIAL), today, null, emptyList())
        assertEquals("lbs", result.cardDataMap[CardId.WEIGHT]?.unit)
    }

    @Test
    fun weightCardNeutralStatusWhenHeightMissing() {
        val result =
            useCase(fullSummary(), basePrefs.copy(heightCm = null), today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.NEUTRAL, result.cardDataMap[CardId.WEIGHT]?.status)
    }

    @Test
    fun weightCardOptimalForNormalBmi() {
        // 75kg / 1.80^2 = 23.1 BMI → Optimal
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.OPTIMAL, result.cardDataMap[CardId.WEIGHT]?.status)
    }

    @Test
    fun weightCardWarningForObeseBmi() {
        // 100kg / 1.70^2 ≈ 34.6 BMI → Warning
        val summary = fullSummary().copy(weightKg = 100f)
        val result =
            useCase(summary, basePrefs.copy(heightCm = 170f), today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.WARNING, result.cardDataMap[CardId.WEIGHT]?.status)
    }

    // --- Body fat ---

    @Test
    fun bodyFatCardShowsDashWhenPercentNull() {
        val summary = fullSummary().copy(bodyFatPercent = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("—", result.cardDataMap[CardId.BODY_FAT]?.value)
    }

    @Test
    fun bodyFatCardCalibratingWhenGenderMissing() {
        val result =
            useCase(fullSummary(), basePrefs.copy(gender = null), today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.CALIBRATING, result.cardDataMap[CardId.BODY_FAT]?.status)
    }

    @Test
    fun bodyFatCardOptimalForLowPercent() {
        val summary = fullSummary().copy(bodyFatPercent = 15f)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.OPTIMAL, result.cardDataMap[CardId.BODY_FAT]?.status)
    }

    @Test
    fun bodyFatCardPoorForHighPercent() {
        val summary = fullSummary().copy(bodyFatPercent = 40f)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.POOR, result.cardDataMap[CardId.BODY_FAT]?.status)
    }

    // --- Blood pressure ---

    @Test
    fun bloodPressureCardShowsDashWhenSystolicMissing() {
        val summary = fullSummary().copy(bloodPressureSystolic = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("—", result.cardDataMap[CardId.BLOOD_PRESSURE]?.value)
    }

    @Test
    fun bloodPressureCardShowsDashWhenDiastolicMissing() {
        val summary = fullSummary().copy(bloodPressureDiastolic = null)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals("—", result.cardDataMap[CardId.BLOOD_PRESSURE]?.value)
    }

    @Test
    fun bloodPressureCardOptimalForNormalReadings() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.OPTIMAL, result.cardDataMap[CardId.BLOOD_PRESSURE]?.status)
    }

    @Test
    fun bloodPressureCardWarningForStage1() {
        val summary = fullSummary().copy(bloodPressureSystolic = 135, bloodPressureDiastolic = 85)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.WARNING, result.cardDataMap[CardId.BLOOD_PRESSURE]?.status)
    }

    @Test
    fun bloodPressureCardPoorForStage2() {
        val summary = fullSummary().copy(bloodPressureSystolic = 160, bloodPressureDiastolic = 100)
        val result = useCase(summary, basePrefs, today, sampleSleep(), emptyList())
        assertEquals(MetricStatus.POOR, result.cardDataMap[CardId.BLOOD_PRESSURE]?.status)
    }

    @Test
    fun bloodPressureCardFormatsSystolicSlashDiastolic() {
        val result = useCase(fullSummary(), basePrefs, today, sampleSleep(), emptyList())
        assertEquals("118/75", result.cardDataMap[CardId.BLOOD_PRESSURE]?.value)
    }

    // --- formatSleepDuration helper ---

    @Test
    fun formatSleepDurationReturnsDashForNull() {
        assertEquals("—", useCase.formatSleepDuration(null))
    }

    @Test
    fun formatSleepDurationHoursOnlyWhenWholeHours() {
        assertEquals("8h", useCase.formatSleepDuration(480))
    }

    @Test
    fun formatSleepDurationHoursAndMinutes() {
        assertEquals("7h 30m", useCase.formatSleepDuration(450))
    }

    @Test
    fun formatSleepDurationZeroMinutes() {
        assertEquals("0h", useCase.formatSleepDuration(0))
    }
}
