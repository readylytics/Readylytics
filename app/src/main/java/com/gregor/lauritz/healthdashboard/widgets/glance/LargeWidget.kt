package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceMetricCard
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceScoreDial
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceStepsBar

private val Context.glanceLargeWidgetDataStore by preferencesDataStore(
    name = "glance_large_widget_state",
)

/**
 * Large widget (2x2) - mini-dashboard with 2x2 card grid.
 */
class LargeWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            LargeWidgetContent(
                context = context,
                glanceId = id,
                widgetId = id.hashCode(),
            )
        }
    }
}

@Composable
@GlanceComposable
private fun LargeWidgetContent(
    context: Context,
    glanceId: GlanceId,
    widgetId: Int,
) {
    val state = currentState<Preferences>()
    val error = state[stringPreferencesKey("widget_large_${widgetId}_error")]
    val lastUpdateMs = state[longPreferencesKey("widget_large_${widgetId}_last_update")] ?: 0L

    // Handle error state
    if (error != null) {
        ErrorWidget(error = error)
        return
    }

    // Handle loading state
    if (lastUpdateMs == 0L) {
        LoadingWidget()
        return
    }

    // 2x2 grid layout
    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .padding(4.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        ) {
            WidgetCard(1, widgetId, state, GlanceModifier.defaultWeight())
            Spacer(modifier = GlanceModifier.width(4.dp))
            WidgetCard(2, widgetId, state, GlanceModifier.defaultWeight())
        }

        Spacer(modifier = GlanceModifier.height(4.dp))

        Row(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        ) {
            WidgetCard(3, widgetId, state, GlanceModifier.defaultWeight())
            Spacer(modifier = GlanceModifier.width(4.dp))
            WidgetCard(4, widgetId, state, GlanceModifier.defaultWeight())
        }
    }
}

@Composable
@GlanceComposable
private fun WidgetCard(
    cardNum: Int,
    widgetId: Int,
    state: Preferences,
    modifier: GlanceModifier,
) {
    val cardType = state[stringPreferencesKey("widget_large_${widgetId}_card${cardNum}_type")] ?: "METRIC"
    val metricName = state[stringPreferencesKey("widget_large_${widgetId}_card${cardNum}_metric")] ?: "HRV"
    val value = state[doublePreferencesKey("widget_large_${widgetId}_card${cardNum}_value")] ?: 0.0
    val statusName = state[stringPreferencesKey("widget_large_${widgetId}_card${cardNum}_status")] ?: "CALIBRATING"
    val currentSteps = state[longPreferencesKey("widget_large_${widgetId}_card${cardNum}_current_steps")] ?: 0L
    val goalSteps = state[longPreferencesKey("widget_large_${widgetId}_card${cardNum}_goal_steps")] ?: 10000L

    val metricType =
        try {
            MetricType.valueOf(metricName)
        } catch (e: Exception) {
            MetricType.HRV
        }
    val status =
        try {
            MetricStatus.valueOf(statusName)
        } catch (e: Exception) {
            MetricStatus.CALIBRATING
        }

    // Create click action
    val clickAction =
        actionStartActivity<MainActivity>()

    when (cardType) {
        "METRIC" -> {
            GlanceMetricCard(
                metricType = metricType,
                value = value,
                status = status,
                label = metricType.displayName,
                onClickAction = clickAction,
                modifier = modifier,
            )
        }
        "SCORE" -> {
            GlanceScoreDial(
                score = value,
                label = metricType.displayName,
                status = status,
                modifier = modifier,
            )
        }
        "STEPS" -> {
            Box(modifier = modifier) {
                GlanceStepsBar(
                    currentSteps = currentSteps,
                    goalSteps = goalSteps,
                )
            }
        }
        else -> {
            // Placeholder for unknown card type
            Box(
                modifier =
                    modifier
                        .background(ColorProvider(android.graphics.Color.parseColor("#E0E0E0"))),
            ) {
                // Empty content
            }
        }
    }
}

@Composable
@GlanceComposable
private fun LoadingWidget() {
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(android.graphics.Color.parseColor("#F5F5F5"))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Loading…",
                style =
                    TextStyle(
                        color = ColorProvider(android.graphics.Color.parseColor("#666666")),
                        fontSize = 14.sp,
                    ),
            )
        }
    }
}

@Composable
@GlanceComposable
private fun ErrorWidget(error: String) {
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(android.graphics.Color.parseColor("#FFF1F0"))),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Error",
                style =
                    TextStyle(
                        color = ColorProvider(android.graphics.Color.parseColor("#D32F2F")),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Text(
                text = error,
                style =
                    TextStyle(
                        color = ColorProvider(android.graphics.Color.parseColor("#D32F2F")),
                        fontSize = 12.sp,
                    ),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
    }
}
