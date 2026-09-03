package app.readylytics.health.feature.settings.search

import app.readylytics.health.feature.settings.nav.SettingsCategoryId

data class ResolvedSearchItem(
    val id: String,
    val categoryId: SettingsCategoryId,
    val label: String,
    val keywords: List<String> = emptyList(),
)

fun matchSettingsItems(
    items: List<ResolvedSearchItem>,
    query: String,
): List<ResolvedSearchItem> {
    if (query.isBlank()) return emptyList()
    val normalizedQuery = query.trim().lowercase()
    return items.filter { item ->
        item.label.lowercase().contains(normalizedQuery) ||
            item.keywords.any { it.lowercase().contains(normalizedQuery) }
    }
}
