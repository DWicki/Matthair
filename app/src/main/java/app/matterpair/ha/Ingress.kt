package app.matterpair.ha

import app.matterpair.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * Authenticated path to the Matter Server through Home Assistant's ingress proxy.
 *
 * The Matter Server's `/ws` and `/ble` endpoints have **no authentication** of their own. Instead
 * of exposing port 5580, we go through `https://<ha>/api/hassio_ingress/<token>/…`, which only
 * forwards requests that carry a valid `ingress_session` cookie. That session is issued by the
 * Supervisor to an authenticated **admin** user over the HA WebSocket API and must be re-validated
 * at least every 15 minutes.
 */
class IngressSession(
    private val api: HaApi,
    private val baseUrl: String,
    private val addon: MatterAddon,
) {
    @Volatile var session: String? = null
        private set
    private var keepAlive: Job? = null

    suspend fun open(scope: CoroutineScope) {
        session = api.createIngressSession()
        keepAlive?.cancel()
        keepAlive = scope.launch {
            while (isActive) {
                delay(5 * 60_000)
                val s = session ?: break
                runCatching { api.validateIngressSession(s) }.onFailure {
                    AppLog.w(TAG, "Ingress session refresh failed, creating a new one", it)
                    session = runCatching { api.createIngressSession() }.getOrNull()
                }
            }
        }
    }

    fun close() {
        keepAlive?.cancel()
        session = null
    }

    /** WebSocket request for a path below the app's ingress root, e.g. `ble` or `ws`. */
    fun webSocketRequest(path: String): Request {
        val ingress = addon.ingressUrl ?: throw HaException("no_ingress", "Matter Server app has no ingress URL")
        val url = baseUrl.trimEnd('/').replaceFirst("http", "ws") + ingress.trimEnd('/') + "/" + path.trimStart('/')
        val s = session ?: throw HaException("no_ingress", "Ingress session not open")
        return Request.Builder().url(url).header("Cookie", "ingress_session=$s").build()
    }

    private companion object { const val TAG = "Ingress" }
}
