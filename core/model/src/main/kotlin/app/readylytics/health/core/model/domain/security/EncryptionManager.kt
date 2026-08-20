package app.readylytics.health.core.model.domain.security

interface EncryptionManager {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}
