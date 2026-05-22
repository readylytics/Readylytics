package com.gregor.lauritz.healthdashboard.ui.settings.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.UnitSystem

@Composable
fun UnitSystemSelector(
    selectedUnit: UnitSystem,
    onUnitSelected: (UnitSystem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Unit System",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selectedUnit == UnitSystem.METRIC,
                onClick = { onUnitSelected(UnitSystem.METRIC) },
            )
            Text("Metric (cm, kg)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(16.dp))
            RadioButton(
                selected = selectedUnit == UnitSystem.IMPERIAL,
                onClick = { onUnitSelected(UnitSystem.IMPERIAL) },
            )
            Text("Imperial (ft/in, lbs)", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
