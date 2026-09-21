package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.ScanStagingDao
import app.readylytics.health.core.databaseschema.data.local.entity.ScanSeenIdEntity
import app.readylytics.health.core.databaseschema.data.local.entity.ScanTypeStateEntity
import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.sync.ScanIdentity
import app.readylytics.health.core.model.domain.sync.ScanStagingStore
import app.readylytics.health.core.model.domain.sync.TypeScanState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomScanStagingStore
    @Inject
    constructor(
        private val scanStagingDao: ScanStagingDao,
        private val clock: Clock = Clock.systemUTC(),
    ) : ScanStagingStore {
        override suspend fun beginTypeScan(
            scan: ScanIdentity,
            type: HealthDataType,
            resume: Boolean,
        ) {
            if (!resume) {
                scanStagingDao.deleteSeenForType(scan.runId, scan.chunkId, type.name)
            }
            writeState(scan, type, ScanStagingDao.STATE_SCANNING)
        }

        override suspend fun stageIds(
            scan: ScanIdentity,
            type: HealthDataType,
            ids: Collection<String>,
        ) {
            if (ids.isEmpty()) return
            ids.chunked(ScanStagingStore.STAGE_BATCH_SIZE).forEach { batch ->
                currentCoroutineContext().ensureActive()
                scanStagingDao.insertSeenIds(
                    batch.map { id ->
                        ScanSeenIdEntity(
                            runId = scan.runId,
                            chunkId = scan.chunkId,
                            recordType = type.name,
                            sourceId = id,
                        )
                    },
                )
            }
        }

        override suspend fun markTypeScanComplete(
            scan: ScanIdentity,
            type: HealthDataType,
        ) = writeState(scan, type, ScanStagingDao.STATE_COMPLETE)

        override suspend fun stateOf(
            scan: ScanIdentity,
            type: HealthDataType,
        ): TypeScanState? =
            when (scanStagingDao.getState(scan.runId, scan.chunkId, type.name)?.state) {
                ScanStagingDao.STATE_COMPLETE -> TypeScanState.COMPLETE
                ScanStagingDao.STATE_SCANNING -> TypeScanState.SCANNING
                else -> null
            }

        override suspend fun stagedCount(
            scan: ScanIdentity,
            type: HealthDataType,
        ): Int = scanStagingDao.countSeen(scan.runId, scan.chunkId, type.name)

        override suspend fun clearTypeScan(
            scan: ScanIdentity,
            type: HealthDataType,
        ) {
            scanStagingDao.deleteSeenForType(scan.runId, scan.chunkId, type.name)
            scanStagingDao.deleteStateForType(scan.runId, scan.chunkId, type.name)
        }

        override suspend fun clearRun(runId: String) {
            scanStagingDao.deleteSeenForRun(runId)
            scanStagingDao.deleteStateForRun(runId)
        }

        override suspend fun clearRunsOtherThan(runId: String) {
            scanStagingDao.deleteSeenForOtherRuns(runId)
            scanStagingDao.deleteStateForOtherRuns(runId)
        }

        private suspend fun writeState(
            scan: ScanIdentity,
            type: HealthDataType,
            state: String,
        ) {
            scanStagingDao.upsertState(
                ScanTypeStateEntity(
                    runId = scan.runId,
                    chunkId = scan.chunkId,
                    recordType = type.name,
                    state = state,
                    stagedCount = scanStagingDao.countSeen(scan.runId, scan.chunkId, type.name),
                    updatedAtMs = clock.millis(),
                ),
            )
        }
    }
