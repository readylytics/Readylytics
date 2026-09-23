package app.readylytics.health.core.healthconnect.domain.sync

import app.readylytics.health.core.model.domain.model.HealthDataType
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.sync.HistoricalRunIdentity
import app.readylytics.health.core.model.domain.sync.ResyncCheckpoint
import app.readylytics.health.core.model.domain.sync.ResyncPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
        val base =
            HistoricalRunIdentity.create(
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

        val savedRun =
            createRun(
                runId = "run-dst-test",
                startDate = startDate,
                endDate = endBeforeDst,
                zoneId = berlin,
            )

        // Advance through DST transition (2026-03-29 02:00 -> 03:00 CEST) and midnight to 2026-03-30
        val nextDayInstant = ZonedDateTime.of(2026, 3, 30, 8, 0, 0, 0, berlin).toInstant()
        val requestForTomorrow =
            createRun(
                runId = "run-new-attempt",
                startDate = startDate,
                endDate = LocalDate.of(2026, 3, 30),
                zoneId = berlin,
                startedAtEpochMs = nextDayInstant.toEpochMilli(),
            )

        val phases =
            listOf(
                ResyncPhase.INGEST,
                ResyncPhase.PRUNE,
                ResyncPhase.RECONCILE,
                ResyncPhase.RECOMPUTE,
            )

        for (phase in phases) {
            val checkpoint =
                ResyncCheckpoint(
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
    fun `resolve returns request when existing protocolVersion is not current`() {
        val legacyRun = createRun(protocolVersion = 1)
        val request = createRun(protocolVersion = HistoricalRunIdentity.CURRENT_PROTOCOL_VERSION)

        assertEquals(request, HistoricalRunResolver.resolve(legacyRun, request))
    }

    @Test
    fun `resolve returns request when requested protocolVersion differs`() {
        val savedRun = createRun(protocolVersion = 2)
        val request = createRun(protocolVersion = HistoricalRunIdentity.CURRENT_PROTOCOL_VERSION)

        assertEquals(request, HistoricalRunResolver.resolve(savedRun, request))
    }

    @Test
    fun `retention change creates a new snapshot identity and restarts`() {
        val old = createRun(prefs = UserPreferences(retentionDays = 365))
        val requested = createRun(prefs = UserPreferences(retentionDays = 730))

        assertNotEquals(old.scoringSnapshotId, requested.scoringSnapshotId)
        assertSame(requested, HistoricalRunResolver.resolve(old, requested))
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

        val changedPrefs =
            listOf(
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

    // --- Review fix (finding #4): HR-zone/link-policy change -> RECONCILE restart ------------

    @Test
    fun `resolveEffectiveCheckpoint restarts reconcile when HR zone threshold changes`() {
        val startDate = LocalDate.of(2026, 3, 1)
        val endDate = LocalDate.of(2026, 3, 28)
        val oldRun = createRun(startDate = startDate, endDate = endDate, prefs = UserPreferences(zone3MaxBpm = 160))
        val newRun =
            createRun(
                runId = "new-attempt",
                startDate = startDate,
                endDate = endDate,
                prefs = UserPreferences(zone3MaxBpm = 170),
            )
        val checkpoint =
            ResyncCheckpoint(
                startDate = startDate,
                endDate = endDate,
                phase = ResyncPhase.RECOMPUTE,
                nextDate = startDate.plusDays(10),
                selectionHash = "old-hash",
                baselineChangeTokens = mapOf(HealthDataType.SLEEP to "tok-sleep"),
                completedTypes = setOf(HealthDataType.SLEEP, HealthDataType.HEART_RATE),
                runIdentity = oldRun,
            )

        val resolved =
            HistoricalRunResolver.resolveEffectiveCheckpoint(
                savedCheckpoint = checkpoint,
                runIdentity = newRun,
                isSameRun = false,
                skipIngestAndPrune = false,
                runStartDate = startDate,
            )

        // Not a no-op: phase rewound from RECOMPUTE to RECONCILE and nextDate reset to the run start.
        assertNotNull(resolved)
        assertEquals(ResyncPhase.RECONCILE, resolved?.phase)
        assertEquals(startDate, resolved?.nextDate)
        assertEquals(newRun, resolved?.runIdentity)
        // Not a full restart: a full restart clears the checkpoint (resolveEffectiveCheckpoint would
        // return null here, forcing the caller to recapture fresh baseline tokens/completedTypes).
        assertEquals(checkpoint.baselineChangeTokens, resolved?.baselineChangeTokens)
        assertEquals(checkpoint.completedTypes, resolved?.completedTypes)
    }

    @Test
    fun `resolveEffectiveCheckpoint restarts reconcile when source link policy changes`() {
        val startDate = LocalDate.of(2026, 3, 1)
        val endDate = LocalDate.of(2026, 3, 28)
        val oldRun =
            createRun(
                startDate = startDate,
                endDate = endDate,
                prefs = UserPreferences(rasSourceMode = LoadSourceMode.WORKOUT_ONLY),
            )
        val newRun =
            createRun(
                runId = "new-attempt",
                startDate = startDate,
                endDate = endDate,
                prefs = UserPreferences(rasSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE),
            )
        val checkpoint =
            ResyncCheckpoint(
                startDate = startDate,
                endDate = endDate,
                phase = ResyncPhase.RECOMPUTE,
                nextDate = startDate.plusDays(10),
                selectionHash = "old-hash",
                runIdentity = oldRun,
            )

        val resolved =
            HistoricalRunResolver.resolveEffectiveCheckpoint(
                savedCheckpoint = checkpoint,
                runIdentity = newRun,
                isSameRun = false,
                skipIngestAndPrune = false,
                runStartDate = startDate,
            )

        assertNotNull(resolved)
        assertEquals(ResyncPhase.RECONCILE, resolved?.phase)
        assertEquals(startDate, resolved?.nextDate)
    }

    // --- Review fix (finding #2): source-selection change -> affected-type INGEST restart -----

    @Test
    fun `resolveEffectiveCheckpoint restarts ingest for source selection change, preserving types and tokens`() {
        val startDate = LocalDate.of(2026, 3, 1)
        val endDate = LocalDate.of(2026, 3, 28)
        val oldRun =
            createRun(
                startDate = startDate,
                endDate = endDate,
                prefs = UserPreferences(deviceByDataType = mapOf("HEART_RATE" to "watch-a", "SLEEP" to "phone-a")),
            )
        val newRun =
            createRun(
                runId = "new-attempt",
                startDate = startDate,
                endDate = endDate,
                prefs = UserPreferences(deviceByDataType = mapOf("HEART_RATE" to "watch-b", "SLEEP" to "phone-a")),
            )
        val preservedTokens = mapOf(HealthDataType.SLEEP to "tok-sleep", HealthDataType.HEART_RATE to "tok-hr")
        val preservedCompletedTypes = setOf(HealthDataType.SLEEP, HealthDataType.HEART_RATE, HealthDataType.EXERCISE)
        val checkpoint =
            ResyncCheckpoint(
                startDate = startDate,
                endDate = endDate,
                phase = ResyncPhase.RECOMPUTE,
                nextDate = startDate.plusDays(15),
                selectionHash = "old-hash",
                baselineChangeTokens = preservedTokens,
                completedTypes = preservedCompletedTypes,
                runIdentity = oldRun,
            )

        val resolved =
            HistoricalRunResolver.resolveEffectiveCheckpoint(
                savedCheckpoint = checkpoint,
                runIdentity = newRun,
                isSameRun = false,
                skipIngestAndPrune = false,
                runStartDate = startDate,
            )

        // Only the INGEST phase (and what naturally follows it) restarts -- not a full restart.
        assertNotNull(resolved)
        assertEquals(ResyncPhase.INGEST, resolved?.phase)
        assertEquals(startDate, resolved?.nextDate)
        assertEquals(newRun, resolved?.runIdentity)
        // Other types' bookkeeping (and the run's original Changes-API baseline) is preserved rather
        // than reset -- a full restart would instead clear the checkpoint and recapture a fresh
        // baseline via changeSynchronizer.captureChangesTokens(), losing this continuity.
        assertEquals(preservedCompletedTypes, resolved?.completedTypes)
        assertEquals(preservedTokens, resolved?.baselineChangeTokens)
    }

    @Test
    fun `source selection and zones changing together restart ingestion from every phase`() {
        val start = LocalDate.of(2026, 3, 1)
        val old = createRun(prefs = UserPreferences(deviceByDataType = mapOf("HEART_RATE" to "watch-a")))
        val next =
            createRun(
                prefs = UserPreferences(deviceByDataType = mapOf("HEART_RATE" to "watch-b"), zone3MaxBpm = 170),
            )
        ResyncPhase.entries.forEach { phase ->
            val checkpoint =
                ResyncCheckpoint(
                    startDate = start,
                    endDate = LocalDate.of(2026, 3, 28),
                    phase = phase,
                    nextDate = start.plusDays(10),
                    selectionHash = old.scoringSnapshotId,
                    runIdentity = old,
                    hrPageToken = "stale-page",
                    chunkDaysOverride = 5,
                )
            val resolved = HistoricalRunResolver.resolveEffectiveCheckpoint(checkpoint, next, false, false, start)
            assertEquals("phase $phase", ResyncPhase.INGEST, resolved?.phase)
            assertEquals(start, resolved?.nextDate)
            assertEquals(null, resolved?.hrPageToken)
            assertEquals(null, resolved?.chunkDaysOverride)
        }
    }

    @Test
    fun `resolveEffectiveCheckpoint does not restart ingest when only the primary device fallback changes`() {
        val startDate = LocalDate.of(2026, 3, 1)
        val endDate = LocalDate.of(2026, 3, 28)
        val oldRun =
            createRun(startDate = startDate, endDate = endDate, prefs = UserPreferences(primaryDeviceName = "phone-a"))
        val newRun =
            createRun(
                runId = "new-attempt",
                startDate = startDate,
                endDate = endDate,
                prefs = UserPreferences(primaryDeviceName = "phone-b"),
            )
        val checkpoint =
            ResyncCheckpoint(
                startDate = startDate,
                endDate = endDate,
                phase = ResyncPhase.RECOMPUTE,
                nextDate = startDate.plusDays(10),
                selectionHash = "old-hash",
                runIdentity = oldRun,
            )

        val resolved =
            HistoricalRunResolver.resolveEffectiveCheckpoint(
                savedCheckpoint = checkpoint,
                runIdentity = newRun,
                isSameRun = false,
                skipIngestAndPrune = false,
                runStartDate = startDate,
            )

        // Ingestion filters by deviceByDataType only (never the primary-device fallback), so a
        // primary-device-only change has no effect on what is ingested -- it must not force an
        // ingest restart, instead falling through to the scoring-only recompute-restart branch.
        assertNotNull(resolved)
        assertEquals(ResyncPhase.RECOMPUTE, resolved?.phase)
        assertEquals(checkpoint.startDate, resolved?.nextDate)
    }
}
