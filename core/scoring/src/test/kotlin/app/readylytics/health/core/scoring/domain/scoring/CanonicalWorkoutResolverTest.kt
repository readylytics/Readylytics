package app.readylytics.health.core.scoring.domain.scoring

import app.readylytics.health.core.model.domain.model.getOrNull
import app.readylytics.health.core.model.domain.preferences.UserPreferences
import app.readylytics.health.core.model.domain.scoring.CanonicalWorkoutResult
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import app.readylytics.health.core.model.domain.scoring.WorkoutHrQuality
import app.readylytics.health.core.model.domain.scoring.WorkoutScoringIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CanonicalWorkoutResolverTest {
    private val computeWorkoutTrimpUseCase = ComputeWorkoutTrimpUseCase()
    private val resolver = CanonicalWorkoutResolver(computeWorkoutTrimpUseCase)

    private val workoutStartMs = Instant.parse("2026-06-09T08:00:00Z").toEpochMilli()
    private val workoutEndMs = Instant.parse("2026-06-09T09:00:00Z").toEpochMilli()

    private val samples =
        listOf(
            ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.parse("2026-06-09T08:15:00Z"), 120),
            ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.parse("2026-06-09T08:30:00Z"), 140),
            ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.parse("2026-06-09T08:45:00Z"), 160),
        )

    private val identity =
        WorkoutScoringIdentity(
            sourceRevision = 1L,
            scoringSnapshotId = "snap-123",
            algorithmRevision = 1,
        )

    @Test
    fun `all supported TRIMP models use frozen hrMax and ignore conflicting current prefs maxHr`() {
        for (model in TrimpModel.entries) {
            val prefs =
                UserPreferences(
                    maxHeartRate = 210, // Conflicting current pref
                    autoCalculateMaxHr = false,
                    trimpModel = model,
                    zone3MaxBpm = 150, // for Cheng LT
                )
            val context =
                WorkoutScoringContext(
                    prefs = prefs,
                    rhrBaseline = 60f,
                    frozenHrMax = 190f, // Must be used instead of 210
                    identity = identity,
                )
            val input =
                CanonicalWorkoutInput(
                    workoutId = "w1",
                    startMs = workoutStartMs,
                    endMs = workoutEndMs,
                    samples = samples,
                    quality = WorkoutHrQuality.RAW,
                    context = context,
                    prior = null,
                )

            val result = resolver.resolve(input)

            assertNotNull("TRIMP for $model should not be null", result.trimp)
            assertTrue("TRIMP for $model must be finite", result.trimp!!.isFinite())
            assertTrue("TRIMP for $model must be positive", result.trimp!! > 0f)
            assertEquals(WorkoutHrQuality.RAW, result.quality)
            assertEquals(1L, result.sourceRevision)
            assertEquals("snap-123", result.scoringSnapshotId)
            assertEquals(1, result.algorithmRevision)

            // Verify that result matches a direct calculation with frozenHrMax=190f, not current 210f
            val expectedTrimp =
                computeWorkoutTrimpUseCase.execute(
                    workoutStartTime = workoutStartMs,
                    workoutEndTime = workoutEndMs,
                    workoutAvgHr = 140f,
                    samples = samples,
                    prefs = prefs,
                    restingHrBaseline = 60f,
                    frozenHrMax = 190f,
                ).getOrNull()!!
            assertEquals(expectedTrimp, result.trimp!!, 0.001f)
        }
    }

    @Test
    fun `warm input preserves warm approximate quality`() {
        val prefs = UserPreferences()
        val context =
            WorkoutScoringContext(
                prefs = prefs,
                rhrBaseline = 60f,
                frozenHrMax = 190f,
                identity = identity,
            )
        val input =
            CanonicalWorkoutInput(
                workoutId = "w-warm",
                startMs = workoutStartMs,
                endMs = workoutEndMs,
                samples = samples,
                quality = WorkoutHrQuality.WARM_APPROXIMATE,
                context = context,
                prior = null,
            )

        val result = resolver.resolve(input)

        assertNotNull(result.trimp)
        assertEquals(WorkoutHrQuality.WARM_APPROXIMATE, result.quality)
    }

    @Test
    fun `zero duration workout produces finite zero trimp`() {
        val prefs = UserPreferences()
        val context =
            WorkoutScoringContext(
                prefs = prefs,
                rhrBaseline = 60f,
                frozenHrMax = 190f,
                identity = identity,
            )
        val zeroDurationSamples =
            listOf(
                ComputeWorkoutTrimpUseCase.HeartRateSample(Instant.ofEpochMilli(workoutStartMs), 140),
            )
        val input =
            CanonicalWorkoutInput(
                workoutId = "w-zero",
                startMs = workoutStartMs,
                endMs = workoutStartMs, // Zero duration
                samples = zeroDurationSamples,
                quality = WorkoutHrQuality.RAW,
                context = context,
                prior = null,
            )

        val result = resolver.resolve(input)

        assertNotNull(result.trimp)
        assertTrue(result.trimp!!.isFinite())
        assertEquals(0f, result.trimp!!, 0.0f)
        assertEquals(WorkoutHrQuality.RAW, result.quality)
    }

    @Test
    fun `missing HR with no prior returns null trimp and UNAVAILABLE quality`() {
        val prefs = UserPreferences()
        val context =
            WorkoutScoringContext(
                prefs = prefs,
                rhrBaseline = 60f,
                frozenHrMax = 190f,
                identity = identity,
            )
        val input =
            CanonicalWorkoutInput(
                workoutId = "w-missing",
                startMs = workoutStartMs,
                endMs = workoutEndMs,
                samples = emptyList(),
                quality = WorkoutHrQuality.UNAVAILABLE,
                context = context,
                prior = null,
            )

        val result = resolver.resolve(input)

        assertNull("Missing HR with no prior must yield null TRIMP", result.trimp)
        assertEquals(WorkoutHrQuality.UNAVAILABLE, result.quality)
        assertEquals(identity.sourceRevision, result.sourceRevision)
        assertEquals(identity.scoringSnapshotId, result.scoringSnapshotId)
        assertEquals(identity.algorithmRevision, result.algorithmRevision)
    }

    @Test
    fun `missing HR with matching prior reuses prior as VALIDATED_PRIOR`() {
        val prefs = UserPreferences()
        val context =
            WorkoutScoringContext(
                prefs = prefs,
                rhrBaseline = 60f,
                frozenHrMax = 190f,
                identity = identity,
            )
        val matchingPrior =
            CanonicalWorkoutResult(
                workoutId = "w-prior",
                endTimeMs = workoutEndMs,
                trimp = 48.5f,
                quality = WorkoutHrQuality.RAW,
                sourceRevision = identity.sourceRevision,
                scoringSnapshotId = identity.scoringSnapshotId,
                algorithmRevision = identity.algorithmRevision,
            )
        val input =
            CanonicalWorkoutInput(
                workoutId = "w-prior",
                startMs = workoutStartMs,
                endMs = workoutEndMs,
                samples = emptyList(),
                quality = WorkoutHrQuality.UNAVAILABLE,
                context = context,
                prior = matchingPrior,
            )

        val result = resolver.resolve(input)

        assertEquals(48.5f, result.trimp!!, 0.001f)
        assertEquals(WorkoutHrQuality.VALIDATED_PRIOR, result.quality)
        assertEquals(identity.sourceRevision, result.sourceRevision)
        assertEquals(identity.scoringSnapshotId, result.scoringSnapshotId)
        assertEquals(identity.algorithmRevision, result.algorithmRevision)
    }

    @Test
    fun `missing HR with mismatched sourceRevision rejects prior and returns UNAVAILABLE`() {
        val prefs = UserPreferences()
        val context =
            WorkoutScoringContext(
                prefs = prefs,
                rhrBaseline = 60f,
                frozenHrMax = 190f,
                identity = identity.copy(sourceRevision = 2L),
            )
        val stalePrior =
            CanonicalWorkoutResult(
                workoutId = "w-stale-source",
                endTimeMs = workoutEndMs,
                trimp = 48.5f,
                quality = WorkoutHrQuality.RAW,
                sourceRevision = 1L, // Mismatch!
                scoringSnapshotId = identity.scoringSnapshotId,
                algorithmRevision = identity.algorithmRevision,
            )
        val input =
            CanonicalWorkoutInput(
                workoutId = "w-stale-source",
                startMs = workoutStartMs,
                endMs = workoutEndMs,
                samples = emptyList(),
                quality = WorkoutHrQuality.UNAVAILABLE,
                context = context,
                prior = stalePrior,
            )

        val result = resolver.resolve(input)

        assertNull(result.trimp)
        assertEquals(WorkoutHrQuality.UNAVAILABLE, result.quality)
    }

    @Test
    fun `missing HR with mismatched scoringSnapshotId rejects prior and returns UNAVAILABLE`() {
        val prefs = UserPreferences()
        val context =
            WorkoutScoringContext(
                prefs = prefs,
                rhrBaseline = 60f,
                frozenHrMax = 190f,
                identity = identity.copy(scoringSnapshotId = "snap-new"),
            )
        val stalePrior =
            CanonicalWorkoutResult(
                workoutId = "w-stale-snap",
                endTimeMs = workoutEndMs,
                trimp = 48.5f,
                quality = WorkoutHrQuality.RAW,
                sourceRevision = identity.sourceRevision,
                scoringSnapshotId = "snap-old", // Mismatch!
                algorithmRevision = identity.algorithmRevision,
            )
        val input =
            CanonicalWorkoutInput(
                workoutId = "w-stale-snap",
                startMs = workoutStartMs,
                endMs = workoutEndMs,
                samples = emptyList(),
                quality = WorkoutHrQuality.UNAVAILABLE,
                context = context,
                prior = stalePrior,
            )

        val result = resolver.resolve(input)

        assertNull(result.trimp)
        assertEquals(WorkoutHrQuality.UNAVAILABLE, result.quality)
    }

    @Test
    fun `missing HR with mismatched algorithmRevision rejects prior and returns UNAVAILABLE`() {
        val prefs = UserPreferences()
        val context =
            WorkoutScoringContext(
                prefs = prefs,
                rhrBaseline = 60f,
                frozenHrMax = 190f,
                identity = identity.copy(algorithmRevision = 2),
            )
        val stalePrior =
            CanonicalWorkoutResult(
                workoutId = "w-stale-algo",
                endTimeMs = workoutEndMs,
                trimp = 48.5f,
                quality = WorkoutHrQuality.RAW,
                sourceRevision = identity.sourceRevision,
                scoringSnapshotId = identity.scoringSnapshotId,
                algorithmRevision = 1, // Mismatch!
            )
        val input =
            CanonicalWorkoutInput(
                workoutId = "w-stale-algo",
                startMs = workoutStartMs,
                endMs = workoutEndMs,
                samples = emptyList(),
                quality = WorkoutHrQuality.UNAVAILABLE,
                context = context,
                prior = stalePrior,
            )

        val result = resolver.resolve(input)

        assertNull(result.trimp)
        assertEquals(WorkoutHrQuality.UNAVAILABLE, result.quality)
    }
}
