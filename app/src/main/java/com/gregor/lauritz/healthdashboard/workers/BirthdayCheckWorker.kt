package com.gregor.lauritz.healthdashboard.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gregor.lauritz.healthdashboard.data.preferences.SettingsRepository
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
            if (!prefs.isBirthdayConfigured) {
                return Result.success()
            }
            val newAge =
                Period
                    .between(
                        LocalDate.of(prefs.birthYear, prefs.birthMonth, prefs.birthDay),
                        LocalDate.now(),
                    ).years
            if (newAge != prefs.age) {
                settingsRepo.updateAge(newAge)
            }
            return Result.success()
        }
    }
