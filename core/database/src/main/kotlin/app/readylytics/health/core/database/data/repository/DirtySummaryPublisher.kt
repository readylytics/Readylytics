package app.readylytics.health.core.database.data.repository

import app.readylytics.health.core.databaseschema.data.local.dao.DirtyRangeDao
import app.readylytics.health.core.databaseschema.data.local.dao.HealthMutationStateDao
import app.readylytics.health.core.databaseschema.data.local.entity.DirtyRangeEntity
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirtySummaryPublisher
    @Inject
    constructor(
        private val transactionRunner: TransactionRunner,
        private val healthMutationStateDao: HealthMutationStateDao,
        private val dirtyRangeDao: DirtyRangeDao,
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
                    write()
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
                    true
                }
            } catch (_: PublishAbortedException) {
                false
            }

        private class PublishAbortedException : RuntimeException()
    }
