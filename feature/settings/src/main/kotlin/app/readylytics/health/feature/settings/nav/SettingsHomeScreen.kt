package app.readylytics.health.feature.settings.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.readylytics.health.core.designsystem.spacing
import app.readylytics.health.feature.settings.R
import app.readylytics.health.feature.settings.search.ResolvedSearchItem
import app.readylytics.health.feature.settings.search.allSettingsSearchItems
import app.readylytics.health.feature.settings.search.matchSettingsItems

@Composable
fun SettingsHomeScreen(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    onCategorySelected: (SettingsCategoryId) -> Unit,
    onSearchResultSelected: (ResolvedSearchItem) -> Unit,
) {
    val resolvedItems =
        allSettingsSearchItems.map {
            ResolvedSearchItem(
                id = it.id,
                categoryId = it.categoryId,
                label = stringResource(it.labelRes),
                keywords = it.keywords,
            )
        }
    val results = matchSettingsItems(resolvedItems, searchQuery)

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsSearchBar(searchQuery = searchQuery, onSearchQueryChanged = onSearchQueryChanged)
        if (searchQuery.isBlank()) {
            CategoryList(onCategorySelected = onCategorySelected)
        } else {
            SearchResultsList(results = results, onSearchResultSelected = onSearchResultSelected)
        }
    }
}

@Composable
private fun SettingsSearchBar(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChanged,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MaterialTheme.spacing.pageHorizontal,
                    vertical = MaterialTheme.spacing.pageSectionGapSmall,
                ),
        placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.accessibility_search))
        },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChanged("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.accessibility_clear))
                }
            }
        },
        shape = MaterialTheme.shapes.large,
        singleLine = true,
    )
}

@Composable
private fun CategoryList(onCategorySelected: (SettingsCategoryId) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = SettingsCategoryId.entries, key = { it.name }) { category ->
            ListItem(
                headlineContent = { Text(stringResource(category.titleRes)) },
                supportingContent = { Text(stringResource(category.subtitleRes)) },
                leadingContent = { Icon(category.icon, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickableSettingsRow { onCategorySelected(category) },
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    results: List<ResolvedSearchItem>,
    onSearchResultSelected: (ResolvedSearchItem) -> Unit,
) {
    if (results.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_search_no_results),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(MaterialTheme.spacing.pageHorizontal),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = results, key = { it.id }) { result ->
            ListItem(
                headlineContent = { Text(result.label) },
                supportingContent = { Text(stringResource(result.categoryId.titleRes)) },
                modifier = Modifier.fillMaxWidth().clickableSettingsRow { onSearchResultSelected(result) },
            )
        }
    }
}

private fun Modifier.clickableSettingsRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
