package app.readylytics.health.ui.onboarding

import android.net.Uri
import app.readylytics.health.R
import app.readylytics.health.domain.backup.RestoreResult
import app.readylytics.health.domain.backup.RestoreService
import app.readylytics.health.domain.backup.RestoreStage
import app.readylytics.health.core.ui.common.UiText
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingRestoreViewModelTest {
    private lateinit var restoreService: RestoreService
    private lateinit var viewModel: OnboardingRestoreViewModel
    private val backupUri = Uri.parse("file:///dummy/backup.zip")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        restoreService = mockk(relaxed = true)
        viewModel = OnboardingRestoreViewModel(restoreService = restoreService)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun restore_partialSuccessShowsRestartRequiredMessage() =
        runTest {
            coEvery { restoreService.validate(any(), any()) } returns Result.success(Unit)
            coEvery { restoreService.applyRestore(any(), any()) } returns
                RestoreResult.PartialSuccessRequiresRestart(
                    failedStage = RestoreStage.PREFERENCES,
                    cause = IllegalStateException("prefs fail"),
                )

            viewModel.restore(uri = backupUri, password = "password")

            val state = viewModel.state.value
            assertFalse(state.isRestoring)
            assertTrue(state.restoreRequiresRestart)
            assertEquals(
                UiText.StringRes(R.string.restore_partial_success_message),
                state.error,
            )
        }
}
