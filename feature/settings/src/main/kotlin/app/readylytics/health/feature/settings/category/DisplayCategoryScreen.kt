package app.readylytics.health.feature.settings.category

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.core.ui.components.settings.WeekStartDayPicker
import app.readylytics.health.core.ui.settings.common.UnitSystemSelector
import app.readylytics.health.feature.settings.AppThemeItem
import app.readylytics.health.feature.settings.DashboardCardsSettingsSection
import app.readylytics.health.feature.settings.DynamicColorSettings
import app.readylytics.health.feature.settings.SettingsEvent
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.SettingsStates
import app.readylytics.health.feature.settings.WorkoutDetailLayoutSettingsSection
import app.readylytics.health.feature.settings.nav.SettingsCategoryId
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.search.SettingsItemIds

@Composable
internal fun DisplayCategoryScreen(
    states: SettingsStates,
    intents: SettingsIntents,
    highlightItemId: String?,
) {
    val uiState = states.uiState
    SettingsCategoryScaffold(
        titleRes = SettingsCategoryId.DISPLAY.titleRes,
        items =
            listOf(
                SettingsCategoryListItem(SettingsItemIds.DISPLAY_APP_THEME) {
                    AppThemeItem(uiState = uiState, onEvent = intents.onUIEvent)
                },
                SettingsCategoryListItem(SettingsItemIds.DISPLAY_DYNAMIC_COLOR_PALETTE) {
                    DynamicColorSettings(uiState = uiState, onUIEvent = intents.onUIEvent)
                },
                SettingsCategoryListItem(SettingsItemIds.DISPLAY_UNIT_SYSTEM) {
                    UnitSystemSelector(
                        selectedUnit = uiState.unitSystem,
                        onUnitSelected = { intents.onUIEvent(SettingsEvent.UnitSystemChanged(it)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.DISPLAY_WEEK_START_DAY) {
                    WeekStartDayPicker(
                        selectedDay = uiState.weekStartDay,
                        onDaySelected = { intents.onUIEvent(SettingsEvent.WeekStartDayChanged(it)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.pageHorizontal),
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.DISPLAY_DASHBOARD_CARDS) {
                    DashboardCardsSettingsSection(
                        uiState = states.dashboardCardsState,
                        onEvent = intents.onDashboardCardsEvent,
                    )
                },
                SettingsCategoryListItem(SettingsItemIds.DISPLAY_WORKOUT_LAYOUT) {
                    WorkoutDetailLayoutSettingsSection(onEvent = intents.onUIEvent)
                },
            ),
        highlightItemId = highlightItemId,
    )
}
