package app.readylytics.health.docs

import app.readylytics.health.data.preferences.PhysiologyProfile
import app.readylytics.health.data.preferences.SettingsDefaults
import app.readylytics.health.domain.circadian.CircadianThresholdDefaults
import app.readylytics.health.domain.scoring.LoadCoverageConfidence
import app.readylytics.health.domain.scoring.LoadSourceMode
import app.readylytics.health.domain.scoring.ScoringConfigFactory
import app.readylytics.health.domain.scoring.ScoringConstants
import app.readylytics.health.domain.scoring.components.Phase
import app.readylytics.health.domain.scoring.components.SleepArchitectureTargets
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drift-detection between the scoring engine's numeric source of truth and the user-facing
 * documentation (ABOUT.md, in-app About strings, DATA_FLOW.md). Per `.claude/CLAUDE.md`'s
 * Documentation Synchronization Rule, a PR changing any of these constants must update the
 * corresponding documentation in the same PR — this test fails if they drift apart.
 */
class DocumentationDriftTest {
    private val aboutMd = readRepoFile("ABOUT.md")
    private val stringsXml =
        listOf(
            "app/src/main/res/values/strings.xml",
            "core/ui/src/main/res/values/strings.xml",
            "feature/about/src/main/res/values/strings.xml",
            "feature/insights/src/main/res/values/strings.xml",
            "feature/sleep/src/main/res/values/strings.xml",
            "feature/workouts/src/main/res/values/strings.xml",
            "feature/vitals/src/main/res/values/strings.xml",
            "feature/dashboard/src/main/res/values/strings.xml",
            "feature/settings/src/main/res/values/strings.xml",
            "feature/onboarding/src/main/res/values/strings.xml",
        ).mapNotNull { path ->
            try {
                readRepoFile(path)
            } catch (e: Throwable) {
                null
            }
        }.joinToString("\n")
    private val dataFlowMd = readRepoFile("internal-docs/DATA_FLOW.md")
    private val buildGradleKts = readRepoFile("app/build.gradle.kts")
    private val governanceDocPaths =
        listOf(
            ".claude/CLAUDE.md",
            "GEMINI.md",
            "AGENTS.md",
            ".gemini/instructions.md",
        )
    private val configFactory = ScoringConfigFactory()

    @Test
    fun `sleep score weights match ABOUT md`() {
        assertEquals(0.50f, ScoringConstants.Sleep.WEIGHT_DURATION)
        assertEquals(0.25f, ScoringConstants.Sleep.WEIGHT_ARCHITECTURE)
        assertEquals(0.25f, ScoringConstants.Sleep.WEIGHT_RESTORATION)

        assertTrue(aboutMd.contains("Duration (50%)"))
        assertTrue(aboutMd.contains("Architecture (25%)"))
        assertTrue(aboutMd.contains("Restoration (25%)"))
    }

    @Test
    fun `readiness weights and emergency caps match ABOUT md and strings`() {
        assertEquals(0.4f, ScoringConstants.Readiness.WEIGHT_RESTORATION)
        assertEquals(0.3f, ScoringConstants.Readiness.WEIGHT_SLEEP)
        assertEquals(0.3f, ScoringConstants.Readiness.WEIGHT_LOAD)
        assertEquals(50f, ScoringConstants.Readiness.ILLNESS_MAX_SCORE)

        assertTrue(aboutMd.contains("0.4 × Restoration"))
        assertTrue(aboutMd.contains("0.3 × Sleep Score"))
        assertTrue(aboutMd.contains("0.3 × Load Score"))
        assertTrue(stringsXml.contains("0.4 × Restoration"))
        assertTrue(stringsXml.contains("0.3 × Sleep Score"))
        assertTrue(stringsXml.contains("0.3 × Load Score"))

        assertTrue(aboutMd.contains("caps it at 50"))
        assertTrue(stringsXml.contains("caps it at 50"))
        assertTrue(aboutMd.contains("Strong recovery signals do not cap Readiness"))
        assertTrue(stringsXml.contains("Strong recovery signals do not cap Readiness"))
    }

    @Test
    fun `circadian thresholds match per-profile defaults`() {
        assertEquals(20, CircadianThresholdDefaults.getProfileDefault(PhysiologyProfile.ATHLETE))
        assertEquals(30, CircadianThresholdDefaults.getProfileDefault(PhysiologyProfile.ACTIVE))
        assertEquals(45, CircadianThresholdDefaults.getProfileDefault(PhysiologyProfile.SEDENTARY))

        for (text in listOf(aboutMd, stringsXml)) {
            assertTrue(text.contains("±20"), "expected ±20 minutes (Athlete) in doc")
            assertTrue(text.contains("±30"), "expected ±30 minutes (Active) in doc")
            assertTrue(text.contains("±45"), "expected ±45 minutes (Sedentary) in doc")
        }
    }

    @Test
    fun `load curve sweet spot and decay constant match ABOUT md and strings`() {
        assertEquals(1.3f, ScoringConstants.Strain.SR_SWEET_SPOT_MAX)
        assertEquals(2.5f, ScoringConstants.Strain.QUADRATIC_PENALTY_K)

        assertTrue(aboutMd.contains("sr ≤ 1.3 → 100"))
        assertTrue(aboutMd.contains("100 × exp(−2.5 × (sr − 1.3)²)"))
        assertTrue(stringsXml.contains("sr ≤ 1.3 → 100"))
        assertTrue(stringsXml.contains("100 × exp(−2.5 × (sr − 1.3)²)"))
    }

    @Test
    fun `age-banded deep and REM sleep targets match ABOUT md table`() {
        assertAgeBandInAboutMd("18–29", SleepArchitectureTargets.AgeRange18To29())
        assertAgeBandInAboutMd("30–49", SleepArchitectureTargets.AgeRange30To49())
        assertAgeBandInAboutMd("50–59", SleepArchitectureTargets.AgeRange50To59())
        assertAgeBandInAboutMd("60+", SleepArchitectureTargets.AgeRange60Plus())
    }

    private fun assertAgeBandInAboutMd(
        ageRangeLabel: String,
        targets: SleepArchitectureTargets,
    ) {
        val deepPercent = "${(targets.deepPercentage * 100).toInt()}%"
        val remPercent = "${(targets.remPercentage * 100).toInt()}%"
        val row = aboutMd.lineSequence().firstOrNull { it.contains("| $ageRangeLabel") }
        assertTrue(row != null, "expected a table row for age range $ageRangeLabel in ABOUT.md")
        assertTrue(row.contains(deepPercent), "row for $ageRangeLabel should contain deep target $deepPercent")
        assertTrue(row.contains(remPercent), "row for $ageRangeLabel should contain REM target $remPercent")
    }

    @Test
    fun `rhr percentile default matches ABOUT md and strings`() {
        assertEquals(5, SettingsDefaults.RESTING_HR_PERCENTILE)
        assertTrue(aboutMd.contains("default 5%"))
        assertTrue(stringsXml.contains("default 5%"))
    }

    @Test
    fun `phase boundaries match ABOUT md and strings session-count model`() {
        assertEquals(6, Phase.CALIBRATION_MAX_SESSIONS)
        assertEquals(20, Phase.EARLY_BASELINE_MAX_SESSIONS)
        assertEquals(59, Phase.MATURING_MAX_SESSIONS)

        for (text in listOf(aboutMd, stringsXml)) {
            assertTrue(
                text.contains("0-6 valid nights") || text.contains("0–6 valid nights"),
                "expected Calibration 0-6 valid nights in doc",
            )
            assertTrue(text.contains("7-20 nights") || text.contains("7–20"), "expected Early Baseline 7-20 in doc")
            assertTrue(text.contains("21-59 nights") || text.contains("21–59"), "expected Maturing 21-59 in doc")
            assertTrue(text.contains("60+ nights") || text.contains("60+"), "expected Mature 60+ in doc")
        }
    }

    @Test
    fun `calibration docs say headline scores are hidden until seven valid nights`() {
        for (text in listOf(aboutMd, stringsXml)) {
            val normalized = normalizeWhitespace(text)
            assertTrue(normalized.contains("0-6 valid nights") || normalized.contains("0–6 valid nights"))
            assertTrue(normalized.contains("Sleep Score, Load Score, and Readiness stay hidden"))
            assertTrue(normalized.contains("at least 7 valid nights"))
        }
    }

    @Test
    fun `HRV sensitivity tiers match per-profile saturation Z and ABOUT md and strings`() {
        assertEquals(1.2f, configFactory.hrvSaturationZForProfile(PhysiologyProfile.ATHLETE))
        assertEquals(1.5f, configFactory.hrvSaturationZForProfile(PhysiologyProfile.ACTIVE))
        assertEquals(2.0f, configFactory.hrvSaturationZForProfile(PhysiologyProfile.SEDENTARY))

        for (text in listOf(aboutMd, stringsXml)) {
            assertTrue(text.contains("±1.2"), "expected ±1.2 (Athlete HRV sensitivity) in doc")
            assertTrue(text.contains("±1.5"), "expected ±1.5 (Active HRV sensitivity) in doc")
            assertTrue(text.contains("±2.0"), "expected ±2.0 (Sedentary HRV sensitivity) in doc")
        }
    }

    @Test
    fun `load source defaults match ABOUT md and strings`() {
        assertEquals(LoadSourceMode.WORKOUT_ONLY, SettingsDefaults.STRAIN_LOAD_SOURCE_MODE)
        assertEquals(LoadSourceMode.EVERYDAY_HEART_RATE, SettingsDefaults.RAS_SOURCE_MODE)

        for (text in listOf(aboutMd, stringsXml)) {
            val normalized = normalizeWhitespace(text)
            assertTrue(text.contains("Strain / Training Load source"), "expected strain source label in doc")
            assertTrue(normalized.contains("default: **Workout only**"), "expected strain default in doc")
            assertTrue(normalized.contains("default: **Everyday heart-rate load**"), "expected RAS default in doc")
            assertTrue(
                normalized.contains("Readiness always uses this source"),
                "expected Readiness-source link in doc",
            )
            assertTrue(text.contains("exactly once"), "expected workout-TRIMP-once rule in doc")
            assertTrue(
                text.contains("Existing users upgrading") || text.contains("existing-user"),
                "expected existing-user RAS bootstrap note in doc",
            )
        }
    }

    @Test
    fun `activity score docs keep RAS independent from readiness load`() {
        assertTrue(aboutMd.contains("PAI-style motivational activity metric"))
        assertTrue(aboutMd.contains("RAS never feeds Readiness"))

        for (text in listOf(aboutMd, stringsXml)) {
            val normalized = normalizeWhitespace(text)
            assertTrue(normalized.contains("RAS source never affects Readiness"))
            assertTrue(normalized.contains("daily and 7-day total RAS only"))
        }
    }

    @Test
    fun `everyday load coverage confidence thresholds match ABOUT md and strings`() {
        assertEquals(
            listOf(
                LoadCoverageConfidence.NONE,
                LoadCoverageConfidence.LOW,
                LoadCoverageConfidence.MEDIUM,
                LoadCoverageConfidence.HIGH,
            ),
            LoadCoverageConfidence.values().toList(),
        )

        for (text in listOf(aboutMd, stringsXml)) {
            assertTrue(text.contains("coverageMinutes"), "expected coverageMinutes terminology in doc")
            assertTrue(text.contains("validBucketCount"), "expected validBucketCount terminology in doc")
            assertTrue(text.contains("0 → **None**"), "expected None confidence boundary in doc")
            assertTrue(text.contains("1–179 → **Low**"), "expected Low confidence boundary in doc")
            assertTrue(text.contains("180–479 → **Medium**"), "expected Medium confidence boundary in doc")
            assertTrue(text.contains("480+ → **High**"), "expected High confidence boundary in doc")
            assertTrue(text.contains("at least 180 coverage minutes"), "expected 180-minute validity threshold in doc")
            assertTrue(text.contains("Zone 0"), "expected Zone 0 exclusion-from-TRIMP rule in doc")
        }
    }

    @Test
    fun `everyday HR load path documented in DATA_FLOW md`() {
        assertTrue(dataFlowMd.contains("EverydayHeartRateLoadCalculator"))
        assertTrue(dataFlowMd.contains("LoadSourceSelector"))
        assertTrue(dataFlowMd.contains("everydayCoverageMinutes"))
    }

    @Test
    fun `package root in DATA_FLOW md matches actual source root`() {
        assertTrue(dataFlowMd.contains("app/src/main/kotlin/app/readylytics/health/"))
        assertFalse(dataFlowMd.contains("com/gregor/lauritz/healthdashboard"))
    }

    @Test
    fun `no surface mentions retired Shift Worker or General population profiles`() {
        for (text in listOf(aboutMd, stringsXml, dataFlowMd)) {
            assertFalse(text.contains("Shift Worker", ignoreCase = true))
            assertFalse(text.contains("General population", ignoreCase = true))
        }
    }

    @Test
    fun `governance docs declare the same minSdk and targetSdk as app build gradle kts`() {
        val minSdk =
            Regex("""minSdk\s*=\s*(\d+)""").find(buildGradleKts)?.groupValues?.get(1)
                ?: error("could not find minSdk in app/build.gradle.kts")
        val targetSdk =
            Regex("""targetSdk\s*=\s*(\d+)""").find(buildGradleKts)?.groupValues?.get(1)
                ?: error("could not find targetSdk in app/build.gradle.kts")

        for (path in governanceDocPaths) {
            val text = readRepoFile(path)
            assertTrue(
                text.contains("minSdk=$minSdk, targetSdk=$targetSdk"),
                "expected $path to declare minSdk=$minSdk, targetSdk=$targetSdk",
            )
        }
    }

    @Test
    fun `every profile name in ABOUT md exists in strings xml`() {
        for (profile in listOf("Athlete", "Active", "Sedentary")) {
            assertTrue(aboutMd.contains("**$profile**"), "ABOUT.md should describe profile $profile")
            assertTrue(stringsXml.contains(profile), "strings.xml should reference profile $profile")
        }
    }

    /** Collapses whitespace runs (including line wraps) to a single space for wrap-tolerant matching. */
    private fun normalizeWhitespace(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun readRepoFile(pathFromRepoRoot: String): String {
        val candidates =
            listOf(
                File(pathFromRepoRoot),
                File("../$pathFromRepoRoot"),
                File("../../$pathFromRepoRoot"),
            )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue(file != null, "could not locate $pathFromRepoRoot from working dir ${File(".").absolutePath}")
        return file.readText()
    }
}
