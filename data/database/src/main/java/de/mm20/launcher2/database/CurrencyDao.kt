package de.mm20.launcher2.database

import androidx.room.*
import de.mm20.launcher2.database.entities.CurrencyEntity

@Dao
interface CurrencyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(currencies: List<CurrencyEntity>)

    @Query("SELECT * FROM Currency WHERE symbol = :symbol")
    fun getCurrency(symbol: String) : CurrencyEntity?

    @Query("SELECT * FROM Currency")
    fun getAllCurrencies() : List<CurrencyEntity>

    @Transaction
    fun exists(symbol: String): Boolean {
        return getCurrency(symbol) != null
    }

    @Query("SELECT lastUpdate FROM Currency WHERE symbol = :symbol")
    fun getLastUpdate(symbol: String) : Long
}