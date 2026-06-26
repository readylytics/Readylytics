package app.readylytics.health.workers

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SyncNotificationsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ─── Constant stability ─────────────────────────────────────────────────────

    @Test
    fun `CHANNEL_ID has expected stable value`() {
        assertEquals("resync_progress", SyncNotifications.CHANNEL_ID)
    }

    @Test
    fun `NOTIFICATION_ID has expected stable value`() {
        assertEquals(4011, SyncNotifications.NOTIFICATION_ID)
    }

    @Test
    fun `BACKGROUND_SYNC_CHANNEL_ID has expected stable value`() {
        assertEquals("background_sync", SyncNotifications.BACKGROUND_SYNC_CHANNEL_ID)
    }

    @Test
    fun `BACKGROUND_SYNC_NOTIFICATION_ID has expected stable value`() {
        assertEquals(4012, SyncNotifications.BACKGROUND_SYNC_NOTIFICATION_ID)
    }

    // ─── ensureChannel ──────────────────────────────────────────────────────────

    @Test
    fun `ensureChannel creates resync progress channel`() {
        SyncNotifications.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager?.getNotificationChannel(SyncNotifications.CHANNEL_ID))
    }

    @Test
    fun `ensureChannel is idempotent when called twice`() {
        SyncNotifications.ensureChannel(context)
        SyncNotifications.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager?.getNotificationChannel(SyncNotifications.CHANNEL_ID))
    }

    // ─── ensureBackgroundSyncChannel ────────────────────────────────────────────

    @Test
    fun `ensureBackgroundSyncChannel creates background sync channel`() {
        SyncNotifications.ensureBackgroundSyncChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager?.getNotificationChannel(SyncNotifications.BACKGROUND_SYNC_CHANNEL_ID))
    }

    @Test
    fun `ensureBackgroundSyncChannel is idempotent when called twice`() {
        SyncNotifications.ensureBackgroundSyncChannel(context)
        SyncNotifications.ensureBackgroundSyncChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager?.getNotificationChannel(SyncNotifications.BACKGROUND_SYNC_CHANNEL_ID))
    }

    // ─── buildProgressNotification ──────────────────────────────────────────────

    @Test
    fun `buildProgressNotification with total greater than zero returns notification with resync channel`() {
        SyncNotifications.ensureChannel(context)
        val notification = SyncNotifications.buildProgressNotification(context, current = 5, total = 10)

        assertNotNull(notification)
        assertEquals(SyncNotifications.CHANNEL_ID, notification.channelId)
    }

    @Test
    fun `buildProgressNotification with zero total returns indeterminate notification`() {
        SyncNotifications.ensureChannel(context)
        val notification = SyncNotifications.buildProgressNotification(context, current = 0, total = 0)

        assertNotNull(notification)
        assertEquals(SyncNotifications.CHANNEL_ID, notification.channelId)
    }

    // ─── buildBackgroundSyncNotification ────────────────────────────────────────

    @Test
    fun `buildBackgroundSyncNotification returns notification with background sync channel`() {
        SyncNotifications.ensureBackgroundSyncChannel(context)
        val notification = SyncNotifications.buildBackgroundSyncNotification(context)

        assertNotNull(notification)
        assertEquals(SyncNotifications.BACKGROUND_SYNC_CHANNEL_ID, notification.channelId)
    }
}
