package app.readylytics.health.domain.security

data class AppLockSecurityConfig(
    val authBoundKeysEnabled: Boolean,
    val authValiditySeconds: Int,
)
