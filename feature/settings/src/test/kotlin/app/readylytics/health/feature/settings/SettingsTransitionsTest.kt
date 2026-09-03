package app.readylytics.health.feature.settings

import org.junit.Assert.assertNotNull
import org.junit.Test

class SettingsTransitionsTest {
    @Test
    fun `settings transitions are defined and non-null`() {
        assertNotNull(settingsEnterTransition)
        assertNotNull(settingsExitTransition)
        assertNotNull(settingsPopEnterTransition)
        assertNotNull(settingsPopExitTransition)
        assertNotNull(settingsPredictivePopEnterTransition)
        assertNotNull(settingsPredictivePopExitTransition)
    }
}
