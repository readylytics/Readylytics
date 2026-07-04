package app.readylytics.health.feature.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.about.R

@Composable
fun FeedbackSection(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.padding(top = MaterialTheme.spacing.pageSectionGap),
    ) {
        Button(
            onClick = onDismiss,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.pageHorizontal)
                    .padding(top = MaterialTheme.spacing.pageSectionGap),
        ) {
            Text(stringResource(R.string.action_continue_to_app))
        }
    }
}
