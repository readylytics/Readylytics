package com.gregor.lauritz.healthdashboard.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.validation.SettingsValidators
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import java.util.Locale

@Composable
fun HeightInputField(
    heightCm: Float?,
    onHeightChange: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    onHasErrorChange: (Boolean) -> Unit = {},
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
            label = { Text("Height") },
            suffix = { Text("cm") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = isError,
            supportingText = {
                if (isError) {
                    Text(
                        (validation as ValidationResult.Invalid).message,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("Range: 120-250 cm")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Used for BMI calculation in weight tracking",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
