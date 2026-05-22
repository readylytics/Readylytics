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
) {
    val heightText = heightCm?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: ""
    val validation = SettingsValidators.HEIGHT_CM_RULE.validate(heightText)
    val isError = heightText.isNotEmpty() && validation is ValidationResult.Invalid

    Column(modifier = modifier) {
        OutlinedTextField(
            value = heightText,
            onValueChange = { newValue ->
                if (newValue.isEmpty()) {
                    onHeightChange(null)
                } else {
                    val validation = SettingsValidators.HEIGHT_CM_RULE.validate(newValue)
                    if (validation is ValidationResult.Valid) {
                        onHeightChange(newValue.toFloatOrNull())
                    }
                }
            },
            label = { Text("Height") },
            suffix = { Text("cm") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = isError,
            supportingText = {
                if (isError) {
                    Text(
                        validation.message,
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
