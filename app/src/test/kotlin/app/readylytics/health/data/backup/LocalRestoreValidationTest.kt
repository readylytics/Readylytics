package app.readylytics.health.data.backup

import android.net.Uri
import android.util.JsonReader
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.preferences.UserPreferencesProto
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.dashboard.CardConfiguration
import app.readylytics.health.domain.sleep.SleepChartConfiguration
import app.readylytics.health.domain.sleep.SleepChartId
import app.readylytics.health.domain.sleep.SleepMetricCardConfiguration
import app.readylytics.health.domain.sleep.SleepMetricCardId
import app.readylytics.health.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.domain.sleep.SleepTopCardId
import app.readylytics.health.domain.vitals.VitalsChartConfiguration
import app.readylytics.health.domain.vitals.VitalsChartId
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
    fun validate_acceptsBackupVersionsFiveThroughCurrent() =
        runTest {
            for (version in BackupSchemaPolicy.MIN_SUPPORTED_VERSION..BackupSchemaPolicy.MAX_SUPPORTED_VERSION) {
                val json = createValidBackupJson().put("schemaVersion", version)
                val zipFile = createBackupZipFile("backup-v$version.zip", json)

                assertTrue(manager.validate(Uri.fromFile(zipFile)).isSuccess)

                zipFile.delete()
            }
        }

    @Test
    fun validate_rejectsBackupVersionsBelowMinAndAboveMax() =
        runTest {
            val belowMin = BackupSchemaPolicy.MIN_SUPPORTED_VERSION - 1
            val aboveMax = BackupSchemaPolicy.MAX_SUPPORTED_VERSION + 1
            for (version in listOf(belowMin, aboveMax)) {
                val json = createValidBackupJson().put("schemaVersion", version)
                val zipFile = createBackupZipFile("unsupported-v$version.zip", json)

                val result = manager.validate(Uri.fromFile(zipFile))

                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull()?.message?.contains("schema version") == true)
                zipFile.delete()
            }
        }

    @Test
    fun backupSchemaPolicy_tracksHealthDatabaseVersion() {
        // MAX_SUPPORTED_VERSION const-folds from HealthDatabase.DATABASE_VERSION so the two can
        // never drift apart across a schema bump; this asserts that invariant rather than a
        // version number frozen at whatever DATABASE_VERSION happened to be when this was written.
        assertEquals(HealthDatabase.DATABASE_VERSION, BackupSchemaPolicy.MAX_SUPPORTED_VERSION)
    }

    @Test
    fun applyRestore_unsupportedVersionsPreserveExistingRows() =
        runTest {
            val seedZipFile = createBackupZipFile("preservation-seed.zip", createValidBackupJson())
            assertTrue(manager.applyRestore(Uri.fromFile(seedZipFile)) is RestoreResult.SuccessRequiresRestart)
            seedZipFile.delete()

            val belowMin = BackupSchemaPolicy.MIN_SUPPORTED_VERSION - 1
            val aboveMax = BackupSchemaPolicy.MAX_SUPPORTED_VERSION + 1
            for (version in listOf(belowMin, aboveMax)) {
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
            val heartRef =
                db
                    .heartRateDao()
                    .getSince(0)
                    .single()
                    .sourceRecordRef
            assertEquals(
                "hc-heart",
                db
                    .sourceRecordDao()
                    .getAll()
                    .single { it.id == heartRef }
                    .sourceRecordId,
            )
            val hrvRef =
                db
                    .hrvDao()
                    .getSince(0)
                    .single()
                    .sourceRecordRef
            assertEquals(
                "hc-hrv",
                db
                    .sourceRecordDao()
                    .getAll()
                    .single { it.id == hrvRef }
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
            val heartRef =
                db
                    .heartRateDao()
                    .getSince(0)
                    .single()
                    .sourceRecordRef
            assertEquals(
                malformedHeartId,
                db
                    .sourceRecordDao()
                    .getAll()
                    .single { it.id == heartRef }
                    .sourceRecordId,
            )
            val hrvRef =
                db
                    .hrvDao()
                    .getSince(0)
                    .single()
                    .sourceRecordRef
            assertEquals(
                mismatchedHrvId,
                db
                    .sourceRecordDao()
                    .getAll()
                    .single { it.id == hrvRef }
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

    @Test
    fun applyRestore_restoresVitalsCardsAndCharts() =
        runTest {
            val json = createValidBackupJson()
            val vitalsCardsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("cardId", "RESTING_HR")
                            put("isVisible", true)
                            put("position", 0)
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("cardId", "HRV")
                            put("isVisible", false)
                            put("position", 1)
                        },
                    )
                }
            val vitalsChartsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("chartId", "HRV_TREND")
                            put("isVisible", true)
                            put("position", 0)
                        },
                    )
                    put(
                        JSONObject().apply {
                            put("chartId", "BODY_TEMP_TREND")
                            put("isVisible", false)
                            put("position", 3)
                        },
                    )
                }
            json.getJSONObject("preferences").put("vitalsCards", vitalsCardsJson)
            json.getJSONObject("preferences").put("vitalsCharts", vitalsChartsJson)
            val zipFile = createBackupZipFile("vitals_layout_backup.zip", json)

            coEvery { vitalsLayoutRepo.updateVitalsCardConfigurations(any()) } returns Unit
            coEvery { vitalsLayoutRepo.updateVitalsChartConfigurations(any()) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val expectedCards =
                listOf(
                    CardConfiguration(
                        cardId = app.readylytics.health.domain.dashboard.CardId.RESTING_HR,
                        isVisible = true,
                        position = 0,
                        requestedDisplayMode = null,
                    ),
                    CardConfiguration(
                        cardId = app.readylytics.health.domain.dashboard.CardId.HRV,
                        isVisible = false,
                        position = 1,
                        requestedDisplayMode = null,
                    ),
                )
            val expectedCharts =
                listOf(
                    VitalsChartConfiguration(
                        chartId = VitalsChartId.HRV_TREND,
                        isVisible = true,
                        position = 0,
                    ),
                    VitalsChartConfiguration(
                        chartId = VitalsChartId.BODY_TEMP_TREND,
                        isVisible = false,
                        position = 3,
                    ),
                )
            coVerify(exactly = 1) {
                vitalsLayoutRepo.updateVitalsCardConfigurations(expectedCards)
            }
            coVerify(exactly = 1) {
                vitalsLayoutRepo.updateVitalsChartConfigurations(expectedCharts)
            }
            zipFile.delete()
        }

    @Test
    fun applyRestore_oldBackupWithoutVitalsLayoutLeavesVitalsConfigurationsUntouched() =
        runTest {
            // createValidBackupJson() predates the vitals layout integration: no vitalsCards/vitalsCharts
            // keys, matching every backup created before this feature shipped.
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("old_format_no_vitals_layout_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify(exactly = 0) { vitalsLayoutRepo.updateVitalsCardConfigurations(any()) }
            coVerify(exactly = 0) { vitalsLayoutRepo.updateVitalsChartConfigurations(any()) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_restoresSleepTopCardsChartsAndMetricCards() =
        runTest {
            val json = createValidBackupJson()
            val sleepTopCardsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("cardId", "SLEEP_SCORE")
                            put("isVisible", true)
                            put("position", 0)
                        },
                    )
                }
            val sleepChartsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("chartId", "SLEEP_DURATION_TREND")
                            put("isVisible", true)
                            put("position", 0)
                        },
                    )
                }
            val sleepMetricCardsJson =
                JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("cardId", "CIRCADIAN_CONSISTENCY")
                            put("isVisible", true)
                            put("position", 0)
                        },
                    )
                }
            json.getJSONObject("preferences").put("sleepTopCards", sleepTopCardsJson)
            json.getJSONObject("preferences").put("sleepCharts", sleepChartsJson)
            json.getJSONObject("preferences").put("sleepMetricCards", sleepMetricCardsJson)
            val zipFile = createBackupZipFile("sleep_layout_backup.zip", json)

            coEvery { sleepLayoutRepo.updateSleepTopCardConfigurations(any()) } returns Unit
            coEvery { sleepLayoutRepo.updateSleepChartConfigurations(any()) } returns Unit
            coEvery { sleepLayoutRepo.updateSleepMetricCardConfigurations(any()) } returns Unit

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)

            val expectedTopCards =
                listOf(
                    SleepTopCardConfiguration(
                        cardId = SleepTopCardId.SLEEP_SCORE,
                        isVisible = true,
                        position = 0,
                    ),
                )
            val expectedCharts =
                listOf(
                    SleepChartConfiguration(
                        chartId = SleepChartId.SLEEP_DURATION_TREND,
                        isVisible = true,
                        position = 0,
                    ),
                )
            val expectedMetricCards =
                listOf(
                    SleepMetricCardConfiguration(
                        cardId = SleepMetricCardId.CIRCADIAN_CONSISTENCY,
                        isVisible = true,
                        position = 0,
                    ),
                )

            coVerify(exactly = 1) { sleepLayoutRepo.updateSleepTopCardConfigurations(expectedTopCards) }
            coVerify(exactly = 1) { sleepLayoutRepo.updateSleepChartConfigurations(expectedCharts) }
            coVerify(exactly = 1) { sleepLayoutRepo.updateSleepMetricCardConfigurations(expectedMetricCards) }
            zipFile.delete()
        }

    @Test
    fun applyRestore_oldBackupWithoutSleepLayoutLeavesSleepConfigurationsUntouched() =
        runTest {
            val json = createValidBackupJson()
            val zipFile = createBackupZipFile("old_format_no_sleep_layout_backup.zip", json)

            val result = manager.applyRestore(Uri.fromFile(zipFile))

            assertTrue(result is RestoreResult.SuccessRequiresRestart)
            coVerify(exactly = 0) { sleepLayoutRepo.updateSleepTopCardConfigurations(any()) }
            coVerify(exactly = 0) { sleepLayoutRepo.updateSleepChartConfigurations(any()) }
            coVerify(exactly = 0) { sleepLayoutRepo.updateSleepMetricCardConfigurations(any()) }
            zipFile.delete()
        }
}
