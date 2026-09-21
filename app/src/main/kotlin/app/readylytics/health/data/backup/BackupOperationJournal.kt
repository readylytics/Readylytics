package app.readylytics.health.data.backup

import android.content.Context
import app.readylytics.health.core.model.domain.backup.ArchiveRotationEntry
import app.readylytics.health.core.model.domain.backup.BackupOperationPhase
import app.readylytics.health.core.model.domain.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class RotationJournalData(
    val protocolVersion: Int = 1,
    val operationId: String,
    val directoryUri: String?,
    val phase: BackupOperationPhase,
    val encryptedOldPassword: String?,
    val encryptedNewPassword: String?,
    val targetPasswordHash: String?,
    val entries: List<ArchiveRotationEntry>,
    val selectedGeneration: Long,
)

@Singleton
class BackupOperationJournal
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val encryptionManager: EncryptionManager,
    ) {
        val journalFile: File by lazy {
            File(context.filesDir, "backup_rotation_journal.enc")
        }

        fun read(file: File = journalFile): RotationJournalData? {
            if (!file.exists() || file.length() == 0L) return null
            return runCatching {
                val ciphertext = file.readText(Charsets.UTF_8)
                encryptionManager.decrypt(ciphertext)?.let { deserialize(it) }
            }.getOrNull()
        }

        fun write(
            data: RotationJournalData,
            file: File = journalFile,
        ) {
            val plaintext = serialize(data)
            val ciphertext = encryptionManager.encrypt(plaintext)
            file.parentFile?.mkdirs()
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(ciphertext, Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
        }

        fun delete(file: File = journalFile) {
            if (file.exists()) {
                file.delete()
            }
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }

        private fun serialize(data: RotationJournalData): String {
            val json = JSONObject()
            json.put("protocolVersion", data.protocolVersion)
            json.put("operationId", data.operationId)
            json.put("directoryUri", data.directoryUri ?: JSONObject.NULL)
            json.put("phase", data.phase.name)
            json.put("encryptedOldPassword", data.encryptedOldPassword ?: JSONObject.NULL)
            json.put("encryptedNewPassword", data.encryptedNewPassword ?: JSONObject.NULL)
            json.put("targetPasswordHash", data.targetPasswordHash ?: JSONObject.NULL)
            json.put("selectedGeneration", data.selectedGeneration)

            val entriesArray = JSONArray()
            data.entries.forEach { entry ->
                val entryJson = JSONObject()
                entryJson.put("originalLocation", entry.originalLocation)
                entryJson.put("stagedLocation", entry.stagedLocation ?: JSONObject.NULL)
                entryJson.put("publishedLocation", entry.publishedLocation ?: JSONObject.NULL)
                entryJson.put("verified", entry.verified)
                entriesArray.put(entryJson)
            }
            json.put("entries", entriesArray)
            return json.toString()
        }

        private fun deserialize(plaintext: String): RotationJournalData {
            val json = JSONObject(plaintext)
            val entriesArray = json.optJSONArray("entries") ?: JSONArray()
            val entries = mutableListOf<ArchiveRotationEntry>()
            for (i in 0 until entriesArray.length()) {
                val entryJson = entriesArray.getJSONObject(i)
                entries.add(
                    ArchiveRotationEntry(
                        originalLocation = entryJson.getString("originalLocation"),
                        stagedLocation =
                            entryJson
                                .optString(
                                    "stagedLocation",
                                ).takeIf { it.isNotBlank() && it != "null" },
                        publishedLocation =
                            entryJson.optString("publishedLocation").takeIf {
                                it.isNotBlank() && it != "null"
                            },
                        verified = entryJson.optBoolean("verified", false),
                    ),
                )
            }
            return RotationJournalData(
                protocolVersion = json.optInt("protocolVersion", 1),
                operationId = json.getString("operationId"),
                directoryUri = json.optString("directoryUri").takeIf { it.isNotBlank() && it != "null" },
                phase =
                    runCatching {
                        BackupOperationPhase.valueOf(json.getString("phase"))
                    }.getOrDefault(BackupOperationPhase.IDLE),
                encryptedOldPassword =
                    json.optString("encryptedOldPassword").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                encryptedNewPassword =
                    json.optString("encryptedNewPassword").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                targetPasswordHash = json.optString("targetPasswordHash").takeIf { it.isNotBlank() && it != "null" },
                entries = entries,
                selectedGeneration = json.optLong("selectedGeneration", 0L),
            )
        }
    }
