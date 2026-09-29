package com.kachat.app.util

/**
 * Links taken from other people's content - a KNS profile field, above all - open only as web
 * pages or email (iOS 5090ad9). Any other scheme could hand the tap to another app: a wallet's
 * deep link, an intent:// URL, a custom handler.
 */
object SafeExternalLink {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")
    private val EMAIL = Regex("^[^@\\s/:]+@[^@\\s/:]+\\.[^@\\s/:]+$")

    /** The URL to open for [value], or null when it names a scheme other than http(s)/mailto. */
    fun forProfileField(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val scheme = SCHEME.find(trimmed)?.value?.dropLast(1)?.lowercase()
        if (scheme != null) {
            // "host:port/path" reads as a scheme to the regex; a real scheme is never all-digit
            // after the colon, so a port keeps its https.
            val afterColon = trimmed.substring(scheme.length + 1)
            if (afterColon.firstOrNull()?.isDigit() == true && '.' in scheme) return "https://$trimmed"
            return if (scheme == "http" || scheme == "https" || scheme == "mailto") trimmed else null
        }
        if (EMAIL.matches(trimmed)) return "mailto:$trimmed"
        return "https://$trimmed"
    }
}
