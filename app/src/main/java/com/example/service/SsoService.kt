package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.service.auth.SsoAccountManager
import com.example.service.db.AccountDatabase
import com.example.service.db.AccountEntity
import com.example.service.network.ApiService
import com.example.ssoapi.Account
import com.example.ssoapi.AuthResult
import com.example.ssoapi.IAuthCallback
import com.example.ssoapi.sso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch

class SsoService : Service() {

    companion object {
        private const val TAG = "SsoService"
        const val ACTION_BIND = "com.example.ssoapi.BIND_SSO_SERVICE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sso_service_channel"
        private const val MAX_ACCOUNTS = 6
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val db by lazy { AccountDatabase.getDatabase(this) }
    private val accountDao by lazy { db.accountDao() }
    private val apiService by lazy { ApiService() }
    private val ssoAccountManager by lazy { SsoAccountManager(this) }

    private fun sendError(callback: IAuthCallback, message: String) {
        try {
            callback.onResult(AuthResult(success = false, fail = true, message = message))
        } catch (_: RemoteException) { }
    }

    private fun sendSuccess(callback: IAuthCallback, message: String, account: Account) {
        try {
            callback.onResult(AuthResult(success = true, fail = false, message = message))
            callback.onAccountReceived(account)
        } catch (_: RemoteException) { }
    }

    private fun launchWithCallback(callback: IAuthCallback, tag: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Error during $tag", e)
                sendError(callback, e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun saveAccount(entity: AccountEntity) {
        Log.d(TAG, "saveAccount() called for: ${entity.mail} (guid=${entity.guid}, isActive=${entity.isActive})")
        accountDao.setActiveAccount(entity)
        ssoAccountManager.addOrUpdateAccount(
            entity.guid, entity.mail, entity.sessionToken, entity.profileImage
        )
        Log.d(TAG, "saveAccount() completed: account saved to DB and AccountManager")
    }

    private suspend fun checkMaxAccounts(guid: String): Boolean {
        val count = accountDao.getAccountCount()
        val existing = accountDao.getAccountByGuid(guid)
        val isMaxReached = existing == null && count >= MAX_ACCOUNTS
        Log.d(TAG, "checkMaxAccounts() guid=$guid, count=$count, existing=${existing != null}, maxReached=$isMaxReached")
        return isMaxReached
    }

    private val binder = object : sso.Stub() {

        override fun login(mail: String?, password: String?, callback: IAuthCallback?) {
            Log.d(TAG, "login() called for: $mail")
            if (mail.isNullOrEmpty() || password.isNullOrEmpty() || callback == null) {
                Log.w(TAG, "login() rejected: mail=${if (mail.isNullOrEmpty()) "MISSING" else "ok"}, password=${if (password.isNullOrEmpty()) "MISSING" else "ok"}, callback=${if (callback == null) "NULL" else "ok"}")
                callback?.let { sendError(it, "Invalid parameters") }
                return
            }
            launchWithCallback(callback, "login") {
                Log.d(TAG, "login() fetching token for: $mail")
                val tokenResult = apiService.getToken(mail, password)
                if (tokenResult is ApiService.ApiResult.Error) {
                    Log.e(TAG, "login() token fetch failed: ${tokenResult.message}")
                    sendError(callback, tokenResult.message)
                    return@launchWithCallback
                }
                val token = (tokenResult as ApiService.ApiResult.Success).data
                Log.d(TAG, "login() token received for guid: ${token.guid}")

                Log.d(TAG, "login() fetching account info for guid: ${token.guid}")
                val infoResult = apiService.getAccountInfo(token.guid, token.sessionToken)
                val profileImage = (infoResult as? ApiService.ApiResult.Success)?.data?.profileImage
                val accountMail = (infoResult as? ApiService.ApiResult.Success)?.data?.mail ?: mail
                Log.d(TAG, "login() account info received: mail=$accountMail, hasProfileImage=${profileImage != null}")

                if (checkMaxAccounts(token.guid)) {
                    Log.w(TAG, "login() rejected: max accounts ($MAX_ACCOUNTS) reached")
                    sendError(callback, "Maximum of $MAX_ACCOUNTS accounts reached")
                    return@launchWithCallback
                }

                val entity = AccountEntity(token.guid, accountMail, profileImage, token.sessionToken, true)
                saveAccount(entity)
                Log.d(TAG, "login() successful for $accountMail (guid=${token.guid})")
                sendSuccess(callback, "Login successful", entity.toAccount())
            }
        }

        override fun register(mail: String?, password: String?, callback: IAuthCallback?) {
            Log.d(TAG, "register() called for: $mail")
            if (mail.isNullOrEmpty() || password.isNullOrEmpty() || callback == null) {
                Log.w(TAG, "register() rejected: mail=${if (mail.isNullOrEmpty()) "MISSING" else "ok"}, password=${if (password.isNullOrEmpty()) "MISSING" else "ok"}, callback=${if (callback == null) "NULL" else "ok"}")
                callback?.let { sendError(it, "Invalid parameters") }
                return
            }
            launchWithCallback(callback, "register") {
                Log.d(TAG, "register() calling API sign-in for: $mail")
                val result = apiService.signIn(mail, password)
                if (result is ApiService.ApiResult.Error) {
                    Log.e(TAG, "register() API sign-in failed: ${result.message}")
                    sendError(callback, result.message)
                    return@launchWithCallback
                }
                val data = (result as ApiService.ApiResult.Success).data
                Log.d(TAG, "register() API sign-in successful: guid=${data.guid}, mail=${data.mail}")

                if (checkMaxAccounts(data.guid)) {
                    Log.w(TAG, "register() rejected: max accounts ($MAX_ACCOUNTS) reached")
                    sendError(callback, "Maximum of $MAX_ACCOUNTS accounts reached")
                    return@launchWithCallback
                }

                val entity = AccountEntity(data.guid, data.mail, data.profileImage, data.sessionToken, true)
                saveAccount(entity)
                Log.d(TAG, "register() successful for ${data.mail} (guid=${data.guid})")
                sendSuccess(callback, "Registration successful", entity.toAccount())
            }
        }

        override fun logout(guid: String?) {
            Log.d(TAG, "logout() called for: $guid")
            if (guid.isNullOrEmpty()) {
                Log.w(TAG, "logout() rejected: guid is null or empty")
                return
            }
            scope.launch {
                try {
                    val account = accountDao.getAccountByGuid(guid)
                    if (account == null) {
                        Log.w(TAG, "logout() failed: account with guid=$guid not found")
                        return@launch
                    }
                    Log.d(TAG, "logout() removing account: ${account.mail}")
                    try { 
                        apiService.signOut(guid, account.sessionToken)
                        Log.d(TAG, "logout() API signOut successful")
                    } catch (e: Exception) {
                        Log.w(TAG, "logout() API signOut failed (continuing with local removal)", e)
                    }

                    val wasActive = account.isActive
                    accountDao.deleteAccountByGuid(guid)
                    ssoAccountManager.removeAccount(account.mail)
                    Log.d(TAG, "logout() account removed from DB and AccountManager")

                    if (wasActive) {
                        val nextAccount = accountDao.getAllAccounts().firstOrNull()
                        if (nextAccount != null) {
                            accountDao.setActiveAccount(nextAccount)
                            Log.d(TAG, "logout() switched active account to: ${nextAccount.mail}")
                        } else {
                            Log.d(TAG, "logout() no remaining accounts to set as active")
                        }
                    }
                    Log.d(TAG, "logout() completed for guid=$guid")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during logout", e)
                }
            }
        }

        override fun logoutAll() {
            Log.d(TAG, "logoutAll() called")
            scope.launch {
                try {
                    accountDao.getAllAccounts().forEach { account ->
                        try { apiService.signOut(account.guid, account.sessionToken) } catch (_: Exception) { }
                    }
                    accountDao.deleteAllAccounts()
                    ssoAccountManager.removeAllAccounts()
                    Log.d(TAG, "All accounts removed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during logoutAll", e)
                }
            }
        }

        override fun switchAccount(guid: String?) {
            Log.d(TAG, "switchAccount() called for: $guid")
            if (guid.isNullOrEmpty()) {
                Log.w(TAG, "switchAccount() rejected: guid is null or empty")
                return
            }
            scope.launch {
                try {
                    val account = accountDao.getAccountByGuid(guid)
                    if (account != null) {
                        accountDao.setActiveAccount(account)
                        Log.d(TAG, "switchAccount() successful: switched to ${account.mail}")
                    } else {
                        Log.w(TAG, "switchAccount() failed: account with guid=$guid not found")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during switchAccount", e)
                }
            }
        }

        override fun getActiveAccount(): Account? {
            Log.d(TAG, "getActiveAccount() called")
            return try {
                val account = kotlinx.coroutines.runBlocking {
                    accountDao.getActiveAccount()
                }?.toAccount()
                Log.d(TAG, "getActiveAccount() returning: ${account?.mail ?: "null"}")
                account
            } catch (e: Exception) {
                Log.e(TAG, "Error getting active account", e)
                null
            }
        }

        override fun getAllAccounts(): MutableList<Account> {
            Log.d(TAG, "getAllAccounts() called")
            return try {
                val accounts = kotlinx.coroutines.runBlocking {
                    accountDao.getAllAccounts()
                }.map { it.toAccount() }.toMutableList()
                Log.d(TAG, "getAllAccounts() returning ${accounts.size} accounts")
                accounts.forEachIndexed { index, account ->
                    Log.d(TAG, "  Account[$index]: guid=${account.guid}, mail=${account.mail}, isActive=${account.isActive}")
                    Log.d(TAG, "  Account[$index] details: sessionToken.length=${account.sessionToken.length}, profileImage=${account.profileImage}")
                }
                Log.d(TAG, "getAllAccounts() about to return list, size=${accounts.size}, isEmpty=${accounts.isEmpty()}")
                accounts
            } catch (e: Exception) {
                Log.e(TAG, "Error getting all accounts", e)
                mutableListOf()
            }
        }

        override fun fetchToken(mail: String?, password: String?, callback: IAuthCallback?) {
            Log.d(TAG, "fetchToken() called for: $mail")
            if (mail.isNullOrEmpty() || password.isNullOrEmpty() || callback == null) {
                Log.w(TAG, "fetchToken() rejected: mail=${if (mail.isNullOrEmpty()) "MISSING" else "ok"}, password=${if (password.isNullOrEmpty()) "MISSING" else "ok"}, callback=${if (callback == null) "NULL" else "ok"}")
                callback?.let { sendError(it, "Invalid parameters") }
                return
            }
            launchWithCallback(callback, "fetchToken") {
                Log.d(TAG, "fetchToken() calling API for: $mail")
                val result = apiService.getToken(mail, password)
                if (result is ApiService.ApiResult.Error) {
                    Log.e(TAG, "fetchToken() API call failed: ${result.message}")
                    sendError(callback, result.message)
                    return@launchWithCallback
                }
                val data = (result as ApiService.ApiResult.Success).data
                Log.d(TAG, "fetchToken() successful: guid=${data.guid}")
                val account = Account(data.guid, mail, null, data.sessionToken, false)
                sendSuccess(callback, "Token fetched", account)
            }
        }

        override fun fetchAccountInfo(guid: String?, sessionToken: String?, callback: IAuthCallback?) {
            Log.d(TAG, "fetchAccountInfo() called for: $guid")
            if (guid.isNullOrEmpty() || sessionToken.isNullOrEmpty() || callback == null) {
                Log.w(TAG, "fetchAccountInfo() rejected: guid=${if (guid.isNullOrEmpty()) "MISSING" else "ok"}, sessionToken=${if (sessionToken.isNullOrEmpty()) "MISSING" else "ok"}, callback=${if (callback == null) "NULL" else "ok"}")
                callback?.let { sendError(it, "Invalid parameters") }
                return
            }
            launchWithCallback(callback, "fetchAccountInfo") {
                Log.d(TAG, "fetchAccountInfo() calling API for guid: $guid")
                val result = apiService.getAccountInfo(guid, sessionToken)
                if (result is ApiService.ApiResult.Error) {
                    Log.e(TAG, "fetchAccountInfo() API call failed: ${result.message}")
                    sendError(callback, result.message)
                    return@launchWithCallback
                }
                val data = (result as ApiService.ApiResult.Success).data
                Log.d(TAG, "fetchAccountInfo() successful: mail=${data.mail}, hasProfileImage=${data.profileImage != null}")
                val account = Account(data.guid, data.mail, data.profileImage, sessionToken, false)
                sendSuccess(callback, "Account info fetched", account)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SsoService created")
        createNotificationChannel()
        printDatabaseState()
        syncAccountManagerWithDb()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SsoService started")
        startForeground()
        return START_STICKY
    }

    private fun printDatabaseState() {
        scope.launch {
            try {
                val allAccounts = accountDao.getAllAccounts()
                val activeAccount = accountDao.getActiveAccount()
                val accountCount = accountDao.getAccountCount()
                Log.d(TAG, "=== DATABASE STATE AT STARTUP ===")
                Log.d(TAG, "Total accounts in DB: $accountCount")
                Log.d(TAG, "Active account: ${activeAccount?.mail ?: "NONE"}")
                allAccounts.forEachIndexed { index, account ->
                    Log.d(TAG, "  Account[$index]: guid=${account.guid}, mail=${account.mail}, isActive=${account.isActive}, hasToken=${account.sessionToken.isNotEmpty()}")
                }
                Log.d(TAG, "=== END DATABASE STATE ===")
            } catch (e: Exception) {
                Log.e(TAG, "Error printing database state", e)
            }
        }
    }

    private fun syncAccountManagerWithDb() {
        scope.launch {
            try {
                val dbMails = accountDao.getAllAccounts().map { it.mail }.toSet()
                val amMails = ssoAccountManager.getAllAccountMails()
                Log.d(TAG, "Syncing AccountManager: DB has ${dbMails.size} accounts, AM has ${amMails.size} accounts")
                val staleCount = amMails.count { it !in dbMails }
                if (staleCount > 0) {
                    Log.w(TAG, "Removing $staleCount stale AccountManager entries not in Room DB")
                    ssoAccountManager.removeAccountsNotIn(dbMails)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing AccountManager with DB", e)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "SSO Service", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps SSO service running for account management"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSO Service")
            .setContentText("Managing account authentication")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind() called, action=${intent?.action}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind() called")
        return true
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "onRebind() called")
    }

    override fun onDestroy() {
        Log.d(TAG, "SsoService destroyed")
        scope.cancel()
        super.onDestroy()
    }
}
