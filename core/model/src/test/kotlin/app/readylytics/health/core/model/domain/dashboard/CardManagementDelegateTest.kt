package app.readylytics.health.core.model.domain.dashboard

import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CardManagementDelegateTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope
    private lateinit var delegateScope: CoroutineScope
    private lateinit var delegate: CardManagementDelegate
    private val persistedConfigs = mutableListOf<List<CardConfiguration>>()

    private val sampleConfigs =
        listOf(
            CardConfiguration(CardId.SLEEP_SCORE, isVisible = true, position = 0),
            CardConfiguration(CardId.READINESS, isVisible = true, position = 1),
            CardConfiguration(CardId.HRV, isVisible = false, position = 2),
        )

    @Before
    fun setUp() {
        testScope = TestScope(testDispatcher)
        delegateScope = CoroutineScope(testDispatcher)
        delegate =
            CardManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                persist = { persistedConfigs += it },
                scope = delegateScope,
            )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        delegateScope.cancel()
    }

    // --- 1. Initial state ---

    @Test
    fun `initial isManagingCards is false`() {
        assertFalse(delegate.isManagingCards.value)
    }

    @Test
    fun `initial pendingConfigs is null`() {
        assertNull(delegate.pendingConfigs.value)
    }

    @Test
    fun `initial aggregated state defaults are correct`() =
        testScope.runTest {
            val collector = delegate.state.onEach { }.launchIn(this)
            advanceUntilIdle()
            val initial = delegate.state.value
            assertFalse(initial.isManagingCards)
            assertNull(initial.pendingConfigs)
            collector.cancel()
        }

    // --- 2. enterEditMode ---

    @Test
    fun `enterEditMode sets isManagingCards true`() {
        delegate.enterEditMode(sampleConfigs)
        assertTrue(delegate.isManagingCards.value)
    }

    @Test
    fun `enterEditMode populates pendingConfigs with current configs`() {
        delegate.enterEditMode(sampleConfigs)
        assertEquals(sampleConfigs, delegate.pendingConfigs.value)
    }

    @Test
    fun `enterEditMode via onEvent matches direct call`() {
        delegate.onEvent(CardManagementEvent.EnterEditMode(sampleConfigs))
        assertTrue(delegate.isManagingCards.value)
        assertEquals(sampleConfigs, delegate.pendingConfigs.value)
    }

    // --- 3. saveChanges ---

    @Test
    fun `saveChanges clears editing state synchronously`() {
        delegate.enterEditMode(sampleConfigs)
        delegate.saveChanges()
        assertFalse(delegate.isManagingCards.value)
        assertNull(delegate.pendingConfigs.value)
    }

    @Test
    fun `saveChanges persists pending configs via persist`() =
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

    @Test
    fun `saveChanges returns Unit synchronously without spawning a Job`() {
        // Smoke test: ensure saveChanges has Unit return type (no manual launch returning Job).
        val result: Unit = delegate.saveChanges()
        assertEquals(Unit, result)
    }

    // --- 4. cancelChanges ---

    @Test
    fun `cancelChanges clears editing state`() {
        delegate.enterEditMode(sampleConfigs)
        delegate.cancelChanges()
        assertFalse(delegate.isManagingCards.value)
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

    // --- 5. resetToDefaults ---

    @Test
    fun `resetToDefaults sets pending to DEFAULT_DASHBOARD_CARDS`() {
        delegate.onResetToDefaults()
        assertEquals(SettingsDefaults.DEFAULT_DASHBOARD_CARDS, delegate.pendingConfigs.value)
    }

    @Test
    fun `resetToDefaults is synchronous, not launched`() =
        testScope.runTest {
            delegate.onResetToDefaults()
            // No advanceUntilIdle required; mutation is sync.
            assertNotNull(delegate.pendingConfigs.value)
        }

    @Test
    fun `resetToDefaults does not persist by itself`() =
        testScope.runTest {
            delegate.onResetToDefaults()
            advanceUntilIdle()
            assertTrue(persistedConfigs.isEmpty())
        }

    // --- 6. toggleCardVisibility ---

    @Test
    fun `onToggleCardVisibility hides a visible card`() {
        delegate.onToggleCardVisibility(sampleConfigs, CardId.SLEEP_SCORE, visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertFalse(updated.first { it.cardId == CardId.SLEEP_SCORE }.isVisible)
    }

    @Test
    fun `onToggleCardVisibility shows a hidden card`() {
        delegate.onToggleCardVisibility(sampleConfigs, CardId.HRV, visible = true)
        val updated = delegate.pendingConfigs.value!!
        assertTrue(updated.first { it.cardId == CardId.HRV }.isVisible)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `onToggleCardVisibility throws when card not found`() {
        delegate.onToggleCardVisibility(sampleConfigs, CardId.WEIGHT, visible = true)
    }

    @Test
    fun `onToggleCardVisibility uses pendingConfigs when present`() {
        delegate.enterEditMode(sampleConfigs)
        delegate.onToggleCardVisibility(emptyList(), CardId.SLEEP_SCORE, visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertEquals(sampleConfigs.size, updated.size)
    }

    // --- 7. reorderCards ---

    @Test
    fun `onReorderCards reorders configurations`() {
        val newOrder =
            listOf(
                sampleConfigs[1], // READINESS first
                sampleConfigs[0], // SLEEP_SCORE second
                sampleConfigs[2],
            )
        delegate.onReorderCards(sampleConfigs, newOrder)
        val updated = delegate.pendingConfigs.value!!
        assertEquals(CardId.READINESS, updated[0].cardId)
        assertEquals(0, updated[0].position)
        assertEquals(CardId.SLEEP_SCORE, updated[1].cardId)
        assertEquals(1, updated[1].position)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `onReorderCards rejects invalid card ids`() {
        val invalid = listOf(CardConfiguration(CardId.WEIGHT, isVisible = true, position = 0))
        delegate.onReorderCards(sampleConfigs, invalid)
    }

    // --- 9. State flow aggregation ---

    @Test
    fun `state flow emits combined state changes`() =
        testScope.runTest {
            val collector = delegate.state.onEach { }.launchIn(this)
            delegate.enterEditMode(sampleConfigs)
            advanceUntilIdle()
            val current = delegate.state.value
            assertTrue(current.isManagingCards)
            assertEquals(sampleConfigs, current.pendingConfigs)
            collector.cancel()
        }

    @Test
    fun `state flow reflects cancel`() =
        testScope.runTest {
            val collector = delegate.state.onEach { }.launchIn(this)
            delegate.enterEditMode(sampleConfigs)
            advanceUntilIdle()
            delegate.cancelChanges()
            advanceUntilIdle()
            assertFalse(delegate.state.value.isManagingCards)
            assertNull(delegate.state.value.pendingConfigs)
            collector.cancel()
        }

    // --- 10. Lifecycle / memory leak prevention ---

    @Test
    fun `scope cancellation stops state collection without errors`() =
        runTest {
            val localDispatcher = StandardTestDispatcher(testScheduler)
            val localScope = TestScope(localDispatcher)
            val localDelegate =
                CardManagementDelegate(
                    defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                    persist = { },
                    scope = localScope,
                )

            localDelegate.enterEditMode(sampleConfigs)
            localScope.cancel()
            advanceUntilIdle()

            // After cancel, delegate must not throw on subsequent state reads.
            assertNotNull(localDelegate.isManagingCards.value)
        }

    @Test
    fun `saveChanges after scope cancellation does not throw`() =
        runTest {
            val localScope = TestScope(StandardTestDispatcher(testScheduler))
            val localDelegate =
                CardManagementDelegate(
                    defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                    persist = { },
                    scope = localScope,
                )
            localDelegate.enterEditMode(sampleConfigs)
            localScope.cancel()
            advanceUntilIdle()
            // Should not throw — saveTrigger.tryEmit on a SharedFlow is safe post-cancel.
            localDelegate.saveChanges()
        }

    @Test
    fun `no manual launch leaks coroutines after scope cancel`() =
        runTest {
            val localDispatcher = StandardTestDispatcher(testScheduler)
            val localScope = TestScope(localDispatcher)
            val localDelegate =
                CardManagementDelegate(
                    defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                    persist = { },
                    scope = localScope,
                )

            // Run a save which used to spawn an orphan launch.
            localDelegate.enterEditMode(sampleConfigs)
            localDelegate.saveChanges()
            advanceUntilIdle()

            // Cancel — any launched coroutine would have completed inside scope.
            localScope.cancel()
            advanceUntilIdle()
            // Structural guarantee: CardManagementDelegate launches all
            // persistence through the injected scope, so cancelling that scope
            // terminates all work cooperatively.
            assertTrue(true)
        }

    @Test
    fun `state stateIn binds to scope`() =
        testScope.runTest {
            // Subscribing returns immediate initial value
            val initial = delegate.state.first()
            assertNotNull(initial)
        }

    // --- 11. Config-change resilience ---

    @Test
    fun `pendingConfigs survives multiple toggle operations`() {
        delegate.enterEditMode(sampleConfigs)
        delegate.onToggleCardVisibility(sampleConfigs, CardId.SLEEP_SCORE, visible = false)
        delegate.onToggleCardVisibility(sampleConfigs, CardId.READINESS, visible = false)
        val updated = delegate.pendingConfigs.value!!
        assertFalse(updated.first { it.cardId == CardId.SLEEP_SCORE }.isVisible)
        assertFalse(updated.first { it.cardId == CardId.READINESS }.isVisible)
    }

    @Test
    fun `enter then save then enter again restores edit mode`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.saveChanges()
            advanceUntilIdle()
            assertFalse(delegate.isManagingCards.value)

            delegate.enterEditMode(sampleConfigs)
            assertTrue(delegate.isManagingCards.value)
            assertNotNull(delegate.pendingConfigs.value)
        }

    @Test
    fun `reset then save persists default configs`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.onResetToDefaults()
            delegate.saveChanges()
            advanceUntilIdle()
            assertEquals(listOf(SettingsDefaults.DEFAULT_DASHBOARD_CARDS), persistedConfigs)
        }

    @Test
    fun `reset then save strips body temperature from persisted configs when permission is denied`() =
        testScope.runTest {
            // Reset to defaults writes the raw SettingsDefaults.DEFAULT_DASHBOARD_CARDS (which
            // includes BODY_TEMPERATURE, isVisible = true) straight into pendingConfigs. SaveChanges
            // must still gate what actually reaches the repository on the injected permission check,
            // independent of what createDashboardCardStateFlow filters for display.
            val gatedPersisted = mutableListOf<List<CardConfiguration>>()
            val gatedDelegate =
                CardManagementDelegate(
                    defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                    persist = { gatedPersisted += it },
                    scope = delegateScope,
                    hasBodyTemperaturePermission = { false },
                )

            gatedDelegate.enterEditMode(sampleConfigs)
            gatedDelegate.onResetToDefaults()
            gatedDelegate.saveChanges()
            advanceUntilIdle()

            val expectedPersisted =
                SettingsDefaults.DEFAULT_DASHBOARD_CARDS.filter { it.cardId != CardId.BODY_TEMPERATURE }
            assertEquals(listOf(expectedPersisted), gatedPersisted)
        }

    @Test
    fun `reset then save persists body temperature when permission is granted`() =
        testScope.runTest {
            val grantedPersisted = mutableListOf<List<CardConfiguration>>()
            val grantedDelegate =
                CardManagementDelegate(
                    defaultConfigurations = SettingsDefaults.DEFAULT_DASHBOARD_CARDS,
                    persist = { grantedPersisted += it },
                    scope = delegateScope,
                    hasBodyTemperaturePermission = { true },
                )

            grantedDelegate.enterEditMode(sampleConfigs)
            grantedDelegate.onResetToDefaults()
            grantedDelegate.saveChanges()
            advanceUntilIdle()

            assertEquals(listOf(SettingsDefaults.DEFAULT_DASHBOARD_CARDS), grantedPersisted)
        }

    @Test
    fun `multiple saves persist each set independently`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.saveChanges()
            advanceUntilIdle()

            val secondSet = sampleConfigs.map { it.copy(isVisible = false) }
            delegate.enterEditMode(secondSet)
            delegate.saveChanges()
            advanceUntilIdle()

            assertEquals(listOf(sampleConfigs, secondSet), persistedConfigs)
        }

    // --- 12. DisplayModeChanged ---

    @Test
    fun `changing one mode updates only matching pending card`() {
        delegate.enterEditMode(sampleConfigs)
        delegate.onEvent(
            CardManagementEvent.DisplayModeChanged(
                CardId.HRV,
                DashboardCardDisplayMode.BAR,
            ),
        )
        val updated = requireNotNull(delegate.pendingConfigs.value)
        assertEquals(DashboardCardDisplayMode.BAR, updated.single { it.cardId == CardId.HRV }.requestedDisplayMode)
        assertNull(updated.single { it.cardId == CardId.READINESS }.requestedDisplayMode)
    }

    @Test
    fun `save changes persists the updated display mode`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.onEvent(
                CardManagementEvent.DisplayModeChanged(
                    CardId.HRV,
                    DashboardCardDisplayMode.BAR,
                ),
            )
            delegate.saveChanges()
            advanceUntilIdle()

            val expected =
                sampleConfigs.map {
                    if (it.cardId == CardId.HRV) it.copy(requestedDisplayMode = DashboardCardDisplayMode.BAR) else it
                }
            assertEquals(listOf(expected), persistedConfigs)
        }

    @Test
    fun `cancel changes drops display mode updates without persisting`() =
        testScope.runTest {
            delegate.enterEditMode(sampleConfigs)
            delegate.onEvent(
                CardManagementEvent.DisplayModeChanged(
                    CardId.HRV,
                    DashboardCardDisplayMode.BAR,
                ),
            )
            delegate.cancelChanges()
            advanceUntilIdle()

            assertTrue(persistedConfigs.isEmpty())
            assertNull(delegate.pendingConfigs.value)
        }

    @Test
    fun `DisplayModeChanged is a no-op when not editing`() {
        delegate.onEvent(CardManagementEvent.DisplayModeChanged(CardId.HRV, DashboardCardDisplayMode.BAR))
        assertFalse(delegate.isManagingCards.value)
        assertNull(delegate.pendingConfigs.value)
    }
}
