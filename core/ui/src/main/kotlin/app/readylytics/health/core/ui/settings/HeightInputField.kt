package app.readylytics.health.core.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.model.data.preferences.UnitSystem
import app.readylytics.health.core.model.domain.validation.SettingsValidators
import app.readylytics.health.core.model.domain.validation.ValidationResult
import app.readylytics.health.core.ui.R
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun HeightInputField(
    heightCm: Float?,
    onHeightChange: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    onHasErrorChange: (Boolean) -> Unit = {},
) {
    if (unitSystem == UnitSystem.METRIC) {
        MetricHeightInputField(
            heightCm = heightCm,
            onHeightChange = onHeightChange,
            onHasErrorChange = onHasErrorChange,
            modifier = modifier,
        )
    } else {
        ImperialHeightInputField(
            heightCm = heightCm,
            onHeightChange = onHeightChange,
            onHasErrorChange = onHasErrorChange,
            modifier = modifier,
        )
    }
}

@Composable
private fun MetricHeightInputField(
    heightCm: Float?,
    onHeightChange: (Float?) -> Unit,
    onHasErrorChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var textState by remember(heightCm) {
        mutableStateOf(heightCm?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "")
    }

    val validation = SettingsValidators.HEIGHT_CM_RULE.validate(textState)
    val isError = textState.isNotEmpty() && validation is ValidationResult.Invalid

    LaunchedEffect(isError) {
        onHasErrorChange(isError)
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = textState,
            onValueChange = { newValue ->
                val filtered = newValue.filter { it.isDigit() }
                textState = filtered
                if (filtered.isEmpty()) {
                    onHeightChange(null)
                } else {
                    val validationResult = SettingsValidators.HEIGHT_CM_RULE.validate(filtered)
                    if (validationResult is ValidationResult.Valid) {
                        onHeightChange(filtered.toFloatOrNull())
                    }
                }
            },
            label = { Text(stringResource(R.string.label_height)) },
            suffix = { Text(stringResource(R.string.unit_metric_cm)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = isError,
            supportingText = {
                if (isError) {
                    Text(
                        validation.message,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(stringResource(R.string.height_range_hint_metric))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.height_bmi_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImperialHeightInputField(
    heightCm: Float?,
    onHeightChange: (Float?) -> Unit,
    onHasErrorChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember(heightCm) { calculateInitialFeetAndInches(heightCm) }
    var feetState by remember(initial) { mutableStateOf(initial.first) }
    var inchesState by remember(initial) { mutableStateOf(initial.second) }

    val errorState = remember(feetState, inchesState) { evaluateImperialError(feetState, inchesState) }

    LaunchedEffect(errorState.isError) {
        onHasErrorChange(errorState.isError)
    }

    Column(modifier = modifier) {
        ImperialInputRow(
            feetState = feetState,
            inchesState = inchesState,
            errorState = errorState,
            onFeetChange = { filtered ->
                feetState = filtered
                handleImperialHeightChange(filtered, inchesState, onHeightChange)
            },
            onInchesChange = { filtered ->
                inchesState = filtered
                handleImperialInchesChange(feetState, filtered, onHeightChange)
            },
        )
        Spacer(modifier = Modifier.height(4.dp))
        ImperialSupportingText(errorState)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.height_bmi_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class ImperialErrorState(
    val isFeetInvalid: Boolean,
    val isInchesInvalid: Boolean,
    val isTotalInvalid: Boolean,
) {
    val isError: Boolean get() = isFeetInvalid || isInchesInvalid || isTotalInvalid
}

private fun evaluateImperialError(
    feetState: String,
    inchesState: String,
): ImperialErrorState {
    val feetVal = feetState.toIntOrNull()
    val inchesVal = inchesState.toIntOrNull()
    val isFeetInvalid = feetState.isNotEmpty() && (feetVal == null || feetVal !in 3..8)
    val isInchesInvalid = inchesState.isNotEmpty() && (inchesVal == null || inchesVal !in 0..11)
    val totalCm =
        if (feetVal != null && inchesVal != null) {
            (feetVal * 12f + inchesVal) * 2.54f
        } else {
            null
        }
    val isTotalInvalid = totalCm != null && (totalCm < 120f || totalCm > 250f)
    return ImperialErrorState(isFeetInvalid, isInchesInvalid, isTotalInvalid)
}

@Composable
private fun ImperialInputRow(
    feetState: String,
    inchesState: String,
    errorState: ImperialErrorState,
    onFeetChange: (String) -> Unit,
    onInchesChange: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = feetState,
            onValueChange = { onFeetChange(it.filter { c -> c.isDigit() }) },
            label = { Text(stringResource(R.string.label_feet)) },
            suffix = { Text(stringResource(R.string.unit_ft)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = errorState.isFeetInvalid || errorState.isTotalInvalid,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        OutlinedTextField(
            value = inchesState,
            onValueChange = { onInchesChange(it.filter { c -> c.isDigit() }) },
            label = { Text(stringResource(R.string.label_inches)) },
            suffix = { Text(stringResource(R.string.unit_in)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = errorState.isInchesInvalid || errorState.isTotalInvalid,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ImperialSupportingText(errorState: ImperialErrorState) {
    if (errorState.isError) {
        val errorMsg =
            when {
                errorState.isFeetInvalid -> stringResource(R.string.error_height_feet)
                errorState.isInchesInvalid -> stringResource(R.string.error_height_inches)
                errorState.isTotalInvalid -> stringResource(R.string.error_height_out_of_range)
                else -> ""
            }
        Text(
            errorMsg,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            stringResource(R.string.height_range_hint_imperial),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun calculateInitialFeetAndInches(heightCm: Float?): Pair<String, String> {
    if (heightCm == null) return Pair("", "")
    val totalInches = heightCm / 2.54f
    val feet = floor(totalInches / 12f).toInt()
    val inches = (totalInches - (feet * 12f)).roundToInt()
    val (finalFeet, finalInches) =
        if (inches == 12) {
            Pair(feet + 1, 0)
        } else {
            Pair(feet, inches)
        }
    return Pair(finalFeet.toString(), finalInches.toString())
}

private fun handleImperialHeightChange(
    filteredFeet: String,
    inchesState: String,
    onHeightChange: (Float?) -> Unit,
) {
    val f = filteredFeet.toIntOrNull()
    val i = inchesState.toIntOrNull() ?: 0
    if (filteredFeet.isEmpty() && inchesState.isEmpty()) {
        onHeightChange(null)
    } else if (f != null && f in 3..8) {
        val cm = (f * 12f + i) * 2.54f
        if (cm in 120f..250f) {
            onHeightChange(cm)
        }
    }
}

private fun handleImperialInchesChange(
    feetState: String,
    filteredInches: String,
    onHeightChange: (Float?) -> Unit,
) {
    val f = feetState.toIntOrNull() ?: 0
    val i = filteredInches.toIntOrNull()
    if (feetState.isEmpty() && filteredInches.isEmpty()) {
        onHeightChange(null)
    } else if (i != null && i in 0..11) {
        val cm = (f * 12f + i) * 2.54f
        if (cm in 120f..250f) {
            onHeightChange(cm)
        }
    }
}
