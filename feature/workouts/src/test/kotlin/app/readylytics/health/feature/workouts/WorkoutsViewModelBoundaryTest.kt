package app.readylytics.health.feature.workouts

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** ARCH-102: WorkoutsViewModel orchestrates; all data I/O goes through WorkoutsDataLoader. */
class WorkoutsViewModelBoundaryTest {
    @Test
    fun `view model makes no data repository call`() {
        val source =
            File("src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutsViewModel.kt").readText()

        listOf(
            "repositories.dailySummary",
            "repositories.workout",
            "repositories.heartRate",
            "repositories.settings",
        ).forEach { forbidden ->
            assertFalse(
                "WorkoutsViewModel must load data via WorkoutsDataLoader, found $forbidden",
                forbidden in source,
            )
        }
    }
}
