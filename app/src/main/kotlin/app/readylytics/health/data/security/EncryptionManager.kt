package app.readylytics.health.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
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
        @param:ApplicationContext private val context: Context,
        private val keyMetadataStore: KeyMetadataStore,
    ) : app.readylytics.health.domain.security.EncryptionManager {
        init {
            AeadConfig.register()
        }

        private val aead: Aead by lazy {
            val alias = keyAliasForVersion(CURRENT_KEY_VERSION)
            ensureMasterKeyCreated(alias)
            try {
                AndroidKeysetManager
                    .Builder()
                    .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri(masterKeyUriForVersion(CURRENT_KEY_VERSION))
                    .build()
                    .keysetHandle
                    .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            } catch (e: Exception) {
                // Fallback to legacy master key if version 1 key fails to read/decrypt existing keyset
                AndroidKeysetManager
                    .Builder()
                    .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
                    .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                    .withMasterKeyUri("android-keystore://master_key")
                    .build()
                    .keysetHandle
                    .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            }
        }

        private fun ensureMasterKeyCreated(alias: String) {
            val isTest = System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == false
            if (isTest) return

            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                return
            }

            try {
                generateKey(alias, useStrongBox = true)
                keyMetadataStore.setCurrentKey(CURRENT_KEY_VERSION, strongBoxBacked = true)
            } catch (e: Exception) {
                try {
                    generateKey(alias, useStrongBox = false)
                    keyMetadataStore.setCurrentKey(CURRENT_KEY_VERSION, strongBoxBacked = false)
                } catch (ex: Exception) {
                    throw RuntimeException("Failed to generate master key", ex)
                }
            }
        }

        private fun generateKey(
            alias: String,
            useStrongBox: Boolean,
        ) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val builder =
                KeyGenParameterSpec
                    .Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && useStrongBox) {
                builder.setIsStrongBoxBacked(true)
            }

            keyGenerator.init(builder.build())
            keyGenerator.generateKey()
        }

        /**
         * Encrypts a string and returns a Base64 encoded ciphertext.
         */
        override fun encrypt(plaintext: String): String {
            val ciphertext = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), null)
            return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }

        /**
         * Decrypts a Base64 encoded ciphertext and returns the original string.
         */
        override fun decrypt(ciphertext: String): String? =
            try {
                val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
                val plaintext = aead.decrypt(decoded, null)
                String(plaintext, Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }

        companion object {
            private const val KEYSET_NAME = "master_keyset"
            private const val PREF_FILE_NAME = "master_key_preference"
            private const val CURRENT_KEY_VERSION = 1

            fun keyAliasForVersion(version: Int): String = "readylytics_master_key_v$version"

            fun masterKeyUriForVersion(version: Int): String = "android-keystore://${keyAliasForVersion(version)}"
        }
    }
