package com.gregor.lauritz.healthdashboard.performance

import android.content.Context
import android.os.BatteryManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatteryTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun idleDrainRate() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level1 = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        // 5-minute idle window: screen off, no active sync, WorkManager jobs deferred
        Thread.sleep(300_000)

        val level2 = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        if (level1 <= 0) return // device doesn't report charge counter — skip

        val drainPercent = (level1 - level2) * 100.0 / level1
        // <2% per 24h → extrapolate 5-min sample: <0.00694% per 5min
        val drain24hExtrapolated = drainPercent * (24 * 60 / 5.0)
        assertTrue(
            "Idle drain should be <2% per day, was $drain24hExtrapolated% (extrapolated)",
            drain24hExtrapolated < 2.0,
        )
    }
}
