package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoricalRunResolverTest {

    private fun createRun(
        runId: String = "test-run",
        mode: String = HistoricalRunIdentity.MODE_FULL_INGEST,
        startDate: LocalDate = LocalDate.of(2026, 3, 1),
        endDate: LocalDate = LocalDate.of(2026, 3, 28),
        zoneId: ZoneId = ZoneId.of("Europe/Berlin"),
        prefs: UserPreferences = UserPreferences(),
        algorithmRevision: Int = 1,
        protocolVersion: Int = HistoricalRunIdentity.CURRENT_PROTOCOL_VERSION,
        startedAtEpochMs: Long = 1000L,
    ): HistoricalRunIdentity {
        val base = HistoricalRunIdentity.create(
            runId = runId,
            mode = mode,
            startDate = startDate,
            endDate = endDate,
            zoneId = zoneId,
            prefs = prefs,
            resolvedHrMax = 187.0f,
            algorithmRevision = algorithmRevision,
            startedAtEpochMs = startedAtEpochMs,
        )
        return if (protocolVersion != HistoricalRunIdentity.CURRENT_PROTOCOL_VERSION) {
            base.copy(protocolVersion = protocolVersion)
        } else {
            base
        }
    }

    @Test
    fun `resolve returns savedRun when run identity parameters match and request has same or later end date`() {
        val startDate = LocalDate.of(2026, 3, 1)
        val endDate = LocalDate.of(2026, 3, 28)
        val savedRun = createRun(startDate = startDate, endDate = endDate)
        val requestSameEnd = createRun(runId = "new-attempt", startDate = startDate, endDate = endDate)
        val requestLaterEnd = createRun(runId = "new-attempt-2", startDate = startDate, endDate = endDate.plusDays(2))

        assertEquals(savedRun, HistoricalRunResolver.resolve(savedRun, requestSameEnd))
        assertEquals(savedRun, HistoricalRunResolver.resolve(savedRun, requestLaterEnd))
    }

    @Test
    fun `resolve preserves savedRun across DST and midnight transitions in Europe Berlin for all phases`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val startDate = LocalDate.of(2026, 3, 1)
        val endBeforeDst = LocalDate.of(2026, 3, 28)

        val savedRun = createRun(
            runId = "run-dst-test",
            startDate = startDate,
            endDate = endBeforeDst,
            zoneId = berlin,
        )

        // Advance through DST transition (2026-03-29 02:00 -> 03:00 CEST) and midnight to 2026-03-30
        val nextDayInstant = ZonedDateTime.of(2026, 3, 30, 8, 0, 0, 0, berlin).toInstant()
        val requestForTomorrow = createRun(
            runId = "run-new-attempt",
            startDate = startDate,
            endDate = LocalDate.of(2026, 3, 30),
            zoneId = berlin,
            startedAtEpochMs = nextDayInstant.toEpochMilli(),
        )

        val phases = listOf(
            ResyncPhase.INGEST,
            ResyncPhase.PRUNE,
            ResyncPhase.RECONCILE,
            ResyncPhase.RECOMPUTE,
        )

        for (phase in phases) {
            val checkpoint = ResyncCheckpoint(
                startDate = startDate,
                endDate = endBeforeDst,
                phase = phase,
                nextDate = startDate.plusDays(5),
                selectionHash = "test-hash",
                baselineChangeTokens = emptyMap(),
                runIdentity = savedRun,
            )
            val resolved = HistoricalRunResolver.resolve(checkpoint.runIdentity, requestForTomorrow)
            assertEquals("Phase $phase should preserve savedRun", savedRun, resolved)
        }
    }

    @Test
    fun `resolve returns request when existing is null`() {
        val request = createRun()
        assertEquals(request, HistoricalRunResolver.resolve(null, request))
    }

    @Test
    fun `resolve returns request when existing protocolVersion is not 2`() {
        val legacyRun = createRun(protocolVersion = 1)
        val request = createRun(protocolVersion = 2)

        assertEquals(request, HistoricalRunResolver.resolve(legacyRun, request))
    }

    @Test
    fun `resolve returns request when requested protocolVersion differs`() {
        val savedRun = createRun(protocolVersion = 2)
        val request = createRun(protocolVersion = 3)

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `resolve returns request when mode differs`() {
        val savedRun = createRun(mode = HistoricalRunIdentity.MODE_FULL_INGEST)
        val request = createRun(mode = HistoricalRunIdentity.MODE_RECOMPUTE_ONLY)

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `resolve returns request when zoneId differs`() {
        val savedRun = createRun(zoneId = ZoneId.of("Europe/Berlin"))
        val request = createRun(zoneId = ZoneId.of("UTC"))

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `resolve returns request when algorithmRevision differs`() {
        val savedRun = createRun(algorithmRevision = 1)
        val request = createRun(algorithmRevision = 2)

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `resolve returns request when sourceSelection differs`() {
        val savedRun = createRun(prefs = UserPreferences(deviceByDataType = mapOf("SLEEP" to "dev1")))
        val request = createRun(prefs = UserPreferences(deviceByDataType = mapOf("SLEEP" to "dev2")))

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `resolve returns request when scoring preferences snapshot differs`() {
        val savedRun = createRun(prefs = UserPreferences(goalSleepHours = 7.5f))
        val request = createRun(prefs = UserPreferences(goalSleepHours = 8.0f))

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `resolve returns request when startEpochDay differs`() {
        val savedRun = createRun(startDate = LocalDate.of(2026, 3, 1))
        val request = createRun(startDate = LocalDate.of(2026, 3, 2))

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `resolve returns request when request endEpochDayInclusive is strictly before savedRun`() {
        val savedRun = createRun(endDate = LocalDate.of(2026, 3, 28))
        val request = createRun(endDate = LocalDate.of(2026, 3, 27))

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `scoringCheckpointIdentity changes when any scoring field changes`() {
        val basePrefs = UserPreferences()
        val baseIdentity = basePrefs.scoringCheckpointIdentity()

        val changedPrefs = listOf(
            basePrefs.copy(goalSleepHours = 9.0f),
            basePrefs.copy(hrvBaselineOverride = 45f),
            basePrefs.copy(rhrBaselineOverride = 60f),
            basePrefs.copy(maxHeartRate = 195),
            basePrefs.copy(autoCalculateMaxHr = false),
            basePrefs.copy(zone1MinBpm = 100),
            basePrefs.copy(zone1MaxBpm = 120),
            basePrefs.copy(zone2MaxBpm = 140),
            basePrefs.copy(zone3MaxBpm = 160),
            basePrefs.copy(zone4MaxBpm = 180),
            basePrefs.copy(age = 40),
            basePrefs.copy(residualFatigueGain = 2.0f),
        )

        for (changed in changedPrefs) {
            assertNotEquals(
                "scoringCheckpointIdentity should change when preference field changes",
                baseIdentity,
                changed.scoringCheckpointIdentity(),
            )
        }
    }
}
