package app.readylytics.health.feature.settings.nav

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsHomeScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun typingAQuery_showsMatchingResult_andTappingItInvokesCallback() {
        var selectedCategory: SettingsCategoryId? = null
        composeTestRule.setContent {
            Surface {
                var query by remember { mutableStateOf("") }
                SettingsHomeScreen(
                    searchQuery = query,
                    onSearchQueryChanged = { query = it },
                    onCategorySelected = {},
                    onSearchResultSelected = { selectedCategory = it.categoryId },
                )
            }
        }

        composeTestRule.onNodeWithText("Search settings…", substring = true).performTextInput("week start")
        composeTestRule.onNodeWithText("Start of week", substring = true).performClick()

        assertEquals(SettingsCategoryId.DISPLAY, selectedCategory)
    }
}
