package io.github.dwicki.hamatterpair.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the app authenticates against Home Assistant. */
enum class AuthMethod { OAUTH, LONG_LIVED_TOKEN }

/**
 * Non-secret connection settings plus the secret tokens (kept in [SecureStore]).
 *
 * @property localUrl  Base URL used at home, e.g. `http://homeassistant.local:8123`.
 * @property externalUrl Optional URL used when [localUrl] is unreachable.
 * @property pinnedCertSha256 Optional SHA-256 fingerprint (hex) of a self-signed server
 *   certificate the user explicitly chose to trust.
 */
data class ServerConfig(
    val localUrl: String,
    val externalUrl: String?,
    val authMethod: AuthMethod,
    val pinnedCertSha256: String?,
)

/** OAuth token set as returned by `/auth/token`. */
data class OAuthTokens(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long)

/** Simple local-only configuration repository. No cloud sync, no analytics. */
class ConfigRepository(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<ServerConfig?> = _config.asStateFlow()

    private fun load(): ServerConfig? {
        val url = prefs.getString(KEY_LOCAL, null) ?: return null
        return ServerConfig(
            localUrl = url,
            externalUrl = prefs.getString(KEY_EXTERNAL, null),
            authMethod = AuthMethod.valueOf(prefs.getString(KEY_METHOD, AuthMethod.OAUTH.name)!!),
            pinnedCertSha256 = prefs.getString(KEY_PIN, null),
        )
    }

    fun save(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_LOCAL, config.localUrl)
            .putString(KEY_EXTERNAL, config.externalUrl)
            .putString(KEY_METHOD, config.authMethod.name)
            .putString(KEY_PIN, config.pinnedCertSha256)
            .apply()
        _config.value = config
    }

    var longLivedToken: String?
        get() = secure.get(SECRET_LLT)
        set(v) = secure.put(SECRET_LLT, v)

    var oauthTokens: OAuthTokens?
        get() {
            val access = secure.get(SECRET_ACCESS) ?: return null
            val refresh = secure.get(SECRET_REFRESH) ?: return null
            return OAuthTokens(access, refresh, prefs.getLong(KEY_EXPIRES, 0))
        }
        set(v) {
            secure.put(SECRET_ACCESS, v?.accessToken)
            secure.put(SECRET_REFRESH, v?.refreshToken)
            prefs.edit().putLong(KEY_EXPIRES, v?.expiresAtMillis ?: 0).apply()
        }

    /** UI preference: show advanced diagnostic details (VID/PID, RSSI, raw phases). */
    var showDetails: Boolean
        get() = prefs.getBoolean(KEY_DETAILS, false)
        set(v) = prefs.edit().putBoolean(KEY_DETAILS, v).apply()

    /** True when a server is configured *and* credentials for it exist. */
    val isSignedIn: Boolean
        get() {
            val cfg = _config.value ?: return false
            return when (cfg.authMethod) {
                AuthMethod.OAUTH -> oauthTokens != null
                AuthMethod.LONG_LIVED_TOKEN -> longLivedToken != null
            }
        }

    fun signOut() {
        secure.clear()
        prefs.edit().clear().apply()
        _config.value = null
    }

    private companion object {
        const val KEY_LOCAL = "local_url"
        const val KEY_EXTERNAL = "external_url"
        const val KEY_METHOD = "auth_method"
        const val KEY_PIN = "pinned_cert"
        const val KEY_EXPIRES = "token_expires"
        const val KEY_DETAILS = "show_details"
        const val SECRET_LLT = "llt"
        const val SECRET_ACCESS = "access"
        const val SECRET_REFRESH = "refresh"
    }
}
