package app.readylytics.health.data.backup

import android.util.JsonReader
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.DailySummaryEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HealthSourceRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HeartRateRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrvRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import app.readylytics.health.core.databaseschema.data.local.entity.SleepSessionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WorkoutRoutePointEntity
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("TooManyFunctions") // Restoring 15+ tables requires many deserialization handlers
class RestoreBatchLoader
    @Inject
    constructor(
        private val healthDatabase: HealthDatabase,
        val vitalsLoader: RestoreVitalsLoader,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun restoreSleepSessions(reader: JsonReader) {
            val dao = healthDatabase.sleepSessionDao()
            reader.beginArray()
            val batch = mutableListOf<SleepSessionEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 100) {
                    dao.upsertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.upsertAll(batch)
            reader.endArray()
        }

        suspend fun restoreHealthSourceRecords(reader: JsonReader) {
            val dao = healthDatabase.sourceRecordDao()
            reader.beginArray()
            val batch = mutableListOf<HealthSourceRecordEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 500) {
                    dao.insertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.insertAll(batch)
            reader.endArray()
        }

        suspend fun restoreHeartRateRecords(
            reader: JsonReader,
            schemaVersion: Int,
        ) {
            val heartRateDao = healthDatabase.heartRateDao()
            val sourceRecordDao = healthDatabase.sourceRecordDao()
            reader.beginArray()
            val batch = mutableListOf<HeartRateRecordEntity>()
            while (reader.hasNext()) {
                val row = readNextObjectAsString(json, reader)
                val entity = decodeHeartRateRecord(json, row, schemaVersion, sourceRecordDao)
                batch.add(entity)
                if (batch.size >= 500) {
                    heartRateDao.upsertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) heartRateDao.upsertAll(batch)
            reader.endArray()
        }

        suspend fun restoreHrvRecords(
            reader: JsonReader,
            schemaVersion: Int,
        ) {
            val hrvDao = healthDatabase.hrvDao()
            val sourceRecordDao = healthDatabase.sourceRecordDao()
            reader.beginArray()
            val batch = mutableListOf<HrvRecordEntity>()
            while (reader.hasNext()) {
                val row = readNextObjectAsString(json, reader)
                val entity = decodeHrvRecord(json, row, schemaVersion, sourceRecordDao)
                batch.add(entity)
                if (batch.size >= 500) {
                    hrvDao.upsertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) hrvDao.upsertAll(batch)
            reader.endArray()
        }

        suspend fun restoreHrMinuteBuckets(
            reader: JsonReader,
            schemaVersion: Int,
        ) {
            val dao = healthDatabase.minuteBucketDao()
            val coverageDao = healthDatabase.minuteCoverageDao()
            reader.beginArray()
            val batch = mutableListOf<HrMinuteBucketEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 500) {
                    processBucketBatch(batch, schemaVersion, dao, coverageDao)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) {
                processBucketBatch(batch, schemaVersion, dao, coverageDao)
            }
            reader.endArray()
        }

        private suspend fun processBucketBatch(
            batch: List<HrMinuteBucketEntity>,
            schemaVersion: Int,
            dao: app.readylytics.health.core.databaseschema.data.local.dao.MinuteBucketDao,
            coverageDao: app.readylytics.health.core.databaseschema.data.local.dao.MinuteCoverageDao,
        ) {
            dao.upsertBuckets(batch)
            if (schemaVersion < 22) {
                batch
                    .map {
                        MinuteCoverageEntity(
                            bucketStartMs = it.bucketStartMs,
                            visibleGeneration = 0L,
                            tier = "LEGACY_WARM",
                            quality = "LEGACY_UNKNOWN",
                            sourceSelectionId = null,
                        )
                    }.forEach { coverageDao.insertCoverage(it) }
            }
        }

        suspend fun restoreWorkouts(reader: JsonReader) {
            val dao = healthDatabase.workoutDao()
            reader.beginArray()
            val batch = mutableListOf<WorkoutRecordEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 100) {
                    dao.upsertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.upsertAll(batch)
            reader.endArray()
        }

        suspend fun restoreWorkoutRoutePoints(reader: JsonReader) {
            val dao = healthDatabase.workoutRoutePointDao()
            reader.beginArray()
            val batch = mutableListOf<WorkoutRoutePointEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 500) {
                    dao.insertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.insertAll(batch)
            reader.endArray()
        }

        suspend fun restoreDailySummaries(reader: JsonReader) {
            val dao = healthDatabase.dailySummaryDao()
            reader.beginArray()
            val batch = mutableListOf<DailySummaryEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 100) {
                    dao.upsertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.upsertAll(batch)
            reader.endArray()
        }

        suspend fun restoreMinuteCoverage(reader: JsonReader) {
            val dao = healthDatabase.minuteCoverageDao()
            reader.beginArray()
            val batch = mutableListOf<MinuteCoverageEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 500) {
                    batch.forEach { dao.insertCoverage(it) }
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) batch.forEach { dao.insertCoverage(it) }
            reader.endArray()
        }

        suspend fun restoreHrSourceMinuteContributions(reader: JsonReader) {
            val dao = healthDatabase.minuteCoverageDao()
            reader.beginArray()
            val batch = mutableListOf<HrSourceMinuteContributionEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 500) {
                    dao.insertContributions(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.insertContributions(batch)
            reader.endArray()
        }
    }
