package app.readylytics.health.ui.scaffold

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScaffoldTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun navigationBarItemsExist() {
        // Check if at least one tab exists (Dashboard is usually the first)
        composeRule
            .onAllNodesWithContentDescription("Dashboard", substring = true)
            .onFirst()
            .assertIsEnabled()
    }
}
