package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SettingsToggleItem(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors()
            )
        },
        modifier = modifier.clickable { onCheckedChange(!checked) }
    )
}
