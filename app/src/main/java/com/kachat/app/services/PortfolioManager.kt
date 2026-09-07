package com.kachat.app.services

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kachat.app.models.PortfolioEntity
import com.kachat.app.services.database.KaChatDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the current wallet's list of up to 5 named [PortfolioEntity] ledgers and which one is
 * active. A portfolio is purely a bookkeeping split of the manually-entered buy/sell ledger — it
 * never changes the wallet's real address, keys, or on-chain balance. Backed by Room
 * ([com.kachat.app.services.database.PortfolioDefinitionDao]) rather than
 * EncryptedSharedPreferences for the list itself — portfolio names need no encryption and Room
 * gives free reactive Flows matching the rest of Portfolio — but the small "which portfolio is
 * currently selected" value is kept in EncryptedSharedPreferences, one key per wallet, mirroring
 * ColdStorageManager's own key-per-wallet pattern.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PortfolioManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: KaChatDatabase,
    private val walletManager: WalletManager
) {
    /** Serializes the seed-if-empty check with its insert - see [ensureDefaultPortfolio]. */
    private val defaultSeedMutex = Mutex()

    companion object {
        const val MAX_PORTFOLIOS = 5
        /** The name a seeded first portfolio carries, and the only name the duplicate cleanup
         *  in [ensureDefaultPortfolio] will touch. */
        private const val DEFAULT_PORTFOLIO_NAME = "Portfolio 1"
        private const val SECURE_PREFS_NAME = "portfolio_manager_secure_prefs"
        private const val ACTIVE_PORTFOLIO_KEY_PREFIX = "active_portfolio_"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPrefs = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Set immediately by [setActivePortfolio] so the switch is reflected instantly, without waiting on a disk round-trip through [activePortfolioIdFlow]'s recomputation. */
    private val activePortfolioIdOverride = MutableStateFlow<String?>(null)

    private fun activePortfolioKey(walletAddress: String) =
        ACTIVE_PORTFOLIO_KEY_PREFIX + walletAddress.replace(":", "_")

    private fun loadStoredActiveId(walletAddress: String): String? =
        sharedPrefs.getString(activePortfolioKey(walletAddress), null)

    /** Every portfolio for the currently active wallet — seeds a default "Portfolio 1" inline on first read for a wallet that has none yet, same "claim/seed before read" shape as [com.kachat.app.services.database.PortfolioDao.claimUnscopedTransactions]. */
    fun getPortfolios(): Flow<List<PortfolioEntity>> =
        walletManager.activeAddressFlow.flatMapLatest { address ->
            if (address == null) {
                flowOf(emptyList())
            } else {
                flow {
                    ensureDefaultPortfolio(address)
                    emitAll(database.portfolioDefinitionDao().getPortfolios(address))
                }
            }
        }

    /**
     * The active portfolio id for the current wallet: the persisted last-selected id if it still
     * exists among the wallet's current portfolios, else the first portfolio. A mismatched or
     * stale override (e.g. left over from a wallet switch) self-heals here rather than needing
     * explicit resetting, since it's checked against the freshly-scoped portfolio list on every
     * emission. Null only while the wallet has no portfolios yet (momentary, before
     * [ensureDefaultPortfolio] seeds one).
     */
    val activePortfolioIdFlow: Flow<String?> =
        combine(walletManager.activeAddressFlow, getPortfolios(), activePortfolioIdOverride) { address, portfolios, override ->
            if (address == null || portfolios.isEmpty()) {
                null
            } else {
                val stored = override ?: loadStoredActiveId(address)
                portfolios.firstOrNull { it.id == stored }?.id ?: portfolios.first().id
            }
        }.distinctUntilChanged()

    /**
     * Seeds "Portfolio 1" for [walletAddress] if it has no portfolios yet, and cleans up the
     * duplicates an earlier build could leave behind.
     *
     * Called inline from [getPortfolios] on every subscription, so callers cannot forget it - but
     * that means it runs once per collector, and count-then-insert is check-then-act. The
     * portfolio screen, its picker header and the view model all subscribe at once on a fresh
     * account, all three saw a count of zero before any insert landed, and a new account opened
     * with three identical "Portfolio 1" cards. The mutex makes the check and the insert one
     * step, so only the first caller through creates anything.
     *
     * The cleanup is deliberately narrow: it only removes extra portfolios that still carry the
     * seeded name AND hold no transactions, keeping the earliest. A portfolio someone renamed or
     * put a single row into is theirs, whatever created it.
     */
    private suspend fun ensureDefaultPortfolio(walletAddress: String) = defaultSeedMutex.withLock {
        val dao = database.portfolioDefinitionDao()
        val existing = dao.getPortfoliosOnce(walletAddress)
        if (existing.isEmpty()) {
            dao.insert(
                PortfolioEntity(
                    id = UUID.randomUUID().toString(),
                    walletAddress = walletAddress,
                    name = DEFAULT_PORTFOLIO_NAME,
                    sortOrder = 0,
                    createdAtMillis = System.currentTimeMillis()
                )
            )
            return@withLock
        }
        val seeded = existing.filter { it.name == DEFAULT_PORTFOLIO_NAME }
        if (seeded.size < 2) return@withLock
        val keep = seeded.minByOrNull { it.createdAtMillis } ?: return@withLock
        for (duplicate in seeded) {
            if (duplicate.id == keep.id) continue
            if (database.portfolioDao().countForPortfolio(duplicate.id) == 0) {
                dao.delete(duplicate.id)
            }
        }
        normalizeSortOrder(walletAddress)
    }

    suspend fun addPortfolio(name: String): PortfolioEntity? {
        val address = walletManager.getAddress()
        val count = database.portfolioDefinitionDao().count(address)
        if (count >= MAX_PORTFOLIOS) return null
        val trimmed = name.trim()
        val entity = PortfolioEntity(
            id = UUID.randomUUID().toString(),
            walletAddress = address,
            name = trimmed.ifBlank { "Portfolio ${count + 1}" },
            sortOrder = count,
            createdAtMillis = System.currentTimeMillis()
        )
        database.portfolioDefinitionDao().insert(entity)
        normalizeSortOrder(address)
        setActivePortfolio(entity.id)
        return entity
    }

    /**
     * Rewrites sortOrder to match position, so the stored order is always 0 until count with no
     * gaps and no duplicates. Run after every add, delete and reorder - it never was, so deleting
     * from the middle and adding another handed the new portfolio a sortOrder the survivor already
     * had.
     */
    private suspend fun normalizeSortOrder(walletAddress: String) {
        val dao = database.portfolioDefinitionDao()
        val ordered = dao.getPortfoliosOnce(walletAddress)
        val renumbered = ordered.mapIndexed { index, portfolio ->
            if (portfolio.sortOrder == index) portfolio else portfolio.copy(sortOrder = index)
        }
        if (renumbered != ordered) dao.insertAll(renumbered)
    }

    /**
     * Applies a reorder. [orderedIds] must be a permutation of this wallet's portfolios; anything
     * else is ignored rather than partially applied.
     */
    suspend fun reorderPortfolios(orderedIds: List<String>) {
        val address = walletManager.getAddress()
        val dao = database.portfolioDefinitionDao()
        val current = dao.getPortfoliosOnce(address)
        if (orderedIds.size != current.size || orderedIds.toSet() != current.map { it.id }.toSet()) return
        val byId = current.associateBy { it.id }
        dao.insertAll(orderedIds.mapIndexedNotNull { index, id -> byId[id]?.copy(sortOrder = index) })
    }

    suspend fun renamePortfolio(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val existing = database.portfolioDefinitionDao().getById(id) ?: return
        database.portfolioDefinitionDao().insert(existing.copy(name = trimmed))
    }

    /** Never allows deleting the last remaining portfolio — every wallet must always have at least one. Also deletes that portfolio's own ledger rows. */
    suspend fun deletePortfolio(id: String) {
        val address = walletManager.getAddress()
        if (database.portfolioDefinitionDao().count(address) <= 1) return
        database.portfolioDefinitionDao().delete(id)
        database.portfolioDao().deleteAllForPortfolio(id)
        normalizeSortOrder(address)
    }

    fun setActivePortfolio(id: String) {
        val address = walletManager.getAddress()
        sharedPrefs.edit().putString(activePortfolioKey(address), id).apply()
        activePortfolioIdOverride.value = id
    }
}
