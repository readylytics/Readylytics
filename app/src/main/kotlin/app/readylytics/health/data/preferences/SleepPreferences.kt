package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import javax.inject.Inject

internal class SleepPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
    ) {
        private fun Float.toValidRasScaling() = coerceIn(0.1f, 0.3f)

        private fun Int.toValidStepGoal() = coerceIn(1000, 30000)

        private fun Int.toValidRetentionDays() = coerceIn(180, 1095)

        private fun Int.toValidConsistencyMinutes() = coerceIn(0, 90)

        private fun Int.toValidConsistencyDays() = coerceIn(3, 30)

        private fun Int.toValidHrrTolerance() =
            coerceIn(
                SettingsDefaults.MIN_HRR_TOLERANCE_SECONDS,
                SettingsDefaults.MAX_HRR_TOLERANCE_SECONDS,
            )

        suspend fun updateGoalSleepHours(hours: Float) {
            dataStore.updateData { it.toBuilder().setGoalSleepHours(hours).build() }
        }

        suspend fun updateConsistencyThresholdMinutes(minutes: Int) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setConsistencyThresholdMinutes(minutes.toValidConsistencyMinutes())
                    .build()
            }
        }

        suspend fun updateConsistencyEvaluationDays(days: Int) {
            dataStore.updateData {
                it.toBuilder().setConsistencyEvaluationDays(days.toValidConsistencyDays()).build()
            }
        }

        suspend fun updateConsistencyBaselineDays(days: Int) {
            dataStore.updateData {
                it.toBuilder().setConsistencyBaselineDays(days.toValidConsistencyDays()).build()
            }
        }

        suspend fun updateRasScalingFactor(value: Float) {
            dataStore.updateData { it.toBuilder().setRasScalingFactor(value.toValidRasScaling()).build() }
        }

        suspend fun updateStepGoal(steps: Int) {
            dataStore.updateData { it.toBuilder().setStepGoal(steps.toValidStepGoal()).build() }
        }

        suspend fun updateRetentionDaysEnabled(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setRetentionDaysEnabled(enabled).build() }
        }

        suspend fun updateRetentionDays(days: Int) {
            dataStore.updateData { it.toBuilder().setRetentionDays(days.toValidRetentionDays()).build() }
        }

        suspend fun updateHrrToleranceSeconds(value: Int) {
            dataStore.updateData {
                it.toBuilder().setHrrToleranceSeconds(value.toValidHrrTolerance()).build()
            }
        }
    }
