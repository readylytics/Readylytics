package com.gregor.lauritz.healthdashboard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardConfiguration
import com.gregor.lauritz.healthdashboard.domain.dashboard.CardId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardManagementBottomSheet(
    cards: List<CardConfiguration>,
    onCardVisibilityChanged: (CardId, Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Manage Cards",
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = onResetToDefaults) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = "Reset to defaults",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            LazyColumn {
                items(cards.sortedBy { it.position }) { card ->
                    CardManagementItem(
                        card = card,
                        onVisibilityChanged = { visible ->
                            onCardVisibilityChanged(card.cardId, visible)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 16.dp),
            ) {
                Text("Done")
            }
        }
    }
}

@Composable
private fun CardManagementItem(
    card: CardConfiguration,
    onVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = card.cardId.name.replace("_", " ")
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = card.isVisible,
            onCheckedChange = onVisibilityChanged,
        )
    }
}
