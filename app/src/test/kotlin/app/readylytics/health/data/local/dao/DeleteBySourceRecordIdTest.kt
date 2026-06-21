package app.readylytics.health.data.local.dao

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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteBySourceRecordIdTest {
    private lateinit var database: HealthDatabase
    private lateinit var heartRateDao: HeartRateDao
    private lateinit var hrvDao: HrvDao
    private lateinit var weightDao: WeightRecordDao
    private lateinit var bodyFatDao: BodyFatRecordDao
    private lateinit var bloodPressureDao: BloodPressureRecordDao
    private lateinit var oxygenSaturationDao: OxygenSaturationRecordDao

    @Before
    fun setup() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    HealthDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        heartRateDao = database.heartRateDao()
        hrvDao = database.hrvDao()
        weightDao = database.weightRecordDao()
        bodyFatDao = database.bodyFatRecordDao()
        bloodPressureDao = database.bloodPressureRecordDao()
        oxygenSaturationDao = database.oxygenSaturationRecordDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun `heart rate source record methods match only exact source prefix`() =
        runTest {
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity("hc-record_1000", 1000L, 60, "SLEEP"),
                    HeartRateRecordEntity("hc-record_2000", 2000L, 61, "SLEEP"),
                    HeartRateRecordEntity("hc-record-other_1000", 3000L, 62, "SLEEP"),
                ),
            )

            assertEquals(
                listOf("hc-record_1000", "hc-record_2000"),
                heartRateDao.getBySourceRecordId("hc-record").map { it.id },
            )
            assertEquals(2, heartRateDao.deleteBySourceRecordId("hc-record"))
            assertEquals(listOf("hc-record-other_1000"), heartRateDao.getSince(0).map { it.id })
        }

    @Test
    fun `hrv source record methods match only exact source prefix`() =
        runTest {
            hrvDao.upsertAll(
                listOf(
                    HrvRecordEntity("hc-record_1000", 1000L, 40f, "SLEEP"),
                    HrvRecordEntity("hc-record_2000", 2000L, 41f, "SLEEP"),
                    HrvRecordEntity("hc-record-other_1000", 3000L, 42f, "SLEEP"),
                ),
            )

            assertEquals(
                listOf("hc-record_1000", "hc-record_2000"),
                hrvDao.getBySourceRecordId("hc-record").map { it.id },
            )
            assertEquals(2, hrvDao.deleteBySourceRecordId("hc-record"))
            assertEquals(listOf("hc-record-other_1000"), hrvDao.getSince(0).map { it.id })
        }

    @Test
    fun `weight source record methods protect prefix collisions`() =
        runTest {
            weightDao.upsertAll(
                listOf(
                    WeightRecordEntity("hc-record_1000", 1000L, 70f),
                    WeightRecordEntity("hc-record_2000", 2000L, 71f),
                    WeightRecordEntity("hc-record2_1000", 3000L, 72f),
                ),
            )

            assertEquals(
                listOf("hc-record_1000", "hc-record_2000"),
                weightDao.getBySourceRecordId("hc-record").map { it.id },
            )
            assertEquals(2, weightDao.deleteBySourceRecordId("hc-record"))
            assertEquals(listOf("hc-record2_1000"), weightDao.getSince(0).map { it.id })
        }

    @Test
    fun `body fat source record methods protect prefix collisions`() =
        runTest {
            bodyFatDao.upsertAll(
                listOf(
                    BodyFatRecordEntity("hc-record_1000", 1000L, 15f),
                    BodyFatRecordEntity("hc-record_2000", 2000L, 16f),
                    BodyFatRecordEntity("hc-record2_1000", 3000L, 17f),
                ),
            )

            assertEquals(
                listOf("hc-record_1000", "hc-record_2000"),
                bodyFatDao.getBySourceRecordId("hc-record").map { it.id },
            )
            assertEquals(2, bodyFatDao.deleteBySourceRecordId("hc-record"))
            assertEquals(listOf("hc-record2_1000"), bodyFatDao.getSince(0).map { it.id })
        }

    @Test
    fun `blood pressure source record methods protect prefix collisions`() =
        runTest {
            bloodPressureDao.upsertAll(
                listOf(
                    BloodPressureRecordEntity("hc-record_1000", 1000L, 120, 80),
                    BloodPressureRecordEntity("hc-record_2000", 2000L, 121, 81),
                    BloodPressureRecordEntity("hc-record2_1000", 3000L, 122, 82),
                ),
            )

            assertEquals(
                listOf("hc-record_1000", "hc-record_2000"),
                bloodPressureDao.getBySourceRecordId("hc-record").map { it.id },
            )
            assertEquals(2, bloodPressureDao.deleteBySourceRecordId("hc-record"))
            assertEquals(listOf("hc-record2_1000"), bloodPressureDao.getSince(0).map { it.id })
        }

    @Test
    fun `oxygen saturation source record methods protect prefix collisions`() =
        runTest {
            oxygenSaturationDao.upsertAll(
                listOf(
                    OxygenSaturationRecordEntity("hc-record_1000", 1000L, 97f),
                    OxygenSaturationRecordEntity("hc-record_2000", 2000L, 98f),
                    OxygenSaturationRecordEntity("hc-record2_1000", 3000L, 99f),
                ),
            )

            assertEquals(
                listOf("hc-record_1000", "hc-record_2000"),
                oxygenSaturationDao.getBySourceRecordId("hc-record").map { it.id },
            )
            assertEquals(2, oxygenSaturationDao.deleteBySourceRecordId("hc-record"))
            assertEquals(listOf("hc-record2_1000"), oxygenSaturationDao.getSince(0).map { it.id })
        }
}
