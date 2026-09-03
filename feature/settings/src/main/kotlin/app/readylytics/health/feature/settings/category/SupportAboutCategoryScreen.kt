package app.readylytics.health.feature.settings.category

import androidx.compose.runtime.Composable
import app.readylytics.health.core.model.domain.githubissue.GitHubIssueType
import app.readylytics.health.feature.settings.IssueReportingSection
import app.readylytics.health.feature.settings.MiscellaneousSection
import app.readylytics.health.feature.settings.SettingsIntents
import app.readylytics.health.feature.settings.nav.SettingsCategoryListItem
import app.readylytics.health.feature.settings.nav.SettingsCategoryScaffold
import app.readylytics.health.feature.settings.search.SettingsItemIds

@Composable
internal fun SupportAboutCategoryScreen(
    intents: SettingsIntents,
    onReportTypeSelected: (GitHubIssueType) -> Unit,
    highlightItemId: String?,
) {
    SettingsCategoryScaffold(
        items =
            listOf(
                SettingsCategoryListItem(SettingsItemIds.SUPPORT_ISSUE_REPORTING) {
                    IssueReportingSection(onReportTypeSelected = onReportTypeSelected)
                },
                SettingsCategoryListItem(SettingsItemIds.SUPPORT_ABOUT) {
                    MiscellaneousSection(
                        onNavigateToAbout = intents.onNavigateToAbout,
                        onNavigateToLicenses = intents.onNavigateToLicenses,
                        onOpenPrivacyPolicy = intents.onOpenPrivacyPolicy,
                        onOpenSourceCode = intents.onOpenSourceCode,
                    )
                },
            ),
        highlightItemId = highlightItemId,
    )
}
