package app.readylytics.health.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesignSystemOwnershipTest {
    private val root = sequenceOf(File("."), File("..")).first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun `theme primitives belong to core designsystem`() {
        assertTrue(
            "core/designsystem/build.gradle.kts should exist",
            File(root, "core/designsystem/build.gradle.kts").isFile,
        )
        listOf("Color.kt", "Spacing.kt", "Theme.kt", "ThemeColorUtils.kt", "Type.kt").forEach { name ->
            assertFalse(
                "app should not own $name",
                File(root, "app/src/main/kotlin/app/readylytics/health/ui/theme/$name").exists(),
            )
            assertTrue(
                "core/designsystem should own $name",
                File(root, "core/designsystem/src/main/kotlin/app/readylytics/health/core/designsystem/$name").exists(),
            )
        }
    }
}
