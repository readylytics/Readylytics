package app.readylytics.health.core.model.domain.repository

sealed interface ReadOutcome<out T> {
    data class Available<T>(val data: T) : ReadOutcome<T>

    data object Denied : ReadOutcome<Nothing>

    data object Unsupported : ReadOutcome<Nothing>
}

fun <T> ReadOutcome<T>.valueOrPrevious(previous: T): T =
    when (this) {
        is ReadOutcome.Available -> data
        ReadOutcome.Denied, ReadOutcome.Unsupported -> previous
    }

fun <T> ReadOutcome<T>.getOrNull(): T? =
    when (this) {
        is ReadOutcome.Available -> data
        ReadOutcome.Denied, ReadOutcome.Unsupported -> null
    }

inline fun <T, R> ReadOutcome<T>.map(transform: (T) -> R): ReadOutcome<R> =
    when (this) {
        is ReadOutcome.Available -> ReadOutcome.Available(transform(data))
        ReadOutcome.Denied -> ReadOutcome.Denied
        ReadOutcome.Unsupported -> ReadOutcome.Unsupported
    }

fun <T> ReadOutcome<List<T>>.dataOrEmpty(): List<T> =
    when (this) {
        is ReadOutcome.Available -> data
        ReadOutcome.Denied, ReadOutcome.Unsupported -> emptyList()
    }
