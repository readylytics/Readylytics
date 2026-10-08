package app.readylytics.health.core.model.domain.repository

/** Owns retries for individual SDK calls without retrying page consumers. */
interface ReadRetryScope {
    suspend fun <T> execute(label: String, block: suspend () -> T): T
}
