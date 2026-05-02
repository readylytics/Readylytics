package com.gregor.lauritz.healthdashboard.data.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages encryption and decryption using Google Tink.
 * Uses Android Keystore for master key protection.
 */
@Singleton
class EncryptionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    init {
        AeadConfig.register()
    }

    private val aead: Aead by lazy {
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    /**
     * Encrypts a string and returns a Base64 encoded ciphertext.
     */
    fun encrypt(plaintext: String): String {
        val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null)
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    /**
     * Decrypts a Base64 encoded ciphertext and returns the original string.
     */
    fun decrypt(ciphertext: String): String {
        val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
        val plaintext = aead.decrypt(decoded, null)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Provides a secure key for SQLite/SQLCipher encryption.
     * This key is derived or managed by Tink to ensure it's protected.
     * In a production app, this might involve checking if a key exists,
     * generating one if not, and storing it encrypted.
     */
    fun getDatabaseKey(): String {
        // For this implementation, we'll use a stable hash of the keyset handle
        // or a dedicated encrypted preference.
        // Simplified: use a stable identifier derived from the master key.
        // A better approach is to store a random 32-byte key encrypted in prefs.
        val prefs = context.getSharedPreferences(DB_KEY_PREFS, Context.MODE_PRIVATE)
        val encryptedKey = prefs.getString(DB_KEY_NAME, null)
        
        return if (encryptedKey != null) {
            decrypt(encryptedKey)
        } else {
            val newKey = java.util.UUID.randomUUID().toString() // 36 chars of entropy
            val encrypted = encrypt(newKey)
            prefs.edit().putString(DB_KEY_NAME, encrypted).apply()
            newKey
        }
    }

    companion object {
        private const val KEYSET_NAME = "master_keyset"
        private const val PREF_FILE_NAME = "master_key_preference"
        private const val MASTER_KEY_URI = "android-keystore://master_key"
        
        private const val DB_KEY_PREFS = "db_encryption_prefs"
        private const val DB_KEY_NAME = "encrypted_db_key"
    }
}
