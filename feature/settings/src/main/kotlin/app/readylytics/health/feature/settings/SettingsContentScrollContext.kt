package app.readylytics.health.feature.settings

/**
 * Wrapper for settings content scroll context to reduce parameter list length.
 */
internal data class SettingsContentScrollContext(
    val states: SettingsStates,
    val intents: SettingsIntents,
    val searchQuery: String,
    val onSearchQueryChanged: (String) -> Unit,
    val matchingSections: List<SettingsSectionMetadata>,
    val expandState: SettingsExpandState,
    val shouldExpandSection: (String) -> Boolean,
    val controlsEnabled: Boolean,
    val onExpandStateChange: (SettingsExpandState) -> Unit,
    val onReportTypeSelected: (app.readylytics.health.core.model.domain.githubissue.GitHubIssueType) -> Unit,
)
