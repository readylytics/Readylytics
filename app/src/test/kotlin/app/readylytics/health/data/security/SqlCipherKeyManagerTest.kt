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
import javax.crypto.SecretKey
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SqlCipherKeyManagerTest {
    private lateinit var keyManager: SqlCipherKeyManager
    private lateinit var fakeKeyProvider: FakeKeyProvider
    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeKeyProvider : KeyProvider {
        var callCount = 0

        override fun getOrCreateKey(alias: String): SecretKey {
            callCount++
            return javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
        }
    }

    @Before
    fun setUp() {
        fakeKeyProvider = FakeKeyProvider()
        keyManager = SqlCipherKeyManager(context, fakeKeyProvider)
    }

    private fun setupCorruptedPrefs() {
        context
            .getSharedPreferences(SqlCipherKeyManager.PREF_FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SqlCipherKeyManager.PREF_ENCRYPTED_KEY, "corrupted_base64_data")
            .putString(SqlCipherKeyManager.PREF_IV, "corrupted_iv_data")
            .commit()
    }

    @Test
    fun isKeyCorrupted_initiallyFalse() {
        assertFalse(keyManager.isKeyCorrupted.value)
    }

    @Test
    fun validateKeyDecryption_withCorruptedData_setsCorruptionState() {
        setupCorruptedPrefs()

        assertThrows(KeyDecryptionException::class.java) {
            keyManager.validateKeyDecryption()
        }

        assertTrue(keyManager.isKeyCorrupted.value)
    }

    @Test
    fun validateKeyDecryption_delegatesToKeyProvider() {
        setupCorruptedPrefs()

        assertThrows(KeyDecryptionException::class.java) {
            keyManager.validateKeyDecryption()
        }

        assertTrue(fakeKeyProvider.callCount > 0)
    }

    @Test
    fun resetKeyAndDatabase_clearsCorruptionState() {
        setupCorruptedPrefs()

        assertThrows(KeyDecryptionException::class.java) {
            keyManager.validateKeyDecryption()
        }

        assertTrue(keyManager.isKeyCorrupted.value)

        val dummyFile = File(context.filesDir, "dummy.db")
        keyManager.resetKeyAndDatabase(dummyFile)

        assertFalse(keyManager.isKeyCorrupted.value)
    }
}
