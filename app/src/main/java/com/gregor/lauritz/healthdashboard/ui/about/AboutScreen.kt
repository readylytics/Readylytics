package com.gregor.lauritz.healthdashboard.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AboutScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Spacer for notch
        item {
            Box(modifier = Modifier.height(24.dp))
        }

        // Header
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Readylytics",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Train with insight, not guesswork.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Circadian Consistency
        item {
            AboutCard(
                title = "Circadian Consistency",
                subtitle = "Sleep Regularity",
                content = "We calculate your score against your rolling median bedtime and wake time from the last 14 days. Research indicates that sleep regularity is often a better predictor of metabolic health and cognitive performance than duration alone. We allow a configurable grace period (default ±30 mins). If you deviate beyond this, your score decays linearly."
            )
        }

        // Cardiovascular Load
        item {
            AboutCard(
                title = "Cardiovascular Load",
                subtitle = "PAI-Inspired Model",
                content = "We use the Banister TRIMP (Training Impulse) model to weight high-intensity efforts exponentially. Your goal is a 7-day rolling sum of 100 points. Maintaining this '100 PAI' threshold has been clinically shown to reduce cardiovascular mortality risk by up to 25%. Points become harder to earn as you approach 100, and daily gains are capped at 75 points."
            )
        }

        // Readiness & Strain Ratio
        item {
            AboutCard(
                title = "Readiness",
                subtitle = "The 'Sweet Spot' (ACWR)",
                content = "Readylytics calculates your daily training capacity by balancing short-term fatigue against long-term fitness. This is your Acute:Chronic Workload Ratio. A ratio between 0.8 and 1.2 is the scientifically validated 'Sweet Spot' for performance gains. If your ratio exceeds 1.5, you are in the 'Danger Zone' for overtraining and injury."
            )
        }

        // Sleep Architecture
        item {
            AboutCard(
                title = "Sleep Architecture",
                subtitle = "Restoration & Recovery",
                content = "Your Sleep Score is a 100-point composite weighted by duration (50%), architecture (25%), and restoration (25%). We target 15–25% Deep and 20–25% REM sleep based on clinical benchmarks for physical and mental repair. We track your HRV Z-Score and RHR Ratio for autonomic recovery. If your heart rate doesn't reach its lowest point until just before you wake up, we apply a penalty as this suggests metabolic stress instead of recovery."
            )
        }

        // Technical Appendix
        item {
            AboutCard(
                title = "Technical Foundation",
                subtitle = "Scientific Sources",
                content = "Consistency: Sleep Regularity Index (Phillips et al., 2017)\n" +
                        "Activity Load: The HUNT Fitness Study (Nes et al., 2017)\n" +
                        "Strain Ratio: Training—Injury Prevention Paradox (Gabbett, 2016)\n" +
                        "HRV Analysis: 30-day Standard Deviation (Plews et al., 2013)"
            )
        }

        // Calibration Note
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = "Professional-grade accuracy requires data. If your scores seem volatile during your first 7 days, don't worry—the engine is in its 'Calibration' phase to learn your unique biometrics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Continue Button
        item {
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(vertical = 8.dp)
            ) {
                Text("Continue to App")
            }
        }

        // Bottom spacer
        item {
            Box(modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun AboutCard(
    title: String,
    subtitle: String,
    content: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
