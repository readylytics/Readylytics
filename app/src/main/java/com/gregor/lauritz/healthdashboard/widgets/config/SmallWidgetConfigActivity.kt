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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.repository.SmallWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Configuration activity for small widget (1x2).
 * Allows user to select metric, trend display, and timestamp display.
 */
@AndroidEntryPoint
class SmallWidgetConfigActivity : ComponentActivity() {
    @Inject
    lateinit var configRepository: WidgetConfigurationRepository

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedMetric by mutableStateOf(MetricType.HRV)
    private var showTrend by mutableStateOf(true)
    private var showTimestamp by mutableStateOf(true)
    private var showMetricSelector by mutableStateOf(false)

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
                            text = "Configure Small Widget",
                            style = MaterialTheme.typography.headlineMedium,
                        )

                        // Metric Selection
                        MetricSelectionCard(
                            selectedMetric = selectedMetric,
                            onSelectClick = { showMetricSelector = true },
                        )

                        // Options
                        OptionSwitchCard(
                            label = "Show Trend Indicator",
                            description = "Display ↑ or ↓ symbol",
                            checked = showTrend,
                            onCheckedChange = { showTrend = it },
                        )

                        OptionSwitchCard(
                            label = "Show Timestamp",
                            description = "Display 'Updated 2h ago'",
                            checked = showTimestamp,
                            onCheckedChange = { showTimestamp = it },
                        )

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

                // Metric Selector Bottom Sheet
                MetricSelectorBottomSheet(
                    isVisible = showMetricSelector,
                    selectedMetric = selectedMetric,
                    onMetricSelected = { selectedMetric = it },
                    onDismiss = { showMetricSelector = false },
                    title = "Select Metric to Display",
                )
            }
        }
    }

    private fun saveConfig() {
        MainScope().launch {
            configRepository.saveSmallWidgetConfig(
                widgetId,
                SmallWidgetConfig(
                    widgetId = widgetId,
                    metricType = selectedMetric.name,
                    showTrend = showTrend,
                    showTimestamp = showTimestamp,
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
private fun MetricSelectionCard(
    selectedMetric: MetricType,
    onSelectClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelectClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Metric to Display",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = selectedMetric.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = "Change",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun OptionSwitchCard(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}
