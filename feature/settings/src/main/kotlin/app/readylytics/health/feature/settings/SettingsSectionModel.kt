package app.readylytics.health.feature.settings

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SettingsExpandState(
    val genderExpanded: Boolean = false,
    val collapseDataBackup: Boolean = false,
    val collapseDataSources: Boolean = false,
    val collapseBaselinesThresholds: Boolean = false,
    val collapseDisplay: Boolean = false,
    val collapseAdvanced: Boolean = false,
    val collapseMiscellaneous: Boolean = false,
    val aboutDismissed: Boolean = false,
) : Parcelable

data class SettingsSectionMetadata(
    val id: String,
    val name: String,
    val keywords: List<String>,
)

val settingsSections =
    listOf(
        SettingsSectionMetadata(
            id = "data_backup_sync",
            name = "Data & Backup",
            keywords =
                listOf(
                    "backup",
                    "local",
                    "data",
                    "retention",
                    "resync",
                    "health connect",
                    "sync",
                    "foreground",
                ),
        ),
        SettingsSectionMetadata(
            id = "data_sources",
            name = "Data Sources",
            keywords =
                listOf(
                    "device",
                    "source",
                    "data source",
                    "steps",
                    "activity",
                    "vitals",
                    "sleep",
                    "body",
                    "heart rate",
                    "phone",
                    "watch",
                ),
        ),
        SettingsSectionMetadata(
            id = "baselines_thresholds",
            name = "Baselines & Thresholds",
            keywords =
                listOf(
                    "step",
                    "goal",
                    "sleep",
                    "hrv",
                    "rhr",
                    "heart rate",
                    "zone",
                    "baseline",
                    "threshold",
                    "consistency",
                ),
        ),
        SettingsSectionMetadata(
            id = "display",
            name = "Display",
            keywords = listOf("appearance", "theme"),
        ),
        SettingsSectionMetadata(
            id = "advanced",
            name = "Advanced",
            keywords = listOf("advanced", "override", "ras", "resting", "hr timing"),
        ),
        SettingsSectionMetadata(
            id = "miscellaneous",
            name = "Miscellaneous",
            keywords = listOf("miscellaneous", "about", "licenses", "open source", "legal"),
        ),
    )

fun sectionMatches(
    section: SettingsSectionMetadata,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    val lowerQuery = query.lowercase()
    return section.name.lowercase().contains(lowerQuery) ||
        section.keywords.any { it.contains(lowerQuery) }
}
