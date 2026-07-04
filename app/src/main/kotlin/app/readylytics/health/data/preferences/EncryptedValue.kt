package app.readylytics.health.data.preferences

/**
 * Type-safe wrapper for encrypted ciphertext strings.
 * Prevents accidental use of encrypted values as plaintext by forcing explicit intent.
 */
@JvmInline
value class EncryptedValue(
    val ciphertext: String,
) {
    companion object {
        fun from(plaintext: String?): EncryptedValue? = plaintext?.let { EncryptedValue(it) }
    }
}
