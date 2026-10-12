package com.kachat.app.models

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LiveHelp
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.ui.graphics.vector.ImageVector
import com.kachat.app.R

/**
 * One KaChat bot: a Kaspa address that answers in an ordinary 1:1 chat (the KaChat-Bot repo, run
 * from the Kaspa Quick Start panel). Messaging it starts a private conversation; it asks its setup
 * questions in the chat (iOS `KaChatBot`, 82ab31d).
 */
data class KaChatBot(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val summaryRes: Int,
    val icon: ImageVector,
    /** The bot's mainnet address. Bots run on mainnet only. */
    val address: String,
) {
    /** Why the bot can't be messaged from here (a string resource), or null when it can. */
    @get:StringRes
    val unavailableReasonRes: Int?
        get() = when {
            address.isEmpty() -> R.string.bots_coming_soon
            !com.kachat.app.util.KaspaAddress.isValidOnActiveNetwork(address) -> R.string.bots_mainnet_only
            else -> null
        }

    companion object {
        /** A bot chat's circle when the bot has no icon of its own (iOS "cpu"). */
        val fallbackIcon: ImageVector get() = Icons.Outlined.Memory

        /** The bots Kaspa Hub > KaChat Bots lists, in this order (iOS 82ab31d, 7dd508a; the
         *  mainnet addresses from 64ce0c7). */
        val all: List<KaChatBot> = listOf(
            KaChatBot(
                id = "block-rewards",
                nameRes = R.string.bot_block_rewards_name,
                summaryRes = R.string.bot_block_rewards_summary,
                icon = Icons.Outlined.ViewInAr,
                address = "kaspa:qram07v34rnfwthwc7vssvgapxtdy9mheg8mz0v0yudqtm8z94sv5upwasd8q",
            ),
            KaChatBot(
                id = "wallet",
                nameRes = R.string.bot_wallet_name,
                summaryRes = R.string.bot_wallet_summary,
                icon = Icons.Outlined.AccountBalanceWallet,
                address = "kaspa:qzh3m296f44ez75avxd4k0dyvv5hj6sf06cuzaty5hk89w9nsprr6nngjsfvq",
            ),
            KaChatBot(
                id = "price",
                nameRes = R.string.bot_price_name,
                summaryRes = R.string.bot_price_summary,
                icon = Icons.AutoMirrored.Outlined.ShowChart,
                address = "kaspa:qzk2jrk7wq3zdw2n0260v87fglky82cg5u3pwy73wpktk3u864yecm5djll40",
            ),
            KaChatBot(
                id = "kaspa-answers",
                nameRes = R.string.bot_kaspa_answers_name,
                summaryRes = R.string.bot_kaspa_answers_summary,
                icon = Icons.AutoMirrored.Outlined.LiveHelp,
                address = "kaspa:qqj0vdfxv66xad0j382jeur2jpdpsjej2qzqzwaxtw66h2f3zg0djndh8ypq8",
            ),
        )

        private val addresses: Set<String> = all.map { it.address.lowercase() }.toSet()

        fun bot(address: String): KaChatBot? {
            val key = address.lowercase()
            return all.firstOrNull { it.address.lowercase() == key }
        }

        /** True for a bot's address. Bot chats never sit in the chat list: they are circles in
         *  the row above it (with groups and rooms), once you have written to the bot (iOS
         *  d576941, 5fcf755). */
        fun isBot(address: String): Boolean = address.lowercase() in addresses
    }
}
