package app.readylytics.health.domain.repository

interface TransactionRunner {
    suspend fun <R> runInTransaction(block: suspend () -> R): R
}
