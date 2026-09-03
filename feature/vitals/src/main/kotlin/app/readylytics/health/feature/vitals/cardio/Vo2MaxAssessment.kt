package app.readylytics.health.feature.vitals.cardio

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.scoring.domain.cardio.CooperCategory
import app.readylytics.health.core.scoring.domain.cardio.CooperNormsClassifier

/** Vitals-tab presentation of the day's Cardio Fitness (VO2 Max) card. */
@Immutable
data class Vo2MaxAssessment(
    val value: Float?,
    val source: String?,
    val status: MetricStatus,
    val category: CooperCategory?,
)

/**
 * Maps a [CooperCategory] onto the shared dashboard status ladder. SUPERIOR/EXCELLENT read as
 * [MetricStatus.OPTIMAL], GOOD as [MetricStatus.NEUTRAL], and FAIR/POOR as WARNING/POOR
 * respectively -- collapsing the 5-band Cooper scale onto the 4-band metric status scale used by
 * every other Vitals card.
 */
fun CooperCategory.toMetricStatus(): MetricStatus =
    when (this) {
        CooperCategory.SUPERIOR, CooperCategory.EXCELLENT -> MetricStatus.OPTIMAL
        CooperCategory.GOOD -> MetricStatus.NEUTRAL
        CooperCategory.FAIR -> MetricStatus.WARNING
        CooperCategory.POOR -> MetricStatus.POOR
    }

/**
 * Classifies [vo2Max] (already resolved by [app.readylytics.health.core.scoring.domain.cardio.Vo2MaxSourceResolver]
 * upstream) against the user's age/sex Cooper norms. Missing VO2 Max degrades to
 * [MetricStatus.CALIBRATING], matching the other Vitals assessments' "no value yet" treatment.
 */
fun assessVo2Max(
    vo2Max: Float?,
    source: String?,
    age: Int,
    gender: Gender?,
    classifier: CooperNormsClassifier = CooperNormsClassifier(),
): Vo2MaxAssessment {
    val category = vo2Max?.let { classifier.classify(it, age, gender ?: Gender.OTHER) }
    return Vo2MaxAssessment(
        value = vo2Max,
        source = source,
        status = category?.toMetricStatus() ?: MetricStatus.CALIBRATING,
        category = category,
    )
}
