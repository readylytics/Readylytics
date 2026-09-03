package app.readylytics.health.core.healthconnect.data.healthconnect

import android.os.RemoteException
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthChangeSyncSupportTest {
    @Test
    fun `isTokenExpiredException returns true for RemoteException`() {
        assertTrue(isTokenExpiredException(mockk<RemoteException>()))
    }

    @Test
    fun `isTokenExpiredException walks the cause chain to find a wrapped remote exception`() {
        val wrapped = IllegalStateException("boom", mockk<RemoteException>())
        assertTrue(isTokenExpiredException(wrapped))
    }

    @Test
    fun `isTokenExpiredException returns false for unrelated exceptions regardless of message`() {
        assertFalse(isTokenExpiredException(IllegalStateException("invalid token provided")))
    }
}
