package readylytics.buildlogic

object DebugInstallIdentity {
    fun stripMdnsSuffix(hostname: String): String = hostname.removeSuffix(".local")

    fun sanitizeMachineId(rawHostname: String): String {
        val sanitized =
            rawHostname
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "")
                .take(20)
                .ifBlank { "device" }
        return if (sanitized.first().isDigit()) "m$sanitized" else sanitized
    }
}
