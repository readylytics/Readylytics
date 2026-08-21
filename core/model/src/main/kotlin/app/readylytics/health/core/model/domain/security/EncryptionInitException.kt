package app.readylytics.health.core.model.domain.security

/** Thrown when master-key generation fails on both the StrongBox and non-StrongBox paths. */
class EncryptionInitException(message: String, cause: Throwable? = null) : Exception(message, cause)
