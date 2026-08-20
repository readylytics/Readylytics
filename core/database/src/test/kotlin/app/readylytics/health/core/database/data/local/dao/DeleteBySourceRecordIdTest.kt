package app.readylytics.health.core.database.data.local.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.readylytics.health.core.databaseschema.data.local.dao.BloodPressureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyFatRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.BodyTemperatureRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.HeartRateDao
import app.readylytics.health.core.databaseschema.data.local.dao.HrvDao
import app.readylytics.health.core.databaseschema.data.local.dao.OxygenSaturationRecordDao
import app.readylytics.health.core.databaseschema.data.local.dao.WeightRecordDao
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
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
    private lateinit var bodyTemperatureDao: BodyTemperatureRecordDao

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
        bodyTemperatureDao = database.bodyTemperatureRecordDao()
    }

    @After
    fun cleanup() {
        database.close()
    }

    private suspend fun seedSourceRecordParents(vararg refs: Long) {
        database.sourceRecordDao().insertAll(
            refs.map { ref ->
                HealthSourceRecordEntity(
                    id = ref,
                    sourceRecordId = "seed-$ref",
                    recordType = "HEART_RATE",
                    createdAtMs = 0L,
                )
            },
        )
    }

    @Test
    fun `heart rate source record methods match only exact source prefix`() =
        runTest {
            seedSourceRecordParents(1L, 2L)
            heartRateDao.upsertAll(
                listOf(
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 1000L,
                        beatsPerMinute = 60,
                        recordType = "SLEEP",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 1L,
                        timestampMs = 2000L,
                        beatsPerMinute = 61,
                        recordType = "SLEEP",
                    ),
                    HeartRateRecordEntity(
                        sourceRecordRef = 2L,
                        timestampMs = 3000L,
                        beatsPerMinute = 62,
                        recordType = "SLEEP",
                    ),
                ),
            )

            assertEquals(
                listOf(1L, 1L),
                heartRateDao.getBySourceRecordRef(1L).map { it.sourceRecordRef },
            )
            assertEquals(2, heartRateDao.deleteBySourceRecordRef(1L))
            assertEquals(listOf(2L), heartRateDao.getSince(0).map { it.sourceRecordRef })
        }

    @Test
    fun `hrv source record methods match only exact source prefix`() =
        runTest {
            seedSourceRecordParents(1L, 2L)
            hrvDao.upsertAll(
                listOf(
                    HrvRecordEntity(sourceRecordRef = 1L, timestampMs = 1000L, rmssdMs = 40f, recordType = "SLEEP"),
                    HrvRecordEntity(sourceRecordRef = 1L, timestampMs = 2000L, rmssdMs = 41f, recordType = "SLEEP"),
                    HrvRecordEntity(sourceRecordRef = 2L, timestampMs = 3000L, rmssdMs = 42f, recordType = "SLEEP"),
                ),
            )

            assertEquals(
                listOf(1L, 1L),
                hrvDao.getBySourceRecordRef(1L).map { it.sourceRecordRef },
            )
            assertEquals(2, hrvDao.deleteBySourceRecordRef(1L))
            assertEquals(listOf(2L), hrvDao.getSince(0).map { it.sourceRecordRef })
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

    @Test
    fun `body temperature source record methods protect prefix collisions`() =
        runTest {
            bodyTemperatureDao.upsertAll(
                listOf(
                    BodyTemperatureRecordEntity("hc-record_1000", 1000L, 36.6f),
                    BodyTemperatureRecordEntity("hc-record_2000", 2000L, 36.7f),
                    BodyTemperatureRecordEntity("hc-record2_1000", 3000L, 36.8f),
                ),
            )

            assertEquals(
                listOf("hc-record_1000", "hc-record_2000"),
                bodyTemperatureDao.getBySourceRecordId("hc-record").map { it.id },
            )
            assertEquals(2, bodyTemperatureDao.deleteBySourceRecordId("hc-record"))
            assertEquals(
                listOf("hc-record2_1000"),
                bodyTemperatureDao.getByTimeRange(0, Long.MAX_VALUE).map { it.id },
            )
        }
}
