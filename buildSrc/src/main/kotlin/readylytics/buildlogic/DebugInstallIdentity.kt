package readylytics.buildlogic

import java.net.InetAddress

object DebugInstallIdentity {

    val rawHostname: String by lazy { stripMdnsSuffix(detectHostname()) }

    val machineIdSegment: String by lazy { sanitizeMachineId(rawHostname) }

    fun detectHostname(): String =
        (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME"))?.takeIf { it.isNotBlank() }
            ?: runCatching {
                ProcessBuilder("hostname").start().inputStream.bufferedReader().use { it.readText() }.trim()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.name")
            ?: "device"

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
