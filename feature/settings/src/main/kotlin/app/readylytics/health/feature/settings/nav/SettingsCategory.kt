package app.readylytics.health.feature.settings.nav

import androidx.annotation.Keep
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Palette
import androidx.compose.ui.graphics.vector.ImageVector
import app.readylytics.health.feature.settings.R
import kotlinx.serialization.Serializable

@Keep
@Serializable
enum class SettingsCategoryId(
    val titleRes: Int,
    val subtitleRes: Int,
    val icon: ImageVector,
) {
    PHYSIOLOGY_PROFILE(
        R.string.settings_category_physiology_profile_title,
        R.string.settings_category_physiology_profile_subtitle,
        Icons.Filled.MonitorHeart,
    ),
    SLEEP(
        R.string.settings_category_sleep_title,
        R.string.settings_category_sleep_subtitle,
        Icons.Filled.NightsStay,
    ),
    TRAINING(
        R.string.settings_category_training_title,
        R.string.settings_category_training_subtitle,
        Icons.Filled.DirectionsRun,
    ),
    VITALS(
        R.string.settings_category_vitals_title,
        R.string.settings_category_vitals_subtitle,
        Icons.Filled.Bloodtype,
    ),
    DATA_SOURCES_SYNC(
        R.string.settings_category_data_sync_title,
        R.string.settings_category_data_sync_subtitle,
        Icons.Filled.Devices,
    ),
    BACKUP_RESTORE(
        R.string.settings_category_backup_title,
        R.string.settings_category_backup_subtitle,
        Icons.Filled.Backup,
    ),
    DISPLAY(
        R.string.settings_category_display_title,
        R.string.settings_category_display_subtitle,
        Icons.Filled.Palette,
    ),
    SUPPORT_ABOUT(
        R.string.settings_category_support_title,
        R.string.settings_category_support_subtitle,
        Icons.Filled.HelpOutline,
    ),
}

sealed interface SettingsDestination {
    @Serializable
    data object Home : SettingsDestination

    @Serializable
    data class Category(
        val id: SettingsCategoryId,
        val highlightItemId: String? = null,
    ) : SettingsDestination
}
