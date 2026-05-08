package com.gregor.lauritz.healthdashboard.widgets.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.repository.MediumWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.data.repository.WidgetMode
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Configuration activity for medium widget (1x4).
 * Allows user to select widget mode (dual metric or steps progress) and metrics.
 */
@AndroidEntryPoint
class MediumWidgetConfigActivity : ComponentActivity() {
    @Inject
    lateinit var configRepository: WidgetConfigurationRepository

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedMode by mutableStateOf(WidgetMode.DUAL_METRIC)
    private var metric1 by mutableStateOf(MetricType.HRV)
    private var metric2 by mutableStateOf(MetricType.RHR)
    private var showMetric1Selector by mutableStateOf(false)
    private var showMetric2Selector by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Get widget ID from intent
        widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            FitDashboardTheme {
                Scaffold { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        // Header
                        Text(
                            text = "Configure Medium Widget",
                            style = MaterialTheme.typography.headlineMedium,
                        )

                        // Mode Selection
                        ModeSelectionSection(
                            selectedMode = selectedMode,
                            onModeChange = { selectedMode = it },
                        )

                        // Metric Selection (only for dual metric mode)
                        if (selectedMode == WidgetMode.DUAL_METRIC) {
                            MetricSelectionSection(
                                metric1 = metric1,
                                metric2 = metric2,
                                onMetric1Click = { showMetric1Selector = true },
                                onMetric2Click = { showMetric2Selector = true },
                            )
                        } else {
                            StepsProgressDescription()
                        }

                        // Spacer
                        Box(modifier = Modifier.weight(1f))

                        // Save Button
                        Button(
                            onClick = { saveConfig() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Save Configuration")
                        }
                    }
                }

                // Metric Selectors
                MetricSelectorBottomSheet(
                    isVisible = showMetric1Selector,
                    selectedMetric = metric1,
                    onMetricSelected = { metric1 = it },
                    onDismiss = { showMetric1Selector = false },
                    title = "Select First Metric",
                )

                MetricSelectorBottomSheet(
                    isVisible = showMetric2Selector,
                    selectedMetric = metric2,
                    onMetricSelected = { metric2 = it },
                    onDismiss = { showMetric2Selector = false },
                    title = "Select Second Metric",
                )
            }
        }
    }

    private fun saveConfig() {
        MainScope().launch {
            configRepository.saveMediumWidgetConfig(
                widgetId,
                MediumWidgetConfig(
                    widgetId = widgetId,
                    mode = selectedMode.name,
                    metric1 = if (selectedMode == WidgetMode.DUAL_METRIC) metric1.name else null,
                    metric2 = if (selectedMode == WidgetMode.DUAL_METRIC) metric2.name else null,
                ),
            )

            // Return success
            val resultValue = Intent().apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            setResult(Activity.RESULT_OK, resultValue)
            finish()
        }
    }
}

@Composable
private fun ModeSelectionSection(
    selectedMode: WidgetMode,
    onModeChange: (WidgetMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Widget Mode",
            style = MaterialTheme.typography.labelLarge,
        )

        // Dual Metric Option
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selectedMode == WidgetMode.DUAL_METRIC,
                    onClick = { onModeChange(WidgetMode.DUAL_METRIC) },
                ),
            shape = MaterialTheme.shapes.medium,
            color = if (selectedMode == WidgetMode.DUAL_METRIC)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(
                    selected = selectedMode == WidgetMode.DUAL_METRIC,
                    onClick = { onModeChange(WidgetMode.DUAL_METRIC) },
                )
                Column {
                    Text("Two Metrics Side-by-Side", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Display HRV and RHR together",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Steps Progress Option
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selectedMode == WidgetMode.STEPS_PROGRESS,
                    onClick = { onModeChange(WidgetMode.STEPS_PROGRESS) },
                ),
            shape = MaterialTheme.shapes.medium,
            color = if (selectedMode == WidgetMode.STEPS_PROGRESS)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(
                    selected = selectedMode == WidgetMode.STEPS_PROGRESS,
                    onClick = { onModeChange(WidgetMode.STEPS_PROGRESS) },
                )
                Column {
                    Text("Steps Progress Bar", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Track daily steps vs goal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricSelectionSection(
    metric1: MetricType,
    metric2: MetricType,
    onMetric1Click: () -> Unit,
    onMetric2Click: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Select Metrics",
            style = MaterialTheme.typography.labelLarge,
        )

        // Metric 1
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onMetric1Click),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("First Metric", style = MaterialTheme.typography.labelSmall)
                    Text(metric1.displayName, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Metric 2
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onMetric2Click),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Second Metric", style = MaterialTheme.typography.labelSmall)
                    Text(metric2.displayName, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StepsProgressDescription() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.successContainer.copy(alpha = 0.3f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Steps Progress Bar",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "This widget displays your daily step count against your goal with a visual progress bar.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
