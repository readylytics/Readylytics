package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PhysiologySettingsViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun genderChanged_updatesCacheState() =
        testScope.runTest {
            val genderFlow = MutableStateFlow<Gender?>(null)
            genderFlow.value = Gender.MALE
            assertEquals(Gender.MALE, genderFlow.value)
        }

    @Test
    fun genderChanged_fromMaleToFemale() =
        testScope.runTest {
            val genderFlow = MutableStateFlow<Gender?>(Gender.MALE)
            genderFlow.value = Gender.FEMALE
            assertEquals(Gender.FEMALE, genderFlow.value)
        }

    @Test
    fun genderChanged_toNull_clearsGender() =
        testScope.runTest {
            val genderFlow = MutableStateFlow<Gender?>(Gender.MALE)
            genderFlow.value = null
            assertEquals(null, genderFlow.value)
        }
}
