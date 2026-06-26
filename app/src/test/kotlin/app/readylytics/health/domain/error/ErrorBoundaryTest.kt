package app.readylytics.health.domain.error

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorBoundaryTest {
    @Test
    fun safeOperationSuccess() {
        val result = safeOperation("test") { 42 }

        assertTrue(result is SafeResult.Success)
        assertEquals(42, (result as SafeResult.Success).value)
    }

    @Test
    fun safeOperationFailure() {
        val exception = RuntimeException("test error")
        val result = safeOperation("test op", operation = { throw exception })

        assertTrue(result is SafeResult.Failure)
        val failure = result as SafeResult.Failure
        assertEquals(exception, failure.error)
        assertEquals("test op", failure.context)
    }

    @Test
    fun safeOperationCallsErrorHandler() {
        var errorCalled = false
        var capturedError: Throwable? = null
        val exception = IllegalArgumentException("boom")

        safeOperation(
            "test",
            onError = {
                errorCalled = true
                capturedError = it
            },
        ) { throw exception }

        assertTrue(errorCalled)
        assertEquals(exception, capturedError)
    }

    @Test
    fun getOrNullReturnsValueForSuccess() {
        val result: SafeResult<Int> = SafeResult.Success(42)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun getOrNullReturnsValueForPartialSuccess() {
        val result: SafeResult<Int> = SafeResult.PartialSuccess(42, "warning")
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun getOrNullReturnsNullForFailure() {
        val result: SafeResult<Int> = SafeResult.Failure(RuntimeException("error"))
        assertNull(result.getOrNull())
    }

    @Test
    fun getOrElseReturnsValueForSuccess() {
        val result: SafeResult<Int> = SafeResult.Success(42)
        assertEquals(42, result.getOrElse(99))
    }

    @Test
    fun getOrElseReturnsValueForPartialSuccess() {
        val result: SafeResult<Int> = SafeResult.PartialSuccess(42, "warning")
        assertEquals(42, result.getOrElse(99))
    }

    @Test
    fun getOrElseReturnsFallbackForFailure() {
        val result: SafeResult<Int> = SafeResult.Failure(RuntimeException("error"))
        assertEquals(99, result.getOrElse(99))
    }

    @Test
    fun recoverWithReturnValueStrategy() =
        runTest {
            val failure: SafeResult.Failure<Int> = SafeResult.Failure(RuntimeException("error"))
            val strategy = RecoveryStrategy.ReturnValue(42)
            val result = failure.recover(strategy) { 99 }
            assertTrue(result is SafeResult.Success)
            assertEquals(42, (result as SafeResult.Success).value)
        }

    @Test
    fun recoverWithRetryStrategySucceedsImmediately() =
        runTest {
            val failure: SafeResult.Failure<Int> = SafeResult.Failure(RuntimeException("error"))
            val strategy = RecoveryStrategy.Retry<Int>(maxAttempts = 3, delayMs = 0)
            val result = failure.recover(strategy) { 42 }
            assertTrue(result is SafeResult.Success)
            assertEquals(42, (result as SafeResult.Success).value)
        }

    @Test
    fun recoverWithRetryStrategyReturnsFailureAfterExhaustion() =
        runTest {
            val failure: SafeResult.Failure<Int> = SafeResult.Failure(RuntimeException("error"))
            val strategy = RecoveryStrategy.Retry<Int>(maxAttempts = 2, delayMs = 0)
            val result = failure.recover(strategy) { throw RuntimeException("still failing") }
            assertTrue(result is SafeResult.Failure)
        }
}
