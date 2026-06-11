package app.readylytics.health.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.readylytics.health.data.preferences.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.Period

@HiltWorker
class BirthdayCheckWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val settingsRepo: SettingsRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val prefs = settingsRepo.userPreferences.first()
            // Skip if birthday was never configured
            if (!prefs.isBirthdayConfigured || prefs.birthDate == null) {
                return Result.success()
            }

            return try {
                val birthDate = LocalDate.parse(prefs.birthDate)
                val newAge = Period.between(birthDate, LocalDate.now()).years
                if (newAge != prefs.age) {
                    settingsRepo.updateAge(newAge)
                }
                Result.success()
            } catch (e: Exception) {
                Result.failure()
            }
        }
    }
