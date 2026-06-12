package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import javax.inject.Inject

internal class ThresholdPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
    ) {
        private fun Float.toValidHrvOptimal() = coerceIn(1.0f, 1.2f)

        private fun Float.toValidHrvWarning() = coerceIn(0.8f, 1.0f)

        private fun Float.toValidRhrOptimal() = coerceIn(0.8f, 1.0f)

        private fun Float.toValidRhrWarning() = coerceIn(1.0f, 1.2f)

        suspend fun updateHrvOptimalThreshold(value: Float) {
            dataStore.updateData {
                it.toBuilder().setHrvOptimalThreshold(value.toValidHrvOptimal()).build()
            }
        }

        suspend fun updateHrvWarningThreshold(value: Float) {
            dataStore.updateData {
                it.toBuilder().setHrvWarningThreshold(value.toValidHrvWarning()).build()
            }
        }

        suspend fun updateRhrOptimalThreshold(value: Float) {
            dataStore.updateData {
                it.toBuilder().setRhrOptimalThreshold(value.toValidRhrOptimal()).build()
            }
        }

        suspend fun updateRhrWarningThreshold(value: Float) {
            dataStore.updateData {
                it.toBuilder().setRhrWarningThreshold(value.toValidRhrWarning()).build()
            }
        }
    }
