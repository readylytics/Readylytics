package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import android.net.Uri
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.GlanceState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceMetricCard
import com.gregor.lauritz.healthdashboard.widgets.glance.utils.GlanceColorUtils

private val Context.glanceSmallWidgetDataStore by preferencesDataStore(
    name = "glance_small_widget_state"
)

/**
 * Small widget (1x2) - displays a single configurable metric.
 *
 * Features:
 * - Shows metric value with status-based coloring
 * - Configurable metric selection
 * - Click navigates to metric detail screen via deep-link
 * - Auto-updates on app sync + daily refresh
 *
 * Data flow:
 * WidgetDataRepository → SmallWidgetUpdater → DataStore → Glance recomposition
 */
class SmallWidget : GlanceAppWidget(
    stateDefinition = PreferencesGlanceStateDefinition()
) {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            SmallWidgetContent(
                context = context,
                glanceId = id,
                widgetId = id.hashCode(),
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
    val widgetId = glanceId.hashCode()
    val prefix = "widget_${widgetId}_"

    // Read state from preferences
    val state = GlanceState.currentState<androidx.datastore.preferences.core.Preferences>()

    val metricTypeName = state[stringPreferencesKey("${prefix}metric_type")] ?: "HRV"
    val value = state[doublePreferencesKey("${prefix}value")] ?: 0.0
    val statusName = state[stringPreferencesKey("${prefix}status")] ?: "CALIBRATING"
    val error = state[stringPreferencesKey("${prefix}error")]
    val lastUpdateMs = state[longPreferencesKey("${prefix}last_update")] ?: 0L

    val metricType = try {
        MetricType.valueOf(metricTypeName)
    } catch (e: Exception) {
        MetricType.HRV
    }

    val status = try {
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
    val deepLinkUri = "app://metric/${metricType.name.lowercase()}"
    val clickAction = actionStartActivity(
        action = android.content.Intent.ACTION_VIEW,
        uri = deepLinkUri,
        parameters = emptyMap(),
    )

    // Render metric card
    Box(
        modifier = GlanceModifier.fillMaxSize(),
    ) {
        GlanceMetricCard(
            metricType = metricType,
            value = value,
            status = status,
            label = metricType.displayName,
            onClickAction = { clickAction },
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
                modifier = GlanceModifier.padding(top = 12.dp),
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
                modifier = GlanceModifier.padding(top = 12.dp),
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
