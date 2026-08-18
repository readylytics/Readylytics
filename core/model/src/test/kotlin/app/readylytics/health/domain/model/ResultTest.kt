package app.readylytics.health.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultTest {
    // ─── Construction and identity ──────────────────────────────────────────
    @Test
    fun success_factory_buildsSuccess() {
        val r: Result<Int> = Result.success(42)
        assertTrue(r is Result.Success)
        assertEquals(42, (r as Result.Success).data)
    }

    @Test
    fun failure_factory_buildsFailure() {
        val r = Result.failure("nope", "BAD")
        assertTrue(r is Result.Failure)
        val f = r as Result.Failure
        assertEquals("nope", f.reason)
        assertEquals("BAD", f.code)
    }

    @Test
    fun failure_default_code_isUnknown() {
        val r = Result.Failure("oops")
        assertEquals(Result.UNKNOWN_CODE, r.code)
        assertEquals("UNKNOWN", r.code)
    }

    @Test
    fun isSuccess_onSuccess_isTrue() {
        assertTrue((Result.Success(1) as Result<Int>).isSuccess)
    }

    @Test
    fun isSuccess_onFailure_isFalse() {
        assertFalse((Result.Failure("x") as Result<Int>).isSuccess)
    }

    @Test
    fun isFailure_onSuccess_isFalse() {
        assertFalse((Result.Success(1) as Result<Int>).isFailure)
    }

    @Test
    fun isFailure_onFailure_isTrue() {
        assertTrue((Result.Failure("x") as Result<Int>).isFailure)
    }

    // ─── getOrNull ──────────────────────────────────────────────────────────
    @Test
    fun getOrNull_success_returnsValue() {
        assertEquals(7, (Result.Success(7) as Result<Int>).getOrNull())
    }

    @Test
    fun getOrNull_failure_returnsNull() {
        assertNull((Result.Failure("x") as Result<Int>).getOrNull())
    }

    @Test
    fun getOrNull_successOfNullable_returnsNull() {
        val r: Result<String?> = Result.Success(null)
        assertNull(r.getOrNull())
    }

    // ─── getOrElse ──────────────────────────────────────────────────────────
    @Test
    fun getOrElse_success_returnsValue_andDoesNotCallDefault() {
        var called = false
        val v =
            (Result.Success(3) as Result<Int>).getOrElse {
                called = true
                0
            }
        assertEquals(3, v)
        assertFalse(called)
    }

    @Test
    fun getOrElse_failure_invokesDefaultWithFailure() {
        var captured: Result.Failure? = null
        val v =
            (Result.Failure("bad", "X") as Result<Int>).getOrElse {
                captured = it
                99
            }
        assertEquals(99, v)
        assertEquals("bad", captured?.reason)
        assertEquals("X", captured?.code)
    }

    // ─── map ────────────────────────────────────────────────────────────────
    @Test
    fun map_success_appliesTransform() {
        val r = (Result.Success(2) as Result<Int>).map { it * 10 }
        assertEquals(20, (r as Result.Success).data)
    }

    @Test
    fun map_failure_passesThroughUnchanged() {
        val f = Result.Failure("err", "E") as Result<Int>
        val r = f.map { it * 10 }
        assertTrue(r is Result.Failure)
        assertEquals("err", (r as Result.Failure).reason)
        assertEquals("E", r.code)
    }

    @Test
    fun map_changesValueType() {
        val r = (Result.Success(5) as Result<Int>).map { "n=$it" }
        assertEquals("n=5", (r as Result.Success).data)
    }

    // ─── flatMap ────────────────────────────────────────────────────────────
    @Test
    fun flatMap_success_chainsSuccess() {
        val r =
            (Result.Success(2) as Result<Int>).flatMap { x -> Result.Success(x + 1) }
        assertEquals(3, (r as Result.Success).data)
    }

    @Test
    fun flatMap_success_canReturnFailure() {
        val r =
            (Result.Success(2) as Result<Int>).flatMap { Result.Failure("downstream", "D") }
        assertTrue(r is Result.Failure)
        assertEquals("downstream", (r as Result.Failure).reason)
    }

    @Test
    fun flatMap_failure_shortCircuits() {
        var called = false
        val r =
            (Result.Failure("up", "U") as Result<Int>).flatMap {
                called = true
                Result.Success(0)
            }
        assertTrue(r is Result.Failure)
        assertEquals("up", (r as Result.Failure).reason)
        assertFalse(called)
    }

    @Test
    fun flatMap_isAssociative_likeAMonad() {
        val r =
            (Result.Success(1) as Result<Int>)
                .flatMap { Result.Success(it + 1) }
                .flatMap { Result.Success(it * 5) }
        assertEquals(10, (r as Result.Success).data)
    }

    // ─── fold ───────────────────────────────────────────────────────────────
    @Test
    fun fold_success_invokesSuccessBranch() {
        val v =
            (Result.Success(4) as Result<Int>).fold(
                onSuccess = { "ok=$it" },
                onFailure = { "fail=${it.reason}" },
            )
        assertEquals("ok=4", v)
    }

    @Test
    fun fold_failure_invokesFailureBranch() {
        val v =
            (Result.Failure("nope", "N") as Result<Int>).fold(
                onSuccess = { "ok=$it" },
                onFailure = { "fail=${it.reason}/${it.code}" },
            )
        assertEquals("fail=nope/N", v)
    }

    // ─── recover / recoverWith ──────────────────────────────────────────────
    @Test
    fun recover_failure_becomesSuccess() {
        val r = (Result.Failure("err", "E") as Result<Int>).recover { 42 }
        assertEquals(42, (r as Result.Success).data)
    }

    @Test
    fun recover_success_isUnchanged() {
        val original = Result.Success(1) as Result<Int>
        val recovered = original.recover { 99 }
        assertSame(original, recovered)
    }

    @Test
    fun recoverWith_failure_canReturnFailure() {
        val r = (Result.Failure("err", "E") as Result<Int>).recoverWith { Result.Failure("still bad", "S") }
        assertTrue(r is Result.Failure)
        assertEquals("still bad", (r as Result.Failure).reason)
    }

    @Test
    fun recoverWith_failure_canReturnSuccess() {
        val r = (Result.Failure("err", "E") as Result<Int>).recoverWith { Result.Success(7) }
        assertEquals(7, (r as Result.Success).data)
    }

    // ─── side-effect callbacks ──────────────────────────────────────────────
    @Test
    fun onSuccess_fires_onlyOnSuccess() {
        var seen: Int? = null
        (Result.Success(3) as Result<Int>).onSuccess { seen = it }
        assertEquals(3, seen)
    }

    @Test
    fun onSuccess_doesNotFire_onFailure() {
        var seen: Int? = null
        (Result.Failure("x") as Result<Int>).onSuccess { seen = it }
        assertNull(seen)
    }

    @Test
    fun onFailure_fires_onlyOnFailure() {
        var seen: Result.Failure? = null
        (Result.Failure("x", "C") as Result<Int>).onFailure { seen = it }
        assertNotNull(seen)
        assertEquals("C", seen?.code)
    }

    @Test
    fun onFailure_doesNotFire_onSuccess() {
        var seen: Result.Failure? = null
        (Result.Success(1) as Result<Int>).onFailure { seen = it }
        assertNull(seen)
    }

    @Test
    fun onSuccess_returnsSameInstance() {
        val r = Result.Success(1) as Result<Int>
        assertSame(r, r.onSuccess { })
    }

    @Test
    fun onFailure_returnsSameInstance() {
        val r = Result.Failure("x") as Result<Int>
        assertSame(r, r.onFailure { })
    }

    // ─── toResult (nullable lift) ───────────────────────────────────────────
    @Test
    fun toResult_nonNull_isSuccess() {
        val r: Result<String> = "hello".toResult()
        assertEquals("hello", (r as Result.Success).data)
    }

    @Test
    fun toResult_null_isFailureWithDefaults() {
        val r: Result<String> = (null as String?).toResult()
        assertTrue(r is Result.Failure)
        val f = r as Result.Failure
        assertEquals("value is null", f.reason)
        assertEquals(Result.UNKNOWN_CODE, f.code)
    }

    @Test
    fun toResult_null_acceptsCustomReasonAndCode() {
        val r: Result<String> = (null as String?).toResult(reason = "missing user", code = "USER_MISSING")
        val f = r as Result.Failure
        assertEquals("missing user", f.reason)
        assertEquals("USER_MISSING", f.code)
    }

    // ─── Equality (data class semantics) ────────────────────────────────────
    @Test
    fun success_equality_isByValue() {
        assertEquals(Result.Success(1), Result.Success(1))
    }

    @Test
    fun failure_equality_includesReasonAndCode() {
        assertEquals(Result.Failure("a", "C"), Result.Failure("a", "C"))
    }

    @Test
    fun failure_equality_differsOnReason() {
        assertFalse(Result.Failure("a", "C") == Result.Failure("b", "C"))
    }

    // ─── Composability ──────────────────────────────────────────────────────
    @Test
    fun composes_mapAndFlatMap_chainedCleanly() {
        val r: Result<String> =
            (Result.Success(5) as Result<Int>)
                .map { it + 5 }
                .flatMap { if (it > 0) Result.Success("n=$it") else Result.Failure("neg") }
        assertEquals("n=10", (r as Result.Success).data)
    }

    @Test
    fun composes_failureShortCircuitsAcrossOperators() {
        val r: Result<String> =
            (Result.Failure("nope", "E") as Result<Int>)
                .map { it + 5 }
                .flatMap { Result.Success("v=$it") }
        assertTrue(r is Result.Failure)
        assertEquals("nope", (r as Result.Failure).reason)
    }
}
