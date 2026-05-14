package com.gregor.lauritz.healthdashboard.widgets.glance

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gregor.lauritz.healthdashboard.MainActivity
import com.gregor.lauritz.healthdashboard.domain.model.MetricStatus
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceMetricCard
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceStepsBar

/**
 * Medium widget (1x2) - displays two metrics or steps progress.
 */
class MediumWidget : GlanceAppWidget() {
    override val stateDefinition = WidgetGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            MediumWidgetContent(
                context = context,
                glanceId = id,
                widgetId = appWidgetId,
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
    val state = currentState<Preferences>()

    val mode = state[MediumWidgetKeys.mode(widgetId)] ?: "DUAL_METRIC"
    val error = state[MediumWidgetKeys.error(widgetId)]

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
    state: Preferences,
    widgetId: Int,
) {
    val metric1Type = state[MediumWidgetKeys.metric1Type(widgetId)] ?: "HRV"
    val metric1Value = state[MediumWidgetKeys.metric1Value(widgetId)] ?: 0.0
    val metric1Status = state[MediumWidgetKeys.metric1Status(widgetId)] ?: "CALIBRATING"
    val metric2Type = state[MediumWidgetKeys.metric2Type(widgetId)] ?: "RHR"
    val metric2Value = state[MediumWidgetKeys.metric2Value(widgetId)] ?: 0.0
    val metric2Status = state[MediumWidgetKeys.metric2Status(widgetId)] ?: "CALIBRATING"
    val lastUpdateMs = state[MediumWidgetKeys.lastUpdate(widgetId)] ?: 0L

    val m1Type =
        try {
            MetricType.valueOf(metric1Type)
        } catch (e: Exception) {
            MetricType.HRV
        }
    val m1Status =
        try {
            MetricStatus.valueOf(metric1Status)
        } catch (e: Exception) {
            MetricStatus.CALIBRATING
        }
    val m2Type =
        try {
            MetricType.valueOf(metric2Type)
        } catch (e: Exception) {
            MetricType.RHR
        }
    val m2Status =
        try {
            MetricStatus.valueOf(metric2Status)
        } catch (e: Exception) {
            MetricStatus.CALIBRATING
        }

    // Handle loading state
    if (m1Status == MetricStatus.CALIBRATING && lastUpdateMs == 0L) {
        LoadingWidget()
        return
    }

    // Create click actions
    val click1Action =
        actionStartActivity<MainActivity>()

    val click2Action =
        actionStartActivity<MainActivity>()

    Row(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .padding(4.dp),
    ) {
        // Metric 1 (left)
        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .defaultWeight(),
        ) {
            GlanceMetricCard(
                metricType = m1Type,
                value = metric1Value,
                status = m1Status,
                onClickAction = click1Action,
            )
        }

        Spacer(modifier = GlanceModifier.width(4.dp))

        // Metric 2 (right)
        Box(
            modifier =
                GlanceModifier
                    .fillMaxSize()
                    .defaultWeight(),
        ) {
            GlanceMetricCard(
                metricType = m2Type,
                value = metric2Value,
                status = m2Status,
                onClickAction = click2Action,
            )
        }
    }
}

@Composable
@GlanceComposable
private fun StepsProgressMode(
    context: Context,
    state: Preferences,
    widgetId: Int,
) {
    val currentSteps = state[MediumWidgetKeys.currentSteps(widgetId)] ?: 0L
    val goalSteps = state[MediumWidgetKeys.goalSteps(widgetId)] ?: 10000L
    val lastUpdateMs = state[MediumWidgetKeys.lastUpdate(widgetId)] ?: 0L

    // Handle loading state
    if (lastUpdateMs == 0L) {
        LoadingWidget()
        return
    }

    // Create click action to navigate to steps screen
    val clickAction =
        actionStartActivity<MainActivity>()

    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFFFFFFFF))),
        contentAlignment = Alignment.Center,
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
        modifier =
            GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFFF5F5F5))),
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
                        color = ColorProvider(Color(0xFF666666)),
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
                .background(ColorProvider(Color(0xFFFFF1F0))),
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
                        color = ColorProvider(Color(0xFFD32F2F)),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Text(
                text = error,
                style =
                    TextStyle(
                        color = ColorProvider(Color(0xFFD32F2F)),
                        fontSize = 12.sp,
                    ),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
    }
}
