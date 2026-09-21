package app.readylytics.health.core.model.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class MinuteCoverageBoundsTest {

    @Test
    fun `cutoff rounds down including pre epoch timestamps`() {
        // mid-minute
        assertEquals(60_000L, completeMinuteCutoff(90_000L))
        
        // exact boundary
        assertEquals(60_000L, completeMinuteCutoff(60_000L))
        assertEquals(120_000L, completeMinuteCutoff(120_000L))
        
        // mid-minute in the first minute
        assertEquals(0L, completeMinuteCutoff(59_999L))
        
        // zero
        assertEquals(0L, completeMinuteCutoff(0L))
        
        // negative (pre-epoch)
        assertEquals(-60_000L, completeMinuteCutoff(-1L))
        assertEquals(-60_000L, completeMinuteCutoff(-60_000L))
        assertEquals(-120_000L, completeMinuteCutoff(-60_001L))
    }
}
