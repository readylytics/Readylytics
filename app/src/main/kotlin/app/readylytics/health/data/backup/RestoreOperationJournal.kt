package app.readylytics.health.data.backup

import android.content.Context
import app.readylytics.health.core.model.domain.backup.RestorePhase
import app.readylytics.health.core.model.domain.security.EncryptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class RestoreJournalData(
    val protocolVersion: Int = 1,
    val operationId: String,
    val archiveLocation: String,
    val restoredGeneration: Long,
    val phase: RestorePhase,
    val preferencesJson: String?,
    val encryptedPassword: String?,
)

@Singleton
class RestoreOperationJournal
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val encryptionManager: EncryptionManager,
    ) {
        val journalFile: File by lazy {
            File(context.filesDir, "restore_operation_journal.enc")
        }

        fun read(file: File = journalFile): RestoreJournalData? {
            if (!file.exists() || file.length() == 0L) return null
            return runCatching {
                val ciphertext = file.readText(Charsets.UTF_8)
                encryptionManager.decrypt(ciphertext)?.let { deserialize(it) }
            }.getOrNull()
        }

        fun write(
            data: RestoreJournalData,
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

        fun delete(file: File = journalFile): Boolean =
            file.delete().also {
                File(file.parentFile, "${file.name}.tmp").delete()
            }

        private fun serialize(data: RestoreJournalData): String =
            JSONObject()
                .apply {
                    put("protocolVersion", data.protocolVersion)
                    put("operationId", data.operationId)
                    put("archiveLocation", data.archiveLocation)
                    put("restoredGeneration", data.restoredGeneration)
                    put("phase", data.phase.name)
                    putOpt("preferencesJson", data.preferencesJson)
                    putOpt("encryptedPassword", data.encryptedPassword)
                }.toString()

        private fun deserialize(jsonStr: String): RestoreJournalData {
            val json = JSONObject(jsonStr)
            val prefsStr =
                if (json.has("preferencesJson") && !json.isNull("preferencesJson")) {
                    json.getString("preferencesJson")
                } else {
                    null
                }
            val passStr =
                if (json.has("encryptedPassword") && !json.isNull("encryptedPassword")) {
                    json.getString("encryptedPassword")
                } else {
                    null
                }
            return RestoreJournalData(
                protocolVersion = json.optInt("protocolVersion", 1),
                operationId = json.getString("operationId"),
                archiveLocation = json.getString("archiveLocation"),
                restoredGeneration = json.getLong("restoredGeneration"),
                phase = RestorePhase.valueOf(json.getString("phase")),
                preferencesJson = prefsStr,
                encryptedPassword = passStr,
            )
        }
    }
