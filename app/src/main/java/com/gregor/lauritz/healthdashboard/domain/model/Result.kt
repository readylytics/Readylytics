package com.gregor.lauritz.healthdashboard.domain.model

/**
 * Generic outcome envelope used across the domain layer.
 *
 * Replaces ad-hoc result types ([com.gregor.lauritz.healthdashboard.domain.service.BmiResult],
 * `Option<T>`, `PreferenceResult<T>`, `AggregatedValidationResult`) with a single, consistent
 * sealed hierarchy. Services never throw exceptions for predictable failure modes; they return
 * [Result.Failure] with a human-readable [Failure.reason] and a stable [Failure.code].
 *
 * Inspired by functional `Result<T, E>` / `Either<L, R>` patterns, simplified for this app.
 */
sealed class Result<out T> {
    /** Successful outcome carrying [data] of type [T]. */
    data class Success<T>(
        val data: T,
    ) : Result<T>()

    /** Failure outcome carrying a human [reason] and a stable, machine-friendly [code]. */
    data class Failure(
        val reason: String,
        val code: String = UNKNOWN_CODE,
    ) : Result<Nothing>()

    /** True iff this is a [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** True iff this is a [Failure]. */
    val isFailure: Boolean get() = this is Failure

    companion object {
        /** Default code applied to [Failure] when callers don't supply one. */
        const val UNKNOWN_CODE: String = "UNKNOWN"

        /** Build a [Success] result. */
        fun <T> success(data: T): Result<T> = Success(data)

        /** Build a [Failure] result. */
        fun failure(
            reason: String,
            code: String = UNKNOWN_CODE,
        ): Result<Nothing> = Failure(reason, code)
    }
}

/** Returns the contained [Result.Success.data] or `null` if this is a [Result.Failure]. */
fun <T> Result<T>.getOrNull(): T? =
    when (this) {
        is Result.Success -> data
        is Result.Failure -> null
    }

/** Returns the contained data or the result of [default] when this is a [Result.Failure]. */
inline fun <T> Result<T>.getOrElse(default: (Result.Failure) -> T): T =
    when (this) {
        is Result.Success -> data
        is Result.Failure -> default(this)
    }

/** Transforms a [Result.Success] value via [transform]; [Result.Failure] passes through unchanged. */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> =
    when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Failure -> this
    }

/** Chains a dependent computation that itself returns a [Result]; failures short-circuit. */
inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
    when (this) {
        is Result.Success -> transform(data)
        is Result.Failure -> this
    }

/**
 * Collapses a [Result] into a single value by handling both branches.
 *
 * @param onSuccess invoked with the success data
 * @param onFailure invoked with the failure
 */
inline fun <T, R> Result<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (Result.Failure) -> R,
): R =
    when (this) {
        is Result.Success -> onSuccess(data)
        is Result.Failure -> onFailure(this)
    }

/** Maps a [Result.Failure] back to a [Result.Success] via [transform]. */
inline fun <T> Result<T>.recover(transform: (Result.Failure) -> T): Result<T> =
    when (this) {
        is Result.Success -> this
        is Result.Failure -> Result.Success(transform(this))
    }

/** Like [recover] but the recovery function may itself return a [Result]. */
inline fun <T> Result<T>.recoverWith(transform: (Result.Failure) -> Result<T>): Result<T> =
    when (this) {
        is Result.Success -> this
        is Result.Failure -> transform(this)
    }

/** Throws an exception if this is a [Failure], otherwise returns the success data. */
fun <T> Result<T>.getOrThrow(): T =
    when (this) {
        is Result.Success -> data
        is Result.Failure -> throw Exception(reason)
    }

/** Side-effecting callback for successful results; returns the original [Result] unchanged. */
inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

/** Side-effecting callback for failure results; returns the original [Result] unchanged. */
inline fun <T> Result<T>.onFailure(action: (Result.Failure) -> Unit): Result<T> {
    if (this is Result.Failure) action(this)
    return this
}

/** Lifts a nullable value into a [Result], using [reason] / [code] for the null case. */
fun <T> T?.toResult(
    reason: String = "value is null",
    code: String = Result.UNKNOWN_CODE,
): Result<T> = if (this == null) Result.Failure(reason, code) else Result.Success(this)
