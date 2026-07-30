package app.readylytics.health.feature.dashboard.usecase

import app.readylytics.health.domain.util.ResourceProvider
import app.readylytics.health.data.preferences.Gender
import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.dashboard.GetWorkoutMetricsUseCase
import app.readylytics.health.domain.model.DailySummary
import app.readylytics.health.domain.model.MetricStatus
import app.readylytics.health.domain.dashboard.CardId
import app.readylytics.health.feature.dashboard.DashboardMetricVisual
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DashboardMetricPresentationFactoryTest {

    private lateinit var factory: DashboardMetricPresentationFactory
    private lateinit var resourceProvider: ResourceProvider
    private lateinit var getWorkoutMetricsUseCase: GetWorkoutMetricsUseCase

    @Before
    fun setup() {
        resourceProvider = mockk(relaxed = true)
        getWorkoutMetricsUseCase = mockk(relaxed = true)
        factory = DashboardMetricPresentationFactory(resourceProvider, getWorkoutMetricsUseCase)
        every { resourceProvider.getString(any()) } returns "mock_string"
        every { resourceProvider.getString(any(), any()) } returns "BMI mock_string"
    }

    private fun summary(weightKg: Float? = null, bodyFatPercent: Float? = null) = DailySummary(
        date = date,
        weightKg = weightKg,
        bodyFatPercent = bodyFatPercent
    )

    private fun preferences(
        heightCm: Float = 180f,
        gender: Gender = Gender.MALE,
        physiologyProfile: PhysiologyProfile = PhysiologyProfile.ACTIVE
    ) = UserPreferences(
        heightCm = heightCm,
        gender = gender,
        physiologyProfile = physiologyProfile
    )

    private val date = LocalDate.now()

    @Test
    fun `sleep score and readiness share score thresholds`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val sleep = cards.getValue(CardId.SLEEP_SCORE).visual as DashboardMetricVisual.Score
        val readiness = cards.getValue(CardId.READINESS).visual as DashboardMetricVisual.Score
        assertEquals(sleep.bands, readiness.bands)
        assertEquals(0f, sleep.minValue)
        assertEquals(100f, sleep.maxValue)
    }

    @Test
    fun `weight keeps real value and positions bmi around 21 point 7`() {
        val cards = factory.build(
            summary(weightKg = 66.44f),
            preferences(heightCm = 175f),
            date,
            null,
            null,
            null,
        )
        val card = cards.getValue(CardId.WEIGHT)
        val visual = card.visual as DashboardMetricVisual.ReferenceRange
        assertEquals(0.5f, visual.referenceMarkerFraction)
        assertTrue(card.secondaryText.orEmpty().contains("BMI"))
    }

    @Test
    fun `body fat midpoint depends on profile and gender`() {
        val cards = factory.build(
            summary(bodyFatPercent = 9.5f),
            preferences(
                gender = Gender.MALE,
                physiologyProfile = PhysiologyProfile.ATHLETE,
            ),
            date,
            null,
            null,
            null,
        )
        val visual = cards.getValue(CardId.BODY_FAT).visual as DashboardMetricVisual.ReferenceRange
        assertEquals(0.5f, visual.markerFraction)
    }

    @Test
    fun `sleep duration target uses goal sleep hours`() {
        val cards = factory.build(summary().copy(sleepDurationMinutes = 450), preferences().copy(goalSleepHours = 8f), date, null, null, null)
        val visual = cards.getValue(CardId.SLEEP_DURATION).visual as DashboardMetricVisual.Goal
        assertEquals(480f, visual.targetValue)
        assertEquals(450f, visual.rawValue)
    }

    @Test
    fun `ras permits overflow beyond 100`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.RAS_DAILY).visual as DashboardMetricVisual.Score
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `circadian score bounds are 0 to 100`() {
        val circResult = app.readylytics.health.domain.scoring.CircadianConsistencyResult.Ready(85f, 0, 0, 0, 0)
        val cards = factory.build(summary(), preferences(), date, null, circResult, null)
        val visual = cards.getValue(CardId.CIRCADIAN_CONSISTENCY).visual as DashboardMetricVisual.Score
        assertEquals(0f, visual.minValue)
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `sleep efficiency uses 0 to 100 bounds`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.SLEEP_EFFICIENCY).visual as DashboardMetricVisual.Score
        assertEquals(0f, visual.minValue)
        assertEquals(100f, visual.maxValue)
    }
    
    @Test
    fun `spo2 uses 80 to 100 bounds`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.OXYGEN_SATURATION).visual as DashboardMetricVisual.Score
        assertEquals(80f, visual.minValue)
        assertEquals(100f, visual.maxValue)
    }

    @Test
    fun `hrv uses baseline scale`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.HRV).visual as DashboardMetricVisual.PersonalBaseline
        assertNull(visual.ratio)
    }

    @Test
    fun `rhr uses baseline scale`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.RESTING_HR).visual as DashboardMetricVisual.PersonalBaseline
        assertNull(visual.ratio)
    }

    @Test
    fun `strain ratio uses bands from 0 to 2`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        val visual = cards.getValue(CardId.STRAIN_RATIO).visual as DashboardMetricVisual.Score
        assertEquals(0f, visual.minValue)
        assertEquals(2f, visual.maxValue)
    }

    @Test
    fun `heart rate and blood pressure are value only`() {
        val cards = factory.build(summary(), preferences(), date, null, null, null)
        assertTrue(cards.getValue(CardId.HEART_RATE).visual is DashboardMetricVisual.ValueOnly)
        assertTrue(cards.getValue(CardId.BLOOD_PRESSURE).visual is DashboardMetricVisual.ValueOnly)
    }
}
