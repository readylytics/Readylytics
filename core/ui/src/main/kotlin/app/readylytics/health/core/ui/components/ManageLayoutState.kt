package app.readylytics.health.core.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/** State for a manage-layout bottom sheet shared by the dashboard/vitals/sleep screens. */
@OptIn(ExperimentalMaterial3Api::class)
class ManageLayoutState(
    val sheetState: SheetState,
    val isManageOpen: Boolean,
    val openManage: () -> Unit,
    val closeManage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberManageLayoutState(): ManageLayoutState {
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
        )
    var isManageOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    return ManageLayoutState(
        sheetState = sheetState,
        isManageOpen = isManageOpen,
        openManage = { isManageOpen = true },
        closeManage = {
            scope.launch { sheetState.hide() }
            isManageOpen = false
        },
    )
}
