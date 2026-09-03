package app.readylytics.health.feature.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdvancedTrainingReadinessSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun setContent(
        uiState: UIState = UIState(),
        controlsEnabled: Boolean = true,
        isResyncing: Boolean = false,
    ) {
        composeRule.setContent {
            FitDashboardTheme {
                TrainingReadinessSubsection(
                    uiState = uiState,
                    controlsEnabled = controlsEnabled,
                    isResyncing = isResyncing,
                    onUIEvent = {},
                )
            }
        }
    }

    @Test
    fun `renders title, labels and descriptions from string resources`() {
        setContent()

        composeRule.onNodeWithText(context.getString(R.string.advanced_training_readiness_title)).assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_scale_label))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_weight_label))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_reset_button))
            .assertExists()
        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_apply_button))
            .assertExists()
    }

    @Test
    fun `reset affordance is a text button distinct from the apply button`() {
        setContent()

        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_reset_button))
            .assertExists()
            .assertIsEnabled()
        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_apply_button))
            .assertExists()
    }

    @Test
    fun `apply button disabled when no recalculation is pending`() {
        setContent(uiState = UIState(hasPendingTrainingReadinessRecalc = false), controlsEnabled = true)
        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_apply_button))
            .assertIsNotEnabled()
    }

    @Test
    fun `apply button enabled when a recalculation is pending and controls are not gated`() {
        setContent(uiState = UIState(hasPendingTrainingReadinessRecalc = true), controlsEnabled = true)
        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_apply_button))
            .assertIsEnabled()
    }

    @Test
    fun `controls are disabled while resyncing`() {
        setContent(
            uiState = UIState(hasPendingTrainingReadinessRecalc = true),
            controlsEnabled = false,
            isResyncing = true,
        )

        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_reset_button))
            .assertIsNotEnabled()
        composeRule
            .onNodeWithText(context.getString(R.string.advanced_training_readiness_apply_button))
            .assertIsNotEnabled()
    }

    @Test
    fun `legacy disabled preference has no settings switch or state`() {
        setContent()

        composeRule.onNodeWithText("Enabled").assertDoesNotExist()
    }

    @Test
    fun `scale slider stops land on five-unit increments`() {
        val interval =
            (
                SettingsDefaults.MAX_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE -
                    SettingsDefaults.MIN_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE
            ) / (TRAINING_READINESS_SCALE_SLIDER_STEPS + 1)
        assertEquals(SettingsDefaults.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE_STEP, interval, 1e-4f)
        assertOnStop(
            value = SettingsDefaults.TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
            min = SettingsDefaults.MIN_TRAINING_READINESS_RESIDUAL_FATIGUE_SCALE,
            interval = interval,
        )
    }

    @Test
    fun `weight slider stops land on one-percent increments`() {
        val interval =
            (
                SettingsDefaults.MAX_TRAINING_READINESS_LOAD_BALANCE_WEIGHT -
                    SettingsDefaults.MIN_TRAINING_READINESS_LOAD_BALANCE_WEIGHT
            ) / (TRAINING_READINESS_WEIGHT_SLIDER_STEPS + 1)
        assertEquals(SettingsDefaults.TRAINING_READINESS_LOAD_BALANCE_WEIGHT_STEP, interval, 1e-4f)
        assertOnStop(
            value = SettingsDefaults.TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
            min = SettingsDefaults.MIN_TRAINING_READINESS_LOAD_BALANCE_WEIGHT,
            interval = interval,
        )
    }

    private fun assertOnStop(
        value: Float,
        min: Float,
        interval: Float,
    ) {
        val stopIndex = (value - min) / interval
        assertEquals(Math.round(stopIndex).toFloat(), stopIndex, 1e-3f)
    }
}
