package app.readylytics.health.core.model.domain.model

/** Thrown by [Result.getOrThrow] when the receiver is a [Result.Failure]. */
class ResultFailureException(message: String) : Exception(message)
