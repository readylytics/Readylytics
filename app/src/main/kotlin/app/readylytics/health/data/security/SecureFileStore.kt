package app.readylytics.health.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import app.readylytics.health.domain.util.logW
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.KeyGenerator

fun secureFileAssociatedData(file: File): ByteArray = file.name.toByteArray(Charsets.UTF_8)

interface SecureFileStore {
    fun readText(
        file: File,
        associatedData: ByteArray = secureFileAssociatedData(file),
    ): String

    fun writeText(
        file: File,
        content: String,
        associatedData: ByteArray = secureFileAssociatedData(file),
    )
}

internal class TinkSecureFileStore internal constructor(
    private val cipher: StreamingFileCipher,
) : SecureFileStore {
    override fun readText(
        file: File,
        associatedData: ByteArray,
    ): String {
        if (!file.exists()) return ""

        return try {
            file.inputStream().use { input ->
                cipher
                    .newDecryptingStream(input, associatedData)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            }
        } catch (e: Exception) {
            logW(TAG, e) { "Failed to decrypt ${file.name}; treating as empty" }
            ""
        }
    }

    override fun writeText(
        file: File,
        content: String,
        associatedData: ByteArray,
    ) {
        require(content.isNotEmpty()) { "writeText expects non-empty content" }

        file.parentFile?.mkdirs()
        val tempFile = File.createTempFile(file.name, ".tmp", file.parentFile)

        try {
            tempFile.outputStream().use { output ->
                cipher
                    .newEncryptingStream(output, associatedData)
                    .use { encryptedOutput ->
                        encryptedOutput.write(content.toByteArray(Charsets.UTF_8))
                    }
            }
            replaceAtomically(tempFile, file)
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    private fun replaceAtomically(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        private const val TAG = "TinkSecureFileStore"

        fun create(context: Context): SecureFileStore = TinkSecureFileStore(TinkStreamingFileCipher(context))
    }
}

internal interface StreamingFileCipher {
    fun newEncryptingStream(
        outputStream: OutputStream,
        associatedData: ByteArray,
    ): OutputStream

    fun newDecryptingStream(
        inputStream: InputStream,
        associatedData: ByteArray,
    ): InputStream
}

internal class TinkStreamingFileCipher(
    private val context: Context,
) : StreamingFileCipher {
    private val streamingAead: StreamingAead by lazy {
        StreamingAeadConfig.register()
        ensureMasterKeyCreated(KEY_ALIAS)
        AndroidKeysetManager
            .Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE_NAME))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    override fun newEncryptingStream(
        outputStream: OutputStream,
        associatedData: ByteArray,
    ): OutputStream = streamingAead.newEncryptingStream(outputStream, associatedData)

    override fun newDecryptingStream(
        inputStream: InputStream,
        associatedData: ByteArray,
    ): InputStream = streamingAead.newDecryptingStream(inputStream, associatedData)

    private fun ensureMasterKeyCreated(alias: String) {
        val isTest = System.getProperty("java.runtime.name")?.contains("Android", ignoreCase = true) == false
        if (isTest) return

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            return
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
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

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "readylytics_log_file_key_v1"
        private const val KEYSET_NAME = "secure_log_file_keyset"
        private const val PREF_FILE_NAME = "secure_log_file_keyset_prefs"
        private const val KEY_TEMPLATE_NAME = "AES256_GCM_HKDF_4KB"
        private const val MASTER_KEY_URI = "android-keystore://$KEY_ALIAS"
    }
}
