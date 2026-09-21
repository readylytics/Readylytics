package app.readylytics.health.data.backup

import android.net.Uri
import app.readylytics.health.core.model.domain.backup.RestoreResult
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalRestoreRollbackTest : LocalRestoreManagerTestBase() {
    private suspend fun seedInitialSession() {
        val seedZip = createBackupZipFile("seed.zip", createValidBackupJson())
        assertTrue(manager.applyRestore(Uri.fromFile(seedZip)) is RestoreResult.SuccessRequiresRestart)
        seedZip.delete()
        assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
    }

    @Test
    fun applyRestore_rejectsMissingCoreArrayAndPreservesSeededData() =
        runTest {
            seedInitialSession()
            val payload = createValidBackupJson().apply { remove("heartRateRecords") }
            val archive = createBackupZipFile("missing-core.zip", payload)

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.Failure)
            assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
            archive.delete()
        }

    @Test
    fun applyRestore_rejectsDuplicateTopLevelKeyAndPreservesSeededData() =
        runTest {
            seedInitialSession()
            val valid = createValidBackupJson().toString()
            val malformed = valid.substringBeforeLast("}") + ",\"schemaVersion\": 19}"
            val archive = createRawBackupZipFile("duplicate-key.zip", malformed)

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.Failure)
            assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
            archive.delete()
        }

    @Test
    fun applyRestore_rejectsDuplicateSourceIdsAndPreservesSeededData() =
        runTest {
            seedInitialSession()
            val payload =
                createValidBackupJson().apply {
                    val sources =
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("id", 1L)
                                    put("sourceRecordId", "dup-src")
                                    put("recordType", "HEART_RATE")
                                    put("createdAtMs", 1000L)
                                },
                            )
                            put(
                                JSONObject().apply {
                                    put("id", 2L)
                                    put("sourceRecordId", "dup-src")
                                    put("recordType", "HEART_RATE")
                                    put("createdAtMs", 2000L)
                                },
                            )
                        }
                    put("healthSourceRecords", sources)
                    getJSONObject("rowCounts").put("healthSourceRecords", 2)
                }
            val archive = createBackupZipFile("duplicate-source-ids.zip", payload)

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.Failure)
            assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
            archive.delete()
        }

    @Test
    fun applyRestore_rejectsNegativeDeclaredCountAndPreservesSeededData() =
        runTest {
            seedInitialSession()
            val payload =
                createValidBackupJson().apply {
                    getJSONObject("rowCounts").put("heartRateRecords", -1)
                }
            val archive = createBackupZipFile("negative-count.zip", payload)

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.Failure)
            assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
            archive.delete()
        }

    @Test
    fun applyRestore_rejectsMismatchedCountAndPreservesSeededData() =
        runTest {
            seedInitialSession()
            val payload =
                createValidBackupJson().apply {
                    getJSONObject("rowCounts").put("heartRateRecords", 5)
                }
            val archive = createBackupZipFile("mismatched-count.zip", payload)

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.Failure)
            assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
            archive.delete()
        }

    @Test
    fun applyRestore_rejectsMissingSourceFkAndPreservesSeededData() =
        runTest {
            seedInitialSession()
            val payload =
                createValidBackupJson().apply {
                    val sources =
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("id", 1L)
                                    put("sourceRecordId", "src-1")
                                    put("recordType", "HEART_RATE")
                                    put("createdAtMs", 1000L)
                                },
                            )
                        }
                    val hrRecords =
                        JSONArray().apply {
                            put(
                                JSONObject().apply {
                                    put("sourceRecordRef", 9999L)
                                    put("timestampMs", 1000L)
                                    put("beatsPerMinute", 60)
                                    put("recordType", "HEART_RATE")
                                },
                            )
                        }
                    put("healthSourceRecords", sources)
                    put("heartRateRecords", hrRecords)
                    getJSONObject("rowCounts").put("healthSourceRecords", 1)
                    getJSONObject("rowCounts").put("heartRateRecords", 1)
                }
            val archive = createBackupZipFile("missing-fk.zip", payload)

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.Failure)
            assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
            archive.delete()
        }

    @Test
    fun applyRestore_rejectsCorruptOrTruncatedJsonAndPreservesSeededData() =
        runTest {
            seedInitialSession()
            val archive = createRawBackupZipFile("truncated.zip", "{\"schemaVersion\": 19, \"exportedAt\":")

            val result = manager.applyRestore(Uri.fromFile(archive))
            assertTrue(result is RestoreResult.Failure)
            assertEquals(listOf("session_1"), db.sleepSessionDao().getSince(0).map { it.id })
            archive.delete()
        }
}
