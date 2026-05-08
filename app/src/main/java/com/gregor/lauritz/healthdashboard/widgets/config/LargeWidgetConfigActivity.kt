package com.gregor.lauritz.healthdashboard.widgets.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.model.MetricType
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Configuration activity for large widget (2x4).
 * Allows user to select up to 4 cards to display in a 2x2 grid.
 */
@AndroidEntryPoint
class LargeWidgetConfigActivity : ComponentActivity() {
    private val viewModel: LargeWidgetConfigViewModel by viewModels()
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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

        setContent {
            FitDashboardTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(state.isSaved) {
                    if (state.isSaved) {
                        val resultValue = Intent().apply {
                            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        }
                        setResult(Activity.RESULT_OK, resultValue)
                        finish()
                    }
                }

                LargeWidgetConfigScreen(
                    state = state,
                    onCardToggle = viewModel::toggleCard,
                    onSave = viewModel::saveConfiguration,
                    onErrorDismissed = viewModel::clearError,
                )
            }
        }
    }
}

@Composable
private fun LargeWidgetConfigScreen(
    state: LargeWidgetConfigState,
    onCardToggle: (String) -> Unit,
    onSave: () -> Unit,
    onErrorDismissed: () -> Unit,
) {
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Configure Large Widget",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Select up to 4 metrics to display in a 2x2 grid",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Error message
            if (state.error != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Row(
                            modifier = Modifier
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
            }

            // Loading state
            if (state.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                return@Scaffold
            }

            item {
                CardSelectionSection(
                    selectedCardIds = state.selectedCardIds,
                    onCardToggle = onCardToggle,
                )
            }

            item {
                SelectedCardsPreview(
                    selectedCardIds = state.selectedCardIds,
                )
            }

            item {
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                ) {
                    Text("Save Configuration")
                }
            }

            item {
                Box(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CardSelectionSection(
    selectedCardIds: List<String>,
    onCardToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Available Metrics",
            style = MaterialTheme.typography.labelLarge,
        )

        val availableCards = listOf(
            CardOption("SLEEP_SCORE", "Sleep Score", "Overall sleep quality"),
            CardOption("READINESS", "Readiness", "Daily readiness score"),
            CardOption("HRV", "HRV", "Heart rate variability"),
            CardOption("RHR", "RHR", "Resting heart rate"),
            CardOption("RECOVERY", "Recovery", "Recovery percentage"),
            CardOption("STEPS", "Steps", "Daily step count"),
            CardOption("SLEEP_DURATION", "Sleep Duration", "Total sleep time"),
            CardOption("SLEEP_EFFICIENCY", "Sleep Efficiency", "Sleep quality ratio"),
            CardOption("STRESS", "Stress", "Daily stress level"),
            CardOption("BODY_BATTERY", "Body Battery", "Energy level"),
            CardOption("PAI", "PAI", "Personal Activity Index"),
            CardOption("STRAIN_RATIO", "Strain Ratio", "Training load ratio"),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(availableCards) { card ->
                CardSelectionItem(
                    card = card,
                    isSelected = selectedCardIds.contains(card.id),
                    isDisabled = !selectedCardIds.contains(card.id) && selectedCardIds.size >= 4,
                    onToggle = { onCardToggle(card.id) },
                )
            }
        }
    }
}

@Composable
private fun CardSelectionItem(
    card: CardOption,
    isSelected: Boolean,
    isDisabled: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDisabled, onClick = onToggle),
        shape = MaterialTheme.shapes.medium,
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isDisabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDisabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                enabled = !isDisabled,
            )
        }
    }
}

@Composable
private fun SelectedCardsPreview(
    selectedCardIds: List<String>,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Preview (${selectedCardIds.size}/4 selected)",
                style = MaterialTheme.typography.labelLarge,
            )

            if (selectedCardIds.isEmpty()) {
                Text(
                    text = "No cards selected. Select at least 1 metric.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(2) { row ->
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    MaterialTheme.shapes.small,
                                )
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            repeat(2) { col ->
                                val index = row * 2 + col
                                if (index < selectedCardIds.size) {
                                    PreviewCard(cardId = selectedCardIds[index])
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                                MaterialTheme.shapes.small,
                                            )
                                            .padding(8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "Empty",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(cardId: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                MaterialTheme.shapes.small,
            )
            .padding(8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = cardId,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private data class CardOption(
    val id: String,
    val displayName: String,
    val description: String,
)
