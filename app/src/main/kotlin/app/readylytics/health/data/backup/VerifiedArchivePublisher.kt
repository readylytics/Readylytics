package app.readylytics.health.data.backup

import app.readylytics.health.core.model.domain.backup.BackupLocation
import net.lingala.zip4j.ZipFile
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VerifiedArchivePublisher
    @Inject
    constructor(
        private val inventoryValidator: RestoreInventoryValidator,
    ) {
        suspend fun publishAndVerify(
            store: BackupStore,
            stagedFile: File,
            targetName: String,
            password: String?,
        ): BackupLocation {
            check(stagedFile.exists() && stagedFile.length() > 0) {
                "Staged archive must exist and be non-empty before publication"
            }
            val location = store.publishNew(stagedFile, targetName)
            var verified = false
            try {
                verifyPublishedArchive(store, location, password)
                verified = true
                return location
            } finally {
                if (!verified) {
                    runCatching { store.delete(location) }
                }
            }
        }

        suspend fun verifyPublishedArchive(
            store: BackupStore,
            location: BackupLocation,
            password: String?,
        ) {
            val tempVerifyFile = File.createTempFile("verify_readback_", ".zip")
            try {
                store.read(location).use { input ->
                    tempVerifyFile.outputStream().use { output -> input.copyTo(output) }
                }
                check(tempVerifyFile.length() > 0) { "Read-back published archive is empty" }
                val passwordChars = password?.takeIf { it.isNotEmpty() }?.toCharArray()
                ZipFile(tempVerifyFile, passwordChars).use { zip ->
                    val header =
                        zip.getFileHeader("backup.json")
                            ?: error("Archive missing backup.json entry")
                    zip.getInputStream(header).use { jsonStream ->
                        inventoryValidator.validate(jsonStream)
                    }
                }
            } finally {
                tempVerifyFile.delete()
            }
        }
    }
