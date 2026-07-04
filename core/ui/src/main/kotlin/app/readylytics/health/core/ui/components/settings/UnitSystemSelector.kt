package app.readylytics.health.core.ui.settings.common

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
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.R
import app.readylytics.health.data.preferences.UnitSystem

@Composable
fun UnitSystemSelector(
    selectedUnit: UnitSystem,
    onUnitSelected: (UnitSystem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.unit_system_label),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.width(MaterialTheme.spacing.small))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selectedUnit == UnitSystem.METRIC,
                onClick = { onUnitSelected(UnitSystem.METRIC) },
            )
            Text(stringResource(R.string.unit_system_metric_label), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(MaterialTheme.spacing.medium))
            RadioButton(
                selected = selectedUnit == UnitSystem.IMPERIAL,
                onClick = { onUnitSelected(UnitSystem.IMPERIAL) },
            )
            Text(stringResource(R.string.unit_system_imperial_label), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
