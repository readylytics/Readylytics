package app.readylytics.health.feature.vitals.cardio

import androidx.compose.runtime.Immutable
import app.readylytics.health.core.model.domain.model.MetricStatus
import app.readylytics.health.core.model.domain.preferences.Gender
import app.readylytics.health.core.scoring.domain.cardio.CooperCategory
import app.readylytics.health.core.scoring.domain.cardio.CooperNormsClassifier
import app.readylytics.health.core.scoring.domain.cardio.toMetricStatus

/** Vitals-tab presentation of the day's Cardio Fitness (VO2 Max) card. */
@Immutable
data class Vo2MaxAssessment(
    val value: Float?,
    val source: String?,
    val status: MetricStatus,
    val category: CooperCategory?,
)

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
    classifier: CooperNormsClassifier,
): Vo2MaxAssessment {
    val category = vo2Max?.let { classifier.classify(it, age, gender ?: Gender.OTHER) }
    return Vo2MaxAssessment(
        value = vo2Max,
        source = source,
        status = category?.toMetricStatus() ?: MetricStatus.NO_DATA,
        category = category,
    )
}
