package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirtySummaryPublisher
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val healthMutationStateDao: HealthMutationStateDao,
        private val dirtyRangeDao: DirtyRangeDao,
        private val dailySummaryDao: DailySummaryDao,
        private val workoutDao: WorkoutDao,
    ) {
        suspend fun publish(
            ticket: DirtyTicket,
            summary: DailySummary,
            zoneId: ZoneId,
            expectedSourceGeneration: Long,
            stagedWorkoutUpdates: List<ComputeDailyTrimpUseCase.WorkoutModelTrimpUpdate> = emptyList(),
            activeSnapshotId: String? = null,
        ): Boolean =
            transactionRunner.runInTransaction {
                val state = healthMutationStateDao.current()
                if (state.maintenanceOperationId != null) {
                    return@runInTransaction false
                }
                if (state.sourceGeneration != expectedSourceGeneration) {
                    return@runInTransaction false
                }
                if (activeSnapshotId != null && ticket.scoringSnapshotId != activeSnapshotId) {
                    return@runInTransaction false
                }

                dailySummaryDao.upsert(DailySummaryMapper.toEntity(summary, zoneId))

                if (stagedWorkoutUpdates.isNotEmpty()) {
                    val updateMap = stagedWorkoutUpdates.associate { it.workoutId to it.modelTrimp }
                    val dayStartMs = summary.date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val nextDayMs = summary.date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
                    val workouts = workoutDao.getWorkoutsInRange(dayStartMs, nextDayMs)
                    val matching = workouts.filter { it.id in updateMap }
                    if (matching.isNotEmpty()) {
                        workoutDao.upsertAll(
                            matching.map { it.copy(modelTrimp = updateMap[it.id]) },
                        )
                    }
                }

                val expectedDay = ticket.nextDay.toEpochDay()
                val nextDay = ticket.nextDay.plusDays(1).toEpochDay()
                val rowsUpdated =
                    dirtyRangeDao.advance(
                        id = ticket.id,
                        generation = ticket.sourceGeneration,
                        expectedDay = expectedDay,
                        nextDay = nextDay,
                    )
                if (rowsUpdated == 0) {
                    return@runInTransaction false
                }

                dirtyRangeDao.deleteCompleted(
                    id = ticket.id,
                    generation = ticket.sourceGeneration,
                )

                true
            }
    }
