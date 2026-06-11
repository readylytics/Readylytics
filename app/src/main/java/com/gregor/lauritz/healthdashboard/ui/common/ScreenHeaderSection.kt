package com.gregor.lauritz.healthdashboard.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ScreenHeaderSection(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    headerContent: @Composable ColumnScope.(isDisabled: Boolean) -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        headerContent(isLoading)
    }
}
