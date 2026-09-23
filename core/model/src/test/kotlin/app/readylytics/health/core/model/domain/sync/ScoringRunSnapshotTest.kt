package app.readylytics.health.core.model.domain.sync

import app.readylytics.health.core.model.data.preferences.Gender
import app.readylytics.health.core.model.data.preferences.PhysiologyProfile
import app.readylytics.health.core.model.data.preferences.UserPreferences
import app.readylytics.health.core.model.domain.preferences.Vo2MaxEstimationMethod
import app.readylytics.health.core.model.domain.preferences.Vo2MaxSourceMode
import app.readylytics.health.core.model.domain.scoring.LoadSourceMode
import app.readylytics.health.core.model.domain.scoring.SleepScoreWeightProfile
import app.readylytics.health.core.model.domain.scoring.TrimpModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScoringRunSnapshotTest {
    @Test
    fun `capture and toPreferences round trips every listed property`() {
        val originalPrefs = createFullPreferences()
        val resolvedHrMax = 185f
        val snapshot = ScoringRunSnapshot.capture(originalPrefs, resolvedHrMax)
        val restoredPrefs = snapshot.toPreferences()

        assertPart1To4Matches(originalPrefs, restoredPrefs)
        assertPart5To8Matches(originalPrefs, restoredPrefs)
        assertEquals(resolvedHrMax, snapshot.resolvedHrMax)
    }

    private fun createFullPreferences(): UserPreferences =
        applyFullPreferences(createBasePreferences())

    private fun createBasePreferences() =
        UserPreferences(
            goalSleepHours = 7.5f,
            hrvBaselineOverride = 45f,
            rhrBaselineOverride = 52f,
            maxHeartRate = 185,
            autoCalculateMaxHr = false,
            manualZoneEditing = true,
            zone1MinPercent = 50f,
            zone1MaxPercent = 60f,
            zone2MaxPercent = 70f,
            zone3MaxPercent = 80f,
            zone4MaxPercent = 90f,
            zone1MinBpm = 95,
            zone1MaxBpm = 115,
            zone2MaxBpm = 135,
            zone3MaxBpm = 155,
            zone4MaxBpm = 175,
            age = 34,
            birthDate = "1992-05-12",
            gender = Gender.MALE,
            heightCm = 178f,
            hrvOptimalThreshold = 55f,
            hrvWarningThreshold = 35f,
            rhrOptimalThreshold = 50f,
            rhrWarningThreshold = 65f,
            restingHrPercentile = 25,
            consistencyThresholdMinutes = 45,
            consistencyEvaluationDays = 14,
            consistencyBaselineDays = 28,
        )

    private fun applyFullPreferences(base: UserPreferences) =
        base.copy(
            hrrToleranceSeconds = 120,
            rasScalingFactor = 1.15f,
            stepGoal = 12000,
            physiologyProfile = PhysiologyProfile.ATHLETE,
            installDate = 1700000000000L,
            circadianThresholdOverride = "encrypted_secret_threshold",
            trimpModel = TrimpModel.CHENG,
            banisterMultiplier = 1.9f,
            chengBeta = 1.8f,
            itrimB = 1.7f,
            primaryDeviceName = "Garmin Forerunner 955",
            deviceByDataType =
                mapOf(
                    "HEART_RATE" to "Garmin",
                    "SLEEP" to "Oura Ring",
                    "STEPS" to "Pixel Watch",
                ),
            scoringZoneId = "Europe/Berlin",
            strainLoadSourceMode = LoadSourceMode.WORKOUT_ONLY,
            rasSourceMode = LoadSourceMode.EVERYDAY_HEART_RATE,
            coreMergeGapMinutes = 40,
            supplementalCutoffMinutesOfDay = 360,
            minimumCountedSleepSegmentMinutes = 30,
            supplementalArchitectureCoveragePercent = 80,
            bodyTempElevatedThresholdCelsius = 37.8f,
            sleepScoreWeightProfile = SleepScoreWeightProfile.RECOVERY_FOCUSED,
            hypersomniaOnsetPercent = 125,
            residualFatigueHalfLifeHours = 40f,
            residualFatigueGain = 1.2f,
            retentionDaysEnabled = true,
            retentionDays = 540,
            trainingReadinessResidualFatigueScale = 140f,
            trainingReadinessLoadBalanceWeight = 0.85f,
            lastAppliedTrainingReadinessResidualFatigueScale = 135f,
            lastAppliedTrainingReadinessLoadBalanceWeight = 0.82f,
            vo2MaxSourceMode = Vo2MaxSourceMode.WEARABLE_ONLY,
            vo2MaxEstimationMethod = Vo2MaxEstimationMethod.MATERKO_ADAPTED,
        )

    private fun assertPart1To4Matches(original: UserPreferences, restored: UserPreferences) {
        assertEquals(original.goalSleepHours, restored.goalSleepHours)
        assertEquals(original.hrvBaselineOverride, restored.hrvBaselineOverride)
        assertEquals(original.rhrBaselineOverride, restored.rhrBaselineOverride)
        assertEquals(original.maxHeartRate, restored.maxHeartRate)
        assertEquals(original.autoCalculateMaxHr, restored.autoCalculateMaxHr)
        assertEquals(original.manualZoneEditing, restored.manualZoneEditing)
        assertEquals(original.zone1MinPercent, restored.zone1MinPercent)
        assertEquals(original.zone1MaxPercent, restored.zone1MaxPercent)
        assertEquals(original.zone2MaxPercent, restored.zone2MaxPercent)
        assertEquals(original.zone3MaxPercent, restored.zone3MaxPercent)
        assertEquals(original.zone4MaxPercent, restored.zone4MaxPercent)
        assertEquals(original.zone1MinBpm, restored.zone1MinBpm)
        assertEquals(original.zone1MaxBpm, restored.zone1MaxBpm)
        assertEquals(original.zone2MaxBpm, restored.zone2MaxBpm)
        assertEquals(original.zone3MaxBpm, restored.zone3MaxBpm)
        assertEquals(original.zone4MaxBpm, restored.zone4MaxBpm)
        assertEquals(original.age, restored.age)
        assertEquals(original.birthDate, restored.birthDate)
        assertEquals(original.gender, restored.gender)
        assertEquals(original.heightCm, restored.heightCm)
        assertEquals(original.hrvOptimalThreshold, restored.hrvOptimalThreshold)
        assertEquals(original.hrvWarningThreshold, restored.hrvWarningThreshold)
        assertEquals(original.rhrOptimalThreshold, restored.rhrOptimalThreshold)
        assertEquals(original.rhrWarningThreshold, restored.rhrWarningThreshold)
        assertEquals(original.restingHrPercentile, restored.restingHrPercentile)
        assertEquals(original.consistencyThresholdMinutes, restored.consistencyThresholdMinutes)
        assertEquals(original.consistencyEvaluationDays, restored.consistencyEvaluationDays)
        assertEquals(original.consistencyBaselineDays, restored.consistencyBaselineDays)
    }

    private fun assertPart5To8Matches(original: UserPreferences, restored: UserPreferences) {
        assertEquals(original.hrrToleranceSeconds, restored.hrrToleranceSeconds)
        assertEquals(original.rasScalingFactor, restored.rasScalingFactor)
        assertEquals(original.stepGoal, restored.stepGoal)
        assertEquals(original.physiologyProfile, restored.physiologyProfile)
        assertEquals(original.installDate, restored.installDate)
        assertEquals(original.circadianThresholdOverride, restored.circadianThresholdOverride)
        assertEquals(original.trimpModel, restored.trimpModel)
        assertEquals(original.banisterMultiplier, restored.banisterMultiplier)
        assertEquals(original.chengBeta, restored.chengBeta)
        assertEquals(original.itrimB, restored.itrimB)
        assertEquals(original.scoringZoneId, restored.scoringZoneId)
        assertEquals(original.strainLoadSourceMode, restored.strainLoadSourceMode)
        assertEquals(original.rasSourceMode, restored.rasSourceMode)
        assertEquals(original.coreMergeGapMinutes, restored.coreMergeGapMinutes)
        assertEquals(original.supplementalCutoffMinutesOfDay, restored.supplementalCutoffMinutesOfDay)
        assertEquals(original.minimumCountedSleepSegmentMinutes, restored.minimumCountedSleepSegmentMinutes)
        assertEquals(
            original.supplementalArchitectureCoveragePercent,
            restored.supplementalArchitectureCoveragePercent,
        )
        assertEquals(original.bodyTempElevatedThresholdCelsius, restored.bodyTempElevatedThresholdCelsius)
        assertEquals(original.sleepScoreWeightProfile, restored.sleepScoreWeightProfile)
        assertEquals(original.hypersomniaOnsetPercent, restored.hypersomniaOnsetPercent)
        assertEquals(original.residualFatigueHalfLifeHours, restored.residualFatigueHalfLifeHours)
        assertEquals(original.residualFatigueGain, restored.residualFatigueGain)
        assertEquals(
            original.trainingReadinessResidualFatigueScale,
            restored.trainingReadinessResidualFatigueScale,
        )
        assertEquals(original.trainingReadinessLoadBalanceWeight, restored.trainingReadinessLoadBalanceWeight)
        assertEquals(
            original.lastAppliedTrainingReadinessResidualFatigueScale,
            restored.lastAppliedTrainingReadinessResidualFatigueScale,
        )
        assertEquals(
            original.lastAppliedTrainingReadinessLoadBalanceWeight,
            restored.lastAppliedTrainingReadinessLoadBalanceWeight,
        )
        assertEquals(original.vo2MaxSourceMode, restored.vo2MaxSourceMode)
        assertEquals(original.vo2MaxEstimationMethod, restored.vo2MaxEstimationMethod)
        assertEquals(original.retentionDaysEnabled, restored.retentionDaysEnabled)
        assertEquals(original.retentionDays, restored.retentionDays)
        assertEquals(original.primaryDeviceName, restored.primaryDeviceName)
        assertEquals(original.deviceByDataType, restored.deviceByDataType)
    }

    @Test
    fun `sourceSelection sorts keys and stores primary device under dedicated fixed key`() {
        val prefs =
            UserPreferences(
                primaryDeviceName = "Watch 1",
                deviceByDataType =
                    mapOf(
                        "STEPS" to "Device C",
                        "HEART_RATE" to "Device A",
                        "SLEEP" to "Device B",
                    ),
            )
        val snapshot = ScoringRunSnapshot.capture(prefs, 180f)

        assertEquals("Watch 1", snapshot.sourceSelection[PRIMARY_DEVICE_KEY])
        val keys = snapshot.sourceSelection.keys.toList()
        assertEquals(listOf("HEART_RATE", "SLEEP", "STEPS", PRIMARY_DEVICE_KEY), keys)

        val restored = snapshot.toPreferences()
        assertEquals("Watch 1", restored.primaryDeviceName)
        assertEquals(
            mapOf("HEART_RATE" to "Device A", "SLEEP" to "Device B", "STEPS" to "Device C"),
            restored.deviceByDataType,
        )
    }

    @Test
    fun `one-field change alters serialized JSON and SHA-256 snapshot id`() {
        val prefs1 = UserPreferences(goalSleepHours = 8.0f)
        val prefs2 = UserPreferences(goalSleepHours = 8.5f)

        val run1 =
            HistoricalRunIdentity.create(
                runId = "run-1",
                mode = HistoricalRunIdentity.FULL_INGEST,
                startDate = LocalDate.of(2025, 1, 1),
                endDate = LocalDate.of(2025, 1, 31),
                zoneId = ZoneId.of("UTC"),
                prefs = prefs1,
                resolvedHrMax = 180f,
                startedAtEpochMs = 1000L,
            )
        val run2 =
            HistoricalRunIdentity.create(
                runId = "run-2",
                mode = HistoricalRunIdentity.FULL_INGEST,
                startDate = LocalDate.of(2025, 1, 1),
                endDate = LocalDate.of(2025, 1, 31),
                zoneId = ZoneId.of("UTC"),
                prefs = prefs2,
                resolvedHrMax = 180f,
                startedAtEpochMs = 1000L,
            )

        assertNotEquals(run1.scoringSnapshotJson, run2.scoringSnapshotJson)
        assertNotEquals(run1.scoringSnapshotId, run2.scoringSnapshotId)
    }

    @Test
    fun `corrupted snapshot or invalid enum fails safe in decoding`() {
        val badJson = """{"part1":{"goalSleepHours": "invalid"}}"""
        val identity =
            HistoricalRunIdentity(
                runId = "bad-run",
                mode = HistoricalRunIdentity.FULL_INGEST,
                startEpochDay = 0L,
                endEpochDayInclusive = 1L,
                zoneId = "UTC",
                startedAtEpochMs = 1000L,
                sourceSelectionId = "sources",
                algorithmRevision = 5,
                scoringSnapshotJson = badJson,
                scoringSnapshotId = "bad-hash",
            )

        assertNull(identity.decodeScoringSnapshot())
        assertNull(identity.effectivePreferences())
    }
}
