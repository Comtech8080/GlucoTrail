package com.aidexx.reader.updates

import java.net.URI

/** Release metadata is untrusted; only this repository's assets are accepted. */
object UpdatePolicy {
    const val REPO = "Comtech8080/GlucoTrail"
    const val MAX_APK = 250L * 1024 * 1024

    fun assetUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.rawPath.startsWith("/$REPO/releases/download/")
    }.getOrDefault(false)

    fun checksum(text: String, filename: String): String {
        val line = text.trim()
        val match = Regex("^([a-fA-F0-9]{64})(?:[ \\t]+\\*?([^\\r\\n]+))?$").matchEntire(line)
            ?: error("Invalid SHA-256 file")
        require(match.groupValues[2].isEmpty() || match.groupValues[2] == filename) {
            "Checksum names a different APK"
        }
        return match.groupValues[1].lowercase()
    }

    fun validateIdentity(expectedPackage: String, actualPackage: String, installed: Long,
                         advertised: Long, actual: Long, installedSigners: Set<String>,
                         downloadedSigners: Set<String>) {
        require(actualPackage == expectedPackage) { "APK belongs to another app" }
        require(actual == advertised && actual > installed) { "APK version does not match or is not newer" }
        require(installedSigners.isNotEmpty() && installedSigners == downloadedSigners) {
            "Signing certificate differs from this installed app. Keep the existing signing key; do not uninstall."
        }
    }
}
