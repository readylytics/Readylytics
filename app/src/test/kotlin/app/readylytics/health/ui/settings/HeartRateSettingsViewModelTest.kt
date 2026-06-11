package app.readylytics.health.ui.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import app.readylytics.health.domain.validation.SettingsValidators
import app.readylytics.health.domain.validation.ValidationResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class HeartRateSettingsViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun birthdayValidation_validDate_returnsValid() =
        testScope.runTest {
            val result = SettingsValidators.BIRTHDAY_DATE_RULE.validate(LocalDate.of(1990, 6, 15))
            assertTrue(result is ValidationResult.Valid)
        }

    @Test
    fun birthdayValidation_futureDate_returnsInvalid() =
        testScope.runTest {
            val result = SettingsValidators.BIRTHDAY_DATE_RULE.validate(LocalDate.now().plusDays(1))
            assertTrue(result is ValidationResult.Invalid)
        }

    @Test
    fun birthdayValidation_tooOldDate_returnsInvalid() =
        testScope.runTest {
            val result = SettingsValidators.BIRTHDAY_DATE_RULE.validate(LocalDate.of(1899, 12, 31))
            assertTrue(result is ValidationResult.Invalid)
        }
}
