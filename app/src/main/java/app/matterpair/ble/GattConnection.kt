package app.matterpair.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import app.matterpair.matter.MatterBle
import app.matterpair.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** BLE failure carrying a proxy-protocol error code (see `docs/ble-proxy-protocol.md`). */
class BleException(val code: String, message: String, val gattStatus: Int? = null) : Exception(message)

/**
 * One GATT client connection with strictly sequential operations.
 *
 * Android's GATT stack allows only one outstanding operation per connection; issuing a second one
 * before the first callback silently drops it. Every public suspend function therefore takes
 * [opLock] and waits for the matching callback before returning.
 *
 * Connection tuning for BTP throughput: MTU 517 is requested and connection priority HIGH set.
 */
@SuppressLint("MissingPermission")
class GattConnection(
    private val context: Context,
    val address: String,
    private val onNotification: (UUID, ByteArray) -> Unit,
    private val onUnexpectedDisconnect: (Int) -> Unit,
) {
    private val opLock = Mutex()
    private var gatt: BluetoothGatt? = null
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Int>? = null
    @Volatile private var closingByUs = false
    @Volatile var mtu: Int = 23; private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            AppLog.d(TAG, "state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectWaiter?.complete(status)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val waiter = connectWaiter
                if (waiter != null && !waiter.isCompleted) {
                    waiter.completeExceptionally(BleException("connection_failed", "GATT connect failed (status $status)", status))
                } else if (!closingByUs) {
                    pending?.completeExceptionally(BleException("not_connected", "Disconnected (status $status)", status))
                    onUnexpectedDisconnect(status)
                }
                pending?.completeExceptionally(BleException("not_connected", "Disconnected"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
            complete(status, "discovery_failed") { Unit }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            complete(status, "mtu_request_failed") { newMtu }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            complete(status, "read_failed") { value }

        @Deprecated("Pre-API 33 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                complete(status, "read_failed") { c.value ?: ByteArray(0) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            complete(status, "write_failed") { Unit }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            complete(status, "subscribe_failed") { Unit }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onNotification(c.uuid, value)

        @Deprecated("Pre-API 33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onNotification(c.uuid, c.value ?: ByteArray(0))
            }
        }
    }

    private fun complete(status: Int, errorCode: String, value: () -> Any?) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value())
        else p.completeExceptionally(BleException(errorCode, "GATT status $status", status))
    }

    /**
     * Connect with automatic retries for the infamous GATT error 133 (and other transient stack
     * errors), with a short pause in between. Afterwards MTU and connection priority are set.
     */
    suspend fun connect(timeoutMs: Long, attempts: Int = 3): Int = opLock.withLock {
        val device: BluetoothDevice = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        var lastError: Exception? = null
        val deadline = System.currentTimeMillis() + timeoutMs
        for (attempt in 1..attempts) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val waiter = CompletableDeferred<Int>()
            connectWaiter = waiter
            closingByUs = false
            val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt = g
            try {
                withTimeout(remaining) { waiter.await() }
                connectWaiter = null
                // Best effort tuning; failures here are not fatal.
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                runCatching { runOp { g.requestMtu(517) } }
                    .onFailure { AppLog.w(TAG, "MTU request failed, using $mtu") }
                AppLog.i(TAG, "Connected (attempt $attempt), mtu=$mtu")
                return@withLock mtu
            } catch (e: Exception) {
                lastError = e
                connectWaiter = null
                closingByUs = true
                g.close()
                gatt = null
                val status = (e as? BleException)?.gattStatus
                AppLog.w(TAG, "Connect attempt $attempt failed (status=$status)")
                if (attempt < attempts) delay(1000L * attempt)
            }
        }
        throw when (lastError) {
            is BleException -> lastError
            is kotlinx.coroutines.TimeoutCancellationException -> BleException("timeout", "Connection timed out")
            else -> BleException("connection_failed", lastError?.message ?: "Connection failed")
        }
    }

    suspend fun discoverServices(): List<UUID> = opLock.withLock {
        val g = requireGatt()
        runOp { g.discoverServices() }
        g.services.map { it.uuid }
    }

    fun characteristicsOf(service: UUID): List<BluetoothGattCharacteristic> {
        val s = requireGatt().getService(service) ?: throw BleException("service_not_found", "Service $service not found")
        return s.characteristics
    }

    suspend fun read(uuid: UUID): ByteArray = opLock.withLock {
        val g = requireGatt()
        val c = findCharacteristic(uuid)
        runOp { g.readCharacteristic(c) } as ByteArray
    }

    suspend fun write(uuid: UUID, value: ByteArray, withResponse: Boolean) = opLock.withLock { writeLocked(uuid, value, withResponse) }

    suspend fun subscribe(uuid: UUID, enable: Boolean = true) = opLock.withLock { subscribeLocked(uuid, enable) }

    /** Write, wait for the write response, then enable notifications — under one lock. */
    suspend fun writeAndSubscribe(writeUuid: UUID, value: ByteArray, withResponse: Boolean, subscribeUuid: UUID) = opLock.withLock {
        writeLocked(writeUuid, value, withResponse)
        subscribeLocked(subscribeUuid, true)
    }

    suspend fun requestMtu(value: Int): Int = opLock.withLock {
        val g = requireGatt()
        runOp { g.requestMtu(value) } as Int
    }

    private suspend fun writeLocked(uuid: UUID, value: ByteArray, withResponse: Boolean) {
        val g = requireGatt()
        val c = findCharacteristic(uuid)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        runOp {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    private suspend fun subscribeLocked(uuid: UUID, enable: Boolean) {
        val g = requireGatt()
        val c = findCharacteristic(uuid)
        val props = c.properties
        val canIndicate = props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        if (enable && !canIndicate && !canNotify) throw BleException("notify_not_supported", "No notify/indicate on $uuid")
        if (!g.setCharacteristicNotification(c, enable)) throw BleException("subscribe_failed", "setCharacteristicNotification failed")
        val cccd = c.getDescriptor(MatterBle.CCCD_UUID) ?: throw BleException("subscribe_failed", "No CCCD on $uuid")
        val value = when {
            !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            canIndicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        runOp {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    /** Start one GATT op and wait for its callback. [start] returns false if Android refused it. */
    private suspend fun runOp(timeoutMs: Long = 10_000, start: () -> Boolean): Any? {
        val p = CompletableDeferred<Any?>()
        pending = p
        try {
            // Android may refuse an op right after the previous callback while it is still
            // busy internally; retry a few times before giving up.
            var started = start()
            var tries = 0
            while (!started && tries < 5) {
                delay(30)
                started = start()
                tries++
            }
            if (!started) throw BleException("internal_error", "GATT operation could not be started")
            return withTimeout(timeoutMs) { p.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw BleException("timeout", "GATT operation timed out")
        } finally {
            pending = null
        }
    }

    private fun requireGatt() = gatt ?: throw BleException("not_connected", "Not connected")

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic =
        requireGatt().services.asSequence().mapNotNull { it.getCharacteristic(uuid) }.firstOrNull()
            ?: throw BleException("characteristic_not_found", "Characteristic $uuid not found")

    /** Disconnect and free the GATT client. Safe to call multiple times. */
    suspend fun disconnect() {
        val g = gatt ?: return
        closingByUs = true
        runCatching { g.disconnect() }
        delay(150) // let the stack send LL_TERMINATE before closing
        runCatching { g.close() }
        gatt = null
        pending?.completeExceptionally(BleException("not_connected", "Disconnected"))
        AppLog.i(TAG, "Disconnected")
    }

    private companion object { const val TAG = "Gatt" }
}
