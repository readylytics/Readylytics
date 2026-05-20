package com.gregor.lauritz.healthdashboard.data.local

import androidx.room.withTransaction
import com.gregor.lauritz.healthdashboard.domain.repository.TransactionRunner
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
