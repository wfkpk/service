package com.example.service.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiService {

    companion object {
        private const val TAG = "ApiService"
        private const val BASE_URL = "http://10.0.2.2:3000"
        private const val HEADER_TOKEN_KEY = "x-token"
        private const val HEADER_TOKEN_VALUE = "pk-backend"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder().build()

    data class SignInResponse(
        val guid: String,
        val mail: String,
        val profileImage: String?,
        val sessionToken: String
    )

    data class GetTokenResponse(
        val guid: String,
        val sessionToken: String
    )

    data class AccountInfoResponse(
        val guid: String,
        val mail: String,
        val profileImage: String?,
        val tokens: List<String>
    )

    data class SignOutResponse(val message: String)

    sealed class ApiResult<out T> {
        data class Success<T>(val data: T) : ApiResult<T>()
        data class Error(val message: String) : ApiResult<Nothing>()
    }

    // Each request runs on OkHttp's own thread pool via enqueue(),
    // the coroutine suspends without blocking any Dispatchers.IO thread.

    suspend fun signIn(mail: String, password: String): ApiResult<SignInResponse> {
        android.util.Log.d(TAG, "signIn() called for: $mail")
        return try {
            val json = JSONObject().apply {
                put("mail", mail)
                put("password", password)
            }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = buildRequest("$BASE_URL/sign-in", body)
            android.util.Log.d(TAG, "signIn() sending request to: $BASE_URL/sign-in")
            val response = client.newCall(request).await()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                android.util.Log.e(TAG, "signIn() failed with code ${response.code}: ${parseError(responseBody)}")
                return ApiResult.Error(parseError(responseBody))
            }

            val result = parseSignInResponse(responseBody)
            android.util.Log.d(TAG, "signIn() successful: guid=${result.guid}, mail=${result.mail}")
            ApiResult.Success(result)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "signIn() exception", e)
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    suspend fun getToken(mail: String, password: String): ApiResult<GetTokenResponse> {
        android.util.Log.d(TAG, "getToken() called for: $mail")
        return try {
            val json = JSONObject().apply {
                put("mail", mail)
                put("password", password)
            }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = buildRequest("$BASE_URL/get-token", body)
            android.util.Log.d(TAG, "getToken() sending request to: $BASE_URL/get-token")
            val response = client.newCall(request).await()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                android.util.Log.e(TAG, "getToken() failed with code ${response.code}: ${parseError(responseBody)}")
                return ApiResult.Error(parseError(responseBody))
            }

            val result = parseGetTokenResponse(responseBody)
            android.util.Log.d(TAG, "getToken() successful: guid=${result.guid}")
            ApiResult.Success(result)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getToken() exception", e)
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    suspend fun getAccountInfo(guid: String, sessionToken: String): ApiResult<AccountInfoResponse> {
        android.util.Log.d(TAG, "getAccountInfo() called for guid: $guid")
        return try {
            val json = JSONObject().apply {
                put("guid", guid)
                put("session_token", sessionToken)
            }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = buildRequest("$BASE_URL/account-info", body)
            android.util.Log.d(TAG, "getAccountInfo() sending request to: $BASE_URL/account-info")
            val response = client.newCall(request).await()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                android.util.Log.e(TAG, "getAccountInfo() failed with code ${response.code}: ${parseError(responseBody)}")
                return ApiResult.Error(parseError(responseBody))
            }

            val result = parseAccountInfoResponse(responseBody)
            android.util.Log.d(TAG, "getAccountInfo() successful: mail=${result.mail}")
            ApiResult.Success(result)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getAccountInfo() exception", e)
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    suspend fun signOut(guid: String, sessionToken: String): ApiResult<SignOutResponse> {
        android.util.Log.d(TAG, "signOut() called for guid: $guid")
        return try {
            val json = JSONObject().apply {
                put("guid", guid)
                put("session_token", sessionToken)
            }
            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = buildRequest("$BASE_URL/sign-out", body)
            android.util.Log.d(TAG, "signOut() sending request to: $BASE_URL/sign-out")
            val response = client.newCall(request).await()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                android.util.Log.e(TAG, "signOut() failed with code ${response.code}: ${parseError(responseBody)}")
                return ApiResult.Error(parseError(responseBody))
            }

            val result = parseSignOutResponse(responseBody)
            android.util.Log.d(TAG, "signOut() successful: ${result.message}")
            ApiResult.Success(result)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "signOut() exception", e)
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    // Bridges OkHttp async enqueue() to Kotlin coroutines.
    // Each call runs on OkHttp's dispatcher thread - no IO thread blocked.
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

    private fun buildRequest(url: String, body: RequestBody): Request =
        Request.Builder()
            .url(url)
            .post(body)
            .addHeader(HEADER_TOKEN_KEY, HEADER_TOKEN_VALUE)
            .build()

    private fun createParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))
        return parser
    }

    private fun parseError(xml: String): String {
        try {
            val parser = createParser(xml)
            var eventType = parser.eventType
            var inMessage = false
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "message") inMessage = true
                    }
                    XmlPullParser.TEXT -> {
                        if (inMessage) return parser.text.trim()
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "message") inMessage = false
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) { }
        return "Unknown error"
    }

    private fun parseSignInResponse(xml: String): SignInResponse {
        val parser = createParser(xml)
        var eventType = parser.eventType
        var currentTag = ""
        var guid = ""
        var mail = ""
        var profileImage: String? = null
        var sessionToken = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name
                XmlPullParser.TEXT -> {
                    when (currentTag) {
                        "guid" -> guid = parser.text.trim()
                        "mail" -> mail = parser.text.trim()
                        "profile_image" -> profileImage = parser.text.trim().ifEmpty { null }
                        "session_token" -> sessionToken = parser.text.trim()
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return SignInResponse(guid, mail, profileImage, sessionToken)
    }

    private fun parseGetTokenResponse(xml: String): GetTokenResponse {
        val parser = createParser(xml)
        var eventType = parser.eventType
        var currentTag = ""
        var guid = ""
        var sessionToken = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name
                XmlPullParser.TEXT -> {
                    when (currentTag) {
                        "guid" -> guid = parser.text.trim()
                        "session_token" -> sessionToken = parser.text.trim()
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return GetTokenResponse(guid, sessionToken)
    }

    private fun parseAccountInfoResponse(xml: String): AccountInfoResponse {
        val parser = createParser(xml)
        var eventType = parser.eventType
        var currentTag = ""
        var guid = ""
        var mail = ""
        var profileImage: String? = null
        val tokens = mutableListOf<String>()

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name
                XmlPullParser.TEXT -> {
                    when (currentTag) {
                        "guid" -> guid = parser.text.trim()
                        "mail" -> mail = parser.text.trim()
                        "profile_image" -> profileImage = parser.text.trim().ifEmpty { null }
                        "item" -> {
                            val text = parser.text.trim()
                            if (text.isNotEmpty()) tokens.add(text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return AccountInfoResponse(guid, mail, profileImage, tokens)
    }

    private fun parseSignOutResponse(xml: String): SignOutResponse {
        val parser = createParser(xml)
        var eventType = parser.eventType
        var currentTag = ""
        var message = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> currentTag = parser.name
                XmlPullParser.TEXT -> {
                    if (currentTag == "message") message = parser.text.trim()
                }
                XmlPullParser.END_TAG -> currentTag = ""
            }
            eventType = parser.next()
        }
        return SignOutResponse(message)
    }
}
