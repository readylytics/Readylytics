package app.readylytics.health.domain.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateConstantsTest {

    @Test
    fun `MIN_HEART_RATE is 1`() {
        assertEquals(1, HeartRateConstants.MIN_HEART_RATE)
    }

    @Test
    fun `MAX_HEART_RATE is 220`() {
        assertEquals(220, HeartRateConstants.MAX_HEART_RATE)
    }

    @Test
    fun `MIN_HEART_RATE is less than MAX_HEART_RATE`() {
        assertTrue(HeartRateConstants.MIN_HEART_RATE < HeartRateConstants.MAX_HEART_RATE)
    }

    @Test
    fun `bounds match HEART_RATE_RULE - min and max accepted as Valid`() {
        // Validates consistency: HeartRateConstants and SettingsValidators.HEART_RATE_RULE
        // must agree on the valid range.
        assertEquals(
            ValidationResult.Valid,
            SettingsValidators.HEART_RATE_RULE.validate(HeartRateConstants.MIN_HEART_RATE.toString()),
        )
        assertEquals(
            ValidationResult.Valid,
            SettingsValidators.HEART_RATE_RULE.validate(HeartRateConstants.MAX_HEART_RATE.toString()),
        )
    }

    @Test
    fun `bounds match HEART_RATE_RULE - out-of-range values rejected`() {
        assertEquals(
            ValidationResult.Invalid("HR: 1–220 bpm"),
            SettingsValidators.HEART_RATE_RULE.validate("0"),
        )
        assertEquals(
            ValidationResult.Invalid("HR: 1–220 bpm"),
            SettingsValidators.HEART_RATE_RULE.validate("221"),
        )
    }
}
