package app.readylytics.health.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreUiOwnershipTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `core ui module exists and has correct files`() {
        assertTrue("core/ui/build.gradle.kts should exist", File(root, "core/ui/build.gradle.kts").isFile)

        val appPaths =
            listOf(
                "app/src/main/kotlin/app/readylytics/health/ui/common/DailyDataPoint.kt",
                "app/src/main/kotlin/app/readylytics/health/ui/common/TimeRange.kt",
                "app/src/main/kotlin/app/readylytics/health/ui/common/UiText.kt",
                "app/src/main/kotlin/app/readylytics/health/ui/dashboard/DateSwitcher.kt",
                "app/src/main/kotlin/app/readylytics/health/ui/settings/HeightInputField.kt",
                "app/src/main/kotlin/app/readylytics/health/ui/settings/common/UnitSystemSelector.kt",
            )

        val coreUiPaths =
            listOf(
                "core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/DailyDataPoint.kt",
                "core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/TimeRange.kt",
                "core/ui/src/main/kotlin/app/readylytics/health/core/ui/common/UiText.kt",
                "core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/DateSwitcher.kt",
                "core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/settings/HeightInputField.kt",
                "core/ui/src/main/kotlin/app/readylytics/health/core/ui/components/settings/UnitSystemSelector.kt",
            )

        appPaths.forEach { path ->
            assertFalse("app should not own $path", File(root, path).exists())
        }

        coreUiPaths.forEach { path ->
            assertTrue("core/ui should own $path", File(root, path).exists())
        }
    }
}
