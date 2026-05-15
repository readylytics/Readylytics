package com.gregor.lauritz.healthdashboard.domain.backup

import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFailsWith

class RestoreZipSlipTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `unzip should throw SecurityException when entry contains path traversal`() {
        val destDir = tempFolder.newFolder("dest")
        val zipFile = tempFolder.newFile("malicious.zip")

        // Create a malicious ZIP file with an entry that tries to escape the destination directory
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val entry = ZipEntry("../malicious.txt")
            zos.putNextEntry(entry)
            zos.write("malicious content".toByteArray())
            zos.closeEntry()
        }

        // Create an instance of RestoreUseCase with mocked dependencies
        val restoreUseCase =
            RestoreUseCase(
                context = mockk(),
                driveAuthManager = mockk(),
                driveRepository = mockk(),
                healthDatabase = mockk(),
                settingsRepo = mockk(),
                sqlCipherKeyManager = mockk(),
                encryptionManager = mockk(),
                backupEncryptionHelper = mockk(),
            )

        // Call the private unzip method using reflection
        val unzipMethod =
            RestoreUseCase::class.java.declaredMethods.find { it.name == "unzip" }
                ?: throw IllegalStateException("Could not find unzip method")
        unzipMethod.isAccessible = true

        assertFailsWith<SecurityException> {
            try {
                unzipMethod.invoke(restoreUseCase, zipFile, destDir)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException as Exception
            }
            Unit
        }
    }
}
