package com.kachat.app.services.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kachat.app.models.PortfolioEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortfolioDefinitionDao {
    // Tie-broken by createdAtMillis: sortOrder was never renumbered after a delete, so older
    // installs can hold duplicates (delete the middle of three, add another, and the new one is
    // handed a sortOrder the survivor already has). With equal keys and no tie-break, two cards
    // could swap places between launches.
    @Query("SELECT * FROM portfolios WHERE walletAddress = :walletAddress ORDER BY sortOrder ASC, createdAtMillis ASC")
    fun getPortfolios(walletAddress: String): Flow<List<PortfolioEntity>>

    @Query("SELECT * FROM portfolios WHERE walletAddress = :walletAddress ORDER BY sortOrder ASC, createdAtMillis ASC")
    suspend fun getPortfoliosOnce(walletAddress: String): List<PortfolioEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(portfolios: List<PortfolioEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(portfolio: PortfolioEntity)

    @Query("DELETE FROM portfolios WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM portfolios WHERE walletAddress = :walletAddress")
    suspend fun count(walletAddress: String): Int

    @Query("SELECT * FROM portfolios WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PortfolioEntity?
}
