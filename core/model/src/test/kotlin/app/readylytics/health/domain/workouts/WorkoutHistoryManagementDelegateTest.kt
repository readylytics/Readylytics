package app.readylytics.health.domain.workouts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutHistoryManagementDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var delegateScope: CoroutineScope
    private lateinit var delegate: WorkoutHistoryManagementDelegate
    private val persistedConfigs = mutableListOf<List<WorkoutHistoryConfiguration>>()

    private val defaultConfigs =
        listOf(
            WorkoutHistoryConfiguration(WorkoutHistoryId.WORKOUT_LIST, isVisible = true, position = 0),
        )

    @Before
    fun setUp() {
        testScope = TestScope(testDispatcher)
        delegateScope = CoroutineScope(testDispatcher)
        delegate =
            WorkoutHistoryManagementDelegate(
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
    fun `initial isManagingHistory is false`() {
        assertFalse(delegate.isManagingHistory.value)
    }

    @Test
    fun `onToggleHistoryVisibility hides workout list`() {
        delegate.onToggleHistoryVisibility(defaultConfigs, WorkoutHistoryId.WORKOUT_LIST, visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertFalse(updated.first { it.historyId == WorkoutHistoryId.WORKOUT_LIST }.isVisible)
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
    fun `onResetToDefaults sets pending to defaultConfigurations`() {
        delegate.onResetToDefaults()
        assertEquals(defaultConfigs, delegate.pendingConfigs.value)
    }
}
