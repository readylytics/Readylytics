package app.readylytics.health.domain.vitals

import app.readylytics.health.data.preferences.SettingsDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VitalsChartManagementDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var delegateScope: CoroutineScope
    private lateinit var delegate: VitalsChartManagementDelegate
    private val persistedConfigs = mutableListOf<List<VitalsChartConfiguration>>()

    private val sampleConfigs =
        listOf(
            VitalsChartConfiguration(VitalsChartId.HRV_TREND, isVisible = true, position = 0),
            VitalsChartConfiguration(VitalsChartId.RHR_TREND, isVisible = true, position = 1),
            VitalsChartConfiguration(VitalsChartId.SPO2_TREND, isVisible = false, position = 2),
        )

    @Before
    fun setUp() {
        testScope = TestScope(testDispatcher)
        delegateScope = CoroutineScope(testDispatcher)
        delegate =
            VitalsChartManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_VITALS_CHARTS,
                persist = { persistedConfigs += it },
                scope = delegateScope,
            )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        delegateScope.cancel()
    }

    // --- 1. Initial state + editor-mode entry ---

    @Test
    fun `initial isManagingCharts is false`() {
        assertFalse(delegate.isManagingCharts.value)
    }

    @Test
    fun `initial pendingConfigs is null`() {
        assertNull(delegate.pendingConfigs.value)
    }

    @Test
    fun `enterEditMode populates pendingConfigs and sets managing true`() {
        delegate.enterEditMode(sampleConfigs)
        assertTrue(delegate.isManagingCharts.value)
        assertEquals(sampleConfigs, delegate.pendingConfigs.value)
    }

    // --- 2. saveChanges ---

    @Test
    fun `saveChanges clears editing state synchronously`() {
        delegate.enterEditMode(sampleConfigs)
        delegate.saveChanges()
        assertFalse(delegate.isManagingCharts.value)
        assertNull(delegate.pendingConfigs.value)
    }

    @Test
    fun `saveChanges persists pending configs exactly once via lambda`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.saveChanges()
            advanceUntilIdle()
            assertEquals(listOf(sampleConfigs), persistedConfigs)
        }

    @Test
    fun `saveChanges with no pending does not invoke persist`() =
        testScope.runTest {
            delegate.saveChanges()
            advanceUntilIdle()
            assertTrue(persistedConfigs.isEmpty())
        }

    // --- 3. cancelChanges ---

    @Test
    fun `cancelChanges clears editing state`() {
        delegate.enterEditMode(sampleConfigs)
        delegate.cancelChanges()
        assertFalse(delegate.isManagingCharts.value)
        assertNull(delegate.pendingConfigs.value)
    }

    @Test
    fun `cancelChanges does not persist`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.cancelChanges()
            advanceUntilIdle()
            assertTrue(persistedConfigs.isEmpty())
        }

    // --- 4. toggle visibility ---

    @Test
    fun `onToggleChartVisibility hides a visible chart`() {
        delegate.onToggleChartVisibility(sampleConfigs, VitalsChartId.HRV_TREND, visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertFalse(updated.first { it.chartId == VitalsChartId.HRV_TREND }.isVisible)
    }

    @Test
    fun `onToggleChartVisibility shows a hidden chart`() {
        delegate.onToggleChartVisibility(sampleConfigs, VitalsChartId.SPO2_TREND, visible = true)
        val updated = delegate.pendingConfigs.value!!
        assertTrue(updated.first { it.chartId == VitalsChartId.SPO2_TREND }.isVisible)
    }

    @Test
    fun `onToggleChartVisibility uses pendingConfigs when present`() {
        delegate.enterEditMode(sampleConfigs)
        delegate.onToggleChartVisibility(emptyList(), VitalsChartId.RHR_TREND, visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertEquals(sampleConfigs.size, updated.size)
        assertFalse(updated.first { it.chartId == VitalsChartId.RHR_TREND }.isVisible)
    }

    @Test
    fun `uncommitted toggle does not persist`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.onToggleChartVisibility(sampleConfigs, VitalsChartId.RHR_TREND, visible = false)
            advanceUntilIdle()
            assertTrue(persistedConfigs.isEmpty())
        }

    // --- 5. reorder ---

    @Test
    fun `onReorderCharts subset reorders and renumbers positions`() {
        val newOrder =
            listOf(
                sampleConfigs[1], // RHR_TREND first
                sampleConfigs[2], // SPO2_TREND second (hidden in base)
                sampleConfigs[0], // HRV_TREND third
            )
        delegate.onReorderCharts(sampleConfigs, newOrder)
        val updated = delegate.pendingConfigs.value!!
        assertEquals(VitalsChartId.RHR_TREND, updated[0].chartId)
        assertEquals(0, updated[0].position)
        assertEquals(VitalsChartId.SPO2_TREND, updated[1].chartId)
        assertEquals(1, updated[1].position)
        assertEquals(VitalsChartId.HRV_TREND, updated[2].chartId)
        assertEquals(2, updated[2].position)
    }

    @Test
    fun `onReorderCharts preserves charts outside the reorder list`() {
        val newOrder =
            listOf(
                sampleConfigs[1], // RHR_TREND
                sampleConfigs[0], // HRV_TREND
            )
        delegate.onReorderCharts(sampleConfigs, newOrder)
        val updated = delegate.pendingConfigs.value!!
        assertEquals(3, updated.size)
        // SPO2_TREND (hidden, not in newOrder) is preserved at the tail.
        assertEquals(VitalsChartId.SPO2_TREND, updated[2].chartId)
        assertFalse(updated[2].isVisible)
        assertEquals(2, updated[2].position)
    }

    // --- 6. reset to defaults ---

    @Test
    fun `onResetToDefaults sets pending to DEFAULT_VITALS_CHARTS`() {
        delegate.onResetToDefaults()
        assertEquals(SettingsDefaults.DEFAULT_VITALS_CHARTS, delegate.pendingConfigs.value)
    }

    @Test
    fun `onResetToDefaults does not persist by itself`() =
        testScope.runTest {
            delegate.onResetToDefaults()
            advanceUntilIdle()
            assertTrue(persistedConfigs.isEmpty())
        }

    @Test
    fun `reset then save persists default configs once`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.onResetToDefaults()
            delegate.saveChanges()
            advanceUntilIdle()
            assertEquals(listOf(SettingsDefaults.DEFAULT_VITALS_CHARTS), persistedConfigs)
        }

    // --- 7. State flow aggregation ---

    @Test
    fun `state flow emits combined state changes`() =
        testScope.runTest {
            val collector = delegate.state.onEach { }.launchIn(this)
            delegate.enterEditMode(sampleConfigs)
            advanceUntilIdle()
            assertTrue(delegate.state.value.isManagingCharts)
            assertEquals(sampleConfigs, delegate.state.value.pendingConfigs)
            collector.cancel()
        }

    @Test
    fun `save after edit persists final pending state exactly once`() =
        testScope.runTest {
            val collector = delegate.state.onEach { }.launchIn(this)
            delegate.enterEditMode(sampleConfigs)
            delegate.onToggleChartVisibility(sampleConfigs, VitalsChartId.HRV_TREND, visible = false)
            val pending = delegate.pendingConfigs.value!!
            delegate.onReorderCharts(pending, listOf(pending[1], pending[0], pending[2]))
            delegate.saveChanges()
            advanceUntilIdle()

            assertEquals(1, persistedConfigs.size)
            val saved = persistedConfigs.single()
            assertFalse(saved.first { it.chartId == VitalsChartId.HRV_TREND }.isVisible)
            assertEquals(VitalsChartId.RHR_TREND, saved[0].chartId)
            assertEquals(0, saved[0].position)
            collector.cancel()
        }

    // --- 8. Lifecycle ---

    @Test
    fun `scope cancellation stops state collection without errors`() =
        runTest {
            val localDispatcher = StandardTestDispatcher(testScheduler)
            val localScope = TestScope(localDispatcher)
            val localDelegate =
                VitalsChartManagementDelegate(
                    defaultConfigurations = SettingsDefaults.DEFAULT_VITALS_CHARTS,
                    persist = { },
                    scope = localScope,
                )

            localDelegate.enterEditMode(sampleConfigs)
            localScope.cancel()
            advanceUntilIdle()

            // State remains readable after cancel; no orphaned coroutines throw.
            assertTrue(localDelegate.isManagingCharts.value)
        }
}