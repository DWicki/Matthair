package io.github.dwicki.matthair.ble

import android.content.Context
import android.util.Base64
import io.github.dwicki.matthair.matter.BtpObserver
import io.github.dwicki.matthair.matter.MatterBle
import io.github.dwicki.matthair.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client side of the Matter server's **BLE proxy protocol v1** (`/ble`), implemented on top of
 * the Android BLE stack. The phone becomes the Bluetooth radio of the Matter server: the server
 * sends commands (scan, connect, GATT ops), the phone executes them and streams BTP data back.
 *
 * All commands of the protocol are implemented, including `write_and_subscribe` (needed for the
 * BTP handshake) and binary frames `0x01`/`0x02`/`0x03`. The full protocol is described in
 * `docs/ble-proxy-protocol.md`.
 *
 * Safety: the phone only reports Matter devices that match the code being commissioned
 * ([deviceFilter]) and only accepts `connect` for addresses it reported itself.
 */
class BleProxyClient(
    private val context: Context,
    private val http: OkHttpClient,
    private val scanner: BleScanner,
    /** Returns true for advertisements the server may see (matching discriminator). */
    private val deviceFilter: (ScanHit) -> Boolean,
) {
    /** Things the commissioning orchestrator wants to know about. */
    sealed interface Event {
        data object HandshakeOk : Event
        data class ServerScanning(val active: Boolean) : Event
        data class DeviceReported(val hit: ScanHit) : Event
        data class ConnectRequested(val address: String) : Event
        data class Connected(val address: String, val mtu: Int) : Event
        data class ConnectFailed(val address: String, val code: String, val gattStatus: Int?) : Event
        data class Btp(val event: BtpObserver.Event) : Event
        data class PeripheralDisconnected(val unexpected: Boolean, val gattStatus: Int?) : Event
        data class CommandFailed(val command: String, val code: String) : Event
        data class SocketClosed(val reason: String?) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 128)
    val events: SharedFlow<Event> = _events

    private var ws: WebSocket? = null
    private val hello = CompletableDeferred<Unit>()
    @Volatile private var serverScanning = false
    private var scanCollector: Job? = null
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val handles = AtomicInteger(1)
    private val connections = ConcurrentHashMap<Int, ProxyConnection>()

    val btp = BtpObserver { scope.launch { _events.emit(Event.Btp(it)) } }

    /** Per-connection state and its strictly ordered work queue. */
    private inner class ProxyConnection(val handle: Int, val gatt: GattConnection) {
        val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        var writeChar: UUID? = null
        var notifyChar: UUID? = null
        /** Notifications are held back until the `write_and_subscribe` response is sent. */
        @Volatile var holdNotifications = false
        val held = ArrayList<ByteArray>()
        val worker = scope.launch { for (op in queue) runCatching { op() } }
    }

    // --- Socket lifecycle ----------------------------------------------------------------------------

    /** Open the `/ble` WebSocket (through HA ingress) and perform the `hello` handshake. */
    suspend fun connect(request: Request) {
        ws = http.newWebSocket(request, listener)
        withTimeout(10_000) { hello.await() }
        _events.emit(Event.HandshakeOk)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(buildJsonObject { put("type", "hello"); put("version", PROTOCOL_VERSION) }.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            if (msg["type"]?.jsonPrimitive?.contentOrNull == "hello_response") {
                val err = msg["error"]?.jsonPrimitive?.contentOrNull
                if (err != null) {
                    hello.completeExceptionally(BleException(err, msg["message"]?.jsonPrimitive?.contentOrNull ?: err))
                } else {
                    AppLog.i(TAG, "BLE proxy handshake ok (v${msg["version"]})")
                    hello.complete(Unit)
                }
                return
            }
            val id = msg["id"]?.jsonPrimitive?.int ?: return
            val command = msg["command"]?.jsonPrimitive?.contentOrNull ?: return
            val args = msg["args"] as? JsonObject ?: JsonObject(emptyMap())
            dispatch(id, command, args)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            if (data.size < 3) return
            val opcode = data[0].toInt() and 0xFF
            val handle = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
            val payload = data.copyOfRange(3, data.size)
            if (opcode != OP_WRITE_DATA) return
            val conn = connections[handle] ?: return
            conn.queue.trySend {
                val target = conn.writeChar ?: return@trySend
                btp.onFrame(BtpObserver.Direction.TO_DEVICE, payload)
                runCatching { conn.gatt.write(target, payload, withResponse = true) }
                    .onFailure { AppLog.w(TAG, "Binary write failed on handle $handle", it) }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AppLog.w(TAG, "BLE proxy socket failure (HTTP ${response?.code})", t)
            hello.completeExceptionally(BleException("socket", t.message ?: "socket failure"))
            onSocketGone(t.message)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onSocketGone(reason)
    }

    private fun onSocketGone(reason: String?) {
        _events.tryEmit(Event.SocketClosed(reason))
        cleanupBle()
    }

    /** Close the socket and release every BLE resource. */
    fun close() {
        ws?.close(1000, "client closing")
        ws = null
        cleanupBle()
        scope.cancel()
    }

    private fun cleanupBle() {
        stopServerScan()
        val all = connections.values.toList()
        connections.clear()
        // Disconnect synchronously so the radio is free when close() returns.
        runBlocking { all.forEach { runCatching { it.gatt.disconnect() }; it.queue.close() } }
    }

    // --- Command dispatch ----------------------------------------------------------------------------

    private fun dispatch(id: Int, command: String, args: JsonObject) {
        AppLog.d(TAG, "← $command #$id")
        val handle = args["connection_handle"]?.jsonPrimitive?.int
        if (handle != null) {
            val conn = connections[handle]
            if (conn == null) {
                respondError(id, command, "not_connected", "Unknown connection handle $handle")
                return
            }
            conn.queue.trySend { execute(id, command, args, conn) }
        } else {
            scope.launch { execute(id, command, args, null) }
        }
    }

    private suspend fun execute(id: Int, command: String, args: JsonObject, conn: ProxyConnection?) {
        try {
            val result = when (command) {
                "start_scan" -> EMPTY.also { startServerScan() }
                "stop_scan" -> EMPTY.also { stopServerScan() }
                "connect" -> connectPeripheral(args)
                "disconnect" -> disconnectPeripheral(conn.required())
                "discover_services" -> discoverServices(conn.required())
                "discover_characteristics" -> discoverCharacteristics(conn.required(), args)
                "read_characteristic" -> readCharacteristic(conn.required(), args)
                "write_characteristic" -> writeCharacteristic(conn.required(), args)
                "subscribe_characteristic" -> subscribeCharacteristic(conn.required(), args)
                "unsubscribe_characteristic" -> unsubscribeCharacteristic(conn.required(), args)
                "request_mtu" -> buildJsonObject { put("mtu", conn.required().gatt.requestMtu(args.int("mtu"))) }
                "write_and_subscribe" -> {
                    writeAndSubscribe(id, conn.required(), args)
                    return // responds itself, see there
                }
                else -> throw BleException("internal_error", "Unknown command $command")
            }
            respondOk(id, result)
        } catch (e: BleException) {
            respondError(id, command, e.code, e.message ?: e.code)
        } catch (e: Exception) {
            respondError(id, command, "internal_error", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Commands with a `connection_handle` always get a connection from [dispatch]. */
    private fun ProxyConnection?.required(): ProxyConnection = this ?: throw BleException("not_connected", "No connection")

    private suspend fun disconnectPeripheral(conn: ProxyConnection): JsonObject {
        connections.remove(conn.handle)
        conn.gatt.disconnect()
        conn.queue.close()
        _events.emit(Event.PeripheralDisconnected(unexpected = false, gattStatus = null))
        return EMPTY
    }

    private suspend fun discoverServices(conn: ProxyConnection): JsonObject {
        val services = conn.gatt.discoverServices()
        return buildJsonObject {
            putJsonArray("services") { services.forEach { addJsonObject { put("uuid", MatterBle.toProtocolString(it)) } } }
        }
    }

    private fun discoverCharacteristics(conn: ProxyConnection, args: JsonObject): JsonObject {
        val service = MatterBle.parseUuid(args.string("service_uuid"))
        return buildJsonObject {
            putJsonArray("characteristics") {
                conn.gatt.characteristicsOf(service).forEach { characteristic ->
                    addJsonObject {
                        put("uuid", MatterBle.toProtocolString(characteristic.uuid))
                        put("properties", JsonArray(propertiesOf(characteristic.properties).map(::JsonPrimitive)))
                    }
                }
            }
        }
    }

    private suspend fun readCharacteristic(conn: ProxyConnection, args: JsonObject): JsonObject {
        val value = conn.gatt.read(MatterBle.parseUuid(args.string("characteristic_uuid")))
        return buildJsonObject { put("value", b64(value)) }
    }

    private suspend fun writeCharacteristic(conn: ProxyConnection, args: JsonObject): JsonObject {
        val uuid = MatterBle.parseUuid(args.string("characteristic_uuid"))
        val value = unb64(args.string("value"))
        conn.writeChar = uuid
        if (uuid == MatterBle.C1_UUID) btp.onFrame(BtpObserver.Direction.TO_DEVICE, value)
        conn.gatt.write(uuid, value, args.boolean("response") ?: false)
        return EMPTY
    }

    private suspend fun subscribeCharacteristic(conn: ProxyConnection, args: JsonObject): JsonObject {
        val uuid = MatterBle.parseUuid(args.string("characteristic_uuid"))
        conn.notifyChar = uuid
        conn.gatt.subscribe(uuid, true)
        return EMPTY
    }

    private suspend fun unsubscribeCharacteristic(conn: ProxyConnection, args: JsonObject): JsonObject {
        val uuid = MatterBle.parseUuid(args.string("characteristic_uuid"))
        if (conn.notifyChar != uuid) throw BleException("not_subscribed", "Not subscribed to $uuid")
        conn.gatt.subscribe(uuid, false)
        conn.notifyChar = null
        return EMPTY
    }

    /**
     * Writes the BTP handshake request and subscribes to C2 in one step. The device answers
     * with an indication right away, and the server must see our response before that
     * indication, so notifications are held back until the response is sent.
     */
    private suspend fun writeAndSubscribe(id: Int, conn: ProxyConnection, args: JsonObject) {
        val writeUuid = MatterBle.parseUuid(args.string("write_uuid"))
        val subscribeUuid = MatterBle.parseUuid(args.string("subscribe_uuid"))
        val value = unb64(args.string("write_value"))
        conn.writeChar = writeUuid
        conn.notifyChar = subscribeUuid
        conn.holdNotifications = true
        if (writeUuid == MatterBle.C1_UUID) btp.onFrame(BtpObserver.Direction.TO_DEVICE, value)
        try {
            conn.gatt.writeAndSubscribe(writeUuid, value, args.boolean("write_response") ?: false, subscribeUuid)
        } catch (e: Exception) {
            conn.holdNotifications = false
            throw e
        }
        respondOk(id, EMPTY)
        flushHeld(conn)
    }

    // --- Scanning ------------------------------------------------------------------------------------

    private fun startServerScan() {
        if (!scanner.isBluetoothOn) throw BleException("bluetooth_unavailable", "Bluetooth is off")
        if (serverScanning) return // re-arm with new params: our filter is fixed to Matter, nothing to do
        serverScanning = true
        scanner.acquire()
        _events.tryEmit(Event.ServerScanning(true))
        scanCollector = scope.launch {
            scanner.hits.collect { hit ->
                if (!serverScanning || !hit.connectable || !deviceFilter(hit)) return@collect
                reported += hit.address
                _events.emit(Event.DeviceReported(hit))
                sendEvent("device_discovered") {
                    put("address", hit.address)
                    hit.name?.let { put("name", it) }
                    put("rssi", hit.rssi)
                    put("connectable", hit.connectable)
                    putJsonObject("service_data") { put("fff6", b64(hit.serviceData)) }
                    putJsonArray("service_uuids") { add(JsonPrimitive("fff6")) }
                }
            }
        }
    }

    private fun stopServerScan() {
        if (!serverScanning) return
        serverScanning = false
        scanCollector?.cancel()
        scanner.release()
        _events.tryEmit(Event.ServerScanning(false))
    }

    // --- Connections ---------------------------------------------------------------------------------

    private suspend fun connectPeripheral(args: JsonObject): JsonObject {
        val address = args.string("address").uppercase()
        if (address !in reported) throw BleException("device_not_found", "Address was not reported by this proxy")
        if (connections.values.any { it.gatt.address == address }) throw BleException("already_connected", "Already connected")
        val timeout = args["timeout"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 30_000L
        _events.emit(Event.ConnectRequested(address))
        // Scanning while connecting hurts reliability on many Android stacks.
        if (serverScanning) stopServerScan()

        val handle = handles.getAndIncrement() and 0xFFFF
        lateinit var conn: ProxyConnection
        val gatt = GattConnection(
            context, address,
            onNotification = { uuid, value -> onNotification(conn, uuid, value) },
            onUnexpectedDisconnect = { status -> onPeripheralLost(conn, status) },
        )
        conn = ProxyConnection(handle, gatt)
        btp.reset()
        val mtu = try {
            gatt.connect(timeout)
        } catch (e: BleException) {
            _events.emit(Event.ConnectFailed(address, e.code, e.gattStatus))
            conn.queue.close()
            throw e
        }
        connections[handle] = conn
        _events.emit(Event.Connected(address, mtu))
        return buildJsonObject { put("connection_handle", handle); put("mtu", mtu) }
    }

    private fun onNotification(conn: ProxyConnection, uuid: UUID, value: ByteArray) {
        if (uuid == conn.notifyChar) {
            btp.onFrame(BtpObserver.Direction.FROM_DEVICE, value)
            synchronized(conn.held) {
                if (conn.holdNotifications) { conn.held += value; return }
            }
            sendBinary(OP_NOTIFICATION, conn.handle, value)
        } else {
            sendEvent("characteristic_notification") {
                put("connection_handle", conn.handle)
                put("characteristic_uuid", MatterBle.toProtocolString(uuid))
                put("value", b64(value))
            }
        }
    }

    private fun flushHeld(conn: ProxyConnection) {
        val toSend = synchronized(conn.held) {
            conn.holdNotifications = false
            conn.held.toList().also { conn.held.clear() }
        }
        toSend.forEach { sendBinary(OP_NOTIFICATION, conn.handle, it) }
    }

    private fun onPeripheralLost(conn: ProxyConnection, status: Int) {
        if (connections.remove(conn.handle) == null) return
        AppLog.w(TAG, "Peripheral disconnected unexpectedly (status $status)")
        sendEvent("disconnected") {
            put("connection_handle", conn.handle)
            put("reason", "gatt_status_$status")
        }
        _events.tryEmit(Event.PeripheralDisconnected(unexpected = true, gattStatus = status))
        scope.launch { conn.gatt.disconnect(); conn.queue.close() }
    }

    // --- Wire helpers --------------------------------------------------------------------------------

    private fun respondOk(id: Int, result: JsonObject) {
        ws?.send(buildJsonObject { put("id", id); put("success", true); put("result", result) }.toString())
    }

    private fun respondError(id: Int, command: String, code: String, message: String) {
        AppLog.w(TAG, "→ $command #$id failed: $code ($message)")
        _events.tryEmit(Event.CommandFailed(command, code))
        ws?.send(buildJsonObject {
            put("id", id); put("success", false); put("error", code); put("message", message)
        }.toString())
    }

    private fun sendEvent(name: String, data: JsonObjectBuilder.() -> Unit) {
        ws?.send(buildJsonObject { put("event", name); putJsonObject("data", data) }.toString())
    }

    private fun sendBinary(opcode: Int, handle: Int, payload: ByteArray) {
        val frame = ByteArray(3 + payload.size)
        frame[0] = opcode.toByte()
        frame[1] = (handle ushr 8).toByte()
        frame[2] = handle.toByte()
        payload.copyInto(frame, 3)
        ws?.send(frame.toByteString())
    }

    private fun propertiesOf(p: Int) = buildList {
        if (p and 0x02 != 0) add("read")
        if (p and 0x04 != 0) add("write-without-response")
        if (p and 0x08 != 0) add("write")
        if (p and 0x10 != 0) add("notify")
        if (p and 0x20 != 0) add("indicate")
    }

    private fun JsonObject.string(key: String) =
        this[key]?.jsonPrimitive?.contentOrNull ?: throw BleException("internal_error", "Missing argument $key")
    private fun JsonObject.boolean(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.int ?: throw BleException("internal_error", "Missing argument $key")
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.DEFAULT)

    companion object {
        private const val TAG = "BleProxy"
        const val PROTOCOL_VERSION = 1
        const val OP_WRITE_DATA = 0x01
        const val OP_NOTIFICATION = 0x02
        const val OP_READ_RESPONSE = 0x03
        private val EMPTY = JsonObject(emptyMap())
    }
}
