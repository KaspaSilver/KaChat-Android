package com.kachat.app.models

/** Which block explorer website transaction links open in — user picks in Settings > Kaspa Explorer. */
enum class KaspaExplorer(
    val displayName: String,
    private val txBaseUrl: String,
    private val addressBaseUrl: String
) {
    KASPA_STREAM("kaspa.stream", "https://kaspa.stream/transactions/", "https://kaspa.stream/addresses/"),
    KASPA_ORG("explorer.kaspa.org", "https://explorer.kaspa.org/txs/", "https://explorer.kaspa.org/addresses/");

    // On testnet every link opens kaspa.stream's testnet-10 explorer, whichever site is picked:
    // explorer.kaspa.org has no live TN10 site (iOS 421a832).
    fun txUrl(txId: String): String =
        if (com.kachat.app.util.KaspaNetwork.isTestnet) "$TESTNET_TX_BASE$txId" else "$txBaseUrl$txId"
    fun addressUrl(address: String): String =
        if (com.kachat.app.util.KaspaNetwork.isTestnet) "$TESTNET_ADDRESS_BASE$address" else "$addressBaseUrl$address"

    companion object {
        private const val TESTNET_TX_BASE = "https://tn10.kaspa.stream/transactions/"
        private const val TESTNET_ADDRESS_BASE = "https://tn10.kaspa.stream/addresses/"
        val default: KaspaExplorer = KASPA_ORG

        fun fromName(name: String?): KaspaExplorer =
            entries.firstOrNull { it.name == name } ?: default
    }
}
