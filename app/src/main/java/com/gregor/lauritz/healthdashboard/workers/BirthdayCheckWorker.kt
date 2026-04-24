package com.gregor.lauritz.healthdashboard.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
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
        private val prefsRepo: UserPreferencesRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val prefs = prefsRepo.userPreferences.first()
            // Skip if birthday was never configured (all still at defaults)
            if (prefs.birthYear == 1994 && prefs.birthMonth == 1 && prefs.birthDay == 1) {
                return Result.success()
            }
            val newAge = Period.between(
                LocalDate.of(prefs.birthYear, prefs.birthMonth, prefs.birthDay),
                LocalDate.now(),
            ).years
            if (newAge != prefs.age) {
                prefsRepo.updateAge(newAge)
            }
            return Result.success()
        }
    }
