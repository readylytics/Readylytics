package app.readylytics.health.data.backup

import android.net.Uri
import android.util.JsonReader
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.preferences.UserPreferencesProto
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.dashboard.CardConfiguration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class LocalRestoreValidationTest : LocalRestoreManagerTestBase() {
    @Test
    fun validate_correctJson_returnsManifest() =
        runTest {
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("valid_backup.zip", json)

            val result = manager.validate(Uri.fromFile(zipFile))

            assertTrue(result.isSuccess)
            val manifest = result.getOrNull()
            assertEquals(HealthDatabase.DATABASE_VERSION, manifest?.schemaVersion)
            assertEquals(1, manifest?.rowCounts?.get("sleepSessions"))
            zipFile.delete()
        }

    @Test
    fun validate_acceptsBackupVersionsFiveThroughSeven() =
        runTest {
            for (version in listOf(5, 6, 7)) {
                val json = createValidBackupJson().put("schemaVersion", version)
                val zipFile = createBackupZipFile("backup-v$version.zip", json)

                assertTrue(manager.validate(Uri.fromFile(zipFile)).isSuccess)

                zipFile.delete()
            }
        }

    @Test
    fun validate_rejectsBackupVersionsFourAndEight() =
        runTest {
            for (version in listOf(4, 8)) {
                val json = createValidBackupJson().put("schemaVersion", version)
                val zipFile = createBackupZipFile("unsupported-v$version.zip", json)

                val result = manager.validate(Uri.fromFile(zipFile))

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message?.contains("schema version") == true)
                zipFile.delete()
            }
        }

    @Test
    fun backupSchemaPolicy_pinsMaximumSupportedVersionToSeven() {
        assertEquals(7, BackupSchemaPolicy.MAX_SUPPORTED_VERSION)
    }

    @Test
    fun applyRestore_unsupportedVersionsPreserveExistingRows() =
        runTest {
            val seedZipFile = createBackupZipFile("preservation-seed.zip", createValidBackupJson())
            assertTrue(manager.applyRestore(Uri.fromFile(seedZipFile)) is RestoreResult.SuccessRequiresRestart)
            seedZipFile.delete()

            for (version in listOf(4, 8)) {
                val unsupportedJson =
                    createValidBackupJson()
                        .put("schemaVersion", version)
                        .put("sleepSessions", JSONArray())
                val unsupportedZipFile =
                    createBackupZipFile("unsupported-restore-v$version.zip", unsupportedJson)

                assertTrue(manager.applyRestore(Uri.fromFile(unsupportedZipFile)) is RestoreResult.Failure)
                assertEquals(
                    listOf("session_1"),
                    db.sleepSessionDao().getSince(0).map { it.id },
                )
                unsupportedZipFile.delete()
            }
        }

    @Test
    fun applyRestore_legacyHeartAndHrvIdsMigrateToSourceRecordIds() =
        runTest {
            val timestamp = 1_700_000_000_000L
            val json =
                createValidBackupJson()
                    .put("schemaVersion", 5)
                    .put(
                        "heartRateRecords",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "hc-heart_$timestamp")
                                .put("timestampMs", timestamp)
                                .put("beatsPerMinute", 61)
                                .put("recordType", "SLEEP"),
                        ),
                    ).put(
                        "hrvRecords",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "hc-hrv_$timestamp")
                                .put("timestampMs", timestamp)
                                .put("rmssdMs", 48.5)
                                .put("recordType", "SLEEP"),
                        ),
                    )
            val zipFile = createBackupZipFile("legacy-v5.zip", json)

            assertTrue(manager.applyRestore(Uri.fromFile(zipFile)) is RestoreResult.SuccessRequiresRestart)
            assertEquals(
                "hc-heart",
                db
                    .heartRateDao()
                    .getSince(0)
                    .single()
                    .sourceRecordId,
            )
            assertEquals(
                "hc-hrv",
                db
                    .hrvDao()
                    .getSince(0)
                    .single()
                    .sourceRecordId,
            )
            zipFile.delete()
        }

    @Test
    fun applyRestore_legacyIdWithoutExactTimestampSuffixIsPreserved() =
        runTest {
            val timestamp = 1_700_000_000_000L
            val malformedHeartId = "hc-heart_${timestamp}x"
            val mismatchedHrvId = "hc-hrv_${timestamp + 1}"
            val json =
                createValidBackupJson()
                    .put("schemaVersion", 6)
                    .put(
                        "heartRateRecords",
                        JSONArray().put(
                            JSONObject()
                                .put("id", malformedHeartId)
                                .put("timestampMs", timestamp)
                                .put("beatsPerMinute", 61)
                                .put("recordType", "SLEEP"),
                        ),
                    ).put(
                        "hrvRecords",
                        JSONArray().put(
                            JSONObject()
                                .put("id", mismatchedHrvId)
                                .put("timestampMs", timestamp)
                                .put("rmssdMs", 48.5)
                                .put("recordType", "SLEEP"),
                        ),
                    )
            val zipFile = createBackupZipFile("legacy-malformed-suffix-v6.zip", json)

            assertTrue(manager.applyRestore(Uri.fromFile(zipFile)) is RestoreResult.SuccessRequiresRestart)
            assertEquals(
                malformedHeartId,
                db
                    .heartRateDao()
                    .getSince(0)
                    .single()
                    .sourceRecordId,
            )
            assertEquals(
                mismatchedHrvId,
                db
                    .hrvDao()
                    .getSince(0)
                    .single()
                    .sourceRecordId,
            )
            zipFile.delete()
        }

    @Test
    fun applyRestore_insertsDataIntoDb() =
        runTest {
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("restore_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val sessions = db.sleepSessionDao().getSince(0)
            assertEquals(1, sessions.size)
            assertEquals("session_1", sessions[0].id)
            zipFile.delete()
        }

    @Test
    fun applyRestore_preservesEveryJsonControlCharacterInStrings() =
        runTest {
            val controlCharacters = (0x00..0x1f).map { it.toChar() }.joinToString("")
            val json =
                createValidBackupJson().apply {
                    getJSONArray("sleepSessions")
                        .getJSONObject(0)
                        .put("deviceName", controlCharacters)
                }
            val zipFile = createBackupZipFile("control_characters.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            assertEquals(
                controlCharacters,
                db
                    .sleepSessionDao()
                    .getSince(0)
                    .single()
                    .deviceName,
            )
            zipFile.delete()
        }

    @Test
    fun streamingParser_reemitsStringsWithoutRawJsonControlCharacters() {
        val controlCharacters = (0x00..0x1f).map { it.toChar() }.joinToString("")
        val source = JSONObject().put("value", controlCharacters).toString()
        val reader = JsonReader(StringReader(source))
        val readNextObjectAsString =
            LocalRestoreManager::class.java
                .getDeclaredMethod("readNextObjectAsString", JsonReader::class.java)
                .apply { isAccessible = true }

        val reemitted = readNextObjectAsString.invoke(manager, reader) as String

        assertTrue(reemitted.none { it.code in 0x00..0x1f })
    }

    @Test
    fun applyRestore_updatesPreferences() =
        runTest {
            val json = createValidBackupJson()
            json.getJSONObject("preferences").put("goalSleepHours", 9.5)
            val zipFile = createBackupZipFile("prefs_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify { settingsRepo.batchUpdate(any()) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresHeight() =
        runTest {
            val json = createValidBackupJson()
            json.getJSONObject("preferences").put("heightCm", 182.5)
            val zipFile = createBackupZipFile("height_backup.zip", json)

            val builderSlot =
                io.mockk
                    .slot<app.readylytics.health.data.preferences.UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder =
                app.readylytics.health.data.preferences.UserPreferencesProto
                    .newBuilder()
            builderSlot.captured(builder)
            assertEquals(182.5f, builder.heightCm)
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresHrrToleranceSeconds() =
        runTest {
            val json = createValidBackupJson()
            json.getJSONObject("preferences").put("hrrToleranceSeconds", 45)
            val zipFile = createBackupZipFile("hrr_tolerance_backup.zip", json)

            val builderSlot =
                io.mockk
                    .slot<app.readylytics.health.data.preferences.UserPreferencesProto.Builder.() -> Unit>()
            coEvery { settingsRepo.batchUpdate(capture(builderSlot)) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val builder =
                app.readylytics.health.data.preferences.UserPreferencesProto
                    .newBuilder()
            builderSlot.captured(builder)
            assertEquals(45, builder.hrrToleranceSeconds)
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresDashboardCards() =
        runTest {
            val json = createValidBackupJson()
            val cardsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("cardId", "READINESS")
                            put("isVisible", true)
                            put("position", 2)
                        },
                    )
                }
            json.getJSONObject("preferences").put("dashboardCards", cardsJson)
            val zipFile = createBackupZipFile("cards_backup.zip", json)

            coEvery { cardConfigRepo.updateDashboardCardConfigurations(any()) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val expectedCards =
                listOf(
                    CardConfiguration(
                        cardId = app.readylytics.health.domain.dashboard.CardId.READINESS,
                        isVisible = true,
                        position = 2,
                        requestedDisplayMode = null,
                    ),
                )
            coVerify(exactly = 1) {
                cardConfigRepo.updateDashboardCardConfigurations(expectedCards)
            }
            zipFile.delete()
        }
}
