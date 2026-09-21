package app.readylytics.health.core.model.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOutcomeTest {
    @Test
    fun `unavailable total preserves value while authorized empty is zero`() {
        assertEquals(4200L, ReadOutcome.Denied.valueOrPrevious(4200L))
        assertEquals(4200L, ReadOutcome.Unsupported.valueOrPrevious(4200L))
        assertEquals(0L, ReadOutcome.Available(0L).valueOrPrevious(4200L))
    }

    @Test
    fun `valueOrPrevious returns data when Available`() {
        val outcome: ReadOutcome<String> = ReadOutcome.Available("fresh")
        assertEquals("fresh", outcome.valueOrPrevious("prev"))
    }

    @Test
    fun `valueOrPrevious returns previous when Denied or Unsupported`() {
        val denied: ReadOutcome<String> = ReadOutcome.Denied
        val unsupported: ReadOutcome<String> = ReadOutcome.Unsupported
        assertEquals("prev", denied.valueOrPrevious("prev"))
        assertEquals("prev", unsupported.valueOrPrevious("prev"))
    }

    @Test
    fun `getOrNull returns data when Available and null when Denied or Unsupported`() {
        assertEquals("data", ReadOutcome.Available("data").getOrNull())
        assertNull(ReadOutcome.Denied.getOrNull())
        assertNull(ReadOutcome.Unsupported.getOrNull())
    }

    @Test
    fun `map transforms Available data and preserves Denied and Unsupported`() {
        val available = ReadOutcome.Available(10).map { it * 2 }
        assertTrue(available is ReadOutcome.Available && available.data == 20)
        assertEquals(ReadOutcome.Denied, ReadOutcome.Denied.map { it })
        assertEquals(ReadOutcome.Unsupported, ReadOutcome.Unsupported.map { it })
    }

    @Test
    fun `dataOrEmpty returns list when Available and emptyList when Denied or Unsupported`() {
        val sample = listOf("a", "b")
        val available: ReadOutcome<List<String>> = ReadOutcome.Available(sample)
        val denied: ReadOutcome<List<String>> = ReadOutcome.Denied
        val unsupported: ReadOutcome<List<String>> = ReadOutcome.Unsupported
        assertEquals(sample, available.dataOrEmpty())
        assertEquals(emptyList<String>(), denied.dataOrEmpty())
        assertEquals(emptyList<String>(), unsupported.dataOrEmpty())
    }
}
