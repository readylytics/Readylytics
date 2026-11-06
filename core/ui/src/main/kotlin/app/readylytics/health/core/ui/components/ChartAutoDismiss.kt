package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState

@Composable
fun AutoDismissChartSelection(
    scrollState: VicoScrollState,
    parentScrollInProgress: () -> Boolean,
    onDismiss: () -> Unit,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { currentOnDismiss() }
    }
    val currentParentScrollInProgress by rememberUpdatedState(parentScrollInProgress)
    LaunchedEffect(Unit) {
        snapshotFlow { currentParentScrollInProgress() }.collect { inProgress ->
            if (inProgress) currentOnDismiss()
        }
    }
}
