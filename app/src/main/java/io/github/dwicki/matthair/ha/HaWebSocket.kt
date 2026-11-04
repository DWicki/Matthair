package io.github.dwicki.matthair.ha

import io.github.dwicki.matthair.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
 * The server opens with `auth_required`; we answer with the access token and get `auth_ok`
 * (or `auth_invalid`). After that every command carries an integer `id` and is answered by
 * `{id, type: "result", success, result | error}`. Subscriptions also receive
 * `{id, type: "event", event}` messages.
 */
class HaWebSocket private constructor(private val token: String) {

    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonElement>>()
    private val subscriptions = ConcurrentHashMap<Int, (JsonElement) -> Unit>()
    private val authenticated = CompletableDeferred<String>()
    private val closed = CompletableDeferred<Unit>()
    private lateinit var socket: WebSocket

    var haVersion: String = "?"
        private set

    val isClosed: Boolean get() = closed.isCompleted

    /**
     * Send a command and wait for its result. Throws [HaException] on `success: false`.
     * [args] are top-level fields of the command message; HA does not nest them.
     */
    suspend fun call(type: String, args: Map<String, JsonElement> = emptyMap(), timeoutMs: Long = 30_000): JsonElement {
        val id = ids.getAndIncrement()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        try {
            if (!socket.send(command(id, type, args))) throw HaException("connection_lost", "WebSocket closed")
            return withTimeout(timeoutMs) { result.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Run a subscription command and expose its events as a cold flow. Cancelling it unsubscribes. */
    fun subscribe(type: String, args: Map<String, JsonElement> = emptyMap()): Flow<JsonElement> = callbackFlow {
        val id = ids.getAndIncrement()
        val confirmed = CompletableDeferred<JsonElement>()
        subscriptions[id] = { trySend(it) }
        pending[id] = confirmed
        socket.send(command(id, type, args))
        runCatching { withTimeout(15_000) { confirmed.await() } }.onFailure { close(it) }

        awaitClose {
            subscriptions.remove(id)
            pending.remove(id)
            socket.send(command(ids.getAndIncrement(), "unsubscribe_events", mapOf("subscription" to id.json)))
        }
    }

    fun close() {
        socket.close(1000, null)
    }

    private fun command(id: Int, type: String, args: Map<String, JsonElement>): String = buildJsonObject {
        put("id", id)
        put("type", type)
        args.forEach { (key, value) -> put(key, value) }
    }.toString()

    private fun onMessage(message: JsonObject) {
        when (message.str("type")) {
            "auth_required" -> socket.send(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString())
            "auth_ok" -> authenticated.complete(message.str("ha_version") ?: "?")
            "auth_invalid" -> authenticated.completeExceptionally(HaException("auth_invalid", message.str("message") ?: "Invalid auth"))
            "result" -> onResult(message)
            "event" -> message.int("id")?.let { subscriptions[it]?.invoke(message["event"] ?: JsonNull) }
        }
    }

    private fun onResult(message: JsonObject) {
        val result = message.int("id")?.let { pending[it] } ?: return
        if (message.bool("success") == true) {
            result.complete(message["result"] ?: JsonNull)
        } else {
            val error = message["error"] as? JsonObject
            result.completeExceptionally(
                HaException(error?.str("code") ?: "unknown_error", error?.str("message") ?: "Unknown error"),
            )
        }
    }

    private fun onClosed(cause: Throwable?) {
        val error = HaException("connection_lost", cause?.message ?: "Connection closed")
        authenticated.completeExceptionally(cause ?: error)
        pending.values.forEach { it.completeExceptionally(error) }
        closed.complete(Unit)
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // HA may coalesce several messages into one JSON array.
            val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return
            val messages = element as? JsonArray ?: listOf(element)
            messages.forEach { (it as? JsonObject)?.let(::onMessage) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.w(TAG, "HA WebSocket failure", t)
            onClosed(t)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(null)
    }

    companion object {
        private const val TAG = "HaWebSocket"

        /** Connect and authenticate. [baseUrl] is e.g. `http://homeassistant.local:8123`. */
        suspend fun connect(http: OkHttpClient, baseUrl: String, token: String): HaWebSocket {
            val client = HaWebSocket(token)
            val url = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/api/websocket"
            client.socket = http.newWebSocket(Request.Builder().url(url).build(), client.Listener())
            try {
                client.haVersion = withTimeout(15_000) { client.authenticated.await() }
            } catch (e: Exception) {
                client.socket.cancel()
                throw e
            }
            AppLog.i(TAG, "Connected to Home Assistant ${client.haVersion}")
            return client
        }
    }
}
