package com.kachat.app.services

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.kachat.app.models.PortfolioEntity
import com.kachat.app.models.PortfolioFeeRecord
import com.kachat.app.models.PortfolioTransactionEntity

/**
 * A portfolio as Nextcloud Automatic Sync carries it: `{id, name, sortOrder, createdAt,
 * updatedAt?}` (NEXTCLOUD_SYNC.md section 5, Portfolios; iOS `Portfolio`). [id] is canonical - the
 * UUID in uppercase, as iOS's encoder writes it - so both platforms key a portfolio alike.
 */
data class SyncPortfolio(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long?,
)

/**
 * A ledger row as the sync carries it: `{id, type, amountSompi, fiatValue, timestamp, notes?,
 * portfolioId, sourceAddress?, sourceTxId?, updatedAt?}` (iOS `PortfolioTransaction`). [id] is
 * kept exactly as written (iOS compares it as a string); [portfolioId] is canonical.
 */
data class SyncTransaction(
    val id: String,
    val type: String,
    val amountSompi: Long,
    val fiatValue: Double,
    val timestampMillis: Long,
    val notes: String?,
    val portfolioId: String,
    val sourceAddress: String?,
    val sourceTxId: String?,
    val updatedAtMillis: Long?,
)

/**
 * A portfolio or a transaction deleted on this wallet, carried in the backup so a merge or restore
 * never brings it back: `{kind: "portfolio"|"transaction", id, deletedAt}` (iOS
 * `PortfolioTombstone`). A portfolio's [id] is canonical (uppercase UUID); a transaction's is its
 * id as written. Also how this device keeps them (per wallet, [PortfolioLedgerStore]).
 */
data class PortfolioTombstone(
    val kind: String,
    val id: String,
    val deletedAtMillis: Long,
)

/**
 * One wallet's portfolios as Nextcloud Automatic Sync carries them, and how two copies merge
 * (iOS `PortfolioSync`, 11f1548). Pure: the archive merge and the restore both use it. Per item
 * the newest `updatedAt` wins, unless a tombstone at or after it deletes it; a transaction or fee
 * lives only while its portfolio does.
 */
data class PortfolioSync(
    val portfolios: List<SyncPortfolio> = emptyList(),
    val transactions: List<SyncTransaction> = emptyList(),
    /** Fees with a canonical portfolioId; one fee is `portfolioId:txId` ([feeId]). */
    val fees: List<PortfolioFeeRecord> = emptyList(),
    val tombstones: List<PortfolioTombstone> = emptyList(),
) {
    val isEmpty: Boolean
        get() = portfolios.isEmpty() && transactions.isEmpty() && fees.isEmpty() && tombstones.isEmpty()

    /** What this device uploads: everything but a pristine seed. */
    fun forArchive(): PortfolioSync =
        copy(portfolios = portfolios.filterNot { isPristineSeed(it, transactions, fees) })

    /** The four archive keys, in the shape iOS decodes (dates ISO 8601, whole seconds). */
    fun toArchiveArrays(): ArchiveArrays = ArchiveArrays(
        portfolios = JsonArray().apply { portfolios.forEach { add(encodePortfolio(it)) } },
        transactions = JsonArray().apply { transactions.forEach { add(encodeTransaction(it)) } },
        fees = JsonArray().apply { fees.forEach { add(encodeFee(it)) } },
        deleted = JsonArray().apply { tombstones.forEach { add(encodeTombstone(it)) } },
    )

    /** `portfolios`, `portfolioTransactions`, `portfolioFees`, `portfolioDeleted`. */
    data class ArchiveArrays(
        val portfolios: JsonArray,
        val transactions: JsonArray,
        val fees: JsonArray,
        val deleted: JsonArray,
    )

    companion object {
        const val KEY_PORTFOLIOS = "portfolios"
        const val KEY_TRANSACTIONS = "portfolioTransactions"
        const val KEY_FEES = "portfolioFees"
        const val KEY_DELETED = "portfolioDeleted"

        const val KIND_PORTFOLIO = "portfolio"
        const val KIND_TRANSACTION = "transaction"

        /** The name every install seeds a wallet's first portfolio with. */
        const val SEED_NAME = "Portfolio 1"

        private val TYPES = setOf("buy", "sell", "transfer")
        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** A portfolio id as the sync writes and compares it (iOS `UUID.uuidString`), or null
         *  for one that isn't a UUID - iOS decodes these as UUIDs, so such a value never travels. */
        fun canonicalPortfolioId(raw: String?): String? {
            val trimmed = raw?.trim().orEmpty()
            return if (UUID_PATTERN.matches(trimmed)) trimmed.uppercase() else null
        }

        /** iOS `PortfolioFeeRecord.id`. */
        fun feeId(fee: PortfolioFeeRecord): String = "${fee.portfolioId}:${fee.txId}"

        /**
         * A wallet's untouched seed - "Portfolio 1", never edited, holding nothing. Every install
         * creates its own (each with its own id), so seeds are never synced: a device restoring
         * the real list drops its seed instead of showing two "Portfolio 1"s.
         */
        fun isPristineSeed(p: SyncPortfolio, transactions: List<SyncTransaction>, fees: List<PortfolioFeeRecord>): Boolean =
            p.updatedAtMillis == null && p.name == SEED_NAME &&
                transactions.none { it.portfolioId == p.id } && fees.none { it.portfolioId == p.id }

        /** The union of every side's tombstones, the newest per item. */
        fun mergeTombstones(sides: List<List<PortfolioTombstone>>): List<PortfolioTombstone> {
            val newest = LinkedHashMap<String, PortfolioTombstone>()
            for (t in sides.flatten()) {
                val key = "${t.kind}:${t.id}"
                val have = newest[key]
                if (have != null && have.deletedAtMillis >= t.deletedAtMillis) continue
                newest[key] = t
            }
            return newest.values.sortedWith(compareBy<PortfolioTombstone> { it.kind }.thenBy { it.id })
        }

        /** iOS `PortfolioSync.merge`: the earlier side wins a tie. */
        fun merge(sides: List<PortfolioSync>): PortfolioSync {
            val tombstones = mergeTombstones(sides.map { it.tombstones })
            val deletedAt = tombstones.associate { "${it.kind}:${it.id}" to it.deletedAtMillis }

            // portfolios: newest stamp per id, unless deleted at or after it
            val portfolios = LinkedHashMap<String, SyncPortfolio>()
            for (p in sides.flatMap { it.portfolios }) {
                val stamp = p.updatedAtMillis ?: p.createdAtMillis
                val have = portfolios[p.id]
                if (have != null && (have.updatedAtMillis ?: have.createdAtMillis) >= stamp) continue
                portfolios[p.id] = p
            }
            portfolios.entries.removeAll { (id, p) ->
                val gone = deletedAt["$KIND_PORTFOLIO:$id"]
                gone != null && gone >= (p.updatedAtMillis ?: p.createdAtMillis)
            }

            val transactions = LinkedHashMap<String, SyncTransaction>()
            for (t in sides.flatMap { it.transactions }) {
                val stamp = t.updatedAtMillis ?: Long.MIN_VALUE
                val have = transactions[t.id]
                if (have != null && (have.updatedAtMillis ?: Long.MIN_VALUE) >= stamp) continue
                transactions[t.id] = t
            }
            transactions.entries.removeAll { (id, t) ->
                if (!portfolios.containsKey(t.portfolioId)) return@removeAll true
                val gone = deletedAt["$KIND_TRANSACTION:$id"]
                gone != null && gone >= (t.updatedAtMillis ?: Long.MIN_VALUE)
            }

            val fees = LinkedHashMap<String, PortfolioFeeRecord>()
            for (f in sides.flatMap { it.fees }) {
                if (!portfolios.containsKey(f.portfolioId)) continue
                // a priced copy beats an unpriced one; otherwise either (they are the same fee)
                val have = fees[feeId(f)]
                if (have != null && (have.fiatValue != null || f.fiatValue == null)) continue
                fees[feeId(f)] = f
            }

            val txList = transactions.values.toList()
            val feeList = fees.values.toList()
            var list = portfolios.values.toList()
            // a seed only while nothing else is there
            if (list.any { !isPristineSeed(it, txList, feeList) }) {
                list = list.filterNot { isPristineSeed(it, txList, feeList) }
            }
            list = list.sortedWith(compareBy<SyncPortfolio> { it.sortOrder }.thenBy { it.createdAtMillis })
                .mapIndexed { index, p -> if (p.sortOrder == index) p else p.copy(sortOrder = index) }
            return PortfolioSync(
                portfolios = list,
                transactions = txList.sortedWith(compareBy<SyncTransaction> { it.timestampMillis }.thenBy { it.id }),
                fees = feeList.sortedBy { feeId(it) },
                tombstones = tombstones,
            )
        }

        // -----------------------------------------------------------------------------
        // This device's rows <-> the sync's shape
        // -----------------------------------------------------------------------------

        fun of(entity: PortfolioEntity): SyncPortfolio? {
            val id = canonicalPortfolioId(entity.id) ?: return null
            return SyncPortfolio(id, entity.name, entity.sortOrder, entity.createdAtMillis, entity.updatedAtMillis)
        }

        fun of(entity: PortfolioTransactionEntity): SyncTransaction? {
            val id = entity.id.trim().takeIf { it.isNotEmpty() } ?: return null
            val portfolioId = canonicalPortfolioId(entity.portfolioId) ?: return null
            if (entity.type !in TYPES) return null
            return SyncTransaction(
                id = id,
                type = entity.type,
                amountSompi = entity.amountSompi,
                fiatValue = entity.fiatValue,
                timestampMillis = entity.timestampMillis,
                notes = entity.notes,
                portfolioId = portfolioId,
                sourceAddress = entity.sourceAddress,
                sourceTxId = entity.sourceTxId,
                updatedAtMillis = entity.updatedAtMillis,
            )
        }

        fun ofFee(fee: PortfolioFeeRecord): PortfolioFeeRecord? {
            val portfolioId = canonicalPortfolioId(fee.portfolioId) ?: return null
            if (fee.txId.isNullOrBlank()) return null
            return fee.copy(portfolioId = portfolioId, sourceAddress = fee.sourceAddress.orEmpty())
        }

        // -----------------------------------------------------------------------------
        // Archive JSON (NEXTCLOUD_SYNC.md section 5): written in the strictest shape iOS's
        // decoder accepts - one bad element makes it drop a whole array - and read leniently,
        // one element at a time.
        // -----------------------------------------------------------------------------

        /** The four keys of one archive side. A key that is missing or unreadable counts as empty. */
        fun fromArchive(archive: JsonObject): PortfolioSync = fromArchive(
            archive.get(KEY_PORTFOLIOS), archive.get(KEY_TRANSACTIONS), archive.get(KEY_FEES), archive.get(KEY_DELETED)
        )

        fun fromArchive(
            portfolios: JsonElement?,
            transactions: JsonElement?,
            fees: JsonElement?,
            deleted: JsonElement?,
        ): PortfolioSync = PortfolioSync(
            portfolios = objects(portfolios).mapNotNull(::decodePortfolio),
            transactions = objects(transactions).mapNotNull(::decodeTransaction),
            fees = objects(fees).mapNotNull(::decodeFee),
            tombstones = objects(deleted).mapNotNull(::decodeTombstone),
        )

        private fun objects(element: JsonElement?): List<JsonObject> =
            if (element != null && element.isJsonArray) element.asJsonArray.mapNotNull { it as? JsonObject } else emptyList()

        private fun JsonObject.str(key: String): String? {
            val e = get(key) ?: return null
            if (!e.isJsonPrimitive) return null
            return runCatching { e.asString }.getOrNull()
        }

        private fun JsonObject.long(key: String): Long? {
            val e = get(key) ?: return null
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return null
            return runCatching { e.asLong }.getOrNull()
        }

        private fun JsonObject.double(key: String): Double? {
            val e = get(key) ?: return null
            if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return null
            return runCatching { e.asDouble }.getOrNull()?.takeIf { it.isFinite() }
        }

        /** ISO 8601, or iOS's default Date encoding (seconds since 2001) its decoder falls back to. */
        private fun JsonObject.date(key: String): Long? {
            val e = get(key) ?: return null
            if (!e.isJsonPrimitive) return null
            return AddressBookManager.parseDate(runCatching { e.asString }.getOrNull())
        }

        private fun decodePortfolio(o: JsonObject): SyncPortfolio? {
            val id = canonicalPortfolioId(o.str("id")) ?: return null
            val name = o.str("name") ?: return null
            val sortOrder = o.long("sortOrder")?.toInt() ?: return null
            val createdAt = o.date("createdAt") ?: return null
            return SyncPortfolio(id, name, sortOrder, createdAt, o.date("updatedAt"))
        }

        private fun decodeTransaction(o: JsonObject): SyncTransaction? {
            val id = o.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val type = o.str("type")?.takeIf { it in TYPES } ?: return null
            val amountSompi = o.long("amountSompi") ?: return null
            val fiatValue = o.double("fiatValue") ?: return null
            val timestamp = o.date("timestamp") ?: return null
            // iOS gives a row without one a throwaway UUID, which no portfolio has - dropped here.
            val portfolioId = canonicalPortfolioId(o.str("portfolioId")) ?: return null
            return SyncTransaction(
                id = id,
                type = type,
                amountSompi = amountSompi,
                fiatValue = fiatValue,
                timestampMillis = timestamp,
                notes = o.str("notes"),
                portfolioId = portfolioId,
                sourceAddress = o.str("sourceAddress"),
                sourceTxId = o.str("sourceTxId"),
                updatedAtMillis = o.date("updatedAt"),
            )
        }

        private fun decodeFee(o: JsonObject): PortfolioFeeRecord? {
            val txId = o.str("txId")?.takeIf { it.isNotEmpty() } ?: return null
            val portfolioId = canonicalPortfolioId(o.str("portfolioId")) ?: return null
            val sourceAddress = o.str("sourceAddress") ?: return null
            val amountSompi = o.long("amountSompi") ?: return null
            val timestamp = o.date("timestamp") ?: return null
            return PortfolioFeeRecord(
                txId = txId,
                portfolioId = portfolioId,
                sourceAddress = sourceAddress,
                amountSompi = amountSompi,
                timestampMillis = timestamp,
                fiatValue = o.double("fiatValue"),
            )
        }

        private fun decodeTombstone(o: JsonObject): PortfolioTombstone? {
            val kind = o.str("kind") ?: return null
            val deletedAt = o.date("deletedAt") ?: return null
            val id = when (kind) {
                KIND_PORTFOLIO -> canonicalPortfolioId(o.str("id"))
                KIND_TRANSACTION -> o.str("id")?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            } ?: return null
            return PortfolioTombstone(kind, id, deletedAt)
        }

        private fun iso(ms: Long): String = AddressBookManager.isoSeconds(ms)

        /** A non-finite number is not JSON; Gson would write it bare and iOS would refuse the file. */
        private fun finite(value: Double): Double = if (value.isFinite()) value else 0.0

        private fun encodePortfolio(p: SyncPortfolio) = JsonObject().apply {
            addProperty("createdAt", iso(p.createdAtMillis))
            addProperty("id", p.id)
            addProperty("name", p.name)
            addProperty("sortOrder", p.sortOrder)
            p.updatedAtMillis?.let { addProperty("updatedAt", iso(it)) }
        }

        private fun encodeTransaction(t: SyncTransaction) = JsonObject().apply {
            addProperty("amountSompi", t.amountSompi)
            addProperty("fiatValue", finite(t.fiatValue))
            addProperty("id", t.id)
            t.notes?.let { addProperty("notes", it) }
            addProperty("portfolioId", t.portfolioId)
            t.sourceAddress?.let { addProperty("sourceAddress", it) }
            t.sourceTxId?.let { addProperty("sourceTxId", it) }
            addProperty("timestamp", iso(t.timestampMillis))
            addProperty("type", t.type)
            t.updatedAtMillis?.let { addProperty("updatedAt", iso(it)) }
        }

        private fun encodeFee(f: PortfolioFeeRecord) = JsonObject().apply {
            addProperty("amountSompi", f.amountSompi)
            f.fiatValue?.takeIf { it.isFinite() }?.let { addProperty("fiatValue", it) }
            addProperty("portfolioId", f.portfolioId)
            addProperty("sourceAddress", f.sourceAddress)
            addProperty("timestamp", iso(f.timestampMillis))
            addProperty("txId", f.txId)
        }

        private fun encodeTombstone(t: PortfolioTombstone) = JsonObject().apply {
            addProperty("deletedAt", iso(t.deletedAtMillis))
            addProperty("id", t.id)
            addProperty("kind", t.kind)
        }
    }
}
