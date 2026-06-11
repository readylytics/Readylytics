package app.readylytics.health.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.readylytics.health.R

/**
 * Shown after Health Connect permissions are granted and device discovery has completed.
 * Allows the user to choose a primary device so subsequent syncs prefer that source.
 *
 * Selecting "Use all devices" passes null to indicate no preference. When the discovered
 * list is empty (manually-entered data only), the same option is offered.
 */
@Composable
fun DeviceSelectionScreen(
    devices: List<String>,
    onDeviceSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.device_selection_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.device_selection_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            if (devices.isEmpty()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.device_selection_no_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .weight(1f, fill = true)
                            .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = devices,
                        key = { it },
                    ) { device ->
                        DeviceCard(
                            name = device,
                            onClick = { onDeviceSelected(device) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            TextButton(
                onClick = { onDeviceSelected(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.device_selection_use_all))
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { onDeviceSelected(devices.firstOrNull()) },
                modifier = Modifier.fillMaxWidth(),
                enabled = devices.isNotEmpty(),
            ) {
                Text(
                    stringResource(
                        R.string.device_selection_continue_with,
                        devices.firstOrNull() ?: "auto",
                    ),
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        onClick = onClick,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}
