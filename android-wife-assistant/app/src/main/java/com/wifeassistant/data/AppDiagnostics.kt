package com.wifeassistant.data

/** Explicit allowlist. This function has no Settings, Store or credential access. */
object AppDiagnostics {
    fun render(versionName: String, versionCode: Int, packageName: String, sdk: Int, certificate: String?): String =
        listOf("Wisal build diagnostics", "Version: $versionName ($versionCode)", "Package: $packageName",
            "Android API: $sdk", "Signing certificate SHA-256: ${certificate ?: "unavailable"}",
            "No keys, contacts, message content or account identifiers included.").joinToString("\n")
}
