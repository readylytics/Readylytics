package app.readylytics.health.ui.scaffold

import app.readylytics.health.domain.sync.RecalcProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavHostTest {
    @Test
    fun `sync progress stays open while authoritative resync state is loading`() {
        val result =
            shouldAutoDismissSyncProgress(
                recalcProgress = null,
                isResyncing = null,
                hasSeenProgress = false,
            )

        assertEquals(SyncProgressDismissalState.StayOpen, result)
    }

    @Test
    fun `sync progress stays open while resync is running before progress is emitted`() {
        val result =
            shouldAutoDismissSyncProgress(
                recalcProgress = null,
                isResyncing = true,
                hasSeenProgress = false,
            )

        assertEquals(SyncProgressDismissalState.StayOpen, result)
    }

    @Test
    fun `sync progress dismisses when resync finishes before progress is emitted`() {
        val result =
            shouldAutoDismissSyncProgress(
                recalcProgress = null,
                isResyncing = false,
                hasSeenProgress = false,
            )

        assertEquals(SyncProgressDismissalState.Dismiss, result)
    }

    @Test
    fun `sync progress waits for loaded active resync before dismissing on completion`() {
        val loading =
            shouldAutoDismissSyncProgress(
                recalcProgress = null,
                isResyncing = null,
                hasSeenProgress = false,
            )
        val active =
            shouldAutoDismissSyncProgress(
                recalcProgress = null,
                isResyncing = true,
                hasSeenProgress = false,
            )
        val completed =
            shouldAutoDismissSyncProgress(
                recalcProgress = null,
                isResyncing = false,
                hasSeenProgress = false,
            )

        assertEquals(SyncProgressDismissalState.StayOpen, loading)
        assertEquals(SyncProgressDismissalState.StayOpen, active)
        assertEquals(SyncProgressDismissalState.Dismiss, completed)
    }

    @Test
    fun `sync progress marks progress as seen when determinate progress appears`() {
        val result =
            shouldAutoDismissSyncProgress(
                recalcProgress = RecalcProgress(current = 1, total = 10),
                isResyncing = true,
                hasSeenProgress = false,
            )

        assertEquals(SyncProgressDismissalState.MarkProgressSeen, result)
    }

    @Test
    fun `sync progress dismisses when determinate progress disappears after being seen`() {
        val result =
            shouldAutoDismissSyncProgress(
                recalcProgress = null,
                isResyncing = false,
                hasSeenProgress = true,
            )

        assertEquals(SyncProgressDismissalState.Dismiss, result)
    }
}
