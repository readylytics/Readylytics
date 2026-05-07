package com.gregor.lauritz.healthdashboard.widgets.config

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.repository.LargeWidgetConfig
import com.gregor.lauritz.healthdashboard.data.repository.WidgetConfigurationRepository
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Configuration activity for large widget (2x4).
 * Allows user to select up to 4 cards to display in a 2x2 grid.
 */
@AndroidEntryPoint
class LargeWidgetConfigActivity : ComponentActivity() {
    @Inject
    lateinit var configRepository: WidgetConfigurationRepository

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

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
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Select Cards for Dashboard Widget",
                            style = MaterialTheme.typography.headlineSmall
                        )

                        Text(
                            text = "Choose up to 4 metrics to display",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        // TODO: Add card selection UI (checkboxes or chips, max 4)
                        // TODO: Show preview of selected cards

                        Button(
                            onClick = { saveConfig() },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text("Save Configuration")
                        }
                    }
                }
            }
        }
    }

    private fun saveConfig() {
        MainScope().launch {
            configRepository.saveLargeWidgetConfig(
                widgetId,
                LargeWidgetConfig(
                    widgetId = widgetId,
                    cardIds = emptyList(), // TODO: Get selected card IDs
                )
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
