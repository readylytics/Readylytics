package app.readylytics.health.core.database.data.local

import app.readylytics.health.core.databaseschema.data.local.dao.Vo2MaxRecordDao
import app.readylytics.health.core.model.domain.repository.TransactionRunner
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.ScoringRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class RetentionCleanupVo2MaxTest {
    @Test
    fun retentionCleanupDeletesVo2MaxRecordsBeforeCutoff() = runTest {
        val vo2MaxRecordDao = mockk<Vo2MaxRecordDao>(relaxed = true)
        coEvery { vo2MaxRecordDao.deleteBefore(any()) } returns 5

        val transactionRunner =
            object : TransactionRunner {
                override suspend fun <R> runInTransaction(block: suspend () -> R): R = block()
            }

        val cleanup =
            RetentionCleanup(
                transactionRunner = transactionRunner,
                daos = mockk(relaxed = true),
                dailySummaryDao = mockk(relaxed = true),
                vo2MaxRecordDao = vo2MaxRecordDao,
            )

        cleanup.deleteBefore(
            100_000L,
            ScoringRunContext.capture(UserPreferences(), Instant.parse("2026-08-31T12:00:00Z")),
        )

        coVerify { vo2MaxRecordDao.deleteBefore(100_000L) }
    }
}
