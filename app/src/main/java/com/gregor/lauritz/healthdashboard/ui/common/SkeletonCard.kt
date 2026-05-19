package com.gregor.lauritz.healthdashboard.ui.common

import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    height: Float = 140f,
) {
    val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
    val alpha = transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1500
                0.3f at 0 with LinearEasing
                0.9f at 750 with LinearEasing
                0.3f at 1500 with LinearEasing
            },
        ),
        label = "skeleton_alpha",
    )

    Card(
        modifier = modifier.height(height.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .graphicsLayer { this.alpha = alpha.value }
        )
    }
}

@Composable
fun MetricCardSkeleton(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        val transition = rememberInfiniteTransition(label = "metric_skeleton_shimmer")
        val alpha = transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1500
                    0.3f at 0 with LinearEasing
                    0.9f at 750 with LinearEasing
                    0.3f at 1500 with LinearEasing
                },
            ),
            label = "metric_skeleton_alpha",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .graphicsLayer { this.alpha = alpha.value }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        RoundedCornerShape(4.dp)
                    )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(32.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                        RoundedCornerShape(4.dp)
                    )
            )
        }
    }
}

@Composable
fun ScoreDialSkeleton(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        val transition = rememberInfiniteTransition(label = "dial_skeleton_shimmer")
        val alpha = transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1500
                    0.3f at 0 with LinearEasing
                    0.9f at 750 with LinearEasing
                    0.3f at 1500 with LinearEasing
                },
            ),
            label = "dial_skeleton_alpha",
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(16.dp)
                .graphicsLayer { this.alpha = alpha.value }
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    RoundedCornerShape(8.dp)
                )
        )
    }
}
