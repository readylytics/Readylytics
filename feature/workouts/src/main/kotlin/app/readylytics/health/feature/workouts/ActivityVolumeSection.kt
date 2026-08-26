package app.readylytics.health.feature.workouts

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.readylytics.health.core.designsystem.LocalStatusColors
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.scoring.domain.workouts.weekly.ActivityMetricType
import app.readylytics.health.core.scoring.domain.workouts.weekly.ActivityVolume
import app.readylytics.health.core.scoring.domain.workouts.weekly.WeeklyTrainingStats
import app.readylytics.health.core.ui.common.SkeletonCard
import app.readylytics.health.core.ui.components.SectionHeader
import kotlin.math.roundToInt

/** Number of rows shown inline before the rest move behind "View all". */
private const val INLINE_ROW_LIMIT = 3

/**
 * Per-activity-type training volume this week vs last week, ranked by this week's share of total
 * training time. Top [INLINE_ROW_LIMIT] types inline, everything else behind "View all"; renders
 * nothing once loaded when the week had no workouts.
 */
@Composable
fun ActivityVolumeSection(
    stats: WeeklyTrainingStats?,
    isLoading: Boolean,
    unitSystem: UnitSystem,
    modifier: Modifier = Modifier,
    hasDistancePermission: Boolean = true,
) {
    var showAllSheet by rememberSaveable { mutableStateOf(false) }
    val rows = remember(stats) { stats?.let(::buildActivityVolumeRows).orEmpty() }

    if (!isLoading && stats != null && rows.isEmpty()) return

    Column(modifier = modifier) {
        Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
        when {
            isLoading || stats == null -> ActivityVolumeSkeleton()
            rows.isNotEmpty() -> {
                val missingDistance =
                    !hasDistancePermission && rows.any { it.metricType == ActivityMetricType.DISTANCE }
                if (missingDistance) {
                    DistancePermissionBanner(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    )
                    Spacer(Modifier.height(MaterialTheme.spacing.extraSmall))
                }
                ActivityVolumeRows(
                    rows = rows.take(INLINE_ROW_LIMIT),
                    hasMore = rows.size > INLINE_ROW_LIMIT,
                    unitSystem = unitSystem,
                    onShowAll = { showAllSheet = true },
                )
            }
        }
    }

    if (showAllSheet && rows.isNotEmpty()) {
        ActivityVolumeBottomSheet(
            rows = rows,
            unitSystem = unitSystem,
            onDismiss = { showAllSheet = false },
        )
    }
}

/** Shown when the optional READ_DISTANCE permission is missing while distance-type activities are
 *  listed — otherwise every such row silently renders "—". Offers the Health Connect deep link. */
@Composable
private fun DistancePermissionBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(MaterialTheme.spacing.medium),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(MaterialTheme.spacing.smallMedium))
            Text(
                text = stringResource(R.string.activity_volume_distance_permission_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { openHealthConnectPermissions(context) }) {
                Text(stringResource(R.string.activity_volume_distance_permission_action))
            }
        }
    }
}

private const val ACTION_MANAGE_HEALTH_PERMISSIONS = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"

private fun openHealthConnectPermissions(context: Context) {
    runCatching {
        context.startActivity(
            Intent(ACTION_MANAGE_HEALTH_PERMISSIONS)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName),
        )
    }
}

@Composable
private fun ActivityVolumeSkeleton() {
    SectionHeader(
        title = stringResource(R.string.activity_volume_title),
        enabled = false,
    )
    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
    SkeletonCard(
        height = 260.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
    )
}

@Composable
private fun ActivityVolumeRows(
    rows: List<ActivityVolume>,
    hasMore: Boolean,
    unitSystem: UnitSystem,
    onShowAll: () -> Unit,
) {
    SectionHeader(
        title = stringResource(R.string.activity_volume_title),
        trailingContent =
            if (hasMore) {
                {
                    TextButton(onClick = onShowAll) {
                        Text(stringResource(R.string.activity_volume_view_all))
                    }
                }
            } else {
                null
            },
    )
    Spacer(Modifier.height(MaterialTheme.spacing.pageSectionGapSmall))
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.pageHorizontal),
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            rows.forEachIndexed { index, volume ->
                ActivityVolumeRow(volume = volume, unitSystem = unitSystem)
                if (index < rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.medium),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ActivityVolumeRow(
    volume: ActivityVolume,
    unitSystem: UnitSystem,
) {
    ListItem(
        headlineContent = {
            Text(text = stringResource(volume.activityType.displayNameResId))
        },
        supportingContent = {
            Text(
                text =
                    stringResource(
                        R.string.activity_volume_last_week,
                        ActivityVolumeFormatter.formatValue(
                            volume.previousWeekValue,
                            volume.metricType,
                            unitSystem,
                        ),
                    ),
            )
        },
        leadingContent = {
            Icon(
                imageVector = volume.activityType.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text =
                        ActivityVolumeFormatter.formatValue(
                            volume.currentWeekValue,
                            volume.metricType,
                            unitSystem,
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                val delta = activityVolumeDeltaDisplay(volume)
                Text(text = delta.text, style = MaterialTheme.typography.labelMedium, color = delta.color)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Same delta treatment as the Weekly training cards (HIGHER_IS_BETTER); a type with no
 *  previous-week volume shows a plain dash instead of a misleading percentage. */
@Composable
private fun activityVolumeDeltaDisplay(volume: ActivityVolume): WeeklyDeltaDisplay {
    val detail = ActivityVolumeFormatter.formatPercentDelta(volume.percentChange)
    if (detail == null) {
        return WeeklyDeltaDisplay(
            text = stringResource(R.string.activity_volume_delta_missing),
            color = LocalStatusColors.current.neutral,
        )
    }
    return weeklyDeltaDisplay(
        current = volume.currentWeekValue.roundToInt(),
        previous = volume.previousWeekValue.roundToInt(),
        detail = detail,
    )
}
