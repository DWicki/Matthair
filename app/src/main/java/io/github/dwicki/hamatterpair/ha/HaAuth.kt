package io.github.dwicki.hamatterpair.ha

import io.github.dwicki.hamatterpair.data.AuthMethod
import io.github.dwicki.hamatterpair.data.ConfigRepository
import io.github.dwicki.hamatterpair.data.OAuthTokens
import io.github.dwicki.hamatterpair.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Home Assistant authentication.
 *
 * **OAuth2 (IndieAuth flavour)**: HA identifies clients by a URL (`client_id`). A redirect URI on
 * the same host as the client id is accepted without HA fetching anything. We use a loopback
 * client id; the in-app WebView intercepts the redirect to it, so no server needs to listen there
 * and nothing ever leaves the phone. See `docs/architecture.md#authentication`.
 *
 * **Long-lived access token**: created by the user in their HA profile and pasted into the app.
 */
class HaAuth(private val config: ConfigRepository) {

    private val refreshLock = Mutex()

    /** Valid access token for WebSocket/HTTP calls; refreshes OAuth tokens when needed. */
    suspend fun accessToken(http: OkHttpClient, baseUrl: String): String {
        val cfg = config.config.value ?: throw HaException("not_configured", "No server configured")
        return when (cfg.authMethod) {
            AuthMethod.LONG_LIVED_TOKEN -> config.longLivedToken ?: throw HaException("no_token", "No token stored")
            AuthMethod.OAUTH -> refreshLock.withLock {
                val tokens = config.oauthTokens ?: throw HaException("no_token", "Not signed in")
                if (tokens.expiresAtMillis - System.currentTimeMillis() > 60_000) {
                    tokens.accessToken
                } else {
                    refresh(http, baseUrl, tokens.refreshToken).also { config.oauthTokens = it }.accessToken
                }
            }
        }
    }

    /** Exchange the authorization code from the WebView redirect for tokens. */
    suspend fun exchangeCode(http: OkHttpClient, baseUrl: String, code: String): OAuthTokens =
        tokenRequest(
            http, baseUrl,
            FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .build(),
            previousRefresh = null,
        )

    private suspend fun refresh(http: OkHttpClient, baseUrl: String, refreshToken: String): OAuthTokens {
        AppLog.i(TAG, "Refreshing access token")
        return tokenRequest(
            http, baseUrl,
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", CLIENT_ID)
                .build(),
            previousRefresh = refreshToken,
        )
    }

    private suspend fun tokenRequest(
        http: OkHttpClient,
        baseUrl: String,
        body: FormBody,
        previousRefresh: String?,
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/auth/token").post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HaException("auth_failed", "Token request failed: HTTP ${resp.code}")
            }
            val json = Json.parseToJsonElement(text).jsonObject
            OAuthTokens(
                accessToken = json.getValue("access_token").jsonPrimitive.content,
                // A refresh grant does not return a new refresh token; keep the old one.
                refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: previousRefresh
                    ?: throw HaException("auth_failed", "No refresh token"),
                expiresAtMillis = System.currentTimeMillis() + json.getValue("expires_in").jsonPrimitive.long * 1000,
            )
        }
    }

    companion object {
        private const val TAG = "HaAuth"

        /** Loopback client id: accepted by HA without fetching (same-host redirect). */
        const val CLIENT_ID = "http://127.0.0.1/matterpair/"
        const val REDIRECT_URI = "http://127.0.0.1/matterpair/callback"

        fun authorizeUrl(baseUrl: String, state: String): String =
            (baseUrl.trimEnd('/') + "/auth/authorize").toHttpUrl().newBuilder()
                .addQueryParameter("response_type", "code")
                .addQueryParameter("client_id", CLIENT_ID)
                .addQueryParameter("redirect_uri", REDIRECT_URI)
                .addQueryParameter("state", state)
                .build().toString()
    }
}

/** Error from Home Assistant (WebSocket error result or HTTP failure). */
class HaException(val code: String, message: String) : Exception(message)
