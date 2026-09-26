package com.kachat.app.util

/**
 * One place that turns a caught exception into a sentence a person can act on. Port of iOS's
 * `UserFacingError` (d887c98): about forty call sites read `e.message ?: "<fallback>"`, which shows
 * the fallback only when the exception happens to have no message — so the sentence people
 * actually saw was usually the technical one underneath, "Unable to resolve host …" or a Gson
 * parse trace.
 *
 * It differs from iOS on purpose in one respect. iOS ends with `error.localizedDescription`, which
 * Foundation writes as prose; Java's `getMessage()` is written for a log. So an unrecognised
 * exception here returns the CALLER's [fallback] rather than its own message — the call sites
 * already pass a good one ("Consolidation failed", "Transfer failed") and it was only ever being
 * used when the message was null.
 *
 * Exceptions the app throws itself ([IllegalArgumentException], [IllegalStateException] and
 * [PortfolioAddressImportError]-style app types) keep their message: those strings were written
 * for the person reading them, and dropping them would lose the specific reason.
 */
object UserFacingError {

    fun message(error: Throwable?, fallback: String): String {
        if (error == null) return fallback
        return when (error) {
            is kotlinx.coroutines.CancellationException -> "Cancelled."

            // The app's own `require`/`check`/`throw` - authored for the reader.
            is IllegalArgumentException, is IllegalStateException ->
                error.message?.trim()?.stripKnownPrefixes()?.takeIf { it.isNotEmpty() } ?: fallback

            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
            is java.net.PortUnreachableException ->
                "Couldn't reach the server. Check the address in Settings and your connection."

            is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
                "The connection timed out. Check your connection and try again."

            is javax.net.ssl.SSLException ->
                "Couldn't make a secure connection to the server."

            is com.google.gson.JsonParseException, is com.google.gson.JsonIOException ->
                "The server sent something the app couldn't read. Try again; if it keeps happening, update the app."

            is retrofit2.HttpException ->
                "The server returned an error (${error.code()}). Try again in a moment."

            is io.grpc.StatusRuntimeException, is io.grpc.StatusException ->
                "The Kaspa node returned an error. The app will try another node; try again in a moment."

            // Keystore and crypto failures: the underlying text names providers and algorithms and
            // tells the reader nothing they can act on.
            is java.security.GeneralSecurityException -> fallback

            // Anything else IO-shaped is a network problem in this app - OkHttp wraps most of them.
            is java.io.IOException ->
                "A network error occurred. Check your connection and try again."

            else -> {
                val appAuthored = error.javaClass.name.startsWith("com.kachat.app")
                if (appAuthored) {
                    error.message?.trim()?.stripKnownPrefixes()?.takeIf { it.isNotEmpty() } ?: fallback
                } else {
                    fallback
                }
            }
        }
    }

    /** The same treatment for a reason that is already a string. */
    fun message(text: String?, fallback: String): String =
        text?.trim()?.stripKnownPrefixes()?.takeIf { it.isNotEmpty() } ?: fallback

    private fun String.stripKnownPrefixes(): String {
        var cleaned = this
        for (prefix in listOf("Network error: ", "API error: ", "Keychain error: ", "Encryption error: ")) {
            if (cleaned.startsWith(prefix)) cleaned = cleaned.removePrefix(prefix)
        }
        return cleaned.trim()
    }
}
