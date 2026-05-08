package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.Arrangement
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.GlanceState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceMetricCard
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceStepsBar

private val Context.glanceMediumWidgetDataStore by preferencesDataStore(
    name = "glance_medium_widget_state"
)

/**
 * Medium widget (1x4) - displays two metrics or steps progress.
 *
 * Two Modes:
 * 1. DUAL_METRIC: Two metric cards side-by-side
 * 2. STEPS_PROGRESS: Horizontal steps progress bar
 *
 * Features:
 * - User configurable mode and metrics
 * - Click navigation to metric detail screens
 * - Status-based colors (reuses dashboard)
 * - Auto-updates on sync + daily fallback
 */
class MediumWidget : GlanceAppWidget(
    stateDefinition = PreferencesGlanceStateDefinition()
) {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            MediumWidgetContent(
                context = context,
                glanceId = id,
                widgetId = id.hashCode(),
            )
        }
    }
}

@Composable
@GlanceComposable
private fun MediumWidgetContent(
    context: Context,
    glanceId: GlanceId,
    widgetId: Int,
) {
    val state = GlanceState.currentState<androidx.datastore.preferences.core.Preferences>()

    val mode = state[stringPreferencesKey("widget_medium_${widgetId}_mode")] ?: "DUAL_METRIC"
    val error = state[stringPreferencesKey("widget_medium_${widgetId}_error")]

    // Handle error state
    if (error != null) {
        ErrorWidget(error = error)
        return
    }

    when (mode) {
        "DUAL_METRIC" -> DualMetricMode(context, state, widgetId)
        "STEPS_PROGRESS" -> StepsProgressMode(context, state, widgetId)
        else -> DualMetricMode(context, state, widgetId)
    }
}

@Composable
@GlanceComposable
private fun DualMetricMode(
    context: Context,
    state: androidx.datastore.preferences.core.Preferences,
    widgetId: Int,
) {
    val metric1Type = state[stringPreferencesKey("widget_medium_${widgetId}_metric1_type")] ?: "HRV"
    val metric1Value = state[doublePreferencesKey("widget_medium_${widgetId}_metric1_value")] ?: 0.0
    val metric1Status = state[stringPreferencesKey("widget_medium_${widgetId}_metric1_status")] ?: "CALIBRATING"

    val metric2Type = state[stringPreferencesKey("widget_medium_${widgetId}_metric2_type")] ?: "RHR"
    val metric2Value = state[doublePreferencesKey("widget_medium_${widgetId}_metric2_value")] ?: 0.0
    val metric2Status = state[stringPreferencesKey("widget_medium_${widgetId}_metric2_status")] ?: "CALIBRATING"

    val lastUpdateMs = state[longPreferencesKey("widget_medium_${widgetId}_last_update")] ?: 0L

    // Parse enum values
    val m1Type = try { MetricType.valueOf(metric1Type) } catch (e: Exception) { MetricType.HRV }
    val m1Status = try { MetricStatus.valueOf(metric1Status) } catch (e: Exception) { MetricStatus.CALIBRATING }
    val m2Type = try { MetricType.valueOf(metric2Type) } catch (e: Exception) { MetricType.RHR }
    val m2Status = try { MetricStatus.valueOf(metric2Status) } catch (e: Exception) { MetricStatus.CALIBRATING }

    // Handle loading state
    if (m1Status == MetricStatus.CALIBRATING && lastUpdateMs == 0L) {
        LoadingWidget()
        return
    }

    // Create click actions
    val click1Action = actionStartActivity(
        action = android.content.Intent.ACTION_VIEW,
        uri = "app://metric/${m1Type.name.lowercase()}",
        parameters = emptyMap(),
    )

    val click2Action = actionStartActivity(
        action = android.content.Intent.ACTION_VIEW,
        uri = "app://metric/${m2Type.name.lowercase()}",
        parameters = emptyMap(),
    )

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Metric 1 (left)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .weight(1f),
        ) {
            GlanceMetricCard(
                metricType = m1Type,
                value = metric1Value,
                status = m1Status,
                onClickAction = { click1Action },
            )
        }

        // Metric 2 (right)
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .weight(1f),
        ) {
            GlanceMetricCard(
                metricType = m2Type,
                value = metric2Value,
                status = m2Status,
                onClickAction = { click2Action },
            )
        }
    }
}

@Composable
@GlanceComposable
private fun StepsProgressMode(
    context: Context,
    state: androidx.datastore.preferences.core.Preferences,
    widgetId: Int,
) {
    val currentSteps = state[longPreferencesKey("widget_medium_${widgetId}_current_steps")] ?: 0L
    val goalSteps = state[longPreferencesKey("widget_medium_${widgetId}_goal_steps")] ?: 10000L
    val lastUpdateMs = state[longPreferencesKey("widget_medium_${widgetId}_last_update")] ?: 0L

    // Handle loading state
    if (lastUpdateMs == 0L) {
        LoadingWidget()
        return
    }

    // Create click action to navigate to steps screen
    val clickAction = actionStartActivity(
        action = android.content.Intent.ACTION_VIEW,
        uri = "app://metric/steps",
        parameters = emptyMap(),
    )

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(android.graphics.Color.parseColor("#FFFFFF")))
    ) {
        GlanceStepsBar(
            currentSteps = currentSteps,
            goalSteps = goalSteps,
        )
    }
}

@Composable
@GlanceComposable
private fun LoadingWidget() {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(android.graphics.Color.parseColor("#F5F5F5")))
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = androidx.glance.layout.Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Loading…",
                style = TextStyle(
                    color = ColorProvider(android.graphics.Color.parseColor("#666666")),
                    fontSize = 14.sp,
                ),
                modifier = GlanceModifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
@GlanceComposable
private fun ErrorWidget(error: String) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(android.graphics.Color.parseColor("#FFCDD2")))
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = androidx.glance.layout.Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Error",
                style = TextStyle(
                    color = ColorProvider(android.graphics.Color.parseColor("#D32F2F")),
                    fontSize = 12.sp,
                ),
                modifier = GlanceModifier.padding(top = 8.dp),
            )
            Text(
                text = error,
                style = TextStyle(
                    color = ColorProvider(android.graphics.Color.parseColor("#333333")),
                    fontSize = 10.sp,
                ),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
    }
}
