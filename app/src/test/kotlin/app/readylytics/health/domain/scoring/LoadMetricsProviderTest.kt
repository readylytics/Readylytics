package app.readylytics.health.domain.scoring

import app.readylytics.health.data.local.dao.DailySummaryDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class LoadMetricsProviderTest {
    private val dao = mockk<DailySummaryDao>(relaxed = true)
    private val provider = LoadMetricsProvider(dao)

    @Test
    fun `precise strain ratio returns unrounded db value`() =
        runTest {
            coEvery { dao.getPreciseStrainRatio(any()) } returns 0.365

            assertEquals(0.365, provider.getPreciseStrainRatio(LocalDate.of(2026, 6, 9)))
        }

    @Test
    fun `rounded strain ratio returns two decimal value`() =
        runTest {
            coEvery { dao.getPreciseStrainRatio(any()) } returns 0.365

            assertEquals(0.37f, provider.getRoundedStrainRatio(LocalDate.of(2026, 6, 9)))
        }
}
