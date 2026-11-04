package app.matterpair.ha

import app.matterpair.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** First message of the Matter server WebSocket. */
data class MatterServerInfo(
    val sdkVersion: String,
    val schemaVersion: Int,
    val wifiCredentialsSet: Boolean,
    val threadCredentialsSet: Boolean,
    val bluetoothEnabled: Boolean,
)

/** A commissioned node as reported by the Matter server. */
data class MatterNode(
    val nodeId: Long,
    val available: Boolean,
    val isBridge: Boolean,
    val vendorName: String?,
    val productName: String?,
    val nodeLabel: String?,
)

/**
 * Read-mostly client for the Matter server's own WebSocket API (`/ws`), reached through
 * [IngressSession]. Used for things HA does not expose: server capabilities (are Wi-Fi/Thread
 * credentials set? is BLE enabled?), node availability, and the `node_added` event, which is the
 * most precise "commissioning finished" signal.
 *
 * Message format: commands `{message_id, command, args}`, results `{message_id, result}` or
 * `{message_id, error_code, details}`, events `{event, data}`.
 */
class MatterServerClient private constructor(
    private val ws: WebSocket,
    val info: MatterServerInfo,
    private val pending: ConcurrentHashMap<String, CompletableDeferred<JsonElement>>,
    val events: SharedFlow<Pair<String, JsonElement>>,
) {
    private val ids = AtomicInteger(1)

    suspend fun call(command: String, args: JsonObject? = null, timeoutMs: Long = 30_000): JsonElement {
        val id = "mp-" + ids.getAndIncrement()
        val d = CompletableDeferred<JsonElement>()
        pending[id] = d
        ws.send(buildJsonObject {
            put("message_id", id)
            put("command", command)
            if (args != null) put("args", args)
        }.toString())
        return try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
    }

    /** Subscribe to events; the result is the current node list. */
    suspend fun startListening(): List<MatterNode> = call("start_listening").jsonArray.map { parseNode(it.obj) }

    suspend fun nodes(): List<MatterNode> = call("get_nodes").jsonArray.map { parseNode(it.obj) }

    fun close() { ws.close(1000, null) }

    companion object {
        private const val TAG = "MatterServer"

        fun parseNode(o: JsonObject): MatterNode {
            val attrs = o["attributes"] as? JsonObject
            fun attr(path: String) = (attrs?.get(path) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            return MatterNode(
                nodeId = o.long("node_id") ?: -1,
                available = o.bool("available") == true,
                isBridge = o.bool("is_bridge") == true,
                vendorName = attr("0/40/1"),
                productName = attr("0/40/3"),
                nodeLabel = attr("0/40/5"),
            )
        }

        suspend fun connect(http: OkHttpClient, ingress: IngressSession): MatterServerClient {
            val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
            val info = CompletableDeferred<MatterServerInfo>()
            val events = MutableSharedFlow<Pair<String, JsonElement>>(extraBufferCapacity = 64)
            val listener = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val o = runCatching { Json.parseToJsonElement(text).obj }.getOrNull() ?: return
                    when {
                        !info.isCompleted && o.containsKey("sdk_version") -> info.complete(
                            MatterServerInfo(
                                sdkVersion = o.str("sdk_version") ?: "?",
                                schemaVersion = o.int("schema_version") ?: 0,
                                wifiCredentialsSet = o.bool("wifi_credentials_set") == true,
                                threadCredentialsSet = o.bool("thread_credentials_set") == true,
                                bluetoothEnabled = o.bool("bluetooth_enabled") == true,
                            ),
                        )
                        o.containsKey("event") -> events.tryEmit(o.str("event")!! to (o["data"] ?: JsonObject(emptyMap())))
                        o.containsKey("message_id") -> {
                            val d = pending[o.str("message_id")] ?: return
                            if (o.containsKey("error_code")) {
                                d.completeExceptionally(HaException("matter_${o.str("error_code")}", o.str("details") ?: "Matter server error"))
                            } else d.complete(o["result"] ?: JsonObject(emptyMap()))
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    AppLog.w(TAG, "Matter server WebSocket failure (HTTP ${response?.code})", t)
                    val ex = HaException("matter_ws", t.message ?: "failure")
                    info.completeExceptionally(ex)
                    pending.values.forEach { it.completeExceptionally(ex) }
                }
            }
            val ws = http.newWebSocket(ingress.webSocketRequest("ws"), listener)
            val serverInfo = try { withTimeout(15_000) { info.await() } } catch (e: Exception) { ws.cancel(); throw e }
            AppLog.i(TAG, "Matter server: sdk=${serverInfo.sdkVersion} schema=${serverInfo.schemaVersion} ble=${serverInfo.bluetoothEnabled}")
            return MatterServerClient(ws, serverInfo, pending, events)
        }
    }
}
