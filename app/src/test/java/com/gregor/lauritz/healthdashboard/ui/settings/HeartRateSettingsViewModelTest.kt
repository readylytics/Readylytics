package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HeartRateSettingsViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun birthdayValidation_allFieldsEmpty_doesNotFireEvent() =
        testScope.runTest {
            val dayValid = SettingsValidators.BIRTHDAY_DAY_RULE.validate("") is ValidationResult.Valid
            val monthValid = SettingsValidators.BIRTHDAY_MONTH_RULE.validate("") is ValidationResult.Valid
            val yearValid = SettingsValidators.BIRTHDAY_YEAR_RULE.validate("") is ValidationResult.Valid

            assertTrue(dayValid && monthValid && yearValid)
        }

    @Test
    fun birthdayValidation_invalidDay_preventsEventFiring() =
        testScope.runTest {
            val dayResult = SettingsValidators.BIRTHDAY_DAY_RULE.validate("32")
            assertTrue(dayResult is ValidationResult.Invalid)
        }

    @Test
    fun birthdayValidation_invalidMonth_preventsEventFiring() =
        testScope.runTest {
            val monthResult = SettingsValidators.BIRTHDAY_MONTH_RULE.validate("13")
            assertTrue(monthResult is ValidationResult.Invalid)
        }

    @Test
    fun birthdayValidation_allFieldsValid_firesEvent() =
        testScope.runTest {
            val dayValid = SettingsValidators.BIRTHDAY_DAY_RULE.validate("15") is ValidationResult.Valid
            val monthValid = SettingsValidators.BIRTHDAY_MONTH_RULE.validate("6") is ValidationResult.Valid
            val yearValid = SettingsValidators.BIRTHDAY_YEAR_RULE.validate("1990") is ValidationResult.Valid

            assertTrue(dayValid && monthValid && yearValid)
        }
}
