package app.readylytics.health.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.ui.R
import app.readylytics.health.domain.model.MetricStatus

@Composable
fun CircadianConsistencyCard(
    scoreText: String,
    windowText: String?,
    status: MetricStatus,
    tooltipText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    MetricCard(
        title = stringResource(R.string.label_circadian_consistency),
        value = scoreText,
        secondaryText = windowText,
        status = status,
        tooltip = tooltipText,
        onClick = onClick,
        modifier = modifier,
    )
}
