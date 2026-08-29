package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.scoring.domain.scoring.RasCalculator
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import javax.inject.Inject

internal class PhysiologyPreferences
    @Inject
    constructor(
        private val dataStore: DataStore<UserPreferencesProto>,
        private val clock: Clock = Clock.systemDefaultZone(),
    ) {
        private fun Int.toValidMaxHr() = coerceIn(100, 250)

        private fun Int.toValidAge() = coerceIn(1, 120)

        private fun Float.toValidHeight() = coerceIn(120f, 250f)

        private fun Int.toValidRestingHrPercentile() = coerceIn(1, 15)

        private fun Float.toValidBanisterMultiplier() = coerceIn(0.5f, 2.5f)

        private fun Float.toValidChengBeta() = coerceIn(0.04f, 0.12f)

        private fun Float.toValidItrimB() = coerceIn(1.0f, 4.5f)

        private fun Float.toValidFatigueHalfLife() =
            coerceIn(
                SettingsDefaults.MIN_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
                SettingsDefaults.MAX_RESIDUAL_FATIGUE_HALF_LIFE_HOURS,
            )

        private fun Float.toValidFatigueGain() =
            coerceIn(
                SettingsDefaults.MIN_RESIDUAL_FATIGUE_GAIN,
                SettingsDefaults.MAX_RESIDUAL_FATIGUE_GAIN,
            )

        suspend fun updateMaxHeartRate(bpm: Int) {
            dataStore.updateData { it.toBuilder().setMaxHeartRate(bpm.toValidMaxHr()).build() }
        }

        suspend fun updateAutoCalculateMaxHr(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setAutoCalculateMaxHr(enabled).build() }
        }

        suspend fun updateManualZoneEditing(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setManualZoneEditing(enabled).build() }
        }

        suspend fun updateZonePercentages(
            z1Min: Float,
            z1Max: Float,
            z2Max: Float,
            z3Max: Float,
            z4Max: Float,
        ) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setZone1MinPercent(z1Min)
                    .setZone1MaxPercent(z1Max)
                    .setZone2MaxPercent(z2Max)
                    .setZone3MaxPercent(z3Max)
                    .setZone4MaxPercent(z4Max)
                    .build()
            }
        }

        suspend fun updateZoneBpms(
            z1Min: Int,
            z1Max: Int,
            z2Max: Int,
            z3Max: Int,
            z4Max: Int,
        ) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setZone1MinBpm(z1Min)
                    .setZone1MaxBpm(z1Max)
                    .setZone2MaxBpm(z2Max)
                    .setZone3MaxBpm(z3Max)
                    .setZone4MaxBpm(z4Max)
                    .build()
            }
        }

        suspend fun updateAge(age: Int) {
            dataStore.updateData { it.toBuilder().setAge(age.toValidAge()).build() }
        }

        suspend fun updateBirthday(date: LocalDate) {
            val today = LocalDate.now(clock)
            val validBirthDate = if (date > today) today else date
            val age = Period.between(validBirthDate, today).years

            dataStore.updateData {
                it
                    .toBuilder()
                    .setBirthDay(validBirthDate.dayOfMonth)
                    .setBirthMonth(validBirthDate.monthValue)
                    .setBirthYear(validBirthDate.year)
                    .setAge(age.toValidAge())
                    .setIsBirthdayConfigured(true)
                    .build()
            }
        }

        suspend fun updateGender(gender: String?) {
            dataStore.updateData { builder ->
                if (gender != null) {
                    builder.toBuilder().setGender(gender).build()
                } else {
                    builder.toBuilder().clearGender().build()
                }
            }
        }

        suspend fun updateHeight(heightCm: Float?) {
            dataStore.updateData { builder ->
                if (heightCm != null) {
                    builder.toBuilder().setHeightCm(heightCm.toValidHeight()).build()
                } else {
                    builder.toBuilder().clearHeightCm().build()
                }
            }
        }

        suspend fun updateHrvBaselineOverride(rmssdMs: Float?) {
            dataStore.updateData { builder ->
                if (rmssdMs != null) {
                    builder.toBuilder().setHrvBaselineOverride(rmssdMs).build()
                } else {
                    builder.toBuilder().clearHrvBaselineOverride().build()
                }
            }
        }

        suspend fun updateRhrBaselineOverride(bpm: Float?) {
            dataStore.updateData { builder ->
                if (bpm != null) {
                    builder.toBuilder().setRhrBaselineOverride(bpm).build()
                } else {
                    builder.toBuilder().clearRhrBaselineOverride().build()
                }
            }
        }

        suspend fun updateRestingHrPercentile(percentile: Int) {
            dataStore.updateData {
                it.toBuilder().setRestingHrPercentile(percentile.toValidRestingHrPercentile()).build()
            }
        }

        suspend fun updatePhysiologyProfile(profile: PhysiologyProfile) {
            val newRasFactor = RasCalculator.getDefaultRasScalingFactor(profile)
            dataStore.updateData {
                it
                    .toBuilder()
                    .setPhysiologyProfile(
                        when (profile) {
                            PhysiologyProfile.ATHLETE -> PhysiologyProfileProto.PROFILE_ATHLETE
                            PhysiologyProfile.ACTIVE -> PhysiologyProfileProto.PROFILE_ACTIVE
                            PhysiologyProfile.SEDENTARY -> PhysiologyProfileProto.PROFILE_SEDENTARY
                        },
                    ).setRasScalingFactor(newRasFactor)
                    .setRasCalibration(profile.banisterMultiplier)
                    .setChengBeta(profile.defaultChengBeta)
                    .setItrimpB(profile.defaultItrimB)
                    .build()
            }
        }

        suspend fun migrateTrimpDefaultsIfNeeded() {
            dataStore.updateData { proto ->
                if (proto.trimpNormalizationMigrated) return@updateData proto
                val newRasCal =
                    TrimpMigrationHelper.migrateRasCalibration(
                        storedValue = proto.rasCalibration,
                        alreadyMigrated = false,
                    )
                proto
                    .toBuilder()
                    .setRasCalibration(newRasCal)
                    .setTrimpNormalizationMigrated(true)
                    .build()
            }
        }

        suspend fun updateTrimpModel(model: TrimpModel) {
            dataStore.updateData {
                it
                    .toBuilder()
                    .setTrimpMethod(
                        when (model) {
                            TrimpModel.BANISTER -> TrimpMethodProto.TRIMP_BANISTER
                            TrimpModel.I_TRIMP -> TrimpMethodProto.TRIMP_ITRIMP
                            TrimpModel.CHENG -> TrimpMethodProto.TRIMP_CHENG
                        },
                    ).build()
            }
        }

        suspend fun updateBanisterMultiplier(value: Float) {
            dataStore.updateData {
                it.toBuilder().setRasCalibration(value.toValidBanisterMultiplier()).build()
            }
        }

        suspend fun updateChengBeta(value: Float) {
            dataStore.updateData { it.toBuilder().setChengBeta(value.toValidChengBeta()).build() }
        }

        suspend fun updateItrimB(value: Float) {
            dataStore.updateData { it.toBuilder().setItrimpB(value.toValidItrimB()).build() }
        }

        suspend fun updateResidualFatigueEnabled(enabled: Boolean) {
            dataStore.updateData { it.toBuilder().setResidualFatigueEnabled(enabled).build() }
        }

        suspend fun updateResidualFatigueHalfLifeHours(hours: Float) {
            dataStore.updateData {
                it.toBuilder().setResidualFatigueHalfLifeHours(hours.toValidFatigueHalfLife()).build()
            }
        }

        suspend fun updateResidualFatigueGain(value: Float) {
            dataStore.updateData {
                it.toBuilder().setResidualFatigueGain(value.toValidFatigueGain()).build()
            }
        }
    }

internal object TrimpMigrationHelper {
    fun migrateRasCalibration(
        storedValue: Float,
        alreadyMigrated: Boolean,
    ): Float =
        when {
            alreadyMigrated -> storedValue
            storedValue == 0f -> 1f
            else -> storedValue
        }
}
