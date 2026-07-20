package app.readylytics.health.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.data.local.HealthDatabase
import app.readylytics.health.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.data.local.entity.HrvRecordEntity
import app.readylytics.health.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.data.local.entity.WeightRecordEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * PERF-003 regression lock: `getBySourceRecordId`/`deleteBySourceRecordId` were rewritten from a
 * non-sargable `substr(id, 1, length(:x)+1) = :x || '_'` predicate to a sargable range predicate
 * (`id >= :x || '_' AND id < :x || '\`'`). This proves the rewrite is behaviorally identical on
 * adversarial ids: a bare id, its composite children (`id_<ms>`), and an unrelated id that merely
 * shares a string prefix with the source id but isn't one of its `_`-suffixed children.
 */
@RunWith(AndroidJUnit4::class)
class SourceRecordIdSargableQueryTest {
    private lateinit var database: HealthDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `heartRateDao matches the source id and its composite children, not an unrelated prefix match`() =
        runTest {
            val dao = database.heartRateDao()
            dao.upsertAll(
                listOf(
                    HeartRateRecordEntity(id = "abc", timestampMs = 1L, beatsPerMinute = 60, recordType = "RESTING"),
                    HeartRateRecordEntity(id = "abc_1", timestampMs = 2L, beatsPerMinute = 61, recordType = "RESTING"),
                    HeartRateRecordEntity(id = "abc_2", timestampMs = 3L, beatsPerMinute = 62, recordType = "RESTING"),
                    HeartRateRecordEntity(id = "abcd_1", timestampMs = 4L, beatsPerMinute = 63, recordType = "RESTING"),
                ),
            )

            val matches = dao.getBySourceRecordId("abc")
            assertEquals(setOf("abc", "abc_1", "abc_2"), matches.map { it.id }.toSet())

            val deleted = dao.deleteBySourceRecordId("abc")
            assertEquals(3, deleted)
            assertEquals(setOf("abcd_1"), dao.getSince(0L).map { it.id }.toSet())
        }

    @Test
    fun `hrvDao matches the source id and its composite children, not an unrelated prefix match`() =
        runTest {
            val dao = database.hrvDao()
            dao.upsertAll(
                listOf(
                    HrvRecordEntity(id = "abc", timestampMs = 1L, rmssdMs = 10f, recordType = "RESTING"),
                    HrvRecordEntity(id = "abc_1", timestampMs = 2L, rmssdMs = 11f, recordType = "RESTING"),
                    HrvRecordEntity(id = "abcd_1", timestampMs = 3L, rmssdMs = 12f, recordType = "RESTING"),
                ),
            )

            val matches = dao.getBySourceRecordId("abc")
            assertEquals(setOf("abc", "abc_1"), matches.map { it.id }.toSet())

            val deleted = dao.deleteBySourceRecordId("abc")
            assertEquals(2, deleted)
        }

    @Test
    fun `weightRecordDao matches the source id and its composite children, not an unrelated prefix match`() =
        runTest {
            val dao = database.weightRecordDao()
            dao.upsertAll(
                listOf(
                    WeightRecordEntity(id = "abc", timestampMs = 1L, weightKg = 70f),
                    WeightRecordEntity(id = "abc_1", timestampMs = 2L, weightKg = 71f),
                    WeightRecordEntity(id = "abcd_1", timestampMs = 3L, weightKg = 72f),
                ),
            )

            val matches = dao.getBySourceRecordId("abc")
            assertEquals(setOf("abc", "abc_1"), matches.map { it.id }.toSet())

            val deleted = dao.deleteBySourceRecordId("abc")
            assertEquals(2, deleted)
        }

    @Test
    fun `bodyFatRecordDao matches the source id and its composite children, not an unrelated prefix match`() =
        runTest {
            val dao = database.bodyFatRecordDao()
            dao.upsertAll(
                listOf(
                    BodyFatRecordEntity(id = "abc", timestampMs = 1L, bodyFatPercent = 0.2f),
                    BodyFatRecordEntity(id = "abc_1", timestampMs = 2L, bodyFatPercent = 0.21f),
                    BodyFatRecordEntity(id = "abcd_1", timestampMs = 3L, bodyFatPercent = 0.22f),
                ),
            )

            val matches = dao.getBySourceRecordId("abc")
            assertEquals(setOf("abc", "abc_1"), matches.map { it.id }.toSet())

            val deleted = dao.deleteBySourceRecordId("abc")
            assertEquals(2, deleted)
        }

    @Test
    fun `bloodPressureRecordDao matches the source id and its composite children, not an unrelated prefix match`() =
        runTest {
            val dao = database.bloodPressureRecordDao()
            dao.upsertAll(
                listOf(
                    BloodPressureRecordEntity(id = "abc", timestampMs = 1L, systolicMmHg = 120, diastolicMmHg = 80),
                    BloodPressureRecordEntity(id = "abc_1", timestampMs = 2L, systolicMmHg = 121, diastolicMmHg = 81),
                    BloodPressureRecordEntity(id = "abcd_1", timestampMs = 3L, systolicMmHg = 122, diastolicMmHg = 82),
                ),
            )

            val matches = dao.getBySourceRecordId("abc")
            assertEquals(setOf("abc", "abc_1"), matches.map { it.id }.toSet())

            val deleted = dao.deleteBySourceRecordId("abc")
            assertEquals(2, deleted)
        }

    @Test
    fun `oxygenSaturationRecordDao matches the source id and its composite children, not an unrelated prefix match`() =
        runTest {
            val dao = database.oxygenSaturationRecordDao()
            dao.upsertAll(
                listOf(
                    OxygenSaturationRecordEntity(id = "abc", timestampMs = 1L, percentage = 0.97f),
                    OxygenSaturationRecordEntity(id = "abc_1", timestampMs = 2L, percentage = 0.98f),
                    OxygenSaturationRecordEntity(id = "abcd_1", timestampMs = 3L, percentage = 0.99f),
                ),
            )

            val matches = dao.getBySourceRecordId("abc")
            assertEquals(setOf("abc", "abc_1"), matches.map { it.id }.toSet())

            val deleted = dao.deleteBySourceRecordId("abc")
            assertEquals(2, deleted)
        }
}
