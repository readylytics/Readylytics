package app.readylytics.health.domain.workouts

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
class WorkoutChartManagementDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var delegateScope: CoroutineScope
    private lateinit var delegate: WorkoutChartManagementDelegate
    private val persistedConfigs = mutableListOf<List<WorkoutChartConfiguration>>()

    private val defaultConfigs =
        listOf(WorkoutChartConfiguration(WorkoutChartId.ACWR_TRIMP, isVisible = true, position = 0))

    @Before
    fun setUp() {
        testScope = TestScope(testDispatcher)
        delegateScope = CoroutineScope(testDispatcher)
        delegate =
            WorkoutChartManagementDelegate(
                defaultConfigurations = defaultConfigs,
                persist = { persistedConfigs += it },
                scope = delegateScope,
            )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        delegateScope.cancel()
    }

    @Test
    fun `initial isManagingCharts is false`() {
        assertFalse(delegate.isManagingCharts.value)
    }

    @Test
    fun `enterEditMode populates pendingConfigs and sets managing true`() {
        delegate.enterEditMode(defaultConfigs)
        assertTrue(delegate.isManagingCharts.value)
        assertEquals(defaultConfigs, delegate.pendingConfigs.value)
    }

    @Test
    fun `saveChanges persists pending configs exactly once via lambda`() =
        testScope.runTest {
            delegate.enterEditMode(defaultConfigs)
            delegate.saveChanges()
            advanceUntilIdle()
            assertEquals(listOf(defaultConfigs), persistedConfigs)
        }

    @Test
    fun `cancelChanges discards pending changes without persisting`() =
        testScope.runTest {
            delegate.enterEditMode(defaultConfigs)
            delegate.onToggleChartVisibility(defaultConfigs, WorkoutChartId.ACWR_TRIMP, visible = false)
            delegate.cancelChanges()
            advanceUntilIdle()
            assertFalse(delegate.isManagingCharts.value)
            assertNull(delegate.pendingConfigs.value)
            assertTrue(persistedConfigs.isEmpty())
        }

    @Test
    fun `onToggleChartVisibility hides a visible chart`() {
        delegate.onToggleChartVisibility(defaultConfigs, WorkoutChartId.ACWR_TRIMP, visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertFalse(updated.first { it.chartId == WorkoutChartId.ACWR_TRIMP }.isVisible)
    }

    @Test
    fun `onResetToDefaults sets pending to defaultConfigurations`() {
        delegate.onResetToDefaults()
        assertEquals(defaultConfigs, delegate.pendingConfigs.value)
    }

    @Test
    fun `state flow emits combined state changes`() =
        testScope.runTest {
            val collector = delegate.state.onEach { }.launchIn(this)
            delegate.enterEditMode(defaultConfigs)
            advanceUntilIdle()
            assertTrue(delegate.state.value.isManagingCharts)
            assertEquals(defaultConfigs, delegate.state.value.pendingConfigs)
            collector.cancel()
        }
}
