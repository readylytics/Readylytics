package app.readylytics.health.core.ui.components.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.R
import app.readylytics.health.core.ui.components.DropdownPreferenceItem
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun WeekStartDayPicker(
    selectedDay: DayOfWeek,
    onDaySelected: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dayLabel: (DayOfWeek) -> String = { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }

    DropdownPreferenceItem(
        label = stringResource(R.string.week_start_day_label),
        selectedDisplayValue = dayLabel(selectedDay),
        options = DayOfWeek.entries,
        optionLabel = dayLabel,
        onOptionSelected = onDaySelected,
        modifier = modifier,
        enabled = enabled,
    )
}
