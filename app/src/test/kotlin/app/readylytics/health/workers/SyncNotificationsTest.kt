package app.readylytics.health.workers

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.domain.sync.ResyncPhase
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
    fun `buildProgressNotification with null phase returns preparing notification at zero progress`() {
        SyncNotifications.ensureChannel(context)
        val notification = SyncNotifications.buildProgressNotification(context, phase = null, current = 0, total = 0)

        assertNotNull(notification)
        assertEquals(SyncNotifications.CHANNEL_ID, notification.channelId)
        assertEquals(0, notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `buildProgressNotification for INGEST fills first quarter of the bar`() {
        SyncNotifications.ensureChannel(context)
        val notification =
            SyncNotifications.buildProgressNotification(context, phase = ResyncPhase.INGEST, current = 2, total = 4)

        assertNotNull(notification)
        assertEquals(SyncNotifications.CHANNEL_ID, notification.channelId)
        // INGEST is slice 0 of 4 (0-25%); 2/4 batches complete → halfway through that slice = 12.5% ≈ 12/13.
        val progress = notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS)
        assertTrue(progress in 10..15)
    }

    @Test
    fun `buildProgressNotification for PRUNE holds at the second phase slice start`() {
        SyncNotifications.ensureChannel(context)
        val notification =
            SyncNotifications.buildProgressNotification(context, phase = ResyncPhase.PRUNE, current = 0, total = 0)

        assertNotNull(notification)
        // PRUNE is slice 1 of 4 → starts at 25%.
        assertEquals(25, notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `buildProgressNotification for RECONCILE holds at the third phase slice start`() {
        SyncNotifications.ensureChannel(context)
        val notification =
            SyncNotifications.buildProgressNotification(context, phase = ResyncPhase.RECONCILE, current = 0, total = 0)

        assertNotNull(notification)
        // RECONCILE is slice 2 of 4 → starts at 50%.
        assertEquals(50, notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
    }

    @Test
    fun `buildProgressNotification for RECOMPUTE fills the final quarter of the bar`() {
        SyncNotifications.ensureChannel(context)
        val notification =
            SyncNotifications.buildProgressNotification(context, phase = ResyncPhase.RECOMPUTE, current = 5, total = 10)

        assertNotNull(notification)
        // RECOMPUTE is slice 3 of 4 (75-100%); halfway through the days → 87.5% ≈ 87/88.
        val progress = notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS)
        assertTrue(progress in 85..90)
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
