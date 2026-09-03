package app.readylytics.health.ui.scaffold

import org.junit.Assert.assertNotNull
import org.junit.Test

class MainNavHostTransitionsTest {
    @Test
    fun `predictive pop enter and exit transitions are non-null`() {
        assertNotNull(predictivePopEnter())
        assertNotNull(predictivePopExit())
    }
}
