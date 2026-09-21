package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirtyRangeEntityTest {
    @Test
    fun validRangeConstructionSucceeds() {
        val range =
            DirtyRangeEntity(
                sourceGeneration = 1,
                startEpochDay = 100,
                endEpochDayInclusive = 105,
                nextEpochDay = 100,
                reason = "TEST",
                scoringSnapshotId = "SNAP1",
            )
        assertEquals(100L, range.startEpochDay)
        assertEquals(105L, range.endEpochDayInclusive)
        assertEquals(100L, range.nextEpochDay)
    }

    @Test
    fun nextEpochDayAtEndPlusOneSucceeds() {
        val range =
            DirtyRangeEntity(
                sourceGeneration = 1,
                startEpochDay = 100,
                endEpochDayInclusive = 105,
                nextEpochDay = 106,
                reason = "TEST",
                scoringSnapshotId = "SNAP1",
            )
        assertEquals(106L, range.nextEpochDay)
    }

    @Test
    fun startEpochDayGreaterThanNextEpochDayFails() {
        assertThrows(IllegalArgumentException::class.java) {
            DirtyRangeEntity(
                sourceGeneration = 1,
                startEpochDay = 102,
                endEpochDayInclusive = 105,
                nextEpochDay = 101,
                reason = "TEST",
                scoringSnapshotId = "SNAP1",
            )
        }
    }

    @Test
    fun nextEpochDayGreaterThanEndPlusOneFails() {
        assertThrows(IllegalArgumentException::class.java) {
            DirtyRangeEntity(
                sourceGeneration = 1,
                startEpochDay = 100,
                endEpochDayInclusive = 105,
                nextEpochDay = 107,
                reason = "TEST",
                scoringSnapshotId = "SNAP1",
            )
        }
    }

    @Test
    fun startEpochDayGreaterThanEndEpochDayInclusiveFails() {
        assertThrows(IllegalArgumentException::class.java) {
            DirtyRangeEntity(
                sourceGeneration = 1,
                startEpochDay = 106,
                endEpochDayInclusive = 105,
                nextEpochDay = 106,
                reason = "TEST",
                scoringSnapshotId = "SNAP1",
            )
        }
    }
}
