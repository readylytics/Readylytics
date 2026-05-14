package com.gregor.lauritz.healthdashboard.ui.settings.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationResult
import com.gregor.lauritz.healthdashboard.domain.validation.ValidationRule

@Composable
fun ValidatingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    rule: ValidationRule<String>,
    label: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val result = rule.validate(value)
    val isError = value.isNotEmpty() && result is ValidationResult.Invalid

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        keyboardOptions = keyboardOptions,
        isError = isError,
        supportingText = {
            if (isError) {
                Text(result.message)
            }
        },
        singleLine = singleLine,
        modifier = modifier,
    )
}
