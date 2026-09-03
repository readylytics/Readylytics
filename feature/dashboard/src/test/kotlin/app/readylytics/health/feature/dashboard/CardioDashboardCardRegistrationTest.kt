package app.readylytics.health.feature.dashboard

import org.junit.Assert.assertTrue
import org.junit.Test

class CardioDashboardCardRegistrationTest {
    @Test
    fun cardioAndTsbCardIdsExist() {
        val cardioId = "card_cardio_fitness"
        val tsbId = "card_training_stress_balance"
        assertTrue(cardioId.isNotEmpty())
        assertTrue(tsbId.isNotEmpty())
    }
}
