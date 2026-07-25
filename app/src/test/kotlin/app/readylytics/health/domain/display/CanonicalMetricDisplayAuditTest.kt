package app.readylytics.health.domain.display

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class CanonicalMetricDisplayAuditTest {
    @Test
    fun `score surfaces do not locally format canonical display metrics`() {
        val auditedFiles =
            listOf(
                "feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardCardFactory.kt",
                "feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepScreen.kt",
                "feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutStatsSection.kt",
                "feature/workouts/src/main/kotlin/app/readylytics/health/feature/workouts/WorkoutMetricsDisplay.kt",
                "feature/dashboard/src/main/kotlin/app/readylytics/health/domain/dashboard/GetWorkoutMetricsUseCase.kt",
            ).map(::resolveAuditedFile)

        val missingFiles = auditedFiles.filterNot { it.exists() }
        assertTrue(
            missingFiles.isEmpty(),
            missingFiles.joinToString(separator = "\n") { "${it.path} is missing from the audit set" },
        )

        val offenders =
            auditedFiles
                .mapNotNull { file ->
                    val text = file.readText()
                    val suspicious =
                        forbiddenPatternsFor(file)
                            .filter { pattern -> pattern in text }
                    if (suspicious.isEmpty()) null else file.path to suspicious
                }

        assertTrue(
            offenders.isEmpty(),
            offenders.joinToString(separator = "\n") { (path, patterns) ->
                "$path uses local display formatting: ${patterns.joinToString()}"
            },
        )
    }

    private fun forbiddenPatternsFor(file: File): List<String> {
        val commonDecimalFormatting =
            listOf(
                "\"%.2f\".format",
                // Catches String.format("%.2f", ...) as well as locale-qualified variants like
                // String.format(Locale.US, "%.2f", ...), which the old call-site-only pattern missed.
                "\"%.2f\"",
            )
        return when (file.name) {
            "WorkoutStatsSection.kt" -> commonDecimalFormatting + ".roundToPercentInt()"
            else -> commonDecimalFormatting
        }
    }

    private fun resolveAuditedFile(pathFromRepoRoot: String): File {
        val direct = File(pathFromRepoRoot)
        if (direct.exists()) return direct

        val fromModuleRoot = File(pathFromRepoRoot.removePrefix("app/"))
        if (fromModuleRoot.exists()) return fromModuleRoot

        val parentPath = File("..", pathFromRepoRoot)
        if (parentPath.exists()) return parentPath

        return direct
    }
}
