# SSO Login — Complete Thread & Coroutine Deep Dive

## Architecture Overview

```
┌──────────────────────────────────┐       ┌──────────────────────────┐
│          account2 process        │       │  com.example.service     │
│                                  │       │       process            │
│  ┌────────────────┐              │       │                          │
│  │ AccountView-   │              │       │  ┌──────────────────┐   │
│  │ Model          │              │  IPC  │  │   SsoService     │   │
│  │                │   ┌──────────┼──────►│  │   (Binder)       │   │
│  │  login()       │   │sso-api-  │       │  │                  │   │
│  │  register()    ├──►│lib       │◄──────┼──│  login()         │   │
│  │  logout()      │   │(SsoApi-  │       │  │  register()      │   │
│  │                │   │ Client)  │       │  │  logout()        │   │
│  └────────────────┘   └──────────┘       │  └──────────────────┘   │
│                                  │       │                          │
└──────────────────────────────────┘       └──────────────────────────┘

sso-api-lib is an AAR — compiled INTO account2. Same process, same memory.
com.example.service is a SEPARATE process. Communication = Android IPC (Binder).
```

---

## Every Thread & Coroutine — Complete Map

### account2 + sso-api-lib (same process)

| # | Thread / Scope | Owner | Dispatcher | Purpose |
|---|---|---|---|---|
| 1 | **Main Thread** | Android OS | `Dispatchers.Main` | UI, Compose, StateFlow, `mainHandler.post{}` |
| 2 | **viewModelScope** | [AccountViewModel](file:///c:/Users/krpra/AndroidStudioProjects/account2/app/src/main/java/com/example/account/viewmodel/AccountViewModel.kt#14-219) | `Dispatchers.Main` | [register()](file:///c:/Users/krpra/AndroidStudioProjects/service/app/src/main/java/com/example/service/SsoService.kt#164-194), [logout()](file:///c:/Users/krpra/AndroidStudioProjects/service/app/src/main/java/com/example/service/SsoService.kt#195-236), [switchAccount()](file:///c:/Users/krpra/AndroidStudioProjects/service/app/src/main/java/com/example/service/SsoService.kt#253-273), startup fetch |
| 3 | **clientScope** | [SsoApiClient](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#40-562) | `Dispatchers.Main + SupervisorJob` | Lazy connect before [login()](file:///c:/Users/krpra/AndroidStudioProjects/sso-api-lib/ssoapi/src/main/java/com/example/ssoapi/SsoApiClient.kt#212-260) |
| 4 | **Binder Thread (incoming)** | Android OS | none (raw thread) | Receives AIDL callbacks FROM service |

### com.example.service (separate process)

| # | Thread / Scope | Owner | Dispatcher | Purpose |
|---|---|---|---|---|
| 5 | **Main Thread** | Android OS | — | Receives Binder calls (login, register etc.) |
| 6 | **scope (service)** | [SsoService](file:///c:/Users/krpra/AndroidStudioProjects/service/app/src/main/java/com/example/service/SsoService.kt#32-452) | `Dispatchers.IO + SupervisorJob` | All async work: network calls, DB |
| 7 | **Binder Thread (outgoing)** | Android OS | none (raw thread) | Sends IAuthCallback results back to account2 |

---

## All Coroutine Scopes

### 1. `viewModelScope` — in AccountViewModel (account2)

```kotlin
// Created automatically by AndroidViewModel
// Lives while ViewModel is alive, cancelled in onCleared()
// Dispatcher: Dispatchers.Main

// Used by:
fun register(...)   { viewModelScope.launch { ... } }
fun logout(...)     { viewModelScope.launch { ... } }
fun logoutAll(...)  { viewModelScope.launch { ... } }
fun switchAccount(){ viewModelScope.launch { ... } }
fun fetchAccountsOnStartup() { viewModelScope.launch { ... } }

// NOT used by login() — login() is fully non-suspend
```

### 2. `clientScope` — in SsoApiClient (sso-api-lib, lives in account2 process)

```kotlin
private val clientScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
// Lives while SsoApiClient is alive
// Cancelled in unbind() → called from AccountViewModel.onCleared()
// Dispatcher: Dispatchers.Main (so it can call bindService & resume on Main)

// Used ONLY by: login() lazy-connect path
fun login(...) {
    if (not connected) {
        clientScope.launch {           // ← launches here
            ensureConnected()          // suspend: calls tryConnect()
            doLogin(...)               // then calls AIDL
        }
    }
}
```

### 3. `scope` — in SsoService (com.example.service process)

```kotlin
private val job = SupervisorJob()
private val scope = CoroutineScope(Dispatchers.IO + job)
// Lives while SsoService is running
// Cancelled in onDestroy() via job.cancel() / scope.cancel()
// Dispatcher: Dispatchers.IO (for network + DB)

// Used by: login(), register(), logout(), logoutAll(), switchAccount(), fetchToken(), fetchAccountInfo()
override fun login(...) {
    scope.launch {     // ← all async work here
        apiService.getToken(...)           // network - IO
        apiService.getAccountInfo(...)     // network - IO
        accountDao.setActiveAccount(...)   // Room DB - IO
        callback.onResult(...)             // IPC back to account2
    }
}
```

---

## The Login Call — Step by Step with Threads

### Entry Point

```
[Main Thread — account2]

User taps Sign In button
    └─► LoginScreen Button onClick
    └─► viewModel.login("user@x.com", "pass123")
    └─► AccountViewModel.login():

            _loginState.value = LoginState.Loading     // StateFlow emit on Main ✓
            _errorMessage.value = null

            val immediate = ssoApiClient.login(        // non-suspend, returns fast
                mail = "user@x.com",
                password = "pass123",
                onResult = { result -> ... }           // ← lambda stored in memory
            )
```

### Inside ssoApiClient.login()

```
[Main Thread — still in account2]

SsoApiClient.login():

    ┌─ CASE A: Already connected (fast path) ─────────────────────────────────┐
    │                                                                           │
    │   ssoService != null && isBound == true                                  │
    │   └─► doLogin(service, mail, password, onResult)   ← goes to Step 3     │
    │   └─► returns SaResultData immediately to AccountViewModel               │
    │                                                                           │
    └───────────────────────────────────────────────────────────────────────────┘

    ┌─ CASE B: Not connected (lazy path) ─────────────────────────────────────┐
    │                                                                           │
    │   ssoService == null || isBound == false                                 │
    │   └─► clientScope.launch { ... }   ← starts coroutine, returns fast     │
    │   └─► returns SaResultData(success=true, "Connecting") immediately       │
    │                                                                           │
    │   Meanwhile, coroutine runs:                                              │
    │   [clientScope coroutine — Dispatchers.Main, but suspends freely]        │
    │       ensureConnected()                                                   │
    │           └─► tryConnect() [withContext(Dispatchers.Main)]               │
    │               └─► context.bindService(intent, serviceConnection, ...)    │
    │               └─► suspendCancellableCoroutine { ... } ← SUSPENDS         │
    │                   (coroutine paused, Main Thread FREE for other work)    │
    │                                                                           │
    │   [some time: 100–500ms for service to start/connect]                    │
    │                                                                           │
    │   [Main Thread]                                                           │
    │       ServiceConnection.onServiceConnected(binder) fires                 │
    │           ssoService = sso.Stub.asInterface(binder)                      │
    │           isBound = true                                                  │
    │           pendingConnection?.invoke(true)   ← resumes the coroutine      │
    │                                                                           │
    │   [clientScope coroutine resumes — Dispatchers.Main]                     │
    │       ensureConnected() returns true                                      │
    │       └─► doLogin(service, mail, password, onResult)                     │
    │                                                                           │
    └───────────────────────────────────────────────────────────────────────────┘
```

### doLogin() — The IPC Call

```
[Main Thread (fast path) OR clientScope coroutine (lazy path)]

doLogin():
    ① Builds IAuthCallback.Stub():     ← AIDL callback implementation
       override fun onResult(result: AuthResult) { ... }
       override fun onAccountReceived(account: Account) { ... }

    ② service.login("user@x.com", "pass123", callback)
       │
       │  ⚠️  THIS IS A SYNCHRONOUS BINDER IPC CALL
       │  Current thread BLOCKS until service.login() returns SaResultData
       │  (usually microseconds — service returns immediately after storing callback)
       │
       ▼
       ══════════════════ CROSSES PROCESS BOUNDARY ══════════════════
```

### Inside SsoService.binder.login() — Service Process

```
[Binder Thread — com.example.service process]
(Android OS creates Binder threads in the service process to handle incoming IPC)

SsoService.binder.login("user@x.com", "pass123", callback):

    ① Validate params (synchronous):
       mail blank?     → return SaResultData.rejected("mail missing")
       password blank? → return SaResultData.rejected("password missing")
       callback null?  → return SaResultData.rejected("callback null")

    ② Store callback:
       pendingLoginCallbacks["user@x.com"] = callback
       (ConcurrentHashMap — thread-safe, Binder thread writes, IO coroutine reads)

    ③ Launch async work:
       scope.launch {  ← Dispatchers.IO coroutine in service
           // goes to async login below
       }

    ④ return SaResultData.accepted()
       │
       ▼
       ════ CROSSES BACK TO ACCOUNT2 ════
       doLogin() in account2 receives SaResultData immediately
       account2's Main Thread / clientScope unblocks
```

### Async Login Work in Service

```
[scope coroutine — Dispatchers.IO — com.example.service process]

① apiService.getToken("user@x.com", "pass123")
   └─► HTTP POST to your auth API endpoint
   └─► Suspends on IO, response comes back
   └─► Success: { guid, sessionToken }
   └─► Failure: sendError(cb, "Wrong password") → IPC callback back

② apiService.getAccountInfo(guid, sessionToken)
   └─► HTTP GET to fetch profile/mail/image
   └─► Suspends on IO

③ checkMaxAccounts(guid)
   └─► accountDao.getAccountCount()   ← Room DB query on IO thread
   └─► if count >= 6: sendError(cb, "Max accounts reached")

④ accountDao.setActiveAccount(entity) ← Room DB write on IO thread
   ssoAccountManager.addOrUpdateAccount(...)

⑤ sendSuccess(cb, "Login successful", entity.toAccount())
   ├─► cb.onResult(AuthResult(success=true))    ← IPC to account2
   └─► cb.onAccountReceived(account)            ← IPC to account2

   finally: pendingLoginCallbacks.remove("user@x.com")
```

### IAuthCallback Arrives in account2

```
[Binder Thread — account2 process] ← NOT Main Thread!

IAuthCallback.Stub.onResult(AuthResult(success=true, ...)):
    resultReceived = true
    wasSuccess = true
    result.fail is false → do nothing yet (wait for onAccountReceived)

IAuthCallback.Stub.onAccountReceived(account):
    if wasSuccess:
        mainHandler.post {          ← ⚠️ CRITICAL: jump to Main Thread
            onResult(Result.success(account))   ← fires account2's lambda
        }
```

> **Why `mainHandler.post{}`?**
> The Binder thread is a raw OS thread — StateFlow must be updated on Main Thread.
> Without `mainHandler.post{}` you get `IllegalStateException: Cannot access main thread`.

### account2's Lambda Fires

```
[Main Thread — account2]

AccountViewModel's onResult lambda:
    result = Result.success(account)
    account.mail = "user@x.com"
    account.guid = "abc-123-..."
    
    result.fold(
        onSuccess = { account ->
            val activeAccount = account.copy(isActive = true)
            _activeAccount.value = activeAccount       // StateFlow emit ✓
            
            val currentAccounts = _accounts.value.toMutableList()
            currentAccounts.removeAll { it.guid == account.guid }
            currentAccounts.add(activeAccount)
            _accounts.value = currentAccounts          // StateFlow emit ✓
            
            _loginState.value = LoginState.Success     // StateFlow emit ✓
        }
    )

Compose observes StateFlow changes:
    loginState → Success → LaunchedEffect fires → navController.navigate("accounts")
    accounts   → updated → AccountScreen recomposes with new account list
```

---

## SaResultData — The Two-Phase Result

```kotlin
// Phase 1: Synchronous verdict (returned from login() call instantly)
data class SaResultData(
    val success: Boolean,  // true = accepted, wait for async callback
    val fail:    Boolean,  // true = rejected right now, no callback coming
    val message: String    // reason if fail=true / info if success=true
)

// In AccountViewModel:
val immediate = ssoApiClient.login(mail, password) { result ->
    // Phase 2: This fires asynchronously (seconds later)
    result.fold( onSuccess = { ... }, onFailure = { ... } )
}

// Phase 1 check:
if (immediate.fail) {
    // Rejected before reaching the network:
    // - Service not installed
    // - Fields were blank (validated in SsoApiClient before even calling IPC)
    // - RemoteException in IPC
}

if (immediate.success) {
    // Request accepted — spinner should show
    // onResult lambda WILL fire later (success or failure from network)
}
```

---

## Complete Timeline

```
t=0ms     [Main] User taps Sign In
t=0ms     [Main] _loginState = Loading
t=0ms     [Main] ssoApiClient.login() called

── Case: First call (not connected) ──────────────────────────────────────────

t=0ms     [Main] login() returns SaResultData(success, "Connecting") → immediate
t=0ms     [clientScope] ensureConnected() starts → bindService() → SUSPENDS
t=0ms     [Main] Main Thread is FREE (no blocking)

t~300ms   [Main] ServiceConnection.onServiceConnected() fires
t~300ms   [clientScope] coroutine resumes → doLogin() called
t~301ms   [Main/clientScope] service.login() IPC call → BLOCKS briefly
t~301ms   [Service BT] login() validates, stores cb, launches IO coroutine
t~301ms   [Service BT] returns SaResultData.accepted() → UNBLOCKS account2

── Async network work in service ─────────────────────────────────────────────

t~302ms   [Service IO] apiService.getToken() — HTTP request
t~1500ms  [Service IO] HTTP response received
t~1501ms  [Service IO] apiService.getAccountInfo() — HTTP request
t~2000ms  [Service IO] response received
t~2001ms  [Service IO] DB write (Room)
t~2002ms  [Service IO] callback.onResult() + onAccountReceived() — IPC to account2

── Callback arrives in account2 ──────────────────────────────────────────────

t~2003ms  [account2 BT] IAuthCallback.onResult() arrives
t~2003ms  [account2 BT] IAuthCallback.onAccountReceived() arrives
t~2003ms  [account2 BT] mainHandler.post { onResult(account) }

t~2004ms  [Main] lambda fires
t~2004ms  [Main] _activeAccount = account
t~2004ms  [Main] _accounts updated
t~2004ms  [Main] _loginState = Success
t~2005ms  [Main] Compose recomposes → UI shows Accounts screen 🎉

── Case: Already connected ────────────────────────────────────────────────────
t=0ms → t~2000ms (same as above but without the 300ms connect wait)
```

---

## Thread Safety Notes

| Item | Thread-safe? | Why |
|---|---|---|
| `pendingLoginCallbacks` | ✅ Yes | `ConcurrentHashMap` — handles concurrent reads/writes |
| `isBound`, `ssoService` | ⚠️ Mostly | Written on Main, read on Main → OK. But coroutine reads on Main too. |
| `_loginState`, `_accounts` | ✅ Yes | `MutableStateFlow` — thread-safe, but update from Main for Compose |
| AIDL callback arrival | ✅ Handled | `mainHandler.post{}` guarantees Main Thread before StateFlow update |

---

## Lifecycle Summary

```
Application starts
    AccountViewModel created
    SsoApiClient created (no connection yet)

User taps login
    [lazy] clientScope connects to service
    SaResultData returned immediately
    Async: network → callback → StateFlow update

User navigates away / app goes background
    ViewModel alive (process cached)
    Service connection maintained

App process killed / ViewModel cleared
    AccountViewModel.onCleared()
        ssoApiClient.unbind()
            context.unbindService(serviceConnection)   ← service may stop if no other clients
            isBound = false
            ssoService = null
            clientScope.cancel()                        ← any pending lazy-connect cancelled
        viewModelScope.cancel()                         ← AndroidViewModel does this automatically
```
