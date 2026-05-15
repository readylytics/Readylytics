package com.gregor.lauritz.healthdashboard.data.security

/**
 * Lifecycle status of the SQLCipher-backed database encryption layer.
 *
 * Reported by [SqlCipherKeyManager.encryptionStatus] and consumed by the
 * [EncryptionHealthCheck] performed at startup. The application MUST fail
 * fast (refusing to expose user data) when the status is not [INITIALIZED].
 *
 * Encryption required; no plaintext fallback.
 */
enum class EncryptionStatus {
    /** Key material generated/loaded successfully and database is opening with the configured key. */
    INITIALIZED,

    /** Key material has not yet been generated or queried for this process. */
    UNINITIALIZED,

    /**
     * Key generation, key derivation, or AndroidKeyStore access failed.
     * The database MUST NOT be opened — the app should crash to protect user data.
     */
    FAILED,
}
