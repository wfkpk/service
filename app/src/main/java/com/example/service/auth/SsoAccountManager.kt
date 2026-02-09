package com.example.service.auth

import android.accounts.AccountManager
import android.content.Context
import android.accounts.Account as AndroidAccount

class SsoAccountManager(context: Context) {

    companion object {
        const val ACCOUNT_TYPE = "com.example.ssoapi.account"
        private const val KEY_GUID = "guid"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_PROFILE_IMAGE = "profile_image"
    }

    private val accountManager = AccountManager.get(context)

    fun addOrUpdateAccount(
        guid: String,
        mail: String,
        sessionToken: String,
        profileImage: String?
    ) {
        val existing = accountManager.getAccountsByType(ACCOUNT_TYPE)
            .firstOrNull { it.name == mail }

        if (existing != null) {
            accountManager.setUserData(existing, KEY_GUID, guid)
            accountManager.setUserData(existing, KEY_SESSION_TOKEN, sessionToken)
            accountManager.setUserData(existing, KEY_PROFILE_IMAGE, profileImage)
        } else {
            val account = AndroidAccount(mail, ACCOUNT_TYPE)
            accountManager.addAccountExplicitly(account, null, null)
            accountManager.setUserData(account, KEY_GUID, guid)
            accountManager.setUserData(account, KEY_SESSION_TOKEN, sessionToken)
            accountManager.setUserData(account, KEY_PROFILE_IMAGE, profileImage)
        }
    }

    fun removeAccount(mail: String) {
        val account = accountManager.getAccountsByType(ACCOUNT_TYPE)
            .firstOrNull { it.name == mail }
        if (account != null) {
            accountManager.removeAccountExplicitly(account)
        }
    }

    fun removeAllAccounts() {
        accountManager.getAccountsByType(ACCOUNT_TYPE).forEach { account ->
            accountManager.removeAccountExplicitly(account)
        }
    }

    fun getSessionToken(mail: String): String? {
        val account = accountManager.getAccountsByType(ACCOUNT_TYPE)
            .firstOrNull { it.name == mail }
        return account?.let { accountManager.getUserData(it, KEY_SESSION_TOKEN) }
    }

    fun getAllAccountMails(): List<String> {
        return accountManager.getAccountsByType(ACCOUNT_TYPE).map { it.name }
    }

    fun removeAccountsNotIn(validMails: Set<String>) {
        accountManager.getAccountsByType(ACCOUNT_TYPE).forEach { account ->
            if (account.name !in validMails) {
                accountManager.removeAccountExplicitly(account)
            }
        }
    }
}
