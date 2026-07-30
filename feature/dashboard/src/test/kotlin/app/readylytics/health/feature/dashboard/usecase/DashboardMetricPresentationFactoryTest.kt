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
}
