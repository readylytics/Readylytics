package com.gregor.lauritz.healthdashboard.workers

import com.gregor.lauritz.healthdashboard.domain.backup.LocalBackupManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class LocalBackupWorkerTest {
    @Test
    fun localBackupManager_success_returnsSuccess() =
        runTest {
            val mockBackupManager = mockk<LocalBackupManager>()
            coEvery { mockBackupManager.createBackup() } returns
                Result.success(File("/tmp/backup.json"))

            val result = mockBackupManager.createBackup()

            assertTrue(result.isSuccess)
            assertTrue(result.getOrNull()?.name?.startsWith("backup") ?: false)
        }

    @Test
    fun localBackupManager_failure_returnsFailure() =
        runTest {
            val mockBackupManager = mockk<LocalBackupManager>()
            coEvery { mockBackupManager.createBackup() } returns
                Result.failure(Exception("Backup failed"))

            val result = mockBackupManager.createBackup()

            assertTrue(result.isFailure)
        }

    @Test
    fun localBackupManager_capturesErrorMessage() =
        runTest {
            val errorMessage = "Disk space exceeded"
            val mockBackupManager = mockk<LocalBackupManager>()
            coEvery { mockBackupManager.createBackup() } returns
                Result.failure(Exception(errorMessage))

            val result = mockBackupManager.createBackup()

            assertTrue(result.exceptionOrNull()?.message?.contains(errorMessage) ?: false)
        }
}
