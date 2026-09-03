package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.preferences.Gender
import javax.inject.Inject
import javax.inject.Singleton

enum class CooperCategory { SUPERIOR, EXCELLENT, GOOD, FAIR, POOR }

@Singleton
class CooperNormsClassifier @Inject constructor() {
    fun classify(vo2Max: Float, age: Int, sex: Gender): CooperCategory {
        val thresholds = getThresholds(age, sex)
        return when {
            vo2Max >= thresholds.superior -> CooperCategory.SUPERIOR
            vo2Max >= thresholds.excellent -> CooperCategory.EXCELLENT
            vo2Max >= thresholds.good -> CooperCategory.GOOD
            vo2Max >= thresholds.fair -> CooperCategory.FAIR
            else -> CooperCategory.POOR
        }
    }

    data class Thresholds(val superior: Float, val excellent: Float, val good: Float, val fair: Float)

    private fun getThresholds(age: Int, sex: Gender): Thresholds {
        return when (sex) {
            Gender.MALE -> when {
                age < 30 -> Thresholds(superior = 52.5f, excellent = 46.5f, good = 42.5f, fair = 36.5f)
                age < 40 -> Thresholds(superior = 50.5f, excellent = 44.5f, good = 40.5f, fair = 35.5f)
                age < 50 -> Thresholds(superior = 48.5f, excellent = 42.5f, good = 38.5f, fair = 33.5f)
                age < 60 -> Thresholds(superior = 44.5f, excellent = 38.5f, good = 34.5f, fair = 30.5f)
                else -> Thresholds(superior = 40.5f, excellent = 34.5f, good = 30.5f, fair = 26.5f)
            }
            Gender.FEMALE -> when {
                age < 30 -> Thresholds(superior = 44.5f, excellent = 38.5f, good = 34.5f, fair = 28.5f)
                age < 40 -> Thresholds(superior = 42.5f, excellent = 36.5f, good = 32.5f, fair = 27.5f)
                age < 50 -> Thresholds(superior = 40.5f, excellent = 34.5f, good = 30.5f, fair = 25.5f)
                age < 60 -> Thresholds(superior = 36.5f, excellent = 30.5f, good = 26.5f, fair = 22.5f)
                else -> Thresholds(superior = 32.5f, excellent = 26.5f, good = 22.5f, fair = 19.5f)
            }
            Gender.OTHER, Gender.PREFER_NOT_TO_SAY -> when {
                age < 30 -> Thresholds(superior = 48.5f, excellent = 42.5f, good = 38.5f, fair = 32.5f)
                age < 40 -> Thresholds(superior = 46.5f, excellent = 40.5f, good = 36.5f, fair = 31.5f)
                age < 50 -> Thresholds(superior = 44.5f, excellent = 38.5f, good = 34.5f, fair = 29.5f)
                age < 60 -> Thresholds(superior = 40.5f, excellent = 34.5f, good = 30.5f, fair = 26.5f)
                else -> Thresholds(superior = 36.5f, excellent = 30.5f, good = 26.5f, fair = 23.0f)
            }
        }
    }
}
