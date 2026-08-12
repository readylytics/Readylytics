package app.readylytics.health.data.security

import javax.crypto.SecretKey

/**
 * Abstracts Android KeyStore access so tests can substitute a fake
 * without polluting production security code with runtime-name checks.
 */
interface KeyProvider {
    fun getOrCreateKey(alias: String): SecretKey
}
