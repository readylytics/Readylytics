package app.readylytics.health.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.data.security.SqlCipherKeyManager.KeyDecryptionException
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SqlCipherKeyManagerTest {
    private lateinit var keyManager: SqlCipherKeyManager
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        keyManager = SqlCipherKeyManager(context)
    }

    @Test
    fun isKeyCorrupted_initiallyFalse() {
        assertFalse(keyManager.isKeyCorrupted.value)
    }

    @Test
    fun validateKeyDecryption_withCorruptedData_setsCorruptionState() {
        val prefs = context.getSharedPreferences("sqlcipher_key_prefs", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putString("encrypted_key", "corrupted_base64_data")
            .putString("encryption_iv", "corrupted_iv_data")
            .commit()

        assertThrows(KeyDecryptionException::class.java) {
            keyManager.validateKeyDecryption()
        }

        assertTrue(keyManager.isKeyCorrupted.value)
    }

    @Test
    fun resetKeyAndDatabase_clearsCorruptionState() {
        val prefs = context.getSharedPreferences("sqlcipher_key_prefs", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putString("encrypted_key", "corrupted_base64_data")
            .putString("encryption_iv", "corrupted_iv_data")
            .commit()

        assertThrows(KeyDecryptionException::class.java) {
            keyManager.validateKeyDecryption()
        }

        assertTrue(keyManager.isKeyCorrupted.value)

        val dummyFile = File(context.filesDir, "dummy.db")
        keyManager.resetKeyAndDatabase(dummyFile)

        assertFalse(keyManager.isKeyCorrupted.value)
    }
}
