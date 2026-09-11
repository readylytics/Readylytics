package app.readylytics.health.data.backup

import android.util.JsonReader
import android.util.JsonToken
import app.readylytics.health.core.model.domain.backup.BackupInventoryPolicy
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

data class ValidatedBackupInventory(
    val manifest: BackupManifest,
    val declaredCounts: Map<String, Long>,
    val observedCounts: Map<String, Long>,
)

@Singleton
class RestoreInventoryValidator
    @Inject
    constructor() {
        fun validate(inputStream: InputStream): ValidatedBackupInventory =
            JsonReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                validate(reader)
            }

        fun validate(reader: JsonReader): ValidatedBackupInventory {
            var schemaVersion: Int? = null
            var exportedAt: String? = null
            var sourceGeneration: Long? = null
            val declaredCounts = mutableMapOf<String, Long>()
            val observedCounts = mutableMapOf<String, Long>()
            val encounteredKeys = mutableSetOf<String>()

            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                require(encounteredKeys.add(key)) {
                    "BACKUP_DUPLICATE_KEY: Duplicate top-level key '$key'"
                }
                when (key) {
                    "schemaVersion" -> {
                        val version = reader.nextInt()
                        BackupSchemaPolicy.requireSupported(version)
                        schemaVersion = version
                    }
                    "exportedAt" -> {
                        exportedAt = reader.nextString()
                    }
                    "sourceGeneration" -> {
                        sourceGeneration = reader.nextLong()
                    }
                    "rowCounts" -> {
                        readRowCounts(reader, declaredCounts)
                    }
                    "preferences" -> {
                        reader.skipValue()
                    }
                    else -> {
                        val count = readArrayCount(reader)
                        if (count != null) {
                            observedCounts[key] = count
                        }
                    }
                }
            }
            reader.endObject()

            val version =
                requireNotNull(schemaVersion) {
                    "BACKUP_REQUIRED_TABLE_MISSING: Missing schemaVersion"
                }
            val exported = exportedAt ?: ""
            require("rowCounts" in encounteredKeys) { "BACKUP_REQUIRED_COUNT_MISSING: Missing rowCounts" }

            val required = BackupInventoryPolicy.requiredTables(version)
            BackupInventoryPolicy.validateInventory(required, declaredCounts, observedCounts)

            val manifest =
                BackupManifest(
                    schemaVersion = version,
                    exportedAt = exported,
                    rowCounts = declaredCounts.mapValues { it.value.toInt() },
                    sourceGeneration = sourceGeneration ?: 0L,
                )
            return ValidatedBackupInventory(manifest, declaredCounts, observedCounts)
        }

        private fun readArrayCount(reader: JsonReader): Long? {
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                reader.skipValue()
                return null
            }
            reader.beginArray()
            var count = 0L
            while (reader.hasNext()) {
                reader.skipValue()
                count++
            }
            reader.endArray()
            return count
        }

        private fun readRowCounts(
            reader: JsonReader,
            declaredCounts: MutableMap<String, Long>,
        ) {
            val declaredKeys = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val tableName = reader.nextName()
                require(declaredKeys.add(tableName)) {
                    "BACKUP_DUPLICATE_KEY: Duplicate rowCounts key '$tableName'"
                }
                val count = reader.nextLong()
                declaredCounts[tableName] = count
            }
            reader.endObject()
        }
    }
