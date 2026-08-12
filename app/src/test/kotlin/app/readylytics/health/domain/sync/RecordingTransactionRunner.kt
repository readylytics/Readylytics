package app.readylytics.health.domain.sync

import app.readylytics.health.domain.repository.TransactionRunner

/**
 * Shared test double implementation of [TransactionRunner] that tracks transaction counts,
 * current open depth, and peak nesting depth across sync and resync unit tests.
 */
class RecordingTransactionRunner : TransactionRunner {
    var transactionCount = 0
        private set
    var openDepth = 0
        private set
    var maxDepth = 0
        private set

    override suspend fun <R> runInTransaction(block: suspend () -> R): R {
        transactionCount++
        openDepth++
        maxDepth = maxOf(maxDepth, openDepth)
        try {
            return block()
        } finally {
            openDepth--
        }
    }
}
