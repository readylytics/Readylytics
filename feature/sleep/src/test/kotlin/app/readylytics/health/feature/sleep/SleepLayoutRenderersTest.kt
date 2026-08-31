package app.readylytics.health.feature.sleep

import app.readylytics.health.core.model.domain.sleep.SleepMetricCardId
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import app.readylytics.health.core.scoring.domain.scoring.CircadianConsistencyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.time.LocalDate

class SleepLayoutRenderersTest {
    @Test
    fun `sleep metric presentation does not hardcode localized values`() {
        val sourceFile =
            sequenceOf(
                File("feature/sleep/src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt"),
                File("src/main/kotlin/app/readylytics/health/feature/sleep/SleepLayoutRenderers.kt"),
            ).first { it.isFile }
        val source = sourceFile.readText()

        assert(!source.contains("is CircadianConsistencyResult.MissingData -> \"—\""))
        assert(!source.contains("\"\${circadianResult.score.roundToPercentInt()}%\""))
        assert(!source.contains("?: \"0\""))
    }

    @Test
    fun `top card data map covers every top card id`() {
        val map = buildSleepTopCardDataMap(SleepUiState(selectedDate = LocalDate.of(2026, 6, 11)), null)
        assertEquals(SleepTopCardId.entries.toSet(), map.keys)
        SleepTopCardId.entries.forEach { assertNotNull(map[it]) }
    }

    @Test
    fun `metric card data map covers every metric card id`() {
        val map =
            buildSleepMetricCardDataMap(
                SleepUiState(selectedDate = LocalDate.of(2026, 6, 11)),
                CircadianConsistencyResult.Calibrating,
                null,
            )
        assertEquals(SleepMetricCardId.entries.toSet(), map.keys)
        SleepMetricCardId.entries.forEach { assertNotNull(map[it]) }
    }

    @Test
    fun `full width sleep top cards set excludes the gauges`() {
        assertEquals(
            setOf(
                SleepTopCardId.SLEEP_BREAKDOWN_BAR,
                SleepTopCardId.SLEEP_STAGES_TIMELINE,
                SleepTopCardId.SLEEP_HR_CHART,
            ),
            SLEEP_TOP_CARD_FULL_WIDTH_IDS,
        )
        assertEquals(
            setOf(SleepTopCardId.SLEEP_SCORE, SleepTopCardId.SLEEP_DURATION_GAUGE),
            SleepTopCardId.entries.toSet() - SLEEP_TOP_CARD_FULL_WIDTH_IDS,
        )
    }
}
