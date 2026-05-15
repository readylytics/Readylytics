package com.gregor.lauritz.healthdashboard.ui.health

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionDialog(
    availableDevices: List<String>,
    selectedDevice: String?,
    onDeviceSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var localSelection by remember(selectedDevice) { mutableStateOf(selectedDevice) }
    val isError = availableDevices.isEmpty()

    AlertDialog(
        onDismissRequest = { /* Modal: cannot dismiss until selection made */ },
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        title = { Text("Select Primary Device") },
        text = {
            Column {
                Text(
                    text = "Which device should we use for health metrics?",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(16.dp))
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { if (!isError) expanded = it },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = localSelection ?: "",
                        onValueChange = {},
                        readOnly = true,
                        isError = isError,
                        label = { Text("Device") },
                        placeholder = {
                            Text(
                                if (isError) {
                                    "No devices available"
                                } else {
                                    "Choose a device"
                                },
                            )
                        },
                        supportingText =
                            if (isError) {
                                { Text("No devices found from Health Connect") }
                            } else {
                                null
                            },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        colors =
                            if (isError) {
                                ExposedDropdownMenuDefaults.outlinedTextFieldColors(
                                    focusedBorderColor = MaterialTheme.colorScheme.error,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            },
                        modifier =
                            Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        availableDevices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device) },
                                onClick = {
                                    localSelection = device
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val choice = localSelection
                    if (choice != null) {
                        onDeviceSelected(choice)
                    }
                },
                enabled = localSelection != null,
            ) {
                Text("Continue")
            }
        },
    )
}
