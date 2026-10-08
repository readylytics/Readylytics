package app.readylytics.health.feature.settings

import app.readylytics.health.core.model.domain.validation.SettingsValidators
import app.readylytics.health.core.model.domain.validation.ValidationResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HeartRateSettingsViewModelTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-01-01T10:30:00Z"), ZoneOffset.UTC)
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun birthdayValidation_validDate_returnsValid() =
        testScope.runTest {
            val result = SettingsValidators.birthdayDateRule(fixedClock).validate(LocalDate.of(1990, 6, 15))
            assertTrue(result is ValidationResult.Valid)
        }

    @Test
    fun birthdayValidation_futureDate_returnsInvalid() =
        testScope.runTest {
            val result = SettingsValidators.birthdayDateRule(fixedClock).validate(LocalDate.of(2026, 1, 2))
            assertTrue(result is ValidationResult.Invalid)
        }

    @Test
    fun birthdayValidation_tooOldDate_returnsInvalid() =
        testScope.runTest {
            val result = SettingsValidators.birthdayDateRule(fixedClock).validate(LocalDate.of(1899, 12, 31))
            assertTrue(result is ValidationResult.Invalid)
        }
}
