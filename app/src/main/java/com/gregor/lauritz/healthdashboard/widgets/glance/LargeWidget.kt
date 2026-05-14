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
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
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
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceScoreDial
import com.gregor.lauritz.healthdashboard.widgets.glance.components.GlanceStepsBar

/**
 * Large widget (2x2) - mini-dashboard with 2x2 card grid.
 */
class LargeWidget : GlanceAppWidget() {
    override val stateDefinition = WidgetGlanceStateDefinition

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            LargeWidgetContent(
                context = context,
                glanceId = id,
                widgetId = appWidgetId,
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
    val error = state[LargeWidgetKeys.error(widgetId)]
    val lastUpdateMs = state[LargeWidgetKeys.lastUpdate(widgetId)] ?: 0L

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
    val cardTypeKey =
        when (cardNum) {
            1 -> LargeWidgetKeys.card1Type(widgetId)
            2 -> LargeWidgetKeys.card2Type(widgetId)
            3 -> LargeWidgetKeys.card3Type(widgetId)
            4 -> LargeWidgetKeys.card4Type(widgetId)
            else -> null
        }
    val metricKey =
        when (cardNum) {
            1 -> LargeWidgetKeys.card1Metric(widgetId)
            2 -> LargeWidgetKeys.card2Metric(widgetId)
            3 -> LargeWidgetKeys.card3Metric(widgetId)
            4 -> LargeWidgetKeys.card4Metric(widgetId)
            else -> null
        }
    val valueKey =
        when (cardNum) {
            1 -> LargeWidgetKeys.card1Value(widgetId)
            2 -> LargeWidgetKeys.card2Value(widgetId)
            3 -> LargeWidgetKeys.card3Value(widgetId)
            4 -> LargeWidgetKeys.card4Value(widgetId)
            else -> null
        }
    val statusKey =
        when (cardNum) {
            1 -> LargeWidgetKeys.card1Status(widgetId)
            2 -> LargeWidgetKeys.card2Status(widgetId)
            3 -> LargeWidgetKeys.card3Status(widgetId)
            4 -> LargeWidgetKeys.card4Status(widgetId)
            else -> null
        }

    val cardType = cardTypeKey?.let { state[it] } ?: "METRIC"
    val metricName = metricKey?.let { state[it] } ?: "HRV"
    val value = valueKey?.let { state[it] } ?: 0.0
    val statusName = statusKey?.let { state[it] } ?: "CALIBRATING"

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
                    currentSteps = value.toLong(),
                    goalSteps = 10000L, // Default goal as it's not currently stored per-widget
                )
            }
        }
        else -> {
            // Placeholder for unknown card type
            Box(
                modifier =
                    modifier
                        .background(ColorProvider(Color(0xFFE0E0E0))),
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
