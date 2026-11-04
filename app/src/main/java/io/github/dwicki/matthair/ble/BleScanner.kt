package io.github.dwicki.matthair.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import io.github.dwicki.matthair.matter.MatterAdvertisement
import io.github.dwicki.matthair.matter.MatterBle
import io.github.dwicki.matthair.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One received advertisement of a commissionable Matter device. */
data class ScanHit(
    val address: String,
    val name: String?,
    val rssi: Int,
    val connectable: Boolean,
    val serviceData: ByteArray,
    val advertisement: MatterAdvertisement,
    /** `SystemClock.elapsedRealtime()` when received. */
    val receivedAt: Long,
)

/**
 * Shared BLE scanner for Matter advertisements (service data UUID 0xFFF6).
 *
 * Android throttles apps that start scanning more than 5 times in 30 s (scans silently return
 * nothing afterwards). The Matter server may toggle `start_scan`/`stop_scan` frequently, so this
 * class reference-counts users and keeps the radio scan running for a grace period after the
 * last user leaves, instead of really stopping and restarting it.
 */
class BleScanner(private val context: Context, private val scope: CoroutineScope) {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _hits = MutableSharedFlow<ScanHit>(extraBufferCapacity = 256)
    val hits: SharedFlow<ScanHit> = _hits

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Last error code from [ScanCallback.onScanFailed], or null. */
    val lastError = MutableStateFlow<Int?>(null)

    private var users = 0
    private var stopJob: Job? = null
    private val startTimes = ArrayDeque<Long>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            AppLog.w(TAG, "Scan failed: $errorCode")
            lastError.value = errorCode
            _scanning.value = false
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getServiceData(SERVICE) ?: return
        val adv = MatterAdvertisement.parse(data) ?: return
        _hits.tryEmit(
            ScanHit(
                address = result.device.address,
                name = result.scanRecord?.deviceName,
                rssi = result.rssi,
                connectable = result.isConnectable,
                serviceData = data,
                advertisement = adv,
                receivedAt = SystemClock.elapsedRealtime(),
            ),
        )
    }

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Register a user of the scan. Returns false if Bluetooth is unavailable. */
    @Synchronized
    fun acquire(): Boolean {
        if (!isBluetoothOn) return false
        users++
        stopJob?.cancel(); stopJob = null
        if (!_scanning.value) startRadio()
        return _scanning.value
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0 && _scanning.value) {
            stopJob?.cancel()
            stopJob = scope.launch {
                delay(GRACE_MS)
                synchronized(this@BleScanner) { if (users == 0) stopRadio() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRadio() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val now = SystemClock.elapsedRealtime()
        while (startTimes.isNotEmpty() && now - startTimes.first() > 30_000) startTimes.removeFirst()
        if (startTimes.size >= 4) {
            AppLog.w(TAG, "Avoiding Android scan throttling; delaying scan start")
            scope.launch {
                delay(30_000 - (now - startTimes.first()) + 500)
                synchronized(this@BleScanner) { if (users > 0 && !_scanning.value) startRadio() }
            }
            return
        }
        val filter = ScanFilter.Builder().setServiceData(SERVICE, ByteArray(0)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onSuccess {
                startTimes.addLast(now)
                lastError.value = null
                _scanning.value = true
                AppLog.d(TAG, "BLE scan started")
            }
            .onFailure { AppLog.e(TAG, "startScan failed", it) }
    }

    @SuppressLint("MissingPermission")
    private fun stopRadio() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
        AppLog.d(TAG, "BLE scan stopped")
    }

    private companion object {
        const val TAG = "BleScanner"
        const val GRACE_MS = 8_000L
        val SERVICE: ParcelUuid = ParcelUuid(MatterBle.SERVICE_UUID)
    }
}
