package com.example.service.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.ssoapi.Account

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val guid: String,
    val mail: String,
    val profileImage: String?,
    val sessionToken: String,
    val isActive: Boolean = false
) {
    fun toAccount(): Account = Account(guid, mail, profileImage, sessionToken, isActive)

    companion object {
        fun fromAccount(account: Account): AccountEntity =
            AccountEntity(account.guid, account.mail, account.profileImage, account.sessionToken, account.isActive)
    }
}
