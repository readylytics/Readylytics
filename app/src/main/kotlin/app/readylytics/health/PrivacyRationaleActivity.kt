package app.readylytics.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.readylytics.health.feature.onboarding.PrivacyRationaleContent
import app.readylytics.health.feature.onboarding.PrivacyRationaleViewModel
import app.readylytics.health.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PrivacyRationaleActivity : ComponentActivity() {
    private val viewModel: PrivacyRationaleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()

            FitDashboardTheme(appTheme = appTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PrivacyRationaleContent(
                        onBackClick = { finish() },
                    )
                }
            }
        }
    }
}
