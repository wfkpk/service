package com.example.service.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.ssoapi.Account

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val email: String,
    val name: String,
    val isActive: Boolean = false
) {
    // Convert to AIDL Account
    fun toAccount(): Account = Account(id, email, name, isActive)

    companion object {
        // Convert from AIDL Account
        fun fromAccount(account: Account): AccountEntity =
            AccountEntity(account.id, account.email, account.name, account.isActive)
    }
}
