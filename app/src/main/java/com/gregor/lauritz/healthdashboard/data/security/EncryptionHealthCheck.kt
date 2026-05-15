package com.gregor.lauritz.healthdashboard.data.security

import androidx.sqlite.db.SupportSQLiteDatabase
import com.gregor.lauritz.healthdashboard.domain.util.logD
import com.gregor.lauritz.healthdashboard.domain.util.logE

/**
 * Runs a series of fail-fast assertions to guarantee that the database the app is
 * about to read/write is actually encrypted with a key derived from the
 * AndroidKeyStore-protected master key.
 *
 * HIPAA / GDPR / Health-data regulation context: a silent plaintext fallback would
 * be catastrophic; therefore any failure here MUST propagate as an exception so
 * the app crashes before user data can be exposed.
 */
class EncryptionHealthCheck(
    private val keyManager: SqlCipherKeyManager,
) {
    /**
     * Diagnostic snapshot for telemetry / logging. All fields are best-effort.
     */
    data class Report(
        val status: EncryptionStatus,
        val keyDerivationMs: Long,
        val dbOpenMs: Long,
        val integrityOk: Boolean,
        val cipherVersion: String?,
    )

    /**
     * @throws EncryptionUnavailableException if any verification step fails.
     */
    fun verify(db: SupportSQLiteDatabase): Report {
        val tStart = System.nanoTime()

        // 1) Key derivation must succeed and produce INITIALIZED state.
        val keyDerivationStart = System.nanoTime()
        val status =
            try {
                keyManager.encryptionStatus()
            } catch (t: Throwable) {
                logE("EncryptionHealthCheck", t) { "encryptionStatus() threw" }
                EncryptionStatus.FAILED
            }
        val keyDerivationMs = (System.nanoTime() - keyDerivationStart) / 1_000_000L

        if (status != EncryptionStatus.INITIALIZED) {
            throw EncryptionUnavailableException(
                "SQLCipher key manager reported status=$status; refusing to open database.",
            )
        }

        // 2) PRAGMA integrity_check must return "ok". A corrupt or wrongly-keyed
        //    database would fail the read (SQLITE_NOTADB) before we get here, but
        //    integrity_check additionally validates page structure.
        val dbOpenStart = System.nanoTime()
        val integrityOk = runIntegrityCheck(db)
        val dbOpenMs = (System.nanoTime() - dbOpenStart) / 1_000_000L

        if (!integrityOk) {
            throw EncryptionUnavailableException(
                "PRAGMA integrity_check failed for encrypted database.",
            )
        }

        // 3) Read cipher_version (best-effort) for diagnostics.
        val cipherVersion = runCatching { readCipherVersion(db) }.getOrNull()

        val totalMs = (System.nanoTime() - tStart) / 1_000_000L
        logD("EncryptionHealthCheck") {
            "Database encryption verified at startup (totalMs=$totalMs " +
                "keyDerivationMs=$keyDerivationMs dbOpenMs=$dbOpenMs cipher=$cipherVersion)"
        }

        return Report(
            status = status,
            keyDerivationMs = keyDerivationMs,
            dbOpenMs = dbOpenMs,
            integrityOk = integrityOk,
            cipherVersion = cipherVersion,
        )
    }

    private fun runIntegrityCheck(db: SupportSQLiteDatabase): Boolean =
        try {
            db.query("PRAGMA integrity_check").use { cursor ->
                if (cursor.moveToFirst()) {
                    val result = cursor.getString(0)
                    "ok".equals(result, ignoreCase = true)
                } else {
                    false
                }
            }
        } catch (t: Throwable) {
            logE("EncryptionHealthCheck", t) { "PRAGMA integrity_check threw" }
            false
        }

    private fun readCipherVersion(db: SupportSQLiteDatabase): String? =
        db.query("PRAGMA cipher_version").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}

/**
 * Fatal: encryption could not be initialised / verified. The app must crash —
 * no plaintext fallback is allowed for health data at rest.
 */
class EncryptionUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
