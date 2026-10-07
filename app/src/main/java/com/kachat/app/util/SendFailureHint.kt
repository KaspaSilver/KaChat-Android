package com.kachat.app.util

/**
 * Whether the "Failed to Send" alert adds its "check your network connection" line under the
 * reason - iOS ChatDetailView.shouldShowRetryHint. A balance shortfall is not a network problem,
 * so its message goes without the hint; everything else gets it.
 */
object SendFailureHint {
    /** iOS's insufficient-balance message (ChatService+Conversations.formatInsufficientBalanceError). */
    const val INSUFFICIENT_BALANCE_TEMPLATE = "Planned spend %s KAS, but available balance %s KAS is less than required."

    fun showsNetworkHint(message: String, template: String = INSUFFICIENT_BALANCE_TEMPLATE): Boolean {
        val lowered = message.lowercase()
        // Android's own wording for the same shortfall (KaspaWalletEngine's coin selection).
        if (lowered.startsWith("insufficient funds")) return false
        // iOS's check: every literal piece of the template, in order.
        val parts = template.lowercase().split("%s").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return true
        var searchStart = 0
        for (part in parts) {
            val found = lowered.indexOf(part, searchStart)
            if (found < 0) return true
            searchStart = found + part.length
        }
        return false
    }
}
