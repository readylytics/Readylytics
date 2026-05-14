package com.gregor.lauritz.healthdashboard.widgets.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Configuration activity for small widget (1x1).
 * Allows user to select a single metric, trend display, and timestamp display.
 */
@AndroidEntryPoint
class SmallWidgetConfigActivity : ComponentActivity() {
    @Inject
    lateinit var configRepository: WidgetConfigurationRepository

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var viewModel: SmallWidgetConfigViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val savedStateHandle = SavedStateHandle(mapOf("widgetId" to widgetId))
                    return SmallWidgetConfigViewModel(configRepository, savedStateHandle) as T
                }
            }

        viewModel = ViewModelProvider(this, factory).get(SmallWidgetConfigViewModel::class.java)

        setContent {
            FitDashboardTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.isSaved) {
                    if (state.isSaved) {
                        val resultValue =
                            Intent().apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                            }
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    }
                }

                SmallWidgetConfigScreen(
                    state = state,
                    onMetricSelected = viewModel::updateMetric,
                    onTrendToggled = viewModel::updateShowTrend,
                    onTimestampToggled = viewModel::updateShowTimestamp,
                    onSave = viewModel::saveConfiguration,
                    onErrorDismissed = viewModel::clearError,
                )
            }
        }
    }
}

@Composable
private fun SmallWidgetConfigScreen(
    state: SmallWidgetConfigState,
    onMetricSelected: (MetricType) -> Unit,
    onTrendToggled: (Boolean) -> Unit,
    onTimestampToggled: (Boolean) -> Unit,
    onSave: () -> Unit,
    onErrorDismissed: () -> Unit,
) {
    var showMetricSelector by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Header
            Text(
                text = "Configure Small Widget (1x1)",
                style = MaterialTheme.typography.headlineMedium,
            )

            // Error message
            if (state.error != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onErrorDismissed) {
                            Text("Dismiss")
                        }
                    }
                }
            }

            // Loading state
            if (state.isLoading) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            // Metric Selection
            MetricSelectionCard(
                selectedMetric = state.selectedMetric,
                onSelectClick = { showMetricSelector = true },
            )

            // Options
            OptionSwitchCard(
                label = "Show Trend Indicator",
                description = "Display ↑ or ↓ symbol",
                checked = state.showTrend,
                onCheckedChange = onTrendToggled,
            )

            OptionSwitchCard(
                label = "Show Timestamp",
                description = "Display 'Updated 2h ago'",
                checked = state.showTimestamp,
                onCheckedChange = onTimestampToggled,
            )

            // Spacer
            Box(modifier = Modifier.weight(1f))

            // Save Button
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading,
            ) {
                Text("Save Configuration")
            }
        }
    }

    // Metric Selector Bottom Sheet
    MetricSelectorBottomSheet(
        isVisible = showMetricSelector,
        selectedMetric = state.selectedMetric,
        onMetricSelected = { metric ->
            onMetricSelected(metric)
            showMetricSelector = false
        },
        onDismiss = { showMetricSelector = false },
        title = "Select Metric to Display",
    )
}

@Composable
private fun MetricSelectionCard(
    selectedMetric: MetricType,
    onSelectClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelectClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
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
            modifier =
                Modifier
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
