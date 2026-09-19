package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.database.data.mapper.DailySummaryMapper
import app.readylytics.health.core.databaseschema.data.local.dao.DailySummaryDao
import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.dao.WorkoutDao
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.model.domain.model.DailySummary
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.scoring.PublishableDayAssembly
import app.readylytics.health.core.model.domain.sync.DirtyTicket
import app.readylytics.health.core.scoring.domain.scoring.ComputeDailyTrimpUseCase
import java.time.LocalDate
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
        data class DayPublication(
            val day: LocalDate,
            val sourceGeneration: Long,
            val tickets: List<DirtyRangeEntity>,
        )

        /** Capture before reading any scoring inputs, including days without pending tickets. */
        suspend fun captureDay(day: LocalDate): DayPublication =
            transactionRunner.runInTransaction {
                val state = healthMutationStateDao.getOrCreate()
                check(state.maintenanceOperationId == null) { "MAINTENANCE_PENDING" }
                DayPublication(day, state.sourceGeneration, dirtyRangeDao.pendingForDay(day.toEpochDay()))
            }

        /** Publication and every captured acknowledgment commit or roll back together. */
        suspend fun publishDay(
            publication: DayPublication,
            write: suspend () -> Unit,
        ): Boolean =
            try {
                transactionRunner.runInTransaction {
                    val state = healthMutationStateDao.current()
                    if (state.maintenanceOperationId != null ||
                        state.sourceGeneration != publication.sourceGeneration
                    ) {
                        throw PublishAbortedException()
                    }
                    publication.tickets.forEach { ticket ->
                        val updated =
                            dirtyRangeDao.advance(
                                ticket.id,
                                ticket.sourceGeneration,
                                publication.day.toEpochDay(),
                                publication.day.plusDays(1).toEpochDay(),
                            )
                        if (updated != 1) throw PublishAbortedException()
                        dirtyRangeDao.deleteCompleted(ticket.id, ticket.sourceGeneration)
                    }
                    write()
                    true
                }
            } catch (_: PublishAbortedException) {
                false
            }

        suspend fun publish(
            ticket: DirtyTicket,
            summary: DailySummary,
            zoneId: ZoneId,
            expectedSourceGeneration: Long,
            stagedWorkoutUpdates: List<ComputeDailyTrimpUseCase.WorkoutModelTrimpUpdate> = emptyList(),
            activeSnapshotId: String? = null,
        ): Boolean =
            try {
                transactionRunner.runInTransaction {
                    val state = healthMutationStateDao.current()
                    if (state.maintenanceOperationId != null || state.sourceGeneration != expectedSourceGeneration) {
                        throw PublishAbortedException()
                    }
                    if (activeSnapshotId != null && ticket.scoringSnapshotId != activeSnapshotId) {
                        throw PublishAbortedException()
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
                        throw PublishAbortedException()
                    }

                    dailySummaryDao.upsert(DailySummaryMapper.toEntity(summary, zoneId))

                    if (stagedWorkoutUpdates.isNotEmpty()) {
                        val updateMap = stagedWorkoutUpdates.associate { it.workoutId to it.modelTrimp }
                        val dayStartMs =
                            summary.date
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli()
                        val nextDayMs =
                            summary.date
                                .plusDays(1)
                                .atStartOfDay(zoneId)
                                .toInstant()
                                .toEpochMilli()
                        val workouts = workoutDao.getWorkoutsInRange(dayStartMs, nextDayMs)
                        val matching = workouts.filter { it.id in updateMap }
                        if (matching.isNotEmpty()) {
                            workoutDao.upsertAll(
                                matching.map { it.copy(modelTrimp = updateMap[it.id]) },
                            )
                        }
                    }

                    dirtyRangeDao.deleteCompleted(
                        id = ticket.id,
                        generation = ticket.sourceGeneration,
                    )

                    true
                }
            } catch (_: PublishAbortedException) {
                false
            }

        /**
         * C3 (WP-13): [PublishableDayAssembly]-typed entry point. [PublishableDayAssembly] has no
         * case corresponding to `DayAssembly.Unavailable`, so an unavailable assembly cannot be
         * passed here at all -- the exclusion is enforced at compile time by the type system, not
         * by a runtime check inside this function. A caller holding a `DayAssembly` narrows it via
         * `DayAssembly.toPublishableOrNull()` first and must decide what to do with a `null`
         * (unavailable) result -- typically "leave the ticket and whatever was previously persisted
         * for this day entirely alone" -- before ever reaching this overload. This does not change
         * [publish]'s own atomicity/generation-check logic; it only narrows what may call into it.
         */
        suspend fun publish(
            ticket: DirtyTicket,
            assembly: PublishableDayAssembly,
            zoneId: ZoneId,
            expectedSourceGeneration: Long,
            stagedWorkoutUpdates: List<ComputeDailyTrimpUseCase.WorkoutModelTrimpUpdate> = emptyList(),
            activeSnapshotId: String? = null,
        ): Boolean =
            publish(
                ticket = ticket,
                summary = assembly.summary,
                zoneId = zoneId,
                expectedSourceGeneration = expectedSourceGeneration,
                stagedWorkoutUpdates = stagedWorkoutUpdates,
                activeSnapshotId = activeSnapshotId,
            )

        private class PublishAbortedException : RuntimeException()
    }
