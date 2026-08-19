package app.readylytics.health.data.preferences

import androidx.datastore.core.DataStore
import app.readylytics.health.domain.preferences.SleepScoreRecalcBaseline
import app.readylytics.health.domain.scoring.SleepScoreWeightProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeDataStore<T>(
    initialValue: T,
) : DataStore<T> {
    private val state = MutableStateFlow(initialValue)

    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        val result = transform(state.value)
        state.value = result
        return result
    }
}

class SleepScoreRecalcBaselineStoreImplTest {

    @Test
    fun `baseline is null when nothing has been recalced`() =
        runTest {
            val store = SleepScoreRecalcBaselineStoreImpl(FakeDataStore(SleepScoreRecalcBaselineProto.getDefaultInstance()))
            assertNull(store.baseline.first())
        }

    @Test
    fun `markRecalced persists and round-trips the baseline`() =
        runTest {
            val store = SleepScoreRecalcBaselineStoreImpl(FakeDataStore(SleepScoreRecalcBaselineProto.getDefaultInstance()))
            store.markRecalced(SleepScoreWeightProfile.RECOVERY_FOCUSED, 9f, 110)

            assertEquals(
                SleepScoreRecalcBaseline(SleepScoreWeightProfile.RECOVERY_FOCUSED, 9f, 110),
                store.baseline.first(),
            )
        }

    @Test
    fun `unset weight profile maps to balanced`() =
        runTest {
            val proto =
                SleepScoreRecalcBaselineProto.newBuilder()
                    .setGoalSleepHours(8f)
                    .setHypersomniaOnsetPercent(125)
                    .build()
            val store = SleepScoreRecalcBaselineStoreImpl(FakeDataStore(proto))

            assertEquals(
                SleepScoreRecalcBaseline(SleepScoreWeightProfile.BALANCED, 8f, 125),
                store.baseline.first(),
            )
        }
}