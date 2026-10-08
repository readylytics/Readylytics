package app.readylytics.health.feature.dashboard.recommendation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.FitDashboardTheme
import app.readylytics.health.core.model.data.preferences.AppTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkoutRecommendationCardLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactDarkCardKeepsAllTextReadable() {
        renderAndCheck(fontScale = 1f, theme = AppTheme.DARK, name = "compact-dark")
    }

    @Test
    fun compactLightCardKeepsAllTextReadable() {
        renderAndCheck(fontScale = 1f, theme = AppTheme.LIGHT, name = "compact-light")
    }

    @Test
    fun longTextWrapsAtLargeFontScale() {
        renderAndCheck(fontScale = 1.5f, theme = AppTheme.DARK, name = "large-dark", longText = true)
    }

    @Test
    fun longTextWrapsAtDoubleFontScaleInRtl() {
        renderAndCheck(fontScale = 2f, theme = AppTheme.LIGHT, name = "double-rtl-light", longText = true, rtl = true)
    }

    @Test
    fun dynamicThemeKeepsCardTextReadable() {
        renderAndCheck(fontScale = 1f, theme = AppTheme.DARK, name = "dynamic-dark", dynamicColor = true)
    }

    private fun renderAndCheck(
        fontScale: Float,
        theme: AppTheme,
        name: String,
        longText: Boolean = false,
        rtl: Boolean = false,
        dynamicColor: Boolean = false,
    ) {
        val text = fixture(longText)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                FitDashboardTheme(appTheme = theme, dynamicColor = dynamicColor) {
                    Box(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        Box(Modifier.testTag("recommendation-card")) {
                            WorkoutRecommendationCard(text) {}
                        }
                    }
                }
            }
        }

        captureCard(name)
        val example = text.examples.single()
        listOf(text.title, text.category, text.explanation, example.typeLabel, example.recordedSessionDescription)
            .forEach(::assertTextFits)
    }

    private fun captureCard(name: String) {
        val directory = File("build/reports/workout-recommendation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            composeRule
                .onNodeWithTag("recommendation-card")
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertTextFits(value: String) {
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(value, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        // GetTextLayoutResult can retain the parent's paragraph width even when a short
        // Text node measures to its contents. Check actual line bounds, not didOverflowWidth.
        assertFalse("Text must not clip vertically: $value", layout.didOverflowHeight)
        repeat(layout.lineCount) { line ->
            assertFalse("Text must not ellipsize: $value", layout.isLineEllipsized(line))
            assertTrue(
                "Line must fit: $value",
                layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f,
            )
        }
        assertTrue("All characters must fit: $value", layout.getLineEnd(layout.lineCount - 1) == value.length)
    }

    private fun fixture(longText: Boolean): WorkoutRecommendationPresentation =
        WorkoutRecommendationPresentation(
            title = "Workout recommendation",
            category = if (longText) "Building your personal circadian recovery baseline" else "Push harder",
            explanation =
                if (longText) {
                    "Your heart rate variability, sleep and training fatigue are being compared with " +
                        "your personal history. This longer explanation must stay fully readable."
                } else {
                    "Your HRV, sleep, and fatigue are all within their usual range."
                },
            info = "Past workouts matching a similar effort level.",
            examples =
                listOf(
                    WorkoutRecommendationExamplePresentation(
                        workoutId = "run-42",
                        typeLabel = if (longText) "An unusually long localized outdoor workout name" else "Running",
                        recordedSessionDescription = "66 min • 142 bpm avg",
                        openWorkoutLabel = "Open recorded running workout",
                        activityIcon = Icons.AutoMirrored.Filled.DirectionsRun,
                    ),
                ),
        )
}
