package com.gregor.lauritz.healthdashboard.data.security

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptionHealthCheckTest {
    private val keyManager: SqlCipherKeyManager = mockk(relaxed = true)
    private val healthCheck = EncryptionHealthCheck(keyManager)

    private fun mockCursor(value: String?): Cursor =
        mockk(relaxed = true) {
            every { moveToFirst() } returns (value != null)
            every { getString(0) } returns (value ?: "")
            every { close() } returns Unit
        }

    private fun mockDb(
        integrityValue: String? = "ok",
        cipherVersion: String? = "4.5.5 community",
    ): SupportSQLiteDatabase =
        mockk(relaxed = true) {
            every { query("PRAGMA integrity_check") } returns mockCursor(integrityValue)
            every { query("PRAGMA cipher_version") } returns mockCursor(cipherVersion)
        }

    @Test
    fun `verify returns INITIALIZED report when key manager initialised and integrity ok`() {
        every { keyManager.encryptionStatus() } returns EncryptionStatus.INITIALIZED

        val db = mockDb(integrityValue = "ok", cipherVersion = "4.5.5 community")
        val report = healthCheck.verify(db)

        assertEquals(EncryptionStatus.INITIALIZED, report.status)
        assertTrue(report.integrityOk)
        assertEquals("4.5.5 community", report.cipherVersion)
    }

    @Test
    fun `verify throws when encryption status is UNINITIALIZED`() {
        every { keyManager.encryptionStatus() } returns EncryptionStatus.UNINITIALIZED

        val db = mockDb()
        val ex =
            assertThrows(EncryptionUnavailableException::class.java) {
                healthCheck.verify(db)
            }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("UNINITIALIZED"))
    }

    @Test
    fun `verify throws when encryption status is FAILED`() {
        every { keyManager.encryptionStatus() } returns EncryptionStatus.FAILED
        val db = mockDb()
        assertThrows(EncryptionUnavailableException::class.java) {
            healthCheck.verify(db)
        }
    }

    @Test
    fun `verify throws when encryptionStatus itself throws`() {
        every { keyManager.encryptionStatus() } throws RuntimeException("keystore broken")

        val db = mockDb()
        assertThrows(EncryptionUnavailableException::class.java) {
            healthCheck.verify(db)
        }
    }

    @Test
    fun `verify throws when PRAGMA integrity_check returns non-ok`() {
        every { keyManager.encryptionStatus() } returns EncryptionStatus.INITIALIZED

        val db = mockDb(integrityValue = "*** in database main *** page 7 is never used")
        val ex =
            assertThrows(EncryptionUnavailableException::class.java) {
                healthCheck.verify(db)
            }
        assertTrue(ex.message!!.contains("integrity_check"))
    }

    @Test
    fun `verify still succeeds when cipher_version unavailable (best-effort)`() {
        every { keyManager.encryptionStatus() } returns EncryptionStatus.INITIALIZED
        val db = mockDb(integrityValue = "ok", cipherVersion = null)
        val report = healthCheck.verify(db)
        assertEquals(EncryptionStatus.INITIALIZED, report.status)
        assertTrue(report.integrityOk)
    }
}
