package app.readylytics.health.core.database.domain.sync

import app.readylytics.health.core.model.domain.model.Result
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.repository.ScoringRepository
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import app.readylytics.health.core.model.domain.util.DiagnosticFields
import app.readylytics.health.core.model.domain.util.DomainLogSink
import app.readylytics.health.core.model.domain.util.DomainLogger
import app.readylytics.health.core.model.domain.util.LogContext
import app.readylytics.health.core.model.domain.util.LogLevel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class DailyRecomputeSupportDiagnosticsTest {
    private val captured = mutableListOf<LogContext>()

    private fun sink(onLog: (LogContext) -> Unit) =
        object : DomainLogSink {
            override fun log(
                level: LogLevel,
                tag: String,
                message: String,
                throwable: Throwable?,
                context: LogContext,
            ) = onLog(context)
        }

    @Before
    fun installCapturingSink() {
        DomainLogger.installSink(sink { captured += it })
    }

    @After
    fun resetSink() {
        DomainLogger.installSink(sink { })
    }

    @Test
    fun `failed day logs the recompute phase and its offset from the run's today`() =
        runTest {
            val scoringRepository =
                mockk<ScoringRepository> {
                    coEvery { computeAndPersistDailySummary(any(), any(), any(), any(), any()) } throws
                        IllegalStateException("boom")
                }
            val support = DailyRecomputeSupport(scoringRepository, mockk(relaxed = true), mockk(relaxed = true))
            val prefs = UserPreferences()
            val runContext = ScoringRunContext.capture(prefs, Instant.parse("2026-10-08T12:00:00Z"))

            val result =
                support.recomputeDay(
                    day = runContext.today.minusDays(3),
                    steps = null,
                    prefs = prefs,
                    runContext = runContext,
                )

            assertTrue(result is Result.Failure)
            assertEquals(
                DiagnosticFields(phase = ResyncPhase.RECOMPUTE, dayOffsetFromToday = -3),
                captured.single { it.fields != null }.fields,
            )
        }
}
