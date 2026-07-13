package app.readylytics.health.domain.scoring.golden

import app.readylytics.health.data.preferences.UserPreferences
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.domain.security.EncryptionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

/**
 * Minimal, non-mocking fake used by the golden-fixture walk-forward test so the real
 * [app.readylytics.health.data.repository.ScoringRepositoryImpl] can be constructed directly
 * against a fixed [UserPreferences] snapshot. The walk-forward test never mutates preferences
 * mid-run, so every method beyond the [userPreferences] flow is a no-op.
 */
class FakeSettingsRepository(initial: UserPreferences) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val userPreferences: Flow<UserPreferences> = state

    override suspend fun bootstrapRasSourceModeIfUnset(hasWorkoutOnlyHistory: Boolean) = Unit

    override suspend fun updateMaxHeartRate(bpm: Int) = Unit

    override suspend fun migrateDeviceSelectionIfNeeded() = Unit

    override suspend fun updateLastSyncTimestamp(timestamp: Long) = Unit

    override suspend fun updateBirthday(date: LocalDate) = Unit
}

/**
 * Identity no-op [EncryptionManager]. The golden fixture never sets
 * [UserPreferences.circadianThresholdOverride], so decrypt is never exercised, but a real
 * implementation is provided anyway rather than a mock to keep the object graph mock-free.
 */
class FakeEncryptionManager : EncryptionManager {
    override fun encrypt(plaintext: String): String = plaintext

    override fun decrypt(ciphertext: String): String? = ciphertext
}
