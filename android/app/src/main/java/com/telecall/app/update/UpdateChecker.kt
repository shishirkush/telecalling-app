package com.telecall.app.update

import com.telecall.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** What a signed-in agent's phone would need to update itself. */
data class UpdateInfo(
    val version: String,
    val downloadUrl: String
)

/**
 * Checks GitHub Releases for a newer build than the one currently installed.
 *
 * This app is sideloaded, not distributed through Play, so there is no
 * platform-level update mechanism — this is the whole of it. A separate,
 * unauthenticated OkHttp client on purpose: this talks to api.github.com,
 * never to Supabase, and must not share [com.telecall.app.data.SupabaseClient]'s
 * auth headers or session state.
 */
class UpdateChecker {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String,
        val assets: List<GithubAsset> = emptyList()
    )

    @Serializable
    private data class GithubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String
    )

    /**
     * Null on anything short of "there is a genuinely newer release" —
     * offline, GitHub unreachable, rate-limited, a release with no APK
     * asset, or already up to date. A failed check must never surface as
     * an error to the agent; it just means no banner this launch.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/releases/latest")
                .addHeader("Accept", "application/vnd.github+json")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val release = json.decodeFromString<GithubRelease>(body)
                val remoteVersion = release.tagName.removePrefix("v")

                if (!isNewer(remoteVersion, BuildConfig.VERSION_NAME)) return@withContext null

                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                    ?: return@withContext null

                UpdateInfo(version = remoteVersion, downloadUrl = apk.browserDownloadUrl)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Plain dotted-integer comparison: "1.10.0" > "1.9.0", missing parts count as 0. */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    companion object {
        private const val REPO = "shishirkush/telecalling-app"
    }
}
