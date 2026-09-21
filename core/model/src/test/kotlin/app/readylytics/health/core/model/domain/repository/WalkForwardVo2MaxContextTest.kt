package app.readylytics.health.core.model.domain.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

class WalkForwardVo2MaxContextTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `context respects exclusive next-midnight boundary and deterministic tiebreak`() {
        // Berlin 2026-03-29 is a 23h day.
        val date = LocalDate.of(2026, 3, 29)
        val dayStart = date.atStartOfDay(berlin).toInstant().toEpochMilli()
        val nextMidnight = date.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli()

        val map = TreeMap<Vo2MaxKey, Float>()
        
        // Exact next midnight (should be excluded)
        map[Vo2MaxKey(nextMidnight, "x")] = 50.0f
        
        // At end-1ms with ID 'a'
        map[Vo2MaxKey(nextMidnight - 1, "a")] = 45.0f
        
        // At end-1ms with ID 'b' (should win over 'a')
        map[Vo2MaxKey(nextMidnight - 1, "b")] = 46.0f
        
        // Earlier in the day
        map[Vo2MaxKey(dayStart, "c")] = 44.0f

        val context = WalkForwardVo2MaxContext(map)
        
        // lowerEntry with empty string for ID, which is the lowest possible string.
        // It strictly finds the highest entry before (nextMidnight, "")
        val result = context.vo2MaxByTimestampMs.lowerEntry(Vo2MaxKey(nextMidnight, ""))
        
        assertEquals(46.0f, result?.value)
        assertEquals("b", result?.key?.id)
        
        // Test lower bound check (done in assembler, but we can verify our map entries)
        // Elapsed 30 days lower bound:
        val lowerBound = date.minusDays(30).atStartOfDay(berlin).toInstant().toEpochMilli()
        val oldEntry = Vo2MaxKey(lowerBound - 1, "d")
        map[oldEntry] = 40.0f

        // Should find 'b' again since we didn't change nextMidnight
        val result2 = context.vo2MaxByTimestampMs.lowerEntry(Vo2MaxKey(nextMidnight, ""))
        assertEquals(46.0f, result2?.value)

        // When only an out-of-bounds record exists before nextMidnight, filtering by lowerBound yields null
        val mapWithOldOnly = TreeMap<Vo2MaxKey, Float>()
        mapWithOldOnly[oldEntry] = 40.0f
        val oldOnlyContext = WalkForwardVo2MaxContext(mapWithOldOnly)
        val filtered =
            oldOnlyContext.vo2MaxByTimestampMs
                .lowerEntry(Vo2MaxKey(nextMidnight, ""))
                ?.takeIf { it.key.timestampMs >= lowerBound }
        assertNull(filtered)
    }

    @Test
    fun `context respects exclusive next-midnight boundary for Berlin 25h day`() {
        // Berlin 2026-10-25 is a 25h day.
        val date = LocalDate.of(2026, 10, 25)
        val nextMidnight = date.plusDays(1).atStartOfDay(berlin).toInstant().toEpochMilli()

        val map = TreeMap<Vo2MaxKey, Float>()
        map[Vo2MaxKey(nextMidnight, "next_day_id")] = 55.0f
        map[Vo2MaxKey(nextMidnight - 1, "same_day_id")] = 49.0f

        val context = WalkForwardVo2MaxContext(map)
        val result = context.vo2MaxByTimestampMs.lowerEntry(Vo2MaxKey(nextMidnight, ""))

        assertEquals(49.0f, result?.value)
        assertEquals("same_day_id", result?.key?.id)
    }
}
