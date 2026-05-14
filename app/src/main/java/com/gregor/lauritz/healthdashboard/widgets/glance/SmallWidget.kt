package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceMetricCard

private val Context.glanceSmallWidgetDataStore by preferencesDataStore(
    name = "glance_small_widget_state",
)

/**
 * Small widget (1x1) - displays a single configurable metric.
 */
class SmallWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            SmallWidgetContent(
                context = context,
                glanceId = id,
                widgetId = appWidgetId,
            )
        }
    }
}

@Composable
@GlanceComposable
private fun SmallWidgetContent(
    context: Context,
    glanceId: GlanceId,
    widgetId: Int,
) {
    // Read state from preferences
    val state = currentState<Preferences>()

    val metricTypeName = state[SmallWidgetKeys.metricType(widgetId)] ?: "HRV"
    val value = state[SmallWidgetKeys.value(widgetId)] ?: 0.0
    val statusName = state[SmallWidgetKeys.status(widgetId)] ?: "CALIBRATING"
    val error = state[SmallWidgetKeys.error(widgetId)]
    val lastUpdateMs = state[SmallWidgetKeys.lastUpdate(widgetId)] ?: 0L

    val metricType =
        try {
            MetricType.valueOf(metricTypeName)
        } catch (e: Exception) {
            MetricType.HRV
        }

    val status =
        try {
            MetricStatus.valueOf(statusName)
        } catch (e: Exception) {
            MetricStatus.CALIBRATING
        }

    // Handle error state
    if (error != null) {
        ErrorWidget(error = error)
        return
    }

    // Handle loading state
    if (status == MetricStatus.CALIBRATING && lastUpdateMs == 0L) {
        LoadingWidget()
        return
    }

    // Create deep-link action
    val clickAction =
        actionStartActivity<MainActivity>()

    // Render metric card
    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .padding(4.dp),
    ) {
        GlanceMetricCard(
            metricType = metricType,
            value = value,
            status = status,
            label = metricType.displayName,
            onClickAction = clickAction,
        )
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
