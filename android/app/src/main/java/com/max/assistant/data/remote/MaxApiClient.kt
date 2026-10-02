package com.max.assistant.data.remote

import com.max.assistant.data.local.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// Talks to the MAX backend over HTTPS. It only holds the user's session token.
// There are NO provider API keys here: AI calls go through the backend so secret keys
// are never embedded inside the APK.
//
// baseUrl is the public backend URL only (e.g. https://api.example.com/). It must
// NEVER be a Supabase URL/key or contain secrets. Emulator-only values such as
// http://10.0.2.2:3000/, http://localhost:3000/ or http://127.0.0.1:3000/ do NOT
// work on a real device: use the PC's LAN IP (http://192.168.x.x:3000/) or HTTPS.
//
// isNetworkAvailable reports the device's actual connectivity (via
// ConnectivityManager in production). It is what separates a genuine "You're
// offline." from "Can't reach the MAX server." (wrong URL, server down, DNS,
// CORS/HTTPS failure). Tests can inject a fake.
class MaxApiClient(
    private val baseUrl: String,
    private val tokens: TokenStore,
    private val isNetworkAvailable: () -> Boolean = { true },
) {

    init {
        // Fail fast in logcat when a developer runs a real phone against the
        // emulator-only loopback alias. The request would otherwise time out and
        // look like flaky network.
        val host = baseUrl.lowercase()
        if ("10.0.2.2" in host || "localhost" in host || "127.0.0.1" in host) {
            android.util.Log.w(
                "MaxApi",
                "API_BASE_URL=$baseUrl looks emulator-only and will NOT work on a real device. " +
                    "Use the PC's LAN IP (e.g. http://192.168.1.20:3000/) or an HTTPS URL. " +
                    "See DEVELOPMENT_SETUP.md section 10.",
            )
        }
        android.util.Log.i("MaxApi", "Backend base URL: ${baseUrl.trimEnd('/')}")
    }

    // Request timeouts so a slow network can never hang the app.
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    private val json = "application/json".toMediaType()

    suspend fun request(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        // Retry with exponential backoff, but ONLY for safe GET requests on network errors.
        val attempts = if (method == "GET") 3 else 1
        var last: ApiException? = null
        for (i in 0 until attempts) {
            try {
                return@withContext once(method, path, body)
            } catch (e: ApiException) {
                if (e.code != 0 || i == attempts - 1) throw e // only network errors (code 0) retry
                last = e
                delay(300L * (1 shl i))
            }
        }
        throw last ?: ApiException(FriendlyErrors.GENERIC)
    }

    private fun once(method: String, path: String, body: JSONObject?): JSONObject {
        val url = baseUrl.trimEnd('/') + path
        val builder = Request.Builder()
            .url(url)
            .header("X-MAX-Client", "android")
        tokens.token?.let { builder.header("Authorization", "Bearer $it") }
        builder.method(method, if (method == "GET") null else (body ?: JSONObject()).toString().toRequestBody(json))

        try {
            http.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val obj = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (res.isSuccessful) return obj
                if (res.code == 401 && tokens.hasToken()) tokens.clear() // session is gone: force sign-in
                val serverMsg = obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                // Backend 4xx messages are already user-friendly: surface them verbatim
                // so sign-in/sign-up show the real cause (e.g. wrong password, email taken).
                android.util.Log.e("MaxApi", "backend error $method $url -> HTTP ${res.code}: ${serverMsg ?: "(no message)"}")
                throw ApiException(FriendlyErrors.forStatus(res.code, serverMsg), res.code)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            android.util.Log.e("MaxApi", "timeout $method $url: ${e.javaClass.simpleName}: ${e.message}")
            throw ApiException(FriendlyErrors.TIMEOUT, 0)
        } catch (e: IOException) {
            // IOException covers DNS failures, refused connections, SSL errors and
            // cleartext blocks. Log the real cause; only claim "offline" when the
            // device genuinely has no network.
            android.util.Log.e("MaxApi", "network failure $method $url: ${e.javaClass.simpleName}: ${e.message}")
            if (e is UnknownHostException) {
                android.util.Log.e("MaxApi", "UnknownHost: check API_BASE_URL ($baseUrl) and DNS.")
            }
            if (!isNetworkAvailable()) throw ApiException(FriendlyErrors.OFFLINE, 0)
            throw ApiException(FriendlyErrors.SERVER_UNREACHABLE, 0)
        } catch (e: Exception) {
            // Misconfiguration (malformed URL, JSON misuse) must never masquerade as offline.
            android.util.Log.e("MaxApi", "unexpected failure $method $url: ${e.javaClass.simpleName}: ${e.message}")
            throw ApiException(FriendlyErrors.GENERIC, -1)
        }
    }

    suspend fun login(email: String, password: String) {
        val res = request("POST", "/api/auth/login", JSONObject().put("email", email).put("password", password))
        tokens.token = res.optString("token").ifBlank { throw ApiException(FriendlyErrors.GENERIC) }
        refreshProfile()
    }

    suspend fun signUp(name: String, email: String, password: String) {
        val res = request("POST", "/api/auth/signup", JSONObject().put("email", email).put("password", password).put("fullName", name))
        tokens.token = res.optString("token").ifBlank { throw ApiException(FriendlyErrors.GENERIC) }
        refreshProfile()
    }

    suspend fun refreshProfile() {
        val me = request("GET", "/api/auth/me")
        tokens.displayName = me.optJSONObject("user")?.optString("fullName").orEmpty()
    }

    suspend fun logout() {
        try { request("POST", "/api/auth/logout") } catch (_: ApiException) { /* still sign out locally */ }
        tokens.clear()
    }
}
