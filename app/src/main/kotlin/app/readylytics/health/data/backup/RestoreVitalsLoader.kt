package app.readylytics.health.data.backup

import android.util.JsonReader
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.BloodPressureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyFatRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.BodyTemperatureRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.OxygenSaturationRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.StepRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.Vo2MaxRecordEntity
import app.readylytics.health.core.databaseschema.data.local.entity.WeightRecordEntity
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestoreVitalsLoader
    @Inject
    constructor(
        private val healthDatabase: HealthDatabase,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun restoreWeightRecords(reader: JsonReader) {
            val dao = healthDatabase.weightRecordDao()
            reader.beginArray()
            val batch = mutableListOf<WeightRecordEntity>()
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

        suspend fun restoreBodyFatRecords(reader: JsonReader) {
            val dao = healthDatabase.bodyFatRecordDao()
            reader.beginArray()
            val batch = mutableListOf<BodyFatRecordEntity>()
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

        suspend fun restoreBloodPressureRecords(reader: JsonReader) {
            val dao = healthDatabase.bloodPressureRecordDao()
            reader.beginArray()
            val batch = mutableListOf<BloodPressureRecordEntity>()
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

        suspend fun restoreOxygenSaturationRecords(reader: JsonReader) {
            val dao = healthDatabase.oxygenSaturationRecordDao()
            reader.beginArray()
            val batch = mutableListOf<OxygenSaturationRecordEntity>()
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

        suspend fun restoreBodyTemperatureRecords(reader: JsonReader) {
            val dao = healthDatabase.bodyTemperatureRecordDao()
            reader.beginArray()
            val batch = mutableListOf<BodyTemperatureRecordEntity>()
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

        suspend fun restoreStepRecords(reader: JsonReader) {
            val dao = healthDatabase.stepRecordDao()
            reader.beginArray()
            val batch = mutableListOf<StepRecordEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= 500) {
                    dao.upsertAll(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.upsertAll(batch)
            reader.endArray()
        }

        suspend fun restoreVo2MaxRecords(reader: JsonReader) {
            val dao = healthDatabase.vo2MaxRecordDao()
            reader.beginArray()
            val batch = mutableListOf<Vo2MaxRecordEntity>()
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
    }
