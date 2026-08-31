package app.readylytics.health.benchmark

import app.readylytics.health.core.healthconnect.domain.sync.HealthSyncUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Phase-0 (R2) benchmark entry point: exposes the real recompute machinery to the
 * `:database-benchmark` module. `recomputeRange` is the `skipIngestAndPrune = true` resync body —
 * B6 (8 days) and B7 (365 days) measure it for their Phase-0 baselines.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BenchmarkTestEntryPoint {
    fun healthSyncUseCase(): HealthSyncUseCase
}
