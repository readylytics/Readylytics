package app.readylytics.health.feature.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.feature.about.R

@Composable
fun FeedbackSection(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Button(
            onClick = onDismiss,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.action_continue_to_app))
        }
    }
}
