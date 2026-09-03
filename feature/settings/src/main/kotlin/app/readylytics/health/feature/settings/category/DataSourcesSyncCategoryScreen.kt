package app.readylytics.health.feature.settings.category

import androidx.compose.runtime.Composable
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.data.DataManagementSection
import app.readylytics.health.feature.settings.data.DataSourceSettingsSection
import app.readylytics.health.feature.settings.data.SyncSettingsSection
import app.readylytics.health.feature.settings.nav.SettingsCategoryId
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.search.SettingsItemIds

@Composable
internal fun DataSourcesSyncCategoryScreen(
    states: SettingsStates,
    intents: SettingsIntents,
    highlightItemId: String?,
    onNavigateBack: () -> Unit = {},
) {
    SettingsCategoryScaffold(
        titleRes = SettingsCategoryId.DATA_SOURCES_SYNC.titleRes,
        items =
            listOf(
                SettingsCategoryListItem(SettingsItemIds.DATA_DEVICE_SOURCES) {
                    DataSourceSettingsSection()
                },
                SettingsCategoryListItem(SettingsItemIds.DATA_HEALTH_CONNECT_SYNC) {
                    SyncSettingsSection(uiState = states.syncState, onEvent = intents.onSyncEvent)
                },
                SettingsCategoryListItem(SettingsItemIds.DATA_RETENTION_RESYNC) {
                    DataManagementSection(
                        uiState = states.uiState,
                        isResyncing = states.syncState.isResyncing,
                        onEvent = intents.onUIEvent,
                        onSyncEvent = intents.onSyncEvent,
                    )
                },
            ),
        highlightItemId = highlightItemId,
        onNavigateBack = onNavigateBack,
    )
}
