package com.gregor.lauritz.healthdashboard.ui.scaffold

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `navigation bar items exist`() {
        composeRule.setContent {
            MainScaffold()
        }

        // Check if at least one tab exists (Dashboard is usually the first)
        composeRule.onAllNodesWithContentDescription("Dashboard", substring = true).onFirst()
            .assertIsEnabled()
    }
}
