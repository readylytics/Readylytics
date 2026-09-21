package app.readylytics.health.data.backup

import app.readylytics.health.core.database.data.local.HealthDatabase
import app.readylytics.health.core.databaseschema.data.local.entity.HrSourceMinuteContributionEntity
import app.readylytics.health.core.databaseschema.data.local.entity.MinuteCoverageEntity
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes the WP-17 coverage tables (`minute_coverage`, `hr_source_minute_contributions`) into
 * a streaming backup archive. Split out of [BackupStreamWriter] so that class keeps one
 * responsibility per collaborator instead of absorbing another two tables' worth of paging state.
 *
 * Staged refresh work (`staged_hr_*`) is deliberately NOT exported: it is local operational state
 * for an in-flight refresh, not health data, and dirty work is regenerated on restore.
 */
@Singleton
class CoverageBackupWriter
    @Inject
    constructor(
        private val healthDatabase: HealthDatabase,
    ) {
        private val json = Json { encodeDefaults = true }

        suspend fun write(
            writer: BufferedWriter,
            pageHook: (suspend (tableName: String) -> Unit)?,
        ) {
            val dao = healthDatabase.minuteCoverageMaintenanceDao()

            var covAfterTs = Long.MIN_VALUE
            writer.writeBackupTable<MinuteCoverageEntity>(
                json,
                "minuteCoverage",
                page = { dao.pageCoverageAfter(covAfterTs, PAGE_SIZE) },
                advance = { covAfterTs = it.bucketStartMs },
                pageHook = pageHook,
            )
            writer.write(",\n")

            // Keyset cursor over the FULL composite primary key: `bucketStartMs` alone is not
            // unique here, so a page boundary landing inside one minute's group would otherwise
            // drop every remaining row of that group and fail the restore row-count check.
            var contribAfterTs = Long.MIN_VALUE
            var contribAfterSourceRef = Long.MIN_VALUE
            var contribAfterGeneration = Long.MIN_VALUE
            writer.writeBackupTable<HrSourceMinuteContributionEntity>(
                json,
                "hrSourceMinuteContributions",
                page = {
                    dao.pageContributionsAfter(
                        contribAfterTs,
                        contribAfterSourceRef,
                        contribAfterGeneration,
                        PAGE_SIZE,
                    )
                },
                advance = {
                    contribAfterTs = it.bucketStartMs
                    contribAfterSourceRef = it.sourceRecordRef
                    contribAfterGeneration = it.generation
                },
                pageHook = pageHook,
            )
            writer.write(",\n")
        }

        private companion object {
            const val PAGE_SIZE = 500
        }
    }
