package app.matterpair.ha

import app.matterpair.data.ConfigRepository
import app.matterpair.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** An authenticated connection to one Home Assistant instance. */
class HaSession(val http: OkHttpClient, val baseUrl: String, val api: HaApi) {
    val isAlive get() = !api.ws.isClosed
    val isTls get() = baseUrl.startsWith("https", ignoreCase = true)
}

/**
 * Creates and caches the [HaSession]. Tries the local URL first and falls back to the external
 * URL, so the app works both at home and (optionally) remotely.
 */
class HaConnector(private val config: ConfigRepository, private val auth: HaAuth) {
    private val lock = Mutex()
    private var current: HaSession? = null

    suspend fun session(): HaSession = lock.withLock {
        current?.takeIf { it.isAlive }?.let { return@withLock it }
        val cfg = config.config.value ?: throw HaException("not_configured", "No server configured")
        val http = HttpClients.create(cfg.pinnedCertSha256)
        val urls = listOfNotNull(cfg.localUrl, cfg.externalUrl).distinct()
        var lastError: Exception? = null
        for (url in urls) {
            if (!reachable(http, url)) {
                AppLog.i(TAG, "Not reachable: $url")
                continue
            }
            try {
                val token = auth.accessToken(http, url)
                val ws = HaWebSocket.connect(http, url, token)
                return@withLock HaSession(http, url, HaApi(ws, http, url, token)).also { current = it }
            } catch (e: Exception) {
                AppLog.w(TAG, "Connecting to $url failed", e)
                lastError = e
            }
        }
        throw lastError ?: HaException("unreachable", "Home Assistant is not reachable")
    }

    fun invalidate() {
        current?.api?.ws?.close()
        current = null
    }

    private suspend fun reachable(http: OkHttpClient, url: String): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(4000) {
            runCatching {
                http.newCall(Request.Builder().url(url.trimEnd('/') + "/manifest.json").build()).execute().use { it.code in 200..499 }
            }.getOrElse { it is javax.net.ssl.SSLException } // TLS errors must surface, not be hidden as "unreachable"
        } ?: false
    }

    private companion object { const val TAG = "HaConnector" }
}
