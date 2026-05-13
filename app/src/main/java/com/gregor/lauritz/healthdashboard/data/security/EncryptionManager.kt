package com.gregor.lauritz.healthdashboard.data.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
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
class EncryptionManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        init {
            AeadConfig.register()
        }

        private val aead: Aead by lazy {
            AndroidKeysetManager
                .Builder()
                .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
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

        companion object {
            private const val KEYSET_NAME = "master_keyset"
            private const val PREF_FILE_NAME = "master_key_preference"
            private const val MASTER_KEY_URI = "android-keystore://master_key"
        }
    }
