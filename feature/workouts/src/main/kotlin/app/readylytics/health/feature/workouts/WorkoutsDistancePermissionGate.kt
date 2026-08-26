package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Cached `READ_DISTANCE` permission check for UI gating (Activity volume banner). A **granted**
 * result is cached permanently (the process is killed on revocation anyway). A **denied** result
 * is re-probed every call because Android does *not* kill the process when the user grants a
 * permission — only when they revoke one.
 */
class WorkoutsDistancePermissionGate(
    private val probe: suspend () -> Boolean,
) {
    private var cached: Boolean? = null

    suspend fun isGranted(): Boolean {
        if (cached == true) return true
        return probe().also { cached = it }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object WorkoutsPermissionModule {
    @Provides
    @Singleton
    fun provideDistancePermissionGate(
        healthConnectRepository: HealthConnectRepository,
    ): WorkoutsDistancePermissionGate =
        WorkoutsDistancePermissionGate { healthConnectRepository.hasDistancePermission() }
}
