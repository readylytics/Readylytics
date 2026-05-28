package com.gregor.lauritz.healthdashboard.ui.common

import androidx.lifecycle.ViewModel
import com.gregor.lauritz.healthdashboard.domain.model.Result

/**
 * Base ViewModel providing Result<T> handling utilities.
 *
 * Subclasses use [handleResult] for imperative error handling or
 * [resultToState] for mapping Result<T> to UiState in flows.
 */
abstract class BaseViewModel : ViewModel() {
    /**
     * Execute a success/failure handler based on Result<T>.
     *
     * @param result Result to handle
     * @param onSuccess Called with unwrapped data on Success
     * @param onError Called with error code and reason on Failure
     */
    protected fun <T> handleResult(
        result: Result<T>,
        onSuccess: (T) -> Unit,
        onError: (code: String, reason: String) -> Unit,
    ) {
        when (result) {
            is Result.Success -> onSuccess(result.data)
            is Result.Failure -> onError(result.code, result.reason)
        }
    }

    /**
     * Transform Result<T> to UiState synchronously.
     *
     * Used in flows: `result.map { resultToState(it) { uiState } }`
     *
     * @param result Result to transform
     * @param successTransform Maps Success(data) to UiState
     * @return Success state from transform or Error(code, reason) state
     */
    protected fun <T, S : UiState> resultToState(
        result: Result<T>,
        successTransform: (T) -> S,
    ): UiState {
        return when (result) {
            is Result.Success -> successTransform(result.data)
            is Result.Failure -> UiState.Error(code = result.code, message = result.reason)
        }
    }

    /**
     * Extract data from Result<T> or return default.
     *
     * Useful for state initialization: `val data = resultOr(result) { defaultValue }`
     *
     * @param result Result to extract from
     * @param default Called on Failure
     * @return Unwrapped data or default
     */
    protected fun <T> resultOr(
        result: Result<T>,
        default: (Result.Failure) -> T,
    ): T {
        return when (result) {
            is Result.Success -> result.data
            is Result.Failure -> default(this)
        }
    }
}
