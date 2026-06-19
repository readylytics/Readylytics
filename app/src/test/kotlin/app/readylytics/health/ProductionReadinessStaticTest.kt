package app.readylytics.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionReadinessStaticTest {
    @Test
    fun `workers rethrow coroutine cancellation before generic failure handling`() {
        val workerFiles =
            listOf(
                "src/main/kotlin/app/readylytics/health/workers/HealthResyncWorker.kt",
                "src/main/kotlin/app/readylytics/health/workers/PeriodicHealthSyncWorker.kt",
                "src/main/kotlin/app/readylytics/health/workers/BirthdayCheckWorker.kt",
                "src/main/kotlin/app/readylytics/health/workers/DataCleanupWorker.kt",
            )

        val missingCancellationCatch =
            workerFiles
                .map(::sourceFile)
                .filter { file -> !file.readText().contains("catch (e: CancellationException)") }
                .map { it.name }

        assertTrue(
            "Workers must rethrow CancellationException before generic catch: $missingCancellationCatch",
            missingCancellationCatch.isEmpty(),
        )
    }

    @Test
    fun `foreground sync and local backup helpers do not swallow coroutine cancellation`() {
        val foregroundSyncController =
            sourceFile(
                "src/main/kotlin/app/readylytics/health/domain/sync/ForegroundSyncController.kt",
            ).readText()
        val localBackupManager =
            sourceFile(
                "src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt",
            ).readText()

        assertTrue(foregroundSyncController.contains("catch (e: CancellationException)"))
        assertFalse(localBackupManager.contains("runCatching"))
    }

    @Test
    fun `local backup stages encrypted zip before publishing default backup file`() {
        val source = sourceFile("src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt").readText()

        assertTrue(source.contains("moveTempZipToFinal(tempZipFile, file)"))
        assertFalse(source.contains("createZip(jsonFile, file, password)"))
    }

    @Test
    fun `local database has no unencrypted production create helper`() {
        val source = sourceFile("src/main/kotlin/app/readylytics/health/data/local/HealthDatabase.kt").readText()

        assertFalse(source.contains("fun create(context: Context): HealthDatabase"))
        assertFalse(source.contains("databaseBuilder(context, HealthDatabase::class.java, \"health_db\")"))
    }

    @Test
    fun `settings color and circadian controls do not hardcode user visible strings`() {
        val customColorPicker =
            sourceFile(
                "src/main/kotlin/app/readylytics/health/ui/settings/common/CustomColorPicker.kt",
            ).readText()
        val circadianSection =
            sourceFile(
                "src/main/kotlin/app/readylytics/health/ui/settings/CircadianThresholdSettingsSection.kt",
            ).readText()

        assertFalse(customColorPicker.contains("Text(\"Hex Code\")"))
        assertFalse(circadianSection.contains("Text(\"Active\""))
        assertFalse(circadianSection.contains("Text(\"Athlete\""))
    }

    @Test
    fun `secure logger has no production telemetry TODO path`() {
        val source = sourceFile("src/main/kotlin/app/readylytics/health/util/SecureLogger.kt").readText()

        assertFalse(source.contains("TODO"))
        assertFalse(source.contains("reportToCrashlytics"))
    }

    @Test
    fun `google drive integration code is absent`() {
        val roots =
            listOf(
                projectFile("AGENTS.md"),
                projectFile("app/build.gradle.kts"),
                projectFile("gradle/libs.versions.toml"),
                projectFile("app/src/main/kotlin"),
                projectFile("app/src/main/res"),
            )
        val forbidden =
            listOf(
                "Google Drive",
                "driveAccountEmail",
                "collapseCloudData",
                "updateDriveAccountEmail",
                "updateCollapseCloudData",
                "androidx.credentials",
                "googleid",
                "play.services.auth",
            )

        val offenders =
            roots
                .flatMap { root ->
                    if (root.isDirectory) {
                        root
                            .walkTopDown()
                            .filter { it.isFile }
                            .toList()
                    } else {
                        listOf(root)
                    }
                }.flatMap { file ->
                    val text = file.readText()
                    forbidden
                        .filter(text::contains)
                        .map { "${file.path}: $it" }
                }

        assertTrue("Google Drive integration refs remain: $offenders", offenders.isEmpty())
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path))
            .firstOrNull { it.exists() }
            ?: error("Source file not found: $path")

    private fun projectFile(path: String): File =
        listOf(File(path), File("..", path))
            .firstOrNull { it.exists() }
            ?: error("Project file not found: $path")
}
