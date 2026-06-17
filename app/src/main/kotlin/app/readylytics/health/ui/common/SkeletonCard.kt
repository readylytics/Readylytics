package app.readylytics.health.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
private fun Modifier.shimmerAnimation(): Modifier {
    val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
    val alpha =
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 0.9f,
            animationSpec =
                infiniteRepeatable(
                    animation =
                        keyframes {
                            durationMillis = 1500
                            0.3f at 0 using LinearEasing
                            0.9f at 750 using LinearEasing
                            0.3f at 1500 using LinearEasing
                        },
                ),
            label = "skeleton_alpha",
        )
    return this.graphicsLayer { this.alpha = alpha.value }
}

@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
) {
    Card(
        modifier = modifier.fillMaxWidth().height(height),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = 0.2f,
                        ),
                        MaterialTheme.shapes.extraSmall,
                    ).shimmerAnimation(),
        )
    }
}

@Composable
fun MetricCardSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 156.dp,
) {
    Card(
        modifier = modifier.fillMaxWidth().height(height),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .shimmerAnimation(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.2f,
                            ),
                            MaterialTheme.shapes.extraSmall,
                        ),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(32.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.2f,
                            ),
                            MaterialTheme.shapes.extraSmall,
                        ),
            )
        }
    }
}

@Composable
fun ScoreDialSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 156.dp,
) {
    Card(
        modifier = modifier.fillMaxWidth().height(height),
        shape = MaterialTheme.shapes.large,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.6f)
                        .height(14.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            MaterialTheme.shapes.extraSmall,
                        ).shimmerAnimation(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
                            MaterialTheme.shapes.large,
                        ).shimmerAnimation(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.72f)
                        .height(28.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f),
                            MaterialTheme.shapes.extraLarge,
                        ).shimmerAnimation(),
            )
        }
    }
}
