package com.example.service

import android.app.Notification
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
import com.example.service.db.AccountDatabase
import com.example.service.db.AccountEntity
import com.example.ssoapi.Account
import com.example.ssoapi.sso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import kotlinx.coroutines.launch

/**
 * Persistent background service for SSO account management.
 * - Starts on device boot via BootReceiver
 * - Runs continuously (START_STICKY)
 * - Client apps bind via AIDL to perform login, logout, switch account
 */
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

    private val binder = object : sso.Stub() {
        
        @Throws(RemoteException::class)
        override fun login(account: Account?) {
            Log.d(TAG, "login() called with account: id=${account?.id}, email=${account?.email}")
            if (account == null) {
                Log.w(TAG, "login() called with null account")
                return
            }
            scope.launch {
                try {
                    // Use email as unique identifier if ID is empty
                    val accountId = if (account.id.isNullOrEmpty()) account.email else account.id
                    
                    // Check if account already exists by ID or email
                    val existingAccount = if (account.id.isNullOrEmpty()) {
                        accountDao.getAccountByEmail(account.email)
                    } else {
                        accountDao.getAccountById(account.id)
                    }
                    
                    val countBefore = accountDao.getAccountCount()
                    Log.d(TAG, "Account count before: $countBefore, existing: ${existingAccount != null}")
                    
                    if (existingAccount != null) {
                        // Account exists - just make it active
                        Log.d(TAG, "Account ${existingAccount.email} exists, setting as active")
                        accountDao.setActiveAccount(existingAccount)
                    } else {
                        // New account - check if we have room (max 6 accounts)
                        if (countBefore >= MAX_ACCOUNTS) {
                            Log.w(TAG, "Cannot add account: max limit of $MAX_ACCOUNTS reached")
                            return@launch
                        }
                        // Add new account with proper ID (use email if ID is empty)
                        Log.d(TAG, "Adding new account ${account.email}")
                        val entity = AccountEntity(
                            id = accountId,
                            email = account.email,
                            name = account.name,
                            isActive = true
                        )
                        accountDao.setActiveAccount(entity)
                    }
                    
                    val countAfter = accountDao.getAccountCount()
                    Log.d(TAG, "Account count after: $countAfter")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during login", e)
                }
            }
        }

        @Throws(RemoteException::class)
        override fun logout(accountId: String?) {
            Log.d(TAG, "logout() called for accountId: $accountId")
            if (accountId.isNullOrEmpty()) return
            scope.launch {
                try {
                    // Get the account to check if it's active
                    val account = accountDao.getAccountById(accountId)
                    if (account != null) {
                        val wasActive = account.isActive
                        // Delete the account from database
                        accountDao.deleteAccountById(accountId)
                        Log.d(TAG, "Account $accountId removed from database")
                        
                        // If the deleted account was active, set another account as active
                        if (wasActive) {
                            val remainingAccounts = accountDao.getAllAccounts()
                            if (remainingAccounts.isNotEmpty()) {
                                accountDao.setActiveAccount(remainingAccounts.first())
                                Log.d(TAG, "Set ${remainingAccounts.first().id} as new active account")
                            }
                        }
                    } else {
                        Log.w(TAG, "Account $accountId not found for logout")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during logout", e)
                }
            }
        }

        @Throws(RemoteException::class)
        override fun logoutAll() {
            Log.d(TAG, "logoutAll() called")
            scope.launch {
                try {
                    accountDao.deleteAllAccounts()
                    Log.d(TAG, "All accounts removed from database")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during logoutAll", e)
                }
            }
        }

        @Throws(RemoteException::class)
        override fun switchAccount(account: Account?) {
            Log.d(TAG, "switchAccount() called with account: ${account?.email}")
            if (account == null) return
            scope.launch {
                try {
                    // Switch only works for existing accounts
                    val existingAccount = accountDao.getAccountById(account.id)
                    if (existingAccount != null) {
                        Log.d(TAG, "Switching to account ${account.id}")
                        accountDao.setActiveAccount(existingAccount)
                    } else {
                        Log.w(TAG, "Cannot switch: account ${account.id} not found")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during switch account", e)
                }
            }
        }

        @Throws(RemoteException::class)
        override fun getActiveAccount(): Account? {
            return try {
                scope.future { accountDao.getActiveAccount() }.get()?.toAccount()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting active account", e)
                null
            }
        }

        @Throws(RemoteException::class)
        override fun getAllAccounts(): MutableList<Account> {
            return try {
                scope.future { accountDao.getAllAccounts() }.get()
                    .map { it.toAccount() }
                    .toMutableList()
            } catch (e: Exception) {
                Log.e(TAG, "Error getting all accounts", e)
                mutableListOf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SsoService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SsoService started")
        startForeground()
        // START_STICKY: Service restarts automatically if killed by system
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SSO Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps SSO service running for account management"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun startForeground() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        Log.d(TAG, "SsoService running in foreground")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Client bound to SsoService")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Client unbound from SsoService")
        return true // Allow rebind
    }

    override fun onRebind(intent: Intent?) {
        Log.d(TAG, "Client rebound to SsoService")
    }

    override fun onDestroy() {
        Log.d(TAG, "SsoService destroyed")
        scope.cancel()
        super.onDestroy()
    }
}