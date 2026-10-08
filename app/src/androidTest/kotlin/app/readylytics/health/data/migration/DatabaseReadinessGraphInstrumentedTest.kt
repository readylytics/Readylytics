package app.readylytics.health.data.migration

import android.os.StrictMode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.readylytics.health.MainActivity
import app.readylytics.health.core.database.data.migration.DatabaseReadinessGate
import app.readylytics.health.core.database.data.security.DeferredSqlCipherOpenHelper
import app.readylytics.health.core.database.data.security.SqlCipherKeyManager
import app.readylytics.health.core.database.di.DatabaseModule
import app.readylytics.health.core.model.domain.migration.DatabaseReadiness
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseReadinessGraphInstrumentedTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun databaseProvisionOnMainThreadIsIoFree() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val gate = mockk<DatabaseReadinessGate>(relaxed = true)
        every { gate.readiness } returns MutableStateFlow(DatabaseReadiness.Ready)
        val keyManager = mockk<SqlCipherKeyManager>(relaxed = true)
        val dummyFactory =
            SupportSQLiteOpenHelper.Factory { config ->
                val delegate = mockk<SupportSQLiteOpenHelper>(relaxed = true)
                DeferredSqlCipherOpenHelper(config) { delegate }
            }
        every { keyManager.getOrCreateFactory() } returns dummyFactory

        val diskViolations = mutableListOf<String>()
        val oldPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyListener(Runnable::run) { violation ->
                    diskViolations.add(violation.toString())
                }.build(),
        )

        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val db = DatabaseModule.provideDatabase(context, keyManager, gate)
                // db is built, not opened yet
            }
        } finally {
            StrictMode.setThreadPolicy(oldPolicy)
        }

        assertTrue(
            "Expected 0 disk violations during provideDatabase on main thread, but got: $diskViolations",
            diskViolations.isEmpty(),
        )
    }

    @Test
    fun activityLaunchesAndSettlesWhenReady() {
        composeTestRule.waitForIdle()

        // Wait for normal application content (Dashboard or Onboarding) to be displayed
        composeTestRule.waitUntil(timeoutMillis = 15000) {
            composeTestRule
                .onAllNodesWithText("Dashboard", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty() ||
                composeTestRule
                    .onAllNodesWithText("Get started", substring = true, ignoreCase = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }
}
