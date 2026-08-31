package app.readylytics.health.core.model.domain.githubissue

data class IssueReportRequest(
    val issueType: GitHubIssueType,
    val channel: ReportChannel,
    val hasCrashReport: Boolean,
    val includeLogcat: Boolean,
    val logcatDurationMinutes: Int,
)
