package app.readylytics.health.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class RecalcProgressTest {
    @Test
    fun `each phase starts its own equal slice with current zero`() {
        assertEquals(0.00f, RecalcProgress(ResyncPhase.INGEST, 0, 0).fraction(), 0.001f)
        assertEquals(0.25f, RecalcProgress(ResyncPhase.PRUNE, 0, 0).fraction(), 0.001f)
        assertEquals(0.50f, RecalcProgress(ResyncPhase.RECONCILE, 0, 0).fraction(), 0.001f)
        assertEquals(0.75f, RecalcProgress(ResyncPhase.RECOMPUTE, 0, 0).fraction(), 0.001f)
    }

    @Test
    fun `INGEST fills within its slice proportionally to batches completed`() {
        assertEquals(0.125f, RecalcProgress(ResyncPhase.INGEST, 2, 4).fraction(), 0.001f)
        assertEquals(0.25f, RecalcProgress(ResyncPhase.INGEST, 4, 4).fraction(), 0.001f)
    }

    @Test
    fun `RECOMPUTE fills within its slice proportionally to days completed`() {
        assertEquals(0.875f, RecalcProgress(ResyncPhase.RECOMPUTE, 5, 10).fraction(), 0.001f)
        assertEquals(1.0f, RecalcProgress(ResyncPhase.RECOMPUTE, 10, 10).fraction(), 0.001f)
    }

    @Test
    fun `PRUNE and RECONCILE hold at their slice start regardless of current or total`() {
        assertEquals(0.25f, RecalcProgress(ResyncPhase.PRUNE, 7, 9).fraction(), 0.001f)
        assertEquals(0.50f, RecalcProgress(ResyncPhase.RECONCILE, 3, 3).fraction(), 0.001f)
    }

    @Test
    fun `total of zero does not divide by zero`() {
        assertEquals(0.0f, RecalcProgress(ResyncPhase.INGEST, 0, 0).fraction(), 0.001f)
        assertEquals(0.75f, RecalcProgress(ResyncPhase.RECOMPUTE, 0, 0).fraction(), 0.001f)
    }
}
