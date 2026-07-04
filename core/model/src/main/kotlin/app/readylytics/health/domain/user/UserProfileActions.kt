package app.readylytics.health.domain.user

import app.readylytics.health.domain.model.Result
import java.time.LocalDate

interface UserProfileActions {
    suspend fun updateBirthday(date: LocalDate): Result<Unit>

    suspend fun calculateAndSetMaxHr(): Result<Unit>
}
