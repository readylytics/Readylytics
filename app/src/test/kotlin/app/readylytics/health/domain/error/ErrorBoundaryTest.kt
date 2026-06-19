package app.readylytics.health.domain.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ErrorBoundaryTest {
    @Test
    fun `safeOperation rethrows coroutine cancellation`() {
        assertFailsWith<CancellationException> {
            safeOperation("cancel") {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `safeOperation rethrows fatal JVM errors`() {
        assertFailsWith<AssertionError> {
            safeOperation("fatal") {
                throw AssertionError("fatal")
            }
        }
    }

    @Test
    fun `safeOperation converts ordinary exceptions to failure`() {
        val result =
            safeOperation("ordinary") {
                throw IllegalStateException("boom")
            }

        assertIs<SafeResult.Failure<Unit>>(result)
    }

    @Test
    fun `recover retry rethrows coroutine cancellation`() =
        runTest {
            val failure = SafeResult.Failure<Unit>(IllegalStateException("first"), "retry")

            assertFailsWith<CancellationException> {
                failure.recover(RecoveryStrategy.Retry(maxAttempts = 2, delayMs = 0)) {
                    throw CancellationException("cancelled")
                }
            }
        }

    @Test
    fun `recover retry ignores ordinary exception until attempts exhausted`() =
        runTest {
            val failure = SafeResult.Failure<Unit>(IllegalStateException("first"), "retry")

            val result =
                failure.recover(RecoveryStrategy.Retry(maxAttempts = 2, delayMs = 0)) {
                    throw IllegalStateException("second")
                }

            assertTrue(result === failure)
        }
}
