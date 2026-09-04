package app.readylytics.health.feature.settings.physiologyprofile

import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PhysiologyProfileVo2MaxSourceTest {
    @Test
    fun defaultSourceModeIsAuto() {
        val defaultMode = Vo2MaxSourceMode.AUTO
        assertEquals(Vo2MaxSourceMode.AUTO, defaultMode)
    }
}
