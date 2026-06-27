package app.readylytics.health.domain.security

fun interface DatabaseRekeyer {
    suspend fun invoke()
}

fun interface KeyMetadataPersister {
    suspend fun invoke()
}
