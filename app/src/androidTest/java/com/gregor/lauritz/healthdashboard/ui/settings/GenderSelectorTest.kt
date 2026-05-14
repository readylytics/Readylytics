package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.data.preferences.Gender
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenderSelectorTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun genderSelector_displaysNotSet_whenGenderIsNull() {
        composeTestRule.setContent {
            Surface {
                GenderSelector(
                    selectedGender = null,
                    expanded = false,
                    onExpandedChange = {},
                    onGenderSelected = {},
                )
            }
        }

        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun genderSelector_displaysMale_whenGenderIsMale() {
        composeTestRule.setContent {
            Surface {
                GenderSelector(
                    selectedGender = Gender.MALE,
                    expanded = false,
                    onExpandedChange = {},
                    onGenderSelected = {},
                )
            }
        }

        composeTestRule.onRoot().assertExists()
    }

    @Test
    fun genderSelector_displaysFemale_whenGenderIsFemale() {
        composeTestRule.setContent {
            Surface {
                GenderSelector(
                    selectedGender = Gender.FEMALE,
                    expanded = false,
                    onExpandedChange = {},
                    onGenderSelected = {},
                )
            }
        }

        composeTestRule.onRoot().assertExists()
    }
}
