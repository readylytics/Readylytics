package app.readylytics.health.domain.security

interface EncryptionManager {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}
