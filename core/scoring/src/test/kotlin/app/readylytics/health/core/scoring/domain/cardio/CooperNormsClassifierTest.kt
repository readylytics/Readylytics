package app.readylytics.health.core.scoring.domain.cardio

import app.readylytics.health.core.model.domain.preferences.Gender
import org.junit.Assert.assertEquals
import org.junit.Test

class CooperNormsClassifierTest {
    private val classifier = CooperNormsClassifier()

    @Test
    fun classifiesMaleAge25Correctly() {
        assertEquals(CooperCategory.SUPERIOR, classifier.classify(vo2Max = 58.0f, age = 25, sex = Gender.MALE))
        assertEquals(CooperCategory.EXCELLENT, classifier.classify(vo2Max = 49.0f, age = 25, sex = Gender.MALE))
        assertEquals(CooperCategory.GOOD, classifier.classify(vo2Max = 44.0f, age = 25, sex = Gender.MALE))
        assertEquals(CooperCategory.FAIR, classifier.classify(vo2Max = 39.0f, age = 25, sex = Gender.MALE))
        assertEquals(CooperCategory.POOR, classifier.classify(vo2Max = 32.0f, age = 25, sex = Gender.MALE))
    }

    @Test
    fun classifiesFemaleAge35Correctly() {
        assertEquals(CooperCategory.SUPERIOR, classifier.classify(vo2Max = 50.0f, age = 35, sex = Gender.FEMALE))
        assertEquals(CooperCategory.EXCELLENT, classifier.classify(vo2Max = 42.0f, age = 35, sex = Gender.FEMALE))
        assertEquals(CooperCategory.GOOD, classifier.classify(vo2Max = 36.0f, age = 35, sex = Gender.FEMALE))
        assertEquals(CooperCategory.FAIR, classifier.classify(vo2Max = 32.0f, age = 35, sex = Gender.FEMALE))
        assertEquals(CooperCategory.POOR, classifier.classify(vo2Max = 27.0f, age = 35, sex = Gender.FEMALE))
    }
}
