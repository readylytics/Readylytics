package app.readylytics.health.feature.settings.category

import androidx.compose.runtime.Composable
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.backup.LocalBackupSection
import app.readylytics.health.feature.settings.nav.SettingsCategoryId
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.search.SettingsItemIds

@Composable
internal fun BackupRestoreCategoryScreen(
    states: SettingsStates,
    intents: SettingsIntents,
    highlightItemId: String?,
    onNavigateBack: () -> Unit = {},
) {
    SettingsCategoryScaffold(
        titleRes = SettingsCategoryId.BACKUP_RESTORE.titleRes,
        items =
            listOf(
                SettingsCategoryListItem(SettingsItemIds.BACKUP_RESTORE) {
                    LocalBackupSection(uiState = states.localBackupState, onEvent = intents.onLocalBackupEvent)
                },
            ),
        highlightItemId = highlightItemId,
        onNavigateBack = onNavigateBack,
    )
}
