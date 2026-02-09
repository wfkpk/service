# SSO Service - System Documentation

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Architecture](#2-architecture)
3. [Project Structure](#3-project-structure)
4. [AIDL Interface Layer](#4-aidl-interface-layer)
5. [Data Models](#5-data-models)
6. [Network Layer (ApiService)](#6-network-layer-apiservice)
7. [Database Layer (Room)](#7-database-layer-room)
8. [AccountManager Integration](#8-accountmanager-integration)
9. [SsoService - Core Service](#9-ssoservice---core-service)
10. [Threading Model](#10-threading-model)
11. [Request Flow Diagrams](#11-request-flow-diagrams)
12. [Client Implementation Guide](#12-client-implementation-guide)
13. [Backend API Reference](#13-backend-api-reference)
14. [Manifest & Permissions](#14-manifest--permissions)
15. [Build Configuration](#15-build-configuration)

---

## 1. System Overview

This is an Android Single Sign-On (SSO) service application. It acts as a centralized
authentication manager that runs as a persistent foreground service. Other apps (clients)
on the same device bind to this service via AIDL to perform authentication operations.

**What the service does:**

- Registers new users against a backend server
- Authenticates existing users and retrieves session tokens
- Stores authenticated accounts locally in Room database
- Registers accounts in Android's system AccountManager
- Allows clients to switch between multiple stored accounts (max 6)
- Signs out accounts both locally and on the server
- Starts automatically on device boot
- Fetches tokens and account info on demand without saving

**Key characteristics:**

- All network calls happen on background threads (OkHttp async + coroutines)
- Client apps communicate via AIDL (Binder IPC), not HTTP
- The service mediates between clients and the backend server
- Supports up to 6 accounts simultaneously, one active at a time

---

## 2. Architecture

```
+------------------+       AIDL (Binder IPC)       +------------------+
|                  | -----------------------------> |                  |
|   Client App     |    sso.Stub.asInterface()      |   SsoService     |
|                  | <----------------------------- |   (foreground)   |
|  IAuthCallback   |    callback.onResult()         |                  |
+------------------+    callback.onAccountReceived()+--------+---------+
                                                             |
                                          +------------------+------------------+
                                          |                  |                  |
                                    +-----v-----+    +------v------+    +------v------+
                                    |  ApiService|    |  Room DB    |    | Account     |
                                    |  (OkHttp)  |    | (SQLite)   |    | Manager     |
                                    +-----+------+    +------+------+    +------+------+
                                          |                  |                  |
                                          v                  v                  v
                                    +-----------+    +-----------+    +-----------------+
                                    | Backend   |    | accounts  |    | System Settings |
                                    | Server    |    | table     |    | > Accounts      |
                                    | :3000     |    +-----------+    +-----------------+
                                    +-----------+
```

### Layer Responsibilities

| Layer          | Class                                                           | Role                                                     |
| -------------- | --------------------------------------------------------------- | -------------------------------------------------------- |
| IPC Interface  | `sso.aidl`, `IAuthCallback.aidl`                                | Defines the contract between client apps and the service |
| Service        | `SsoService`                                                    | Orchestrates all operations, implements the AIDL binder  |
| Network        | `ApiService`                                                    | HTTP communication with the backend, XML parsing         |
| Database       | `AccountDao`, `AccountDatabase`, `AccountEntity`                | Local persistence via Room/SQLite                        |
| AccountManager | `SsoAccountManager`, `SsoAuthenticator`, `AuthenticatorService` | System-level account registration                        |
| Boot           | `BootReceiver`                                                  | Auto-starts service on device boot                       |
| UI             | `MainActivity`                                                  | Displays service status (not used by clients)            |

---

## 3. Project Structure

```
app/src/main/
├── aidl/com/example/ssoapi/
│   ├── Account.aidl              # Parcelable declaration
│   ├── AuthResult.aidl           # Parcelable declaration
│   ├── IAuthCallback.aidl        # Callback interface
│   └── sso.aidl                  # Main service interface (9 methods)
│
├── java/com/example/
│   ├── ssoapi/                   # Shared AIDL data classes
│   │   ├── Account.kt            # Account parcelable (guid, mail, profileImage, sessionToken, isActive)
│   │   └── AuthResult.kt         # AuthResult parcelable (success, fail, message)
│   │
│   └── service/                  # Service app package
│       ├── SsoService.kt         # Main foreground service (AIDL binder implementation)
│       ├── MainActivity.kt       # Status UI
│       ├── BootReceiver.kt       # Boot broadcast receiver
│       ├── network/
│       │   └── ApiService.kt     # OkHttp communicator + XML parsers
│       ├── auth/
│       │   ├── SsoAuthenticator.kt       # AbstractAccountAuthenticator stub
│       │   ├── AuthenticatorService.kt   # Exposes authenticator to system
│       │   └── SsoAccountManager.kt      # AccountManager wrapper
│       └── db/
│           ├── AccountEntity.kt  # Room entity
│           ├── AccountDao.kt     # Room DAO (queries)
│           └── AccountDatabase.kt # Room database singleton
│
└── res/xml/
    ├── authenticator.xml             # Account type: com.example.ssoapi.account
    └── network_security_config.xml   # Allows cleartext HTTP to 10.0.2.2
```

---

## 4. AIDL Interface Layer

### 4.1 sso.aidl - Main Service Interface

This is the primary contract. Client apps call these methods after binding to the service.

```java
interface sso {
    // --- Async methods (fire-and-forget with callback) ---
    void login(String mail, String password, IAuthCallback callback);
    void register(String mail, String password, IAuthCallback callback);
    void fetchToken(String mail, String password, IAuthCallback callback);
    void fetchAccountInfo(String guid, String sessionToken, IAuthCallback callback);

    // --- Async methods (fire-and-forget, no callback) ---
    void logout(String guid);
    void logoutAll();
    void switchAccount(String guid);

    // --- Synchronous methods (block caller until result) ---
    Account getActiveAccount();
    List<Account> getAllAccounts();
}
```

**Method details:**

| Method             | Type               | API Call                            | Saves to DB?        | Saves to AccountManager? |
| ------------------ | ------------------ | ----------------------------------- | ------------------- | ------------------------ |
| `login`            | async + callback   | POST /get-token, POST /account-info | Yes, sets active    | Yes                      |
| `register`         | async + callback   | POST /sign-in                       | Yes, sets active    | Yes                      |
| `fetchToken`       | async + callback   | POST /get-token                     | No                  | No                       |
| `fetchAccountInfo` | async + callback   | POST /account-info                  | No                  | No                       |
| `logout`           | async, no callback | POST /sign-out (best-effort)        | Deletes             | Removes                  |
| `logoutAll`        | async, no callback | POST /sign-out per account          | Deletes all         | Removes all              |
| `switchAccount`    | async, no callback | None                                | Updates active flag | No                       |
| `getActiveAccount` | synchronous        | None                                | Read only           | No                       |
| `getAllAccounts`   | synchronous        | None                                | Read only           | No                       |

### 4.2 IAuthCallback.aidl - Callback Interface

```java
interface IAuthCallback {
    void onResult(in AuthResult result);
    void onAccountReceived(in Account account);
}
```

**Callback flow:**

```
Success path:
  1. onResult(AuthResult(success=true, fail=false, message="..."))
  2. onAccountReceived(account)     <-- only fires on success

Failure path:
  1. onResult(AuthResult(success=false, fail=true, message="error details"))
     (onAccountReceived is NOT called)
```

**Important:** Callbacks arrive on a Binder thread pool thread. Clients must dispatch
to the main thread before updating UI.

### 4.3 AuthResult.aidl + AuthResult.kt

```kotlin
data class AuthResult(
    val success: Boolean,    // true if operation succeeded
    val fail: Boolean,       // true if operation failed (opposite of success)
    val message: String = "" // Human-readable status or error message
)
```

Both `success` and `fail` are provided so clients can check whichever is more natural:

```kotlin
if (result.success) { /* handle success */ }
// or equivalently:
if (result.fail) { /* handle failure */ }
```

### 4.4 Account.aidl + Account.kt

```kotlin
data class Account(
    val guid: String,          // Server-assigned unique identifier (UUID)
    val mail: String,          // User's email address
    val profileImage: String?, // Profile image URL (nullable, may be empty)
    val sessionToken: String,  // Current session token for API calls
    val isActive: Boolean      // Whether this is the currently active account
)
```

**Parcel order (critical for cross-process compatibility):**
guid -> mail -> profileImage -> sessionToken -> isActive

---

## 5. Data Models

### 5.1 Account (AIDL Parcelable)

Used across IPC boundary between client and service.

| Field        | Type    | Source                           | Notes                             |
| ------------ | ------- | -------------------------------- | --------------------------------- |
| guid         | String  | Server (/get-token, /sign-in)    | UUID, primary identifier          |
| mail         | String  | Server (/account-info, /sign-in) | Email address                     |
| profileImage | String? | Server (/account-info, /sign-in) | Nullable, can be empty            |
| sessionToken | String  | Server (/get-token, /sign-in)    | Used for authenticated API calls  |
| isActive     | Boolean | Local only                       | Which account is currently active |

### 5.2 AuthResult (AIDL Parcelable)

Returned via `IAuthCallback.onResult()`.

| Field   | Type    | Notes                                                                            |
| ------- | ------- | -------------------------------------------------------------------------------- |
| success | Boolean | true = operation succeeded                                                       |
| fail    | Boolean | true = operation failed (mutually exclusive with success)                        |
| message | String  | "Login successful", "Registration successful", "Token fetched", or error message |

### 5.3 AccountEntity (Room Entity)

Stored in SQLite via Room. Maps 1:1 with Account.

| Column       | Type    | Constraint    | Notes                             |
| ------------ | ------- | ------------- | --------------------------------- |
| guid         | String  | PRIMARY KEY   | Server UUID                       |
| mail         | String  |               | Email                             |
| profileImage | String? |               | Nullable                          |
| sessionToken | String  |               | Session credential                |
| isActive     | Boolean | default false | Only one should be true at a time |

Conversion methods:

- `entity.toAccount()` -> Account (for returning via AIDL)
- `AccountEntity.fromAccount(account)` -> AccountEntity (for saving)

---

## 6. Network Layer (ApiService)

**Location:** `com.example.service.network.ApiService`

### 6.1 Configuration

| Setting         | Value                  | Notes                                         |
| --------------- | ---------------------- | --------------------------------------------- |
| Base URL        | `http://10.0.2.2:3000` | 10.0.2.2 = host machine from Android emulator |
| Auth Header     | `x-token: pk-backend`  | Required on every request                     |
| Content-Type    | `application/json`     | Request bodies                                |
| Response Format | XML                    | All responses are XML                         |
| HTTP Client     | OkHttp 4.12.0          | Single shared instance                        |

### 6.2 Threading: Coroutine-native async

Each HTTP request uses OkHttp's `enqueue()` bridged to coroutines via
`suspendCancellableCoroutine`. This means:

- The coroutine **suspends** (not blocks) while the request is in-flight
- The HTTP request executes on **OkHttp's own thread pool** (not Dispatchers.IO)
- Multiple concurrent requests each run on **separate threads**
- Coroutine cancellation properly cancels the HTTP request

```kotlin
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) {
                cont.resumeWithException(e)
            }
        }
    })
    cont.invokeOnCancellation { cancel() }
}
```

### 6.3 Result Type

```kotlin
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String) : ApiResult<Nothing>()
}
```

Every API method returns `ApiResult<T>`. Callers pattern-match:

```kotlin
when (result) {
    is ApiResult.Success -> result.data  // typed response
    is ApiResult.Error   -> result.message // error string
}
```

### 6.4 Endpoints

#### POST /sign-in (Register)

```kotlin
suspend fun signIn(mail: String, password: String): ApiResult<SignInResponse>
```

Request: `{ "mail": "...", "password": "..." }`

Response XML:

```xml
<user>
  <guid>uuid-here</guid>
  <mail>user@example.com</mail>
  <profile_image/>
  <session_token>token-here</session_token>
</user>
```

Parsed into: `SignInResponse(guid, mail, profileImage?, sessionToken)`

Errors: 400 (missing fields), 401 (bad token), 409 (account exists)

#### POST /get-token (Login)

```kotlin
suspend fun getToken(mail: String, password: String): ApiResult<GetTokenResponse>
```

Request: `{ "mail": "...", "password": "..." }`

Response XML:

```xml
<token>
  <guid>uuid-here</guid>
  <session_token>token-here</session_token>
</token>
```

Parsed into: `GetTokenResponse(guid, sessionToken)`

Errors: 400 (missing fields), 401 (invalid credentials)

#### POST /account-info (Get Account Details)

```kotlin
suspend fun getAccountInfo(guid: String, sessionToken: String): ApiResult<AccountInfoResponse>
```

Request: `{ "guid": "...", "session_token": "..." }`

Response XML:

```xml
<account>
  <guid>uuid-here</guid>
  <mail>user@example.com</mail>
  <profile_image/>
  <tokens>
    <item>token-1</item>
    <item>token-2</item>
  </tokens>
</account>
```

Parsed into: `AccountInfoResponse(guid, mail, profileImage?, tokens)`

Errors: 400 (missing fields), 401 (bad token/session), 404 (user not found)

#### POST /sign-out (Logout)

```kotlin
suspend fun signOut(guid: String, sessionToken: String): ApiResult<SignOutResponse>
```

Request: `{ "guid": "...", "session_token": "..." }`

Response XML:

```xml
<response>
  <message>Signed out successfully.</message>
</response>
```

Parsed into: `SignOutResponse(message)`

Errors: 400 (missing fields), 401 (bad token), 404 (user not found)

#### Error Format (all endpoints)

```xml
<error>
  <message>Human-readable error description</message>
</error>
```

### 6.5 XML Parsing

All XML is parsed using Android's built-in `XmlPullParser`. The pattern used in every parser:

```kotlin
var currentTag = ""
while (eventType != XmlPullParser.END_DOCUMENT) {
    when (eventType) {
        XmlPullParser.START_TAG -> currentTag = parser.name
        XmlPullParser.TEXT -> {
            when (currentTag) {
                "guid" -> guid = parser.text.trim()
                "mail" -> mail = parser.text.trim()
                // ... etc
            }
        }
        XmlPullParser.END_TAG -> currentTag = ""
    }
    eventType = parser.next()
}
```

---

## 7. Database Layer (Room)

**Location:** `com.example.service.db`

### 7.1 AccountDatabase

- Database name: `account_database`
- Version: 2
- Migration strategy: `fallbackToDestructiveMigration()` (wipes data on schema change)
- Singleton pattern with double-checked locking

### 7.2 AccountDao - Available Queries

| Method                      | Query                             | Notes                              |
| --------------------------- | --------------------------------- | ---------------------------------- |
| `insertAccount(entity)`     | INSERT OR REPLACE                 | Upsert behavior                    |
| `updateAccount(entity)`     | UPDATE                            | Updates existing row               |
| `deleteAccount(entity)`     | DELETE                            | Deletes by entity match            |
| `getActiveAccount()`        | `WHERE isActive = 1 LIMIT 1`      | Returns single active account      |
| `getAllAccounts()`          | `ORDER BY mail ASC`               | All accounts sorted alphabetically |
| `getAccountByGuid(guid)`    | `WHERE guid = :guid`              | Lookup by server UUID              |
| `getAccountByMail(mail)`    | `WHERE mail = :mail LIMIT 1`      | Lookup by email                    |
| `setActiveAccount(entity)`  | Transaction: clearActive + upsert | Atomic active-account switch       |
| `clearActiveState()`        | `UPDATE SET isActive = 0`         | Deactivates all accounts           |
| `deleteAccountByGuid(guid)` | `DELETE WHERE guid = :guid`       | Remove single account              |
| `deleteAllAccounts()`       | `DELETE FROM accounts`            | Remove all accounts                |
| `getAccountCount()`         | `SELECT COUNT(*)`                 | Used for max-account check         |

### 7.3 setActiveAccount Transaction

This is a `@Transaction` method that atomically:

1. Sets `isActive = 0` on all existing accounts
2. Checks if the account already exists by guid
3. If exists: updates it with `isActive = true`
4. If new: inserts it with `isActive = true`

This guarantees exactly one active account at any time.

---

## 8. AccountManager Integration

**Location:** `com.example.service.auth`

### 8.1 Account Type

```
com.example.ssoapi.account
```

This appears in Android Settings > Accounts as the SSO service's account type.

### 8.2 Components

#### SsoAuthenticator

Extends `AbstractAccountAuthenticator`. All methods return `null` because account
creation is managed exclusively through the AIDL interface, not through the system
Settings UI.

#### AuthenticatorService

A bound `Service` that returns the `SsoAuthenticator`'s `iBinder`. Required by Android's
AccountManager framework to discover the authenticator.

Registered in manifest with:

```xml
<intent-filter>
    <action android:name="android.accounts.AccountAuthenticator" />
</intent-filter>
<meta-data
    android:name="android.accounts.AccountAuthenticator"
    android:resource="@xml/authenticator" />
```

#### SsoAccountManager (Wrapper)

Provides a clean API over Android's `AccountManager`:

| Method                                                       | What it does                                    |
| ------------------------------------------------------------ | ----------------------------------------------- |
| `addOrUpdateAccount(guid, mail, sessionToken, profileImage)` | Creates or updates system account with userData |
| `removeAccount(mail)`                                        | Removes account by email                        |
| `removeAllAccounts()`                                        | Removes all SSO accounts from system            |
| `getSessionToken(mail)`                                      | Reads stored session token from system account  |

**Account storage layout in AccountManager:**

| Key           | Android Account Field | Value                        |
| ------------- | --------------------- | ---------------------------- |
| (name)        | `Account.name`        | User's email (mail)          |
| (type)        | `Account.type`        | `com.example.ssoapi.account` |
| guid          | userData              | Server UUID                  |
| session_token | userData              | Current session token        |
| profile_image | userData              | Profile image URL            |

---

## 9. SsoService - Core Service

**Location:** `com.example.service.SsoService`

### 9.1 Service Lifecycle

```
Device boot -> BootReceiver -> startForegroundService(SsoService)
     OR
MainActivity.onCreate() -> startForegroundService(SsoService)

SsoService.onCreate()
  -> createNotificationChannel()

SsoService.onStartCommand()
  -> startForeground() with notification
  -> returns START_STICKY (restarts if killed)

Client binds -> onBind() returns binder (sso.Stub)
Client unbinds -> onUnbind() returns true (allows rebind)

SsoService.onDestroy()
  -> scope.cancel() (cancels all coroutines)
```

### 9.2 Internal Helpers

The service uses these helpers to reduce code duplication:

```kotlin
// Sends failure AuthResult to client
private fun sendError(callback: IAuthCallback, message: String)

// Sends success AuthResult + Account to client
private fun sendSuccess(callback: IAuthCallback, message: String, account: Account)

// Launches a coroutine with automatic error-to-callback handling
private fun launchWithCallback(callback: IAuthCallback, tag: String, block: suspend () -> Unit)

// Saves account to Room DB + AccountManager atomically
private suspend fun saveAccount(entity: AccountEntity)

// Returns true if max accounts (6) reached and guid is new
private suspend fun checkMaxAccounts(guid: String): Boolean
```

### 9.3 Method Implementations

#### login(mail, password, callback)

1. Validate parameters (non-null, non-empty)
2. Launch coroutine on IO dispatcher
3. Call `POST /get-token` with mail + password
4. On failure -> `sendError(callback, errorMessage)`
5. On success -> extract guid + sessionToken
6. Call `POST /account-info` with guid + sessionToken (non-fatal if fails)
7. Check max accounts (6 limit)
8. Save to Room DB via `setActiveAccount()` (marks as active)
9. Save to AccountManager via `addOrUpdateAccount()`
10. `sendSuccess()` -> `onResult(success)` then `onAccountReceived(account)`

#### register(mail, password, callback)

1. Validate parameters
2. Launch coroutine
3. Call `POST /sign-in` with mail + password
4. On failure -> `sendError()`
5. On success -> extract guid, mail, profileImage, sessionToken
6. Check max accounts
7. Save to Room DB + AccountManager
8. `sendSuccess()`

#### fetchToken(mail, password, callback)

1. Validate parameters
2. Launch coroutine
3. Call `POST /get-token` with mail + password
4. On failure -> `sendError()`
5. On success -> build Account with guid + sessionToken, no profileImage
6. `sendSuccess()` -- does NOT save to DB or AccountManager

#### fetchAccountInfo(guid, sessionToken, callback)

1. Validate parameters
2. Launch coroutine
3. Call `POST /account-info` with guid + sessionToken
4. On failure -> `sendError()`
5. On success -> build Account with full profile data
6. `sendSuccess()` -- does NOT save to DB or AccountManager

#### logout(guid)

1. Look up account in Room DB by guid
2. Call `POST /sign-out` on server (best-effort, errors ignored)
3. Delete from Room DB
4. Remove from AccountManager
5. If the logged-out account was active, set first remaining account as active

#### logoutAll()

1. Get all accounts from Room DB
2. Call `POST /sign-out` for each account (best-effort)
3. Delete all from Room DB
4. Remove all from AccountManager

#### switchAccount(guid)

1. Look up account in Room DB by guid
2. If found, call `setActiveAccount()` (clears other active flags, sets this one)

#### getActiveAccount() - Synchronous

Uses `scope.future { }.get()` to bridge coroutine to blocking call.
Returns `Account?` (null if no active account).

#### getAllAccounts() - Synchronous

Same blocking bridge. Returns `MutableList<Account>`.

---

## 10. Threading Model

```
+-------------------+     +-------------------+     +-------------------+
| Client Binder     |     | SsoService        |     | OkHttp Thread     |
| Thread (1 of 16)  |     | Coroutine (IO)    |     | Pool              |
+-------------------+     +-------------------+     +-------------------+
        |                         |                         |
  login() called -------->  scope.launch {           |
        |                    apiService.getToken() ------> enqueue()
        |                    (suspends)                     |
        |                         |                   HTTP request
        |                         |                   executes here
        |                         |                         |
        |                    (resumes) <---------- onResponse()
        |                    parse XML                      |
        |                    save to Room DB                |
        |                    save to AccountManager         |
  callback.onResult() <---- sendSuccess()                   |
  callback.onAccount() <---                                 |
        |                         |                         |
```

**Thread pools involved:**

| Pool               | Size           | Used for                                          |
| ------------------ | -------------- | ------------------------------------------------- |
| Binder thread pool | 16 per process | Receives AIDL calls from clients                  |
| Dispatchers.IO     | 64 (default)   | Coroutine scope for service logic + DB operations |
| OkHttp dispatcher  | 64 (default)   | HTTP request execution                            |

**Key points:**

- `login/register/fetchToken/fetchAccountInfo` return immediately to the Binder thread.
  The actual work runs in a launched coroutine.
- `getActiveAccount/getAllAccounts` block the Binder thread via `future.get()` until
  the DB query completes. This is acceptable because DB reads are fast.
- OkHttp's `enqueue()` + `suspendCancellableCoroutine` means network requests do NOT
  occupy a Dispatchers.IO thread while waiting for the server.
- Multiple simultaneous client requests (e.g., 10 clients calling `login()` at once)
  each get their own coroutine and their own OkHttp thread. No request blocks another.

---

## 11. Request Flow Diagrams

### 11.1 Login Flow

```
Client                    SsoService              ApiService            Backend
  |                           |                       |                    |
  |-- login(mail,pass,cb) -->|                       |                    |
  |                           |-- getToken(m,p) ---->|                    |
  |                           |                       |-- POST /get-token ->
  |                           |                       |<-- 200 XML --------|
  |                           |<-- Success(guid,tok) |                    |
  |                           |                       |                    |
  |                           |-- getAccountInfo() ->|                    |
  |                           |                       |-- POST /account-info ->
  |                           |                       |<-- 200 XML --------|
  |                           |<-- Success(profile)  |                    |
  |                           |                       |                    |
  |                           |-- Room: setActiveAccount()                |
  |                           |-- AccountManager: addOrUpdate()           |
  |                           |                       |                    |
  |<-- onResult(success) ----|                       |                    |
  |<-- onAccountReceived() --|                       |                    |
```

### 11.2 Register Flow

```
Client                    SsoService              ApiService            Backend
  |                           |                       |                    |
  |-- register(m,p,cb) ----->|                       |                    |
  |                           |-- signIn(m,p) ------>|                    |
  |                           |                       |-- POST /sign-in --->
  |                           |                       |<-- 201 XML --------|
  |                           |<-- Success(all data) |                    |
  |                           |                       |                    |
  |                           |-- Room: setActiveAccount()                |
  |                           |-- AccountManager: addOrUpdate()           |
  |                           |                       |                    |
  |<-- onResult(success) ----|                       |                    |
  |<-- onAccountReceived() --|                       |                    |
```

### 11.3 Logout Flow

```
Client                    SsoService              ApiService            Backend
  |                           |                       |                    |
  |-- logout(guid) --------->|                       |                    |
  |  (returns immediately)    |                       |                    |
  |                           |-- Room: getByGuid()  |                    |
  |                           |-- signOut(guid,tok) ->|                    |
  |                           |                       |-- POST /sign-out -->
  |                           |                       |<-- 200 (ignored) --|
  |                           |                       |                    |
  |                           |-- Room: deleteByGuid()                    |
  |                           |-- AccountManager: remove()                |
  |                           |-- Room: setActiveAccount(first remaining) |
```

### 11.4 fetchToken Flow (Lightweight, No Save)

```
Client                    SsoService              ApiService            Backend
  |                           |                       |                    |
  |-- fetchToken(m,p,cb) --->|                       |                    |
  |                           |-- getToken(m,p) ---->|                    |
  |                           |                       |-- POST /get-token ->
  |                           |                       |<-- 200 XML --------|
  |                           |<-- Success(guid,tok) |                    |
  |                           |                       |                    |
  |                           |  (NO DB save)        |                    |
  |                           |  (NO AccountManager) |                    |
  |                           |                       |                    |
  |<-- onResult(success) ----|                       |                    |
  |<-- onAccountReceived() --|   (Account with guid + sessionToken)      |
```

---

## 12. Client Implementation Guide

### 12.1 Files to Copy

Copy these into your client app under the exact same package names:

```
app/src/main/aidl/com/example/ssoapi/
    Account.aidl
    AuthResult.aidl
    IAuthCallback.aidl
    sso.aidl

app/src/main/java/com/example/ssoapi/
    Account.kt
    AuthResult.kt
```

Also enable AIDL in your client's `build.gradle.kts`:

```kotlin
android {
    buildFeatures {
        aidl = true
    }
}
```

### 12.2 Binding to the Service

```kotlin
private var ssoService: sso? = null
private var isBound = false

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        ssoService = sso.Stub.asInterface(binder)
        isBound = true
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        ssoService = null
        isBound = false
    }
}

// Bind to the service
fun bind(context: Context) {
    val intent = Intent("com.example.service.SSO_SERVICE").apply {
        setPackage("com.example.service")
    }
    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
}

// Unbind when done
fun unbind(context: Context) {
    if (isBound) {
        context.unbindService(connection)
        isBound = false
    }
}
```

### 12.3 Implementing the Callback

```kotlin
private val authCallback = object : IAuthCallback.Stub() {

    override fun onResult(result: AuthResult) {
        // This runs on a BINDER THREAD - not the main thread!
        if (result.success) {
            Log.d("Client", "Operation succeeded: ${result.message}")
            // onAccountReceived() will be called next
        }
        if (result.fail) {
            Log.e("Client", "Operation failed: ${result.message}")
            // onAccountReceived() will NOT be called
            runOnUiThread {
                Toast.makeText(this@Activity, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onAccountReceived(account: Account) {
        // This runs on a BINDER THREAD - not the main thread!
        Log.d("Client", "Received account: ${account.mail}, guid: ${account.guid}")
        runOnUiThread {
            // Update UI with account data
            tvEmail.text = account.mail
            tvGuid.text = account.guid
            // account.sessionToken is available if needed
            // account.profileImage is available if needed
        }
    }
}
```

### 12.4 Calling Service Methods

```kotlin
// Register a new account
ssoService?.register("user@example.com", "password123", authCallback)

// Login with existing account
ssoService?.login("user@example.com", "password123", authCallback)

// Just get a token (no account saved)
ssoService?.fetchToken("user@example.com", "password123", authCallback)

// Get account info by guid + session token (no account saved)
ssoService?.fetchAccountInfo(guid, sessionToken, authCallback)

// Logout a specific account
ssoService?.logout(guid)

// Logout all accounts
ssoService?.logoutAll()

// Switch active account
ssoService?.switchAccount(guid)

// Get current active account (synchronous, blocks calling thread)
val active: Account? = ssoService?.activeAccount
if (active != null) {
    Log.d("Client", "Active: ${active.mail}")
}

// Get all stored accounts (synchronous)
val accounts: List<Account> = ssoService?.allAccounts ?: emptyList()
accounts.forEach { Log.d("Client", "Account: ${it.mail}, active: ${it.isActive}") }
```

### 12.5 Complete Example: Login Activity

```kotlin
class LoginActivity : AppCompatActivity() {

    private var ssoService: sso? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            ssoService = sso.Stub.asInterface(binder)
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            ssoService = null
            isBound = false
        }
    }

    private val callback = object : IAuthCallback.Stub() {
        override fun onResult(result: AuthResult) {
            runOnUiThread {
                if (result.fail) {
                    tvError.text = result.message
                    tvError.visibility = View.VISIBLE
                }
            }
        }

        override fun onAccountReceived(account: Account) {
            runOnUiThread {
                tvError.visibility = View.GONE
                // Navigate to main screen with account data
                val intent = Intent(this@LoginActivity, HomeActivity::class.java)
                intent.putExtra("guid", account.guid)
                intent.putExtra("mail", account.mail)
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent("com.example.service.SSO_SERVICE")
        intent.setPackage("com.example.service")
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    fun onLoginClicked(view: View) {
        val mail = etEmail.text.toString()
        val password = etPassword.text.toString()
        ssoService?.login(mail, password, callback)
    }

    fun onRegisterClicked(view: View) {
        val mail = etEmail.text.toString()
        val password = etPassword.text.toString()
        ssoService?.register(mail, password, callback)
    }
}
```

---

## 13. Backend API Reference

**Server:** `http://localhost:3000` (maps to `http://10.0.2.2:3000` from emulator)

**Authentication:** All requests require header `x-token: pk-backend`

**Response format:** All responses are XML

### POST /sign-in

Register a new user account.

|               | Details                                                                                           |
| ------------- | ------------------------------------------------------------------------------------------------- |
| Request Body  | `{ "mail": "string", "password": "string", "profile_image": "string (optional)" }`                |
| Success (201) | `<user><guid>...</guid><mail>...</mail><profile_image/><session_token>...</session_token></user>` |
| 400           | Missing required fields                                                                           |
| 401           | Unauthorized (bad x-token)                                                                        |
| 409           | Account already exists                                                                            |

### POST /get-token

Authenticate and get a session token.

|               | Details                                                             |
| ------------- | ------------------------------------------------------------------- |
| Request Body  | `{ "mail": "string", "password": "string" }`                        |
| Success (200) | `<token><guid>...</guid><session_token>...</session_token></token>` |
| 400           | Missing required fields                                             |
| 401           | Invalid mail or password                                            |

### POST /account-info

Get full account details.

|               | Details                                                                                                |
| ------------- | ------------------------------------------------------------------------------------------------------ |
| Request Body  | `{ "guid": "string", "session_token": "string" }`                                                      |
| Success (200) | `<account><guid>...</guid><mail>...</mail><profile_image/><tokens><item>...</item></tokens></account>` |
| 400           | Missing required fields                                                                                |
| 401           | Unauthorized or invalid session                                                                        |
| 404           | User not found                                                                                         |

### POST /sign-out

Remove a session token (logout).

|               | Details                                                            |
| ------------- | ------------------------------------------------------------------ |
| Request Body  | `{ "guid": "string", "session_token": "string" }`                  |
| Success (200) | `<response><message>Signed out successfully.</message></response>` |
| 400           | Missing required fields                                            |
| 401           | Unauthorized (bad x-token)                                         |
| 404           | User not found                                                     |

### Error Response Format

All errors return:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<error>
  <message>Human-readable error description.</message>
</error>
```

---

## 14. Manifest & Permissions

### Permissions Used

| Permission                       | Why                                           |
| -------------------------------- | --------------------------------------------- |
| `RECEIVE_BOOT_COMPLETED`         | Start service on device boot via BootReceiver |
| `FOREGROUND_SERVICE`             | Run as foreground service                     |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Foreground service type = specialUse          |
| `POST_NOTIFICATIONS`             | Show foreground service notification          |
| `INTERNET`                       | Make HTTP requests to backend server          |

### Custom Permission

```xml
<permission
    android:name="com.example.ssoapi.permission.BIND_SSO_SERVICE"
    android:protectionLevel="signature" />
```

Signature-level protection: only apps signed with the same certificate can bind.

### Network Security Config

Allows cleartext HTTP traffic to the emulator's host alias:

```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

For physical devices, change `10.0.2.2` to your server's actual IP address.

### Registered Components

| Component              | Type                             | Action                                  |
| ---------------------- | -------------------------------- | --------------------------------------- |
| `MainActivity`         | Activity                         | `android.intent.action.MAIN` (launcher) |
| `SsoService`           | Service (foreground, specialUse) | `com.example.service.SSO_SERVICE`       |
| `BootReceiver`         | BroadcastReceiver                | `android.intent.action.BOOT_COMPLETED`  |
| `AuthenticatorService` | Service                          | `android.accounts.AccountAuthenticator` |

---

## 15. Build Configuration

### Dependencies

| Library                                  | Version | Purpose                         |
| ---------------------------------------- | ------- | ------------------------------- |
| androidx.core:core-ktx                   | 1.17.0  | Kotlin extensions for Android   |
| androidx.appcompat                       | 1.6.1   | Backward-compatible UI          |
| com.google.android.material              | 1.10.0  | Material Design components      |
| androidx.room:room-runtime               | 2.8.0   | Database ORM                    |
| androidx.room:room-ktx                   | 2.8.0   | Room coroutine extensions       |
| androidx.room:room-compiler              | 2.8.0   | Room annotation processor (KSP) |
| org.jetbrains.kotlinx:coroutines-android | 1.10.2  | Coroutines for Android          |
| org.jetbrains.kotlinx:coroutines-jdk8    | 1.10.2  | CompletableFuture bridge        |
| com.squareup.okhttp3:okhttp              | 4.12.0  | HTTP client                     |

### Build Settings

| Setting            | Value            |
| ------------------ | ---------------- |
| compileSdk         | 36               |
| minSdk             | 24 (Android 7.0) |
| targetSdk          | 36               |
| Java compatibility | 11               |
| AGP                | 9.0.0            |
| KSP                | 2.1.10-1.0.29    |
| AIDL               | enabled          |

### Max Accounts

The service enforces a limit of **6 accounts** maximum. This is defined in
`SsoService.MAX_ACCOUNTS` and checked before any new account is saved.
