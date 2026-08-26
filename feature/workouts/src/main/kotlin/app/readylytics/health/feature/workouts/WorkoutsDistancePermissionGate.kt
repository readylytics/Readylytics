package app.readylytics.health.feature.workouts

import app.readylytics.health.core.model.domain.repository.HealthConnectRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Cached `READ_DISTANCE` permission check for UI gating (Activity volume banner). Checked once per
 * instance lifetime: permission changes require an app restart to take effect, so re-querying on
 * every state emission would only add Health Connect IPC calls.
 */
class WorkoutsDistancePermissionGate(
    private val probe: suspend () -> Boolean,
) {
    private var cached: Boolean? = null

    suspend fun isGranted(): Boolean = cached ?: probe().also { cached = it }
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
