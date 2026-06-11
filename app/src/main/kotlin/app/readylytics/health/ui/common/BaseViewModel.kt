package app.readylytics.health.ui.common

import androidx.lifecycle.ViewModel
import app.readylytics.health.domain.model.Result

abstract class BaseViewModel : ViewModel() {
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

    protected fun <T> resultOr(
        result: Result<T>,
        default: (Result.Failure) -> T,
    ): T =
        when (result) {
            is Result.Success -> result.data
            is Result.Failure -> default(result)
        }
}
