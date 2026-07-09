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
        val retentionCleanupFile = sourceFile("src/main/kotlin/app/readylytics/health/data/local/RetentionCleanup.kt")
        val retentionCleanupContent = retentionCleanupFile.readText()

        val expectedDaos =
            listOf(
                "sleepDao.deleteBeforeTimestamp",
                "heartRateDao.deleteBeforeTimestamp",
                "hrvDao.deleteBeforeTimestamp",
                "workoutDao.deleteBeforeTimestamp",
                "dailySummaryDao.deleteBeforeTimestamp",
                "weightDao.deleteBeforeTimestamp",
                "bodyFatDao.deleteBeforeTimestamp",
                "bloodPressureDao.deleteBeforeTimestamp",
                "oxygenSaturationDao.deleteBeforeTimestamp",
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
        val manifestFile = projectFile("app/src/main/AndroidManifest.xml")
        val dbFactory =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(manifestFile)
        doc.documentElement.normalize()
        val applicationNode = doc.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        val allowBackup = applicationNode.getAttribute("android:allowBackup")
        val dataExtractionRules = applicationNode.getAttribute("android:dataExtractionRules")
        val fullBackupContent = applicationNode.getAttribute("android:fullBackupContent")

        org.junit.Assert.assertEquals("false", allowBackup)
        org.junit.Assert.assertEquals("@xml/data_extraction_rules", dataExtractionRules)
        org.junit.Assert.assertEquals("@xml/full_backup_content", fullBackupContent)

        val dataRulesFile = projectFile("app/src/main/res/xml/data_extraction_rules.xml")
        val rulesDoc = dBuilder.parse(dataRulesFile)
        rulesDoc.documentElement.normalize()

        val cloudBackupNode = rulesDoc.getElementsByTagName("cloud-backup").item(0) as? org.w3c.dom.Element
        val cloudExcludes = mutableSetOf<String>()
        if (cloudBackupNode != null) {
            val excludesList = cloudBackupNode.getElementsByTagName("exclude")
            for (i in 0 until excludesList.length) {
                val excludeEl = excludesList.item(i) as org.w3c.dom.Element
                cloudExcludes.add(excludeEl.getAttribute("domain"))
            }
        }
        val expectedCloudDomains = listOf("root", "file", "database", "sharedpref", "external")
        for (domain in expectedCloudDomains) {
            assertTrue("data_extraction_rules.xml cloud-backup should exclude $domain", cloudExcludes.contains(domain))
        }

        val deviceTransferNode = rulesDoc.getElementsByTagName("device-transfer").item(0) as? org.w3c.dom.Element
        val transferExcludes = mutableSetOf<String>()
        if (deviceTransferNode != null) {
            val excludesList = deviceTransferNode.getElementsByTagName("exclude")
            for (i in 0 until excludesList.length) {
                val excludeEl = excludesList.item(i) as org.w3c.dom.Element
                transferExcludes.add(excludeEl.getAttribute("domain"))
            }
        }
        val expectedTransferDomains =
            listOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
        for (domain in expectedTransferDomains) {
            assertTrue(
                "data_extraction_rules.xml device-transfer should exclude $domain",
                transferExcludes.contains(domain),
            )
        }

        val fullBackupFile = projectFile("app/src/main/res/xml/full_backup_content.xml")
        val fullDoc = dBuilder.parse(fullBackupFile)
        fullDoc.documentElement.normalize()
        val fullExcludes = mutableSetOf<String>()
        val fullExcludesList = fullDoc.getElementsByTagName("exclude")
        for (i in 0 until fullExcludesList.length) {
            val excludeEl = fullExcludesList.item(i) as org.w3c.dom.Element
            fullExcludes.add(excludeEl.getAttribute("domain"))
        }
        val expectedFullBackupDomains = listOf("root", "file", "database", "sharedpref", "external")
        for (domain in expectedFullBackupDomains) {
            assertTrue("full_backup_content.xml should exclude $domain", fullExcludes.contains(domain))
        }

        val backupRulesFile = File(projectFile("app/src/main/res/xml").absolutePath, "backup_rules.xml")
        assertFalse("Unused backup_rules.xml should be absent", backupRulesFile.exists())
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
            content.contains("DomainLogger.installSink(SecureFileLogSink(this))") ||
                content.contains("SecureFileLogSink(this)"),
        )
    }
}
