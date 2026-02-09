package com.example.service.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: AccountEntity)

    @Update
    suspend fun updateAccount(account: AccountEntity)

    @Delete
    suspend fun deleteAccount(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccount(): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY mail ASC")
    suspend fun getAllAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE guid = :guid")
    suspend fun getAccountByGuid(guid: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE mail = :mail LIMIT 1")
    suspend fun getAccountByMail(mail: String): AccountEntity?

    @Transaction
    suspend fun setActiveAccount(account: AccountEntity) {
        clearActiveState()
        val existing = getAccountByGuid(account.guid)
        if (existing != null) {
            updateAccount(account.copy(isActive = true))
        } else {
            insertAccount(account.copy(isActive = true))
        }
    }

    @Query("UPDATE accounts SET isActive = 0 WHERE isActive = 1")
    suspend fun clearActiveState()

    @Query("DELETE FROM accounts WHERE guid = :guid")
    suspend fun deleteAccountByGuid(guid: String)

    @Query("DELETE FROM accounts")
    suspend fun deleteAllAccounts()

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun getAccountCount(): Int
}
