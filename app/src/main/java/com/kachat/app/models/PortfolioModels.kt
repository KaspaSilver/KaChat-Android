package com.kachat.app.models

import androidx.room.Entity

/**
 * A buy or sell in the KAS portfolio tracker — either manually entered, or auto-imported from a
 * Kaspa address's on-chain history via "Add Kaspa Address" ([sourceAddress]/[sourceTxId] set).
 * On-chain auto-import is a deliberate, explicit opt-in: an address's transaction history can't
 * distinguish a real trade from an ordinary incoming/outgoing KaChat payment or Kaspa protocol
 * overhead like message self-stashes, so every send/receive on the address is imported as-is
 * (received = buy, sent = sell) with no attempt to filter those out — manual entry remains the
 * default, filtered-by-nothing-because-you-typed-it-yourself path, the same model CoinMarketCap's
 * own portfolio feature uses.
 *
 * Scoped per wallet address (v27->v28 migration) — each account gets its own ledger, matching
 * how contacts/messages/broadcasts/groups are already scoped, rather than mixing every
 * account's manual entries into one shared list. Rows from before the migration have
 * `walletAddress = ""`; [com.kachat.app.repository.PortfolioRepository] claims them for
 * whichever account first loads Portfolio post-upgrade.
 *
 * Further scoped within a wallet to one of up to 5 named [PortfolioEntity] ledgers via
 * [portfolioId] (v28->v29 migration). Rows from before that migration have `portfolioId = ""`;
 * [com.kachat.app.repository.PortfolioRepository] claims them for the wallet's default portfolio.
 */
@Entity(tableName = "portfolio_transactions", primaryKeys = ["id"])
data class PortfolioTransactionEntity(
    val id: String,
    val walletAddress: String = "",
    val portfolioId: String = "",
    /** "buy" | "sell" | "transfer". A transfer is KAS moved between your own addresses - sent
     *  away and brought back later, or between two wallets. A record only: it changes neither
     *  holdings nor cost basis nor profit, so marking both ends of a move as transfers keeps the
     *  portfolio right (iOS 7423330). */
    val type: String,
    val amountSompi: Long,     // KAS amount, sompi — matches how amounts are stored everywhere else in the app
    val fiatValue: Double,     // total USD paid (buy) or received (sell) for this transaction
    val timestampMillis: Long,
    val notes: String? = null,
    // v29->v30 migration. Null for manual/CSV rows. sourceTxId exists purely so re-importing the
    // same address only adds transactions not already present for it (deduped by on-chain tx id).
    val sourceAddress: String? = null,
    val sourceTxId: String? = null
)

/**
 * One of up to 5 named, independent buy/sell ledgers a wallet can have — e.g. "Investing",
 * "Long Term" — all still tracking the same on-chain wallet/address. Only the manually-entered
 * transaction ledger and its derived P&L are separated per portfolio; nothing about the wallet
 * itself (balance, address, keys) changes based on which portfolio is active. Mirrors
 * [PortfolioTransactionEntity]'s wallet-scoping, keyed by [walletAddress].
 */
@Entity(tableName = "portfolios", primaryKeys = ["id"])
data class PortfolioEntity(
    val id: String,
    val walletAddress: String,
    val name: String,
    val sortOrder: Int,
    val createdAtMillis: Long
)

/**
 * A network fee one of a portfolio's imported addresses paid on a transaction it sent - messages,
 * handshakes and payments alike. Recorded by the address import beside the buy/sell rows and
 * totalled on the Fees Spent card. [fiatValue] is the fee at that day's KAS price, null until
 * that price is known (the background backfill fills it in like a row's). Mirrors iOS
 * `PortfolioFeeRecord` (61eff0f).
 */
data class PortfolioFeeRecord(
    val txId: String,
    val portfolioId: String,
    val sourceAddress: String,
    val amountSompi: Long,
    val timestampMillis: Long,
    val fiatValue: Double? = null,
) {
    val amountKas: Double get() = amountSompi / 100_000_000.0
}

/** The Fees Spent card's numbers for one portfolio. */
data class PortfolioFeeSummary(
    val totalKas: Double = 0.0,
    /** Only fees whose day's price is known; [unpricedCount] says how many are still missing. */
    val totalFiat: Double = 0.0,
    val count: Int = 0,
    val unpricedCount: Int = 0,
)

/**
 * Realized profit and loss for one calendar year: every sell dated in that year, against the cost
 * of the KAS it sold, taken from the oldest buys first (FIFO). Buys from earlier years still
 * count as lots - only the sells are limited to the year. Mirrors iOS `RealizedPL`.
 */
data class RealizedPL(
    val year: Int,
    val proceeds: Double = 0.0,
    val costBasis: Double = 0.0,
    val sellCount: Int = 0,
    /** KAS those sells sold beyond every buy on record - counted at zero cost, so it adds to the
     *  gain. Usually KAS that came in from somewhere the portfolio doesn't track. */
    val uncoveredKas: Double = 0.0,
    /** Rows in the calculation whose price is still loading (they count as 0 until it lands). */
    val pendingPriceCount: Int = 0,
) {
    val amount: Double get() = proceeds - costBasis
}
