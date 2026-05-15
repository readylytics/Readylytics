package com.gregor.lauritz.healthdashboard.data.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicationStrategyTest {
    private val garmin = DeviceFingerprint("com.garmin.android.apps.wearable", "fenix7")
    private val pixel = DeviceFingerprint("com.google.android.apps.fitness", "PixelWatch")

    // ---------- Device fingerprint ----------
    @Test
    fun `device fingerprint hash is stable`() {
        assertEquals(garmin.hash(), garmin.hash())
    }

    @Test
    fun `more specific fingerprint detected`() {
        val partial = DeviceFingerprint(null, "PixelWatch")
        assertTrue(pixel.isMoreSpecificThan(partial))
        assertFalse(partial.isMoreSpecificThan(pixel))
    }

    // ---------- Sleep deduplication ----------
    @Test
    fun `sleep duplicate detected within 5 min start and 10 min duration`() {
        val s1 = sleepKey(id = "A", startMs = 100_000L, duration = 400)
        val s2 = sleepKey(id = "B", startMs = 100_000L + 4 * 60_000L, duration = 405)
        assertTrue(SleepSessionDeduplicator().isDuplicate(s1, s2))
    }

    @Test
    fun `sleep distinct when start gap exceeds tolerance`() {
        val s1 = sleepKey(id = "A", startMs = 100_000L, duration = 400)
        val s2 = sleepKey(id = "B", startMs = 100_000L + 10 * 60_000L, duration = 400)
        assertFalse(SleepSessionDeduplicator().isDuplicate(s1, s2))
    }

    @Test
    fun `sleep distinct when duration gap exceeds tolerance`() {
        val s1 = sleepKey(id = "A", startMs = 100_000L, duration = 400)
        val s2 = sleepKey(id = "B", startMs = 100_000L, duration = 420)
        assertFalse(SleepSessionDeduplicator().isDuplicate(s1, s2))
    }

    @Test
    fun `sleep deduplication keeps newer lastModified`() {
        val older = sleepKey(id = "A", startMs = 100_000L, duration = 400, lastModified = 1L)
        val newer = sleepKey(id = "B", startMs = 100_000L, duration = 400, lastModified = 100L)
        val result = SleepSessionDeduplicator().smartMerge(older, newer)
        assertEquals("B", result.hcRecordId)
    }

    @Test
    fun `sleep apply removes duplicates and produces audit`() {
        val records =
            listOf(
                sleepKey(id = "A", startMs = 100_000L, duration = 400, lastModified = 1L),
                sleepKey(id = "B", startMs = 100_000L, duration = 405, lastModified = 200L), // dup of A, newer
                sleepKey(id = "C", startMs = 999_999_999L, duration = 400, lastModified = 50L), // distinct
            )
        val (kept, audit) = SleepSessionDeduplicator().apply(records)
        assertEquals(2, kept.size)
        assertEquals(1, audit.size)
        assertEquals("A", audit[0].removedRecordId)
        assertEquals("B", audit[0].retainedRecordId)
    }

    // ---------- Heart rate deduplication ----------
    @Test
    fun `hr duplicate detected within 1 min and matching bpm and fingerprint`() {
        val h1 = hrKey(id = "A", ts = 100_000L, bpm = 60, fp = garmin)
        val h2 = hrKey(id = "B", ts = 100_000L + 30 * 1000L, bpm = 60, fp = garmin)
        assertTrue(HeartRateDeduplicator().isDuplicate(h1, h2))
    }

    @Test
    fun `hr not duplicate when bpm differs`() {
        val h1 = hrKey(id = "A", ts = 100_000L, bpm = 60, fp = garmin)
        val h2 = hrKey(id = "B", ts = 100_000L, bpm = 61, fp = garmin)
        assertFalse(HeartRateDeduplicator().isDuplicate(h1, h2))
    }

    @Test
    fun `hr not duplicate across devices`() {
        val h1 = hrKey(id = "A", ts = 100_000L, bpm = 60, fp = garmin)
        val h2 = hrKey(id = "B", ts = 100_000L, bpm = 60, fp = pixel)
        assertFalse(HeartRateDeduplicator().isDuplicate(h1, h2))
    }

    // ---------- HRV deduplication ----------
    @Test
    fun `hrv duplicate detected within 30s and 5ms tolerance`() {
        val h1 = hrvKey(id = "A", ts = 100_000L, rmssd = 50f)
        val h2 = hrvKey(id = "B", ts = 100_000L + 20 * 1000L, rmssd = 52f)
        assertTrue(HrvDeduplicator().isDuplicate(h1, h2))
    }

    @Test
    fun `hrv distinct when rmssd delta exceeds tolerance`() {
        val h1 = hrvKey(id = "A", ts = 100_000L, rmssd = 50f)
        val h2 = hrvKey(id = "B", ts = 100_000L, rmssd = 60f)
        assertFalse(HrvDeduplicator().isDuplicate(h1, h2))
    }

    @Test
    fun `hrv distinct when timestamp delta exceeds tolerance`() {
        val h1 = hrvKey(id = "A", ts = 100_000L, rmssd = 50f)
        val h2 = hrvKey(id = "B", ts = 100_000L + 60 * 1000L, rmssd = 50f)
        assertFalse(HrvDeduplicator().isDuplicate(h1, h2))
    }

    // ---------- Multi-device integration scenario ----------
    @Test
    fun `multi-device scenario - Garmin and Pixel sleep merge keeps newer`() {
        // Same biological sleep session, recorded by both devices with slightly different timestamps
        val garminSleep =
            sleepKey(
                id = "garmin-1",
                startMs = 1_700_000_000_000L,
                duration = 420, // 7h
                lastModified = 1_700_086_400_000L, // synced later
                fp = garmin,
            )
        val pixelSleep =
            sleepKey(
                id = "pixel-1",
                startMs = 1_700_000_000_000L + 2 * 60_000L, // 2 min offset
                duration = 425,
                lastModified = 1_700_000_500_000L, // synced earlier
                fp = pixel,
            )
        val (kept, audit) = SleepSessionDeduplicator().apply(listOf(pixelSleep, garminSleep))
        assertEquals(1, kept.size)
        assertEquals("garmin-1", kept[0].hcRecordId) // newer
        assertEquals(1, audit.size)
    }

    // ---------- Helpers ----------
    private fun sleepKey(
        id: String,
        startMs: Long,
        duration: Int,
        lastModified: Long = 0L,
        fp: DeviceFingerprint = garmin,
    ) = SleepDedupKey(
        hcRecordId = id,
        startTimeMs = startMs,
        endTimeMs = startMs + duration * 60_000L,
        durationMinutes = duration,
        endZoneOffsetSeconds = 0,
        deviceFingerprint = fp,
        lastModifiedMs = lastModified,
    )

    private fun hrKey(
        id: String,
        ts: Long,
        bpm: Int,
        fp: DeviceFingerprint,
    ) = HeartRateDedupKey(
        hcRecordId = id,
        timestampMs = ts,
        bpm = bpm,
        deviceFingerprint = fp,
        lastModifiedMs = 0L,
    )

    private fun hrvKey(
        id: String,
        ts: Long,
        rmssd: Float,
        fp: DeviceFingerprint = garmin,
    ) = HrvDedupKey(
        hcRecordId = id,
        timestampMs = ts,
        rmssdMs = rmssd,
        deviceFingerprint = fp,
        lastModifiedMs = 0L,
    )
}
