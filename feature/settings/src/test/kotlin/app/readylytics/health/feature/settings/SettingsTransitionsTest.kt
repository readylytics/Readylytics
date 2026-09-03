package app.readylytics.health.feature.settings

import org.junit.Assert.assertNotNull
import org.junit.Test

class SettingsTransitionsTest {
    @Test
    fun `settings predictive pop transitions are defined and non-null`() {
        assertNotNull(settingsPredictivePopEnterTransition)
        assertNotNull(settingsPredictivePopExitTransition)
    }
}
