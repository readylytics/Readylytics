package app.readylytics.health.core.database.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidKeystoreKeyProvider
    @Inject
    constructor() : KeyProvider {
        override fun getOrCreateKey(alias: String): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return if (keyStore.containsAlias(alias)) {
                (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
                keyGenerator.init(
                    KeyGenParameterSpec
                        .Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                keyGenerator.generateKey()
            }
        }
    }
