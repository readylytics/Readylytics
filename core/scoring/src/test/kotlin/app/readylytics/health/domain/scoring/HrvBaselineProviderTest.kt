package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import app.readylytics.health.domain.preferences.SettingsRepository
import app.readylytics.health.data.preferences.UserPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HrvBaselineProviderTest {
    private val dao = mockk<DailySummaryDao>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>()
    private val baselineComputer = mockk<BaselineComputer>()
    private val provider = HrvBaselineProvider(dao, settingsRepository, baselineComputer)
    private val date = LocalDate.of(2026, 6, 2)

    @Before
    fun setUp() {
        coEvery { dao.getPreciseHrvMu(any()) } returns null
        coEvery { settingsRepository.userPreferences } returns flowOf(UserPreferences())
    }

    @Test
    fun `getRoundedHrvBaseline equals rounded exp(mu) when geometric mu is stored`() =
        runTest {
            val mu = ln(40.0)
            coEvery { dao.getPreciseHrvMu(any()) } returns mu

            val precise = provider.getPreciseHrvBaseline(date)
            val rounded = provider.getRoundedHrvBaseline(date)

            assertEquals(exp(mu), precise)
            assertEquals(Math.round(exp(mu)).toInt(), rounded)
            assertEquals(40, rounded)
        }

    @Test
    fun `getRoundedHrvBaseline falls back to prefs override when no stored mu`() =
        runTest {
            val overridePrefs = UserPreferences(hrvBaselineOverride = 35f)
            coEvery { settingsRepository.userPreferences } returns flowOf(overridePrefs)

            val rounded = provider.getRoundedHrvBaseline(date)

            assertEquals(35, rounded)
        }

    @Test
    fun `getRoundedHrvBaseline falls back to arithmetic baseline computer as last resort`() =
        runTest {
            coEvery { baselineComputer.computeHrvBaseline(any(), any()) } returns 28

            val rounded = provider.getRoundedHrvBaseline(date)

            assertEquals(28, rounded)
        }

    @Test
    fun `getRoundedHrvBaseline returns null when no source is available`() =
        runTest {
            coEvery { baselineComputer.computeHrvBaseline(any(), any()) } returns null

            assertNull(provider.getRoundedHrvBaseline(date))
        }
}
