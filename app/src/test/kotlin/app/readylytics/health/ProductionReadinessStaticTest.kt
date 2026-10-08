package app.readylytics.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionReadinessStaticTest {
    @Test
    fun `release build has no runtime network capability or client dependencies`() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()
        val appBuild = projectFile("app/build.gradle.kts").readText()
        val libs = projectFile("gradle/libs.versions.toml").readText()

        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(appBuild.contains("libs.retrofit"))
        assertFalse(appBuild.contains("libs.okhttp"))
        assertFalse(libs.contains("retrofit = "))
        assertFalse(libs.contains("retrofit-kotlinx-serialization"))
        assertFalse(libs.contains("okhttp = "))
    }

    @Test
    fun `production code uses centralized logging helpers only`() {
        val kotlinRoot = projectFile("app/src/main/kotlin")
        val allowedLogFiles =
            setOf(
                "app/readylytics/health/domain/util/AppLog.kt",
                "app/readylytics/health/HealthDashboardApplication.kt",
                "app/readylytics/health/util/SecureFileLogSink.kt",
                "app/readylytics/health/util/SecureLogger.kt",
            )

        val offenders =
            kotlinRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { file ->
                    file.relativeTo(kotlinRoot).invariantSeparatorsPath in allowedLogFiles
                }.flatMap { file ->
                    val text = file.readText()
                    buildList {
                        if (text.contains("import android.util.Log")) add("${file.path}: import android.util.Log")
                        Regex("""(?<![A-Za-z])Log\.[A-Za-z]+\(""")
                            .findAll(text)
                            .forEach { add("${file.path}: ${it.value}") }
                        Regex("""android\.util\.Log\.[A-Za-z]+\(""")
                            .findAll(text)
                            .forEach { add("${file.path}: ${it.value}") }
                    }
                }.toList()

        assertTrue("Direct Log.* usage remains outside AppLog.kt: $offenders", offenders.isEmpty())
    }

    @Test
    fun `ui code does not surface raw throwable messages`() {
        val uiFiles =
            listOf(
                "feature/dashboard/src/main/kotlin/app/readylytics/health/feature/dashboard/DashboardViewModel.kt",
                "feature/settings/src/main/kotlin/app/readylytics/health/feature/settings/LocalBackupViewModel.kt",
                "app/src/main/kotlin/app/readylytics/health/ui/sync/SyncViewModel.kt",
                "app/src/main/kotlin/app/readylytics/health/MainActivity.kt",
            )

        val offenders =
            uiFiles.flatMap { path ->
                val file = projectFile(path)
                val text = file.readText()
                buildList {
                    Regex("""UiText\.RawString\([^)]*message""")
                        .findAll(text)
                        .forEach { add("${file.path}: ${it.value}") }
                    Regex("""SyncUiState\.Error\([^)]*message""")
                        .findAll(text)
                        .forEach { add("${file.path}: ${it.value}") }
                    Regex("""cause\.message""")
                        .findAll(text)
                        .forEach { add("${file.path}: ${it.value}") }
                }
            }

        assertTrue("Raw throwable messages still reach UI state: $offenders", offenders.isEmpty())
    }

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
                "src/main/kotlin/app/readylytics/health/core/healthconnect/domain/sync/ForegroundSyncController.kt",
            ).readText()
        val localBackupManager =
            sourceFile(
                "src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt",
            ).readText()

        assertTrue(foregroundSyncController.contains("catch (e: CancellationException)"))
        assertFalse(localBackupManager.contains("runCatching"))
    }

    @Test
    fun `main activity diagnostics rethrows coroutine cancellation`() {
        val source = projectFile("app/src/main/kotlin/app/readylytics/health/MainActivity.kt").readText()
        val cancellationRethrow = source.indexOf("is CancellationException")
        val genericFailureHandler = source.indexOf("Failed to prepare diagnostic log")

        assertTrue(
            "MainActivity must import kotlinx.coroutines.CancellationException",
            source.contains("import kotlinx.coroutines.CancellationException"),
        )
        assertTrue(
            "CancellationException must be rethrown before the generic failure handler",
            cancellationRethrow >= 0 && genericFailureHandler >= 0 && cancellationRethrow < genericFailureHandler,
        )
    }

    @Test
    fun `local backup stages encrypted zip before publishing default backup file`() {
        val source = sourceFile("src/main/kotlin/app/readylytics/health/data/backup/LocalBackupManager.kt").readText()

        assertTrue(source.contains("store.publish(tempZipFile, zipFilename)"))
        assertFalse(source.contains("createZip(jsonFile, file, password)"))
    }

    @Test
    fun `local database has no unencrypted production create helper`() {
        val source =
            sourceFile(
                "src/main/kotlin/app/readylytics/health/core/database/data/local/HealthDatabase.kt",
            ).readText()

        assertFalse(source.contains("fun create(context: Context): HealthDatabase"))
        assertFalse(source.contains("databaseBuilder(context, HealthDatabase::class.java, \"health_db\")"))
    }

    @Test
    fun `settings color and circadian controls do not hardcode user visible strings`() {
        val customColorPicker =
            sourceFile(
                "src/main/kotlin/app/readylytics/health/feature/settings/common/CustomColorPicker.kt",
            ).readText()
        val circadianSection =
            sourceFile(
                "src/main/kotlin/app/readylytics/health/feature/settings/CircadianThresholdSettingsSection.kt",
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
                projectFile("app/build.gradle.kts"),
                projectFile("gradle/libs.versions.toml"),
                projectFile("app/src/main/kotlin"),
                projectFile("app/src/main/res"),
                projectFile("app/src/main/AndroidManifest.xml"),
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

    @Test
    fun `all nine DAO deletions are owned by RetentionCleanup`() {
        val retentionCleanupFile =
            sourceFile("src/main/kotlin/app/readylytics/health/core/database/data/local/RetentionCleanup.kt")
        val retentionCleanupContent = retentionCleanupFile.readText()

        val expectedDaos =
            listOf(
                "sleepSessionDao.deleteBeforeTimestamp",
                "heartRateDao.deleteBeforeTimestamp",
                "hrvDao.deleteBeforeTimestamp",
                "workoutDao.deleteBeforeTimestamp",
                "dailySummaryDao.deleteBeforeTimestamp",
                "weightRecordDao.deleteBeforeTimestamp",
                "bodyFatRecordDao.deleteBeforeTimestamp",
                "bloodPressureRecordDao.deleteBeforeTimestamp",
                "oxygenSaturationRecordDao.deleteBeforeTimestamp",
            )

        val missingDaos =
            expectedDaos.filter { daoCall ->
                !retentionCleanupContent.contains(daoCall)
            }

        assertTrue(
            "RetentionCleanup must call deleteBeforeTimestamp for all nine sensitive DAOs: missing $missingDaos",
            missingDaos.isEmpty(),
        )
    }

    @Test
    fun `data backup and transfer exclusions are fully configured`() {
        val manifest = xmlRoot("app/src/main/AndroidManifest.xml")
        val application = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        org.junit.Assert.assertEquals("false", application.getAttribute("android:allowBackup"))
        org.junit.Assert.assertEquals(
            "@xml/data_extraction_rules",
            application.getAttribute("android:dataExtractionRules"),
        )
        org.junit.Assert.assertEquals("@xml/full_backup_content", application.getAttribute("android:fullBackupContent"))

        val rules = xmlRoot("app/src/main/res/xml/data_extraction_rules.xml")
        val standardDomains = listOf("root", "file", "database", "sharedpref", "external")
        assertExcludedDomains(rules, "cloud-backup", standardDomains, "data_extraction_rules.xml cloud-backup")
        assertExcludedDomains(
            rules,
            "device-transfer",
            standardDomains + listOf("device_root", "device_file", "device_database", "device_sharedpref"),
            "data_extraction_rules.xml device-transfer",
        )
        assertExcludedDomains(
            xmlRoot("app/src/main/res/xml/full_backup_content.xml"),
            null,
            standardDomains,
            "full_backup_content.xml",
        )
        val backupRulesFile = File(projectFile("app/src/main/res/xml").absolutePath, "backup_rules.xml")
        assertFalse("Unused backup_rules.xml should be absent", backupRulesFile.exists())
    }

    private fun xmlRoot(path: String): org.w3c.dom.Element =
        javax.xml.parsers.DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(projectFile(path))
            .documentElement
            .also { it.normalize() }

    private fun assertExcludedDomains(
        root: org.w3c.dom.Element,
        section: String?,
        expected: List<String>,
        label: String,
    ) {
        val container = if (section == null) root else root.getElementsByTagName(section).item(0) as org.w3c.dom.Element
        val excludes = container.getElementsByTagName("exclude")
        val domains =
            (0 until excludes.length)
                .map { index ->
                    (excludes.item(index) as org.w3c.dom.Element).getAttribute("domain")
                }.toSet()
        expected.forEach { domain ->
            assertTrue("$label should exclude $domain", domain in domains)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(
            File(path),
            File("app", path),
            File("core/database", path),
            File("core/model", path),
            File("core/scoring", path),
            File("core/healthconnect", path),
            File("feature/about", path),
            File("feature/dashboard", path),
            File("feature/insights", path),
            File("feature/settings", path),
            File("feature/sleep", path),
            File("feature/vitals", path),
            File("feature/workouts", path),
            File("..", path),
            File("../app", path),
            File("../core/database", path),
            File("../core/model", path),
            File("../core/scoring", path),
            File("../core/healthconnect", path),
            File("../feature/about", path),
            File("../feature/dashboard", path),
            File("../feature/insights", path),
            File("../feature/settings", path),
            File("../feature/sleep", path),
            File("../feature/vitals", path),
            File("../feature/workouts", path),
        ).firstOrNull { it.exists() }
            ?: error("Source file not found: $path")

    private fun projectFile(path: String): File =
        listOf(File(path), File("..", path))
            .firstOrNull { it.exists() }
            ?: error("Project file not found: $path")

    @Test
    fun `production application installs SecureFileLogSink in release`() {
        val appFile = projectFile("app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt")
        val content = appFile.readText()
        assertTrue(
            "Application should install SecureFileLogSink(this) in release builds",
            content.contains("DomainLogger.installSink(secureLogSink)") ||
                content.contains("DomainLogger.installSink(SecureFileLogSink(this))") ||
                content.contains("lateinit var secureLogSink: SecureFileLogSink"),
        )
    }

    @Test
    fun `application keeps indirectly Room-backed settings lazy until database Ready`() {
        val content =
            projectFile(
                "app/src/main/kotlin/app/readylytics/health/HealthDashboardApplication.kt",
            ).readText()

        assertTrue(content.contains("lateinit var settingsRepo: Lazy<SettingsRepository>"))
        assertFalse(content.contains("lateinit var settingsRepo: SettingsRepository"))
    }

    @Test
    fun `non-ready activity content uses a Room-free theme`() {
        val activity =
            projectFile(
                "app/src/main/kotlin/app/readylytics/health/MainActivity.kt",
            ).readText()
        val readinessContent =
            activity
                .substringAfter("setContent {")
                .substringBefore("private fun ReadylyticsContent")

        assertTrue(readinessContent.contains("DatabaseReadinessContent("))
        assertFalse(readinessContent.contains("FitDashboardTheme {"))
        val renderer =
            projectFile(
                "app/src/main/kotlin/app/readylytics/health/ui/migration/DatabaseReadinessContent.kt",
            ).readText()
        val recoveryBranch = renderer.substringAfter("DatabaseReadiness.KeyCorrupted").substringBefore("else ->")
        val blockedBranch = renderer.substringAfter("else ->")
        assertTrue(recoveryBranch.contains("DatabaseReadinessTheme"))
        assertTrue(blockedBranch.contains("DatabaseReadinessTheme"))
        assertFalse(renderer.contains("FitDashboardTheme {"))
        assertFalse(renderer.contains("hiltViewModel"))

        val theme =
            projectFile(
                "app/src/main/kotlin/app/readylytics/health/ui/theme/FitDashboardTheme.kt",
            ).readText()
        val readinessTheme =
            theme
                .substringAfter("fun DatabaseReadinessTheme")
                .substringBefore("fun FitDashboardTheme")

        assertTrue(readinessTheme.contains("CoreFitDashboardTheme"))
        assertFalse(readinessTheme.contains("hiltViewModel"))
    }
}
