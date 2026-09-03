package app.readylytics.health.feature.settings.category

import androidx.compose.runtime.Composable
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.data.DataManagementSection
import app.readylytics.health.feature.settings.data.DataSourceSettingsSection
import app.readylytics.health.feature.settings.data.SyncSettingsSection
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.search.SettingsItemIds

/**
 * [controlsEnabled] is unused in this screen's body: none of [DataSourceSettingsSection],
 * [SyncSettingsSection], or [DataManagementSection] expose an external disable-during-resync
 * hook (matching their pre-split behavior — see `SettingsSectionWrappers.kt`/
 * `SettingsSectionComposables.kt`). The parameter is kept to match this category screen's
 * plan-specified signature, which the nav-graph wiring (a later task) is expected to call
 * uniformly alongside sibling category screens that DO use it. No structural fix removes the
 * parameter without risking a signature mismatch at that future call site — flagging the
 * suppression below for explicit human sign-off per project policy.
 */
@Suppress("UnusedParameter")
@Composable
internal fun DataSourcesSyncCategoryScreen(
    states: SettingsStates,
    intents: SettingsIntents,
    controlsEnabled: Boolean,
    highlightItemId: String?,
) {
    SettingsCategoryScaffold(
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
    )
}
