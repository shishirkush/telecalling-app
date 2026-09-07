package com.telecall.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.telecall.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin client over Supabase's REST surface (GoTrue for auth, PostgREST for
 * data). Deliberately hand-rolled on OkHttp rather than pulling in a Supabase
 * SDK: fewer transitive dependencies, and the wire format is stable and
 * documented, so this will not break on an SDK major-version bump.
 */
class SupabaseClient(context: Context) {

    private val appContext = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

    // -----------------------------------------------------------------
    // Session storage (encrypted at rest)
    // -----------------------------------------------------------------

    private val prefs: SharedPreferences by lazy {
        try {
            val key = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "telecall_session",
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore unavailable on some heavily-modified OEM ROMs. Falling
            // back keeps the app usable; the token is short-lived either way.
            appContext.getSharedPreferences("telecall_session_fallback", Context.MODE_PRIVATE)
        }
    }

    @Volatile private var session: AuthSession? = null

    init {
        prefs.getString(KEY_SESSION, null)?.let { raw ->
            session = runCatching { json.decodeFromString<AuthSession>(raw) }.getOrNull()
        }
    }

    val currentUserId: String? get() = session?.user?.id
    val isSignedIn: Boolean get() = session != null

    private fun persist(s: AuthSession?) {
        session = s
        prefs.edit().apply {
            if (s == null) remove(KEY_SESSION) else putString(KEY_SESSION, json.encodeToString(s))
        }.apply()
    }

    fun signOut() = persist(null)

    // -----------------------------------------------------------------
    // Auth
    // -----------------------------------------------------------------

    @Serializable
    private data class LoginBody(val email: String, val password: String)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token")  val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in")    val expiresIn: Long = 3600,
        val user: AuthUser
    )

    @Serializable
    private data class RefreshBody(@SerialName("refresh_token") val refreshToken: String)

    /**
     * Agents sign in with a login ID issued by their supervisor, never an
     * email address. GoTrue is an email/password service, so the ID is
     * expanded to "<login_id>@<LOGIN_DOMAIN>" here. The domain is an
     * internal detail: it is never displayed and never receives mail.
     *
     * Kept deliberately dumb — lowercase and trim only — so that what the
     * supervisor typed into the Supabase dashboard and what the agent types
     * on the phone resolve to the same address.
     */
    private fun loginIdToEmail(loginId: String): String {
        val id = loginId.trim().lowercase()
        // Tolerate an agent who types the full address out of habit.
        return if (id.contains('@')) id else "$id@${BuildConfig.LOGIN_DOMAIN}"
    }

    suspend fun signIn(loginId: String, password: String): Outcome<AuthSession> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || anonKey.isBlank()) {
                return@withContext Outcome.Err(
                    "App is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties and rebuild."
                )
            }
            val req = Request.Builder()
                .url("$baseUrl/auth/v1/token?grant_type=password")
                .addHeader("apikey", anonKey)
                .addHeader("Content-Type", "application/json")
                .post(
                    json.encodeToString(LoginBody(loginIdToEmail(loginId), password))
                        .toRequestBody(JSON_MEDIA)
                )
                .build()

            execute(req).fold(
                onOk = { body ->
                    val t = json.decodeFromString<TokenResponse>(body)
                    val s = AuthSession(
                        accessToken = t.accessToken,
                        refreshToken = t.refreshToken,
                        expiresAt = System.currentTimeMillis() + (t.expiresIn * 1000),
                        user = t.user
                    )
                    persist(s)
                    Outcome.Ok(s)
                },
                onErr = { Outcome.Err(friendlyAuthError(it)) }
            )
        }

    /** Returns true if a usable access token is available after this call. */
    private fun ensureFreshToken(): Boolean {
        val s = session ?: return false
        // Refresh a minute before expiry to avoid racing the boundary.
        if (System.currentTimeMillis() < s.expiresAt - 60_000) return true

        val req = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
            .addHeader("apikey", anonKey)
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(RefreshBody(s.refreshToken)).toRequestBody(JSON_MEDIA))
            .build()

        return execute(req).fold(
            onOk = { body ->
                val t = json.decodeFromString<TokenResponse>(body)
                persist(
                    AuthSession(
                        accessToken = t.accessToken,
                        refreshToken = t.refreshToken,
                        expiresAt = System.currentTimeMillis() + (t.expiresIn * 1000),
                        user = t.user
                    )
                )
                true
            },
            onErr = {
                persist(null)   // refresh token dead — force re-login
                false
            }
        )
    }

    // -----------------------------------------------------------------
    // Data
    // -----------------------------------------------------------------

    /** GET against a PostgREST table, e.g. get("leads", "assigned_to=eq.$uid"). */
    suspend fun get(table: String, query: String): Outcome<String> =
        withContext(Dispatchers.IO) {
            if (!ensureFreshToken()) return@withContext Outcome.Err(SESSION_EXPIRED)
            val url = "$baseUrl/rest/v1/$table".toHttpUrl().newBuilder().apply {
                query.split('&').filter { it.isNotBlank() }.forEach { pair ->
                    val i = pair.indexOf('=')
                    if (i > 0) addQueryParameter(pair.substring(0, i), pair.substring(i + 1))
                }
            }.build()

            execute(authed(Request.Builder().url(url).get()).build())
                .fold(onOk = { Outcome.Ok(it) }, onErr = { Outcome.Err(it) })
        }

    /** POST to a Postgres function exposed at /rest/v1/rpc/{name}. */
    suspend fun rpc(name: String, bodyJson: String = "{}"): Outcome<String> =
        withContext(Dispatchers.IO) {
            if (!ensureFreshToken()) return@withContext Outcome.Err(SESSION_EXPIRED)
            val req = authed(
                Request.Builder()
                    .url("$baseUrl/rest/v1/rpc/$name")
                    .post(bodyJson.toRequestBody(JSON_MEDIA))
            ).build()

            execute(req).fold(onOk = { Outcome.Ok(it) }, onErr = { Outcome.Err(it) })
        }

    private fun authed(b: Request.Builder): Request.Builder = b
        .addHeader("apikey", anonKey)
        .addHeader("Authorization", "Bearer ${session?.accessToken.orEmpty()}")
        .addHeader("Content-Type", "application/json")
        .addHeader("Accept", "application/json")

    // -----------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------

    private class Result(val ok: Boolean, val body: String)

    private fun execute(req: Request): Result = try {
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (res.isSuccessful) Result(true, body)
            else Result(false, extractError(body, res.code))
        }
    } catch (e: IOException) {
        Result(false, "Network unavailable. Check the phone's data connection.")
    } catch (e: Exception) {
        Result(false, e.message ?: "Unexpected error")
    }

    private inline fun <T> Result.fold(onOk: (String) -> T, onErr: (String) -> T): T =
        if (ok) {
            try { onOk(body) } catch (e: Exception) { onErr("Could not read server response: ${e.message}") }
        } else onErr(body)

    private fun extractError(body: String, code: Int): String {
        // PostgREST returns {"message": "..."}; GoTrue returns
        // {"error_description": "..."} or {"msg": "..."}.
        val keys = listOf("message", "error_description", "msg", "error")
        for (k in keys) {
            val m = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(body)
            if (m != null) return m.groupValues[1].replace("\\\"", "\"")
        }
        return "Request failed (HTTP $code)"
    }

    private fun friendlyAuthError(raw: String): String = when {
        raw.contains("Invalid login", ignoreCase = true) ||
            raw.contains("invalid_grant", ignoreCase = true) -> "Incorrect login ID or password."
        // Agents have no real mailbox, so they can never action this
        // themselves — point them at the person who can.
        raw.contains("Email not confirmed", ignoreCase = true) ->
            "This account is not active yet. Ask your supervisor to confirm it."
        else -> raw
    }

    companion object {
        private const val KEY_SESSION = "session_json"
        const val SESSION_EXPIRED = "Your session expired. Please sign in again."
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
