package app.readylytics.health.core.model.domain.layout

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

private data class FakeReorderableConfig(
    val configId: String,
    override val isVisible: Boolean = true,
    override val position: Int = 0,
) : ReorderableItem<String> {
    override val id: String get() = configId
}

@OptIn(ExperimentalCoroutinesApi::class)
class LayoutManagementDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var delegateScope: CoroutineScope
    private lateinit var delegate: LayoutManagementDelegate<FakeReorderableConfig, String>
    private val persistedConfigs = mutableListOf<List<FakeReorderableConfig>>()

    private val defaults =
        listOf(
            FakeReorderableConfig("a", position = 0),
            FakeReorderableConfig("b", position = 1),
            FakeReorderableConfig("c", isVisible = false, position = 2),
        )

    @Before
    fun setUp() {
        testScope = TestScope(testDispatcher)
        delegateScope = CoroutineScope(testDispatcher)
        delegate =
            LayoutManagementDelegate(
                defaultConfigurations = defaults,
                persist = { persistedConfigs += it },
                scope = delegateScope,
                withVisibility = { config, visible -> config.copy(isVisible = visible) },
                withPosition = { config, pos -> config.copy(position = pos) },
            )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        delegateScope.cancel()
    }

    @Test
    fun `initial isManaging is false`() {
        assertFalse(delegate.isManaging.value)
    }

    @Test
    fun `initial pendingConfigs is null`() {
        assertNull(delegate.pendingConfigs.value)
    }

    @Test
    fun `enterEditMode populates pendingConfigs and sets managing true`() {
        delegate.enterEditMode(defaults)
        assertTrue(delegate.isManaging.value)
        assertEquals(defaults, delegate.pendingConfigs.value)
    }

    @Test
    fun `saveChanges clears editing state synchronously`() {
        delegate.enterEditMode(defaults)
        delegate.saveChanges()
        assertFalse(delegate.isManaging.value)
        assertNull(delegate.pendingConfigs.value)
    }

    @Test
    fun `saveChanges persists pending configs exactly once via lambda`() =
        testScope.runTest {
            delegate.enterEditMode(defaults)
            delegate.saveChanges()
            advanceUntilIdle()
            assertEquals(listOf(defaults), persistedConfigs)
        }

    @Test
    fun `saveChanges with no pending does not invoke persist`() =
        testScope.runTest {
            delegate.saveChanges()
            advanceUntilIdle()
            assertTrue(persistedConfigs.isEmpty())
        }

    @Test
    fun `cancelChanges clears editing state without persisting`() =
        testScope.runTest {
            delegate.enterEditMode(defaults)
            delegate.cancelChanges()
            advanceUntilIdle()
            assertFalse(delegate.isManaging.value)
            assertNull(delegate.pendingConfigs.value)
            assertTrue(persistedConfigs.isEmpty())
        }

    @Test
    fun `onToggleVisibility flips the target entry only`() {
        delegate.onToggleVisibility(defaults, "a", visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertFalse(updated.first { it.id == "a" }.isVisible)
        assertTrue(updated.first { it.id == "b" }.isVisible)
    }

    @Test
    fun `onToggleVisibility uses pendingConfigs when present`() {
        delegate.enterEditMode(defaults)
        delegate.onToggleVisibility(emptyList(), "b", visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertEquals(defaults.size, updated.size)
        assertFalse(updated.first { it.id == "b" }.isVisible)
    }

    @Test
    fun `onReorder subset reorders and renumbers positions, preserving items outside the reorder list`() {
        val newOrder = listOf(defaults[1], defaults[0])
        delegate.onReorder(defaults, newOrder)
        val updated = delegate.pendingConfigs.value!!
        assertEquals(3, updated.size)
        assertEquals("b", updated[0].id)
        assertEquals(0, updated[0].position)
        assertEquals("a", updated[1].id)
        assertEquals(1, updated[1].position)
        assertEquals("c", updated[2].id)
        assertEquals(2, updated[2].position)
        assertFalse(updated[2].isVisible)
    }

    @Test
    fun `onResetToDefaults sets pending to the injected defaults`() {
        delegate.onResetToDefaults()
        assertEquals(defaults, delegate.pendingConfigs.value)
    }

    @Test
    fun `reset then save persists default configs once`() =
        testScope.runTest {
            delegate.enterEditMode(defaults)
            delegate.onResetToDefaults()
            delegate.saveChanges()
            advanceUntilIdle()
            assertEquals(listOf(defaults), persistedConfigs)
        }

    @Test
    fun `state flow aggregates isManaging and pendingConfigs`() =
        testScope.runTest {
            val collector = delegate.state.onEach { }.launchIn(this)
            delegate.enterEditMode(defaults)
            advanceUntilIdle()
            assertTrue(delegate.state.value.isManaging)
            assertEquals(defaults, delegate.state.value.pendingConfigs)
            collector.cancel()
        }

    @Test
    fun `scope cancellation stops state collection without errors`() =
        runTest {
            val localDispatcher = StandardTestDispatcher(testScheduler)
            val localScope = TestScope(localDispatcher)
            val localDelegate =
                LayoutManagementDelegate(
                    defaultConfigurations = defaults,
                    persist = { },
                    scope = localScope,
                    withVisibility = { config, visible -> config.copy(isVisible = visible) },
                    withPosition = { config, pos -> config.copy(position = pos) },
                )

            localDelegate.enterEditMode(defaults)
            localScope.cancel()
            advanceUntilIdle()

            assertTrue(localDelegate.isManaging.value)
        }
}
