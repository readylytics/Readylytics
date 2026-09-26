package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.model.domain.sync.DirtyRangeStore
import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.model.domain.sync.RETIRED_AGING_DIRTY_REASONS
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomDirtyRangeStore
    @Inject
    constructor(
        private val dirtyRangeDao: DirtyRangeDao,
        private val healthMutationStateDao: HealthMutationStateDao,
    ) : DirtyRangeStore {
        override suspend fun pending(limit: Int): List<DirtyTicket> =
            dirtyRangeDao.pending(limit).map { entity ->
                DirtyTicket(
                    id = entity.id,
                    sourceGeneration = entity.sourceGeneration,
                    nextDay = LocalDate.ofEpochDay(entity.nextEpochDay),
                    endInclusive = LocalDate.ofEpochDay(entity.endEpochDayInclusive),
                    scoringSnapshotId = entity.scoringSnapshotId,
                    reason = entity.reason,
                )
            }

        override suspend fun discardBefore(retentionStart: LocalDate) {
            dirtyRangeDao.discardBefore(retentionStart.toEpochDay())
        }

        override suspend fun discardRetiredAgingTickets(): Int =
            dirtyRangeDao.deleteByReasons(RETIRED_AGING_DIRTY_REASONS)

        suspend fun append(
            start: LocalDate,
            endInclusive: LocalDate,
            reason: String,
            snapshotId: String,
        ): Long {
            val currentGen = healthMutationStateDao.current().sourceGeneration
            return dirtyRangeDao.insert(
                DirtyRangeEntity(
                    sourceGeneration = currentGen,
                    startEpochDay = start.toEpochDay(),
                    endEpochDayInclusive = endInclusive.toEpochDay(),
                    nextEpochDay = start.toEpochDay(),
                    reason = reason,
                    scoringSnapshotId = snapshotId,
                ),
            )
        }

    }
