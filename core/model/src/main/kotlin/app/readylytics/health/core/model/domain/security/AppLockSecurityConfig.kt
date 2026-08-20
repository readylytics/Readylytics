package app.readylytics.health.core.model.domain.security

data class AppLockSecurityConfig(
    val authBoundKeysEnabled: Boolean,
    val authValiditySeconds: Int,
)
