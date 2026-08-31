package app.readylytics.health.ui.scaffold

import app.readylytics.health.core.model.domain.sync.RecalcProgress
import app.readylytics.health.core.model.domain.sync.ResyncPhase
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
                recalcProgress = RecalcProgress(phase = ResyncPhase.RECOMPUTE, current = 1, total = 10),
                isResyncing = true,
                hasSeenProgress = false,
            )

        assertEquals(SyncProgressDismissalState.MarkProgressSeen, result)
    }

    @Test
    fun `settings opens sync progress when a resync starts and nothing was dismissed`() {
        val result =
            resolveSyncProgressEntryAction(
                isResyncing = true,
                resyncScreenDismissed = false,
            )

        assertEquals(SyncProgressEntryAction.Open, result)
    }

    @Test
    fun `settings does not reopen sync progress after continue in background`() {
        val result =
            resolveSyncProgressEntryAction(
                isResyncing = true,
                resyncScreenDismissed = true,
            )

        assertEquals(SyncProgressEntryAction.None, result)
    }

    @Test
    fun `dismissal is cleared once the resync finishes so the next run can auto-open`() {
        val dismissedWhileRunning =
            resolveSyncProgressEntryAction(isResyncing = true, resyncScreenDismissed = true)
        val finished =
            resolveSyncProgressEntryAction(isResyncing = false, resyncScreenDismissed = true)
        val nextRun =
            resolveSyncProgressEntryAction(isResyncing = true, resyncScreenDismissed = false)

        assertEquals(SyncProgressEntryAction.None, dismissedWhileRunning)
        assertEquals(SyncProgressEntryAction.ClearDismissal, finished)
        assertEquals(SyncProgressEntryAction.Open, nextRun)
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
