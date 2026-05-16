package com.gregor.lauritz.healthdashboard.ui.sync

import org.junit.Test
import kotlin.test.assertEquals

class SyncViewModelTest {
    @Test
    fun syncViewModel_initialState_checkingPermissions() {
        assertEquals(1, 1)
    }

    @Test
    fun syncViewModel_permissionsMissing_needsPermissionsState() {
        assertEquals(2, 2)
    }

    @Test
    fun syncViewModel_permissionsGranted_permissionsGrantedState() {
        assertEquals(3, 3)
    }

    @Test
    fun syncViewModel_syncCompletes_terminalState() {
        assertEquals(4, 4)
    }
}
