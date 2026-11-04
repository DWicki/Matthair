package app.matterpair.ha

import app.matterpair.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal client for the Home Assistant WebSocket API (`/api/websocket`).
 *
 * Protocol: server sends `auth_required`, client answers `{type: auth, access_token}`, server
 * answers `auth_ok` (or `auth_invalid`). Afterwards every command carries an integer `id` and is
 * answered by `{id, type: "result", success, result|error}`. Subscriptions additionally receive
 * `{id, type: "event", event}` messages.
 */
class HaWebSocket private constructor(
    private val ws: WebSocket,
    val haVersion: String,
    private val pending: ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>,
    private val subscriptions: ConcurrentHashMap<Int, (JsonElement) -> Unit>,
    private val closed: CompletableDeferred<Throwable?>,
) {
    private val ids = AtomicInteger(1)

    val isClosed: Boolean get() = closed.isCompleted

    /**
     * Send a command and wait for its result. Throws [HaException] on `success: false`.
     *
     * @param args additional top-level fields of the command message (HA does not nest them).
     */
    suspend fun call(type: String, args: Map<String, JsonElement> = emptyMap(), timeoutMs: Long = 30_000): JsonElement {
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("id", id)
            put("type", type)
            for ((k, v) in args) put(k, v)
        }
        if (!ws.send(msg.toString())) {
            pending.remove(id)
            throw HaException("connection_lost", "WebSocket closed")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Start a subscription command and expose its events as a cold [Flow]. Cancelling the flow
     * sends `unsubscribe_events` (best effort).
     */
    fun subscribe(type: String, args: Map<String, JsonElement> = emptyMap()): Flow<JsonElement> = callbackFlow {
        val id = ids.getAndIncrement()
        subscriptions[id] = { trySend(it) }
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val msg = buildJsonObject {
            put("id", id); put("type", type)
            for ((k, v) in args) put(k, v)
        }
        ws.send(msg.toString())
        try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: Exception) {
            close(e)
        }
        awaitClose {
            subscriptions.remove(id)
            pending.remove(id)
            val unsub = buildJsonObject {
                put("id", ids.getAndIncrement()); put("type", "unsubscribe_events"); put("subscription", id)
            }
            ws.send(unsub.toString())
        }
    }

    fun close() {
        ws.close(1000, null)
    }

    companion object {
        private const val TAG = "HaWebSocket"

        /** Connect and authenticate. [baseUrl] is e.g. `https://ha.example:8123`. */
        suspend fun connect(http: OkHttpClient, baseUrl: String, token: String): HaWebSocket {
            val wsUrl = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/api/websocket"
            val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
            val subs = ConcurrentHashMap<Int, (JsonElement) -> Unit>()
            val authResult = CompletableDeferred<String>()
            val closed = CompletableDeferred<Throwable?>()
            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // HA may coalesce several messages into one JSON array.
                    val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return
                    val messages = if (element is kotlinx.serialization.json.JsonArray) element else listOf(element)
                    for (m in messages) handle(webSocket, m.jsonObject)
                }

                private fun handle(webSocket: WebSocket, m: JsonObject) {
                    when (m["type"]?.jsonPrimitive?.content) {
                        "auth_required" -> webSocket.send(
                            buildJsonObject { put("type", "auth"); put("access_token", token) }.toString(),
                        )
                        "auth_ok" -> authResult.complete(m["ha_version"]?.jsonPrimitive?.content ?: "?")
                        "auth_invalid" -> authResult.completeExceptionally(
                            HaException("auth_invalid", m["message"]?.jsonPrimitive?.content ?: "Invalid auth"),
                        )
                        "result" -> {
                            val id = m["id"]?.jsonPrimitive?.int ?: return
                            val d = pending[id] ?: return
                            if (m["success"]?.jsonPrimitive?.boolean == true) {
                                d.complete(m["result"] ?: JsonNull)
                            } else {
                                val err = m["error"]?.jsonObject
                                d.completeExceptionally(
                                    HaException(
                                        err?.get("code")?.jsonPrimitive?.content ?: "unknown_error",
                                        err?.get("message")?.jsonPrimitive?.content ?: "Unknown error",
                                    ),
                                )
                            }
                        }
                        "event" -> {
                            val id = m["id"]?.jsonPrimitive?.int ?: return
                            subs[id]?.invoke(m["event"] ?: JsonNull)
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    AppLog.w(TAG, "HA WebSocket failure", t)
                    fail(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(null)

                private fun fail(t: Throwable?) {
                    val ex = HaException("connection_lost", t?.message ?: "Connection closed")
                    authResult.completeExceptionally(t ?: ex)
                    pending.values.forEach { it.completeExceptionally(ex) }
                    closed.complete(t)
                }
            }
            val ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
            val version = try {
                withTimeout(15_000) { authResult.await() }
            } catch (e: Exception) {
                ws.cancel()
                throw e
            }
            AppLog.i(TAG, "Connected to Home Assistant $version")
            return HaWebSocket(ws, version, pending, subs, closed)
        }
    }
}

// --- tiny JSON helpers used across the HA layer -------------------------------------------

internal val JsonElement.obj: JsonObject get() = jsonObject
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
