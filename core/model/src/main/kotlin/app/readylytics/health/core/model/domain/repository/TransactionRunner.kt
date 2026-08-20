package app.readylytics.health.core.model.domain.repository

interface TransactionRunner {
    suspend fun <R> runInTransaction(block: suspend () -> R): R
}
