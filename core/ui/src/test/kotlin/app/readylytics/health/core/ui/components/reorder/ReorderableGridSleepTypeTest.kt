package app.readylytics.health.core.ui.components.reorder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performTouchInput
import app.readylytics.health.core.model.domain.sleep.SleepTopCardConfiguration
import app.readylytics.health.core.model.domain.sleep.SleepTopCardId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the generic ReorderableGrid works for a NON-dashboard domain type (sleep), i.e.
 * that the type parameterization is real and the drag mechanics are type-agnostic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReorderableGridSleepTypeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun renderMap(): Map<SleepTopCardId, @Composable (SleepTopCardConfiguration) -> Unit> =
        SleepTopCardId.entries.associateWith { id ->
            val label = id.name
            @Composable { _: SleepTopCardConfiguration -> Text(label) }
        }

    private fun configs(): List<SleepTopCardConfiguration> =
        listOf(
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_SCORE, isVisible = true, position = 0),
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_DURATION_GAUGE, isVisible = true, position = 1),
            SleepTopCardConfiguration(SleepTopCardId.SLEEP_BREAKDOWN_BAR, isVisible = true, position = 2),
        )

    private val fullWidthIds = setOf(SleepTopCardId.SLEEP_BREAKDOWN_BAR)

    @Composable
    private fun GridHost(content: @Composable () -> Unit) {
        // ReorderableGrid is always hosted inside a vertical scroll container in production.
        // Without it, a full-width slot's fillMaxHeight stretches to the viewport and the
        // bottom delete zone lands in a degenerate position, so reproduce the real layout here.
        Column(
            modifier =
                androidx.compose.ui.Modifier
                    .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }

    @Test
    fun `drag handle starts controller drag for sleep card type`() {
        val controller = DragController(listOf(SleepTopCardId.SLEEP_SCORE, SleepTopCardId.SLEEP_DURATION_GAUGE))
        composeTestRule.setContent {
            GridHost {
                ReorderableGrid(
                    items = configs(),
                    dataMap = renderMap(),
                    isEditing = true,
                    onItemReorder = {},
                    onItemDropToRemove = {},
                    fullWidthIds = fullWidthIds,
                    controller = controller,
                )
            }
        }

        // First "Drag to reorder" handle in display order belongs to SLEEP_SCORE (position 0).
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, 100f))
        }
        composeTestRule.waitForIdle()

        assertEquals(SleepTopCardId.SLEEP_SCORE, controller.draggedCardId)
    }

    @Test
    fun `dragging across slots emits reorder callback with sleep configs`() {
        var reordered: List<SleepTopCardConfiguration>? = null
        val controller = DragController(listOf(SleepTopCardId.SLEEP_SCORE, SleepTopCardId.SLEEP_DURATION_GAUGE))

        composeTestRule.setContent {
            GridHost {
                ReorderableGrid(
                    items = configs(),
                    dataMap = renderMap(),
                    isEditing = true,
                    onItemReorder = { reordered = it },
                    onItemDropToRemove = {},
                    fullWidthIds = fullWidthIds,
                    controller = controller,
                )
            }
        }
        composeTestRule.waitForIdle()

        // SLEEP_SCORE + SLEEP_DURATION_GAUGE are paired side-by-side in one row. Drag SCORE's
        // center onto GAUGE's slot center, then lift. onItemReorder must fire with sleep configs.
        val sleepCenter = controller.slotBounds.getValue(SleepTopCardId.SLEEP_SCORE).center
        val gaugeCenter = controller.slotBounds.getValue(SleepTopCardId.SLEEP_DURATION_GAUGE).center
        val delta = Offset(gaugeCenter.x - sleepCenter.x, gaugeCenter.y - sleepCenter.y)

        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(delta)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(SleepTopCardId.SLEEP_DURATION_GAUGE, reordered!!.first().cardId)
    }
}
