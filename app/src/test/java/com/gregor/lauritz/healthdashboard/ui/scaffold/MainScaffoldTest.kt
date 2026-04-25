package com.gregor.lauritz.healthdashboard.ui.scaffold

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `navigation bar items enabled when not resyncing`() {
        composeRule.setContent {
            MainScaffold(isResyncingInProgress = false)
        }

        composeRule.onAllNodesWithContentDescription("Dashboard").onFirst()
            .assertIsEnabled()
    }

    @Test
    fun `navigation bar items disabled when resyncing`() {
        composeRule.setContent {
            MainScaffold(isResyncingInProgress = true)
        }

        composeRule.onAllNodesWithContentDescription("Dashboard").onFirst()
            .assertIsNotEnabled()
    }
}
