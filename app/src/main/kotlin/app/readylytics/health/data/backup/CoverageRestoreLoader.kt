package app.readylytics.health.data.backup

import android.util.JsonReader
import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HrMinuteBucketEntity
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deserializes the WP-17 coverage tables from a backup archive, and initializes coverage for
 * archives written before schema v22 (which carry warm buckets but no coverage ledger). Split out
 * of [RestoreBatchLoader] so neither class carries a `TooManyFunctions` suppression.
 *
 * Old archives are initialized as `LEGACY_WARM`/`LEGACY_UNKNOWN` at generation 0 -- the same state
 * migration 21→22 produces -- so restored legacy minutes stay pending an authorized complete
 * refresh instead of being presented as source-backed, and tiers are never mixed by concatenation.
 */
@Singleton
class CoverageRestoreLoader
    @Inject
    constructor(
        private val healthDatabase: HealthDatabase,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        /** Coverage rows a pre-v22 archive's warm buckets imply, at legacy quality. */
        suspend fun initializeLegacyCoverage(buckets: List<HrMinuteBucketEntity>) {
            healthDatabase.minuteCoverageDao().upsertCoverage(
                buckets
                    .distinctBy { it.bucketStartMs }
                    .map {
                        MinuteCoverageEntity(
                            bucketStartMs = it.bucketStartMs,
                            visibleGeneration = 0L,
                            tier = LEGACY_TIER,
                            quality = LEGACY_QUALITY,
                            sourceSelectionId = null,
                        )
                    },
            )
        }

        suspend fun restoreMinuteCoverage(reader: JsonReader) {
            val dao = healthDatabase.minuteCoverageDao()
            reader.beginArray()
            val batch = mutableListOf<MinuteCoverageEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= BATCH_SIZE) {
                    dao.upsertCoverage(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.upsertCoverage(batch)
            reader.endArray()
        }

        suspend fun restoreContributions(reader: JsonReader) {
            val dao = healthDatabase.minuteCoverageDao()
            reader.beginArray()
            val batch = mutableListOf<HrSourceMinuteContributionEntity>()
            while (reader.hasNext()) {
                batch.add(json.decodeFromString(readNextObjectAsString(json, reader)))
                if (batch.size >= BATCH_SIZE) {
                    dao.upsertContributions(batch)
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) dao.upsertContributions(batch)
            reader.endArray()
        }

        private companion object {
            const val BATCH_SIZE = 500
            const val LEGACY_TIER = "LEGACY_WARM"
            const val LEGACY_QUALITY = "LEGACY_UNKNOWN"
        }
    }
