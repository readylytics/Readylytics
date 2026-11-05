package app.readylytics.health.core.healthconnect.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectAvailability
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val client: HealthConnectClient,
    ) {
        fun isAvailable(): Boolean =
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

        fun isHistoryReadAvailable(): Boolean =
            if (!isAvailable()) {
                false
            } else {
                try {
                    client.features.getFeatureStatus(
                        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
                    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
                } catch (_: Exception) {
                    false
                }
            }

        fun isBackgroundReadAvailable(): Boolean =
            if (!isAvailable()) {
                false
            } else {
                try {
                    client.features.getFeatureStatus(
                        HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
                    ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
                } catch (_: Exception) {
                    false
                }
            }
    }
