package app.readylytics.health.data.local

import androidx.room.withTransaction
import app.readylytics.health.domain.repository.TransactionRunner
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomTransactionRunner
    @Inject
    constructor(
        private val database: HealthDatabase,
    ) : TransactionRunner {
        override suspend fun <R> runInTransaction(block: suspend () -> R): R = database.withTransaction(block)
    }
