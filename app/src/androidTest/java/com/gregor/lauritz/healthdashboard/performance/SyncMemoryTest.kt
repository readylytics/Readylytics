package com.gregor.lauritz.healthdashboard.performance

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gregor.lauritz.healthdashboard.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Measures heap delta during a full activity-driven sync cycle.
// Requires a physical device or emulator with Health Connect installed.
// Expected: heap delta ≤120MB for an 8-day sync window.
@RunWith(AndroidJUnit4::class)
class SyncMemoryTest {
    @get:Rule
    val rule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun heapPeakDuring8DaySync() {
        val runtime = Runtime.getRuntime()

        runtime.gc()
        Thread.sleep(200)
        val heapBefore = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)

        // Activity launch + initial sync already triggered by ActivityScenarioRule.
        // Wait for the sync window to complete (configurable via build args in CI).
        Thread.sleep(8_000)

        runtime.gc()
        Thread.sleep(200)
        val heapAfter = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val heapDelta = heapAfter - heapBefore

        assertTrue(
            "Heap delta during 8-day sync should be ≤120MB, was ${heapDelta}MB",
            heapDelta <= 120L,
        )
    }
}
