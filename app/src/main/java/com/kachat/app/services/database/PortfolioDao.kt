package com.kachat.app.services.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kachat.app.models.PortfolioTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolio_transactions WHERE walletAddress = :walletAddress AND portfolioId = :portfolioId ORDER BY timestampMillis ASC")
    fun getTransactions(walletAddress: String, portfolioId: String): Flow<List<PortfolioTransactionEntity>>

    /** Every portfolio's rows for this wallet, unfiltered by portfolioId — used by the picker header to compute all portfolios' cards at once. */
    @Query("SELECT * FROM portfolio_transactions WHERE walletAddress = :walletAddress ORDER BY timestampMillis ASC")
    fun getAllTransactionsForWallet(walletAddress: String): Flow<List<PortfolioTransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: PortfolioTransactionEntity)

    @Query("DELETE FROM portfolio_transactions WHERE id = :id")
    suspend fun delete(id: String)

    /** Used when a portfolio itself is deleted — removes its whole ledger. */
    @Query("SELECT COUNT(*) FROM portfolio_transactions WHERE portfolioId = :portfolioId")
    suspend fun countForPortfolio(portfolioId: String): Int

    @Query("DELETE FROM portfolio_transactions WHERE portfolioId = :portfolioId")
    suspend fun deleteAllForPortfolio(portfolioId: String)

    /** One-time claim of pre-wallet-scoping rows (walletAddress = "") for whichever account first loads Portfolio after the upgrade. A no-op once every row has been claimed. */
    @Query("UPDATE portfolio_transactions SET walletAddress = :walletAddress WHERE walletAddress = ''")
    suspend fun claimUnscopedTransactions(walletAddress: String)

    /** One-time claim of pre-portfolio-scoping rows (portfolioId = "") for this wallet's default portfolio. Run after [claimUnscopedTransactions] so a very old install claims wallet first, then portfolio. */
    @Query("UPDATE portfolio_transactions SET portfolioId = :portfolioId WHERE walletAddress = :walletAddress AND portfolioId = ''")
    suspend fun claimUnscopedPortfolio(walletAddress: String, portfolioId: String)
}
