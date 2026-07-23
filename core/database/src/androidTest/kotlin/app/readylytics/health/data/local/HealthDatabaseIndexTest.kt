package app.readylytics.health.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthDatabaseIndexTest {
    private lateinit var db: HealthDatabase

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                HealthDatabase::class.java,
            ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun heartRateRecords_keepsRecordTypeTimestampIndexForSleepAggregation() {
        db.openHelper.readableDatabase
            .query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'heart_rate_records' " +
                    "AND name = 'index_heart_rate_records_recordType_timestampMs'",
            ).use { cursor ->
                assertEquals(1, cursor.count)
            }
    }
}
