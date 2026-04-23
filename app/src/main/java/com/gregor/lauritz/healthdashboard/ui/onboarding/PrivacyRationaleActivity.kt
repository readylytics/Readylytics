package com.gregor.lauritz.healthdashboard.ui.onboarding

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.data.preferences.AppTheme
import com.gregor.lauritz.healthdashboard.data.preferences.UserPreferencesRepository
import com.gregor.lauritz.healthdashboard.ui.theme.FitDashboardTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PrivacyRationaleActivity : ComponentActivity() {
    @Inject
    lateinit var prefsRepo: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs by prefsRepo.userPreferences.collectAsState(initial = null)
            val appTheme = prefs?.appTheme ?: AppTheme.SYSTEM

            FitDashboardTheme(appTheme = appTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Privacy & Health Data",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text =
                                "This app reads sleep, heart rate, heart rate variability, " +
                                    "and exercise data from Health Connect solely to compute your " +
                                    "personal recovery and readiness scores. All data is processed " +
                                    "locally on your device and is never uploaded to any server.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = { finish() }) {
                            Text("Back")
                        }
                    }
                }
            }
        }
    }
}
