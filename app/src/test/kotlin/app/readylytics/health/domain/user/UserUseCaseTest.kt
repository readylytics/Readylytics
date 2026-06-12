package app.readylytics.health.domain.user

import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals

class UserUseCaseTest {
    private val settingsRepo =
        mockk<
            app.readylytics.health.data.preferences.SettingsRepository,
        >(relaxed = true)
    private val healthSyncUseCase =
        mockk<
            app.readylytics.health.domain.sync.HealthSyncUseCase,
        >(relaxed = true)
    private val scoringRepository =
        mockk<
            app.readylytics.health.domain.repository.ScoringRepository,
        >(relaxed = true)

    private val useCase = UserUseCase(settingsRepo, healthSyncUseCase, scoringRepository)

    // Pure function tests (no mocks needed)

    @Test
    fun calculateAge_knownDOB_1990_returnsCorrectAge() {
        val age = useCase.calculateAge(java.time.LocalDate.of(1990, 1, 1))
        val expectedAge =
            java.time.LocalDate
                .now()
                .year - 1990
        assertEquals(expectedAge, age)
    }

    @Test
    fun calculateAge_knownDOB_2000_returnsCorrectAge() {
        val age = useCase.calculateAge(java.time.LocalDate.of(2000, 1, 1))
        val expectedAge =
            java.time.LocalDate
                .now()
                .year - 2000
        assertEquals(expectedAge, age)
    }

    @Test
    fun calculateMaxHeartRate_age30_returnsTanakaFormula_187() {
        val maxHr = useCase.calculateMaxHeartRate(30)
        assertEquals(187, maxHr)
    }

    @Test
    fun calculateMaxHeartRate_age60_returnsTanakaFormula_166() {
        val maxHr = useCase.calculateMaxHeartRate(60)
        assertEquals(166, maxHr)
    }

    @Test
    fun calculateMaxHeartRate_age0_returnsTanakaFormula_208() {
        val maxHr = useCase.calculateMaxHeartRate(0)
        assertEquals(208, maxHr)
    }
}
