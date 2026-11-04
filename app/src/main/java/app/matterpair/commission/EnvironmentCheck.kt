package app.matterpair.commission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import app.matterpair.ble.BleProxyClient
import app.matterpair.ble.BleScanner
import app.matterpair.ble.ScanHit
import app.matterpair.ha.HaConnector
import app.matterpair.ha.IngressSession
import app.matterpair.ha.MatterAddon
import app.matterpair.ha.MatterServerClient
import app.matterpair.ha.str
import app.matterpair.matter.SetupPayload
import app.matterpair.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Traffic-light status of one check. */
enum class CheckStatus { PENDING, OK, WARN, FAIL }

enum class CheckId {
    BLUETOOTH, PERMISSIONS, BATTERY, NETWORK,
    HA_ADMIN, MATTER_APP, BLE_PROXY_OPTION, BLE_ENDPOINT,
    THREAD, WIFI, OTHER_PROXIES, DEVICE,
}

/** Optional one-tap fix offered next to a check. */
enum class CheckAction { ENABLE_BLUETOOTH, GRANT_PERMISSIONS, BATTERY_SETTINGS, ENABLE_BLE_PROXY, SET_WIFI, SHARE_THREAD }

data class CheckItem(
    val id: CheckId,
    val status: CheckStatus = CheckStatus.PENDING,
    /** Extra info shown below the title (versions, counts, RSSI…). Not localized: technical. */
    val detail: String? = null,
    val action: CheckAction? = null,
)

/** Devices nearby that match the payload, plus all Matter devices seen (for "wrong code?" hints). */
data class NearbyDevices(val matching: List<ScanHit> = emptyList(), val others: Int = 0)

data class EnvironmentReport(
    val items: List<CheckItem> = CheckId.entries.map { CheckItem(it) },
    val nearby: NearbyDevices = NearbyDevices(),
    val running: Boolean = false,
    val addon: MatterAddon? = null,
) {
    operator fun get(id: CheckId) = items.first { it.id == id }
    /** Blocking failures prevent the "Add" button; warnings do not. */
    val canProceed get() = items.none { it.status == CheckStatus.FAIL } && !running
    val hasWarnings get() = items.any { it.status == CheckStatus.WARN }
}

/**
 * Pre-flight check shown before each commissioning ("Ampelliste"). Runs phone-side and HA-side
 * checks in parallel and scans for the device while the user waits.
 */
class EnvironmentCheck(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connector: HaConnector,
    private val scanner: BleScanner,
) {
    private val _report = MutableStateFlow(EnvironmentReport())
    val report: StateFlow<EnvironmentReport> = _report

    fun run(payload: SetupPayload?) {
        _report.value = EnvironmentReport(running = true)
        scope.launch {
            val phone = launch { phoneChecks() }
            val device = launch { if (payload != null) scanForDevice(payload) else set(CheckId.DEVICE, CheckStatus.OK) }
            runCatching { haChecks() }.onFailure {
                AppLog.w(TAG, "HA checks failed", it)
                set(CheckId.HA_ADMIN, CheckStatus.FAIL, it.message)
                listOf(CheckId.MATTER_APP, CheckId.BLE_PROXY_OPTION, CheckId.BLE_ENDPOINT, CheckId.THREAD, CheckId.WIFI, CheckId.OTHER_PROXIES)
                    .forEach { id -> if (_report.value[id].status == CheckStatus.PENDING) set(id, CheckStatus.FAIL) }
            }
            phone.join(); device.join()
            _report.update { it.copy(running = false) }
        }
    }

    private fun phoneChecks() {
        set(CheckId.BLUETOOTH, if (scanner.isBluetoothOn) CheckStatus.OK else CheckStatus.FAIL, action = if (scanner.isBluetoothOn) null else CheckAction.ENABLE_BLUETOOTH)
        val perms = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        set(CheckId.PERMISSIONS, if (perms) CheckStatus.OK else CheckStatus.FAIL, action = if (perms) null else CheckAction.GRANT_PERMISSIONS)
        val pm = context.getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
        set(CheckId.BATTERY, if (unrestricted) CheckStatus.OK else CheckStatus.WARN, action = if (unrestricted) null else CheckAction.BATTERY_SETTINGS)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        set(CheckId.NETWORK, if (onWifi) CheckStatus.OK else CheckStatus.WARN)
    }

    private suspend fun haChecks() {
        val session = connector.session()
        val api = session.api
        val admin = api.isAdmin()
        set(CheckId.HA_ADMIN, if (admin) CheckStatus.OK else CheckStatus.FAIL, "Home Assistant ${api.ws.haVersion}")
        if (!admin) return

        val addon = api.findMatterAddon()
        _report.update { it.copy(addon = addon) }
        when {
            addon == null -> { set(CheckId.MATTER_APP, CheckStatus.FAIL); return }
            !addon.isRunning -> { set(CheckId.MATTER_APP, CheckStatus.FAIL, "${addon.version} · ${addon.state}"); return }
            addon.majorVersion < MIN_ADDON_MAJOR -> set(CheckId.MATTER_APP, CheckStatus.WARN, addon.version)
            else -> set(CheckId.MATTER_APP, CheckStatus.OK, addon.version)
        }
        if (!addon.bleProxyEnabled) {
            set(CheckId.BLE_PROXY_OPTION, CheckStatus.FAIL, action = CheckAction.ENABLE_BLE_PROXY)
            set(CheckId.BLE_ENDPOINT, CheckStatus.FAIL)
        } else {
            set(CheckId.BLE_PROXY_OPTION, CheckStatus.OK)
        }

        val ingress = IngressSession(api, session.baseUrl, addon).apply { open(scope) }
        try {
            val matter = runCatching { MatterServerClient.connect(session.http, ingress) }.getOrNull()
            if (addon.bleProxyEnabled) {
                val probe = BleProxyClient(context, session.http, scanner) { false }
                val ok = runCatching { probe.connect(ingress.webSocketRequest("ble")) }.isSuccess
                probe.close()
                set(CheckId.BLE_ENDPOINT, if (ok) CheckStatus.OK else CheckStatus.FAIL)
            }

            // Thread: preferred dataset in HA, at least one border router visible.
            val datasets = api.threadDatasets()
            val preferred = datasets.firstOrNull { it.preferred }
            val routers = if (preferred != null) api.countBorderRouters() else 0
            val threadDetail = preferred?.let { "${it.networkName} · ch ${it.channel ?: "?"} · $routers BR" }
            set(
                CheckId.THREAD,
                when {
                    preferred == null -> CheckStatus.WARN
                    routers == 0 -> CheckStatus.WARN
                    else -> CheckStatus.OK
                },
                threadDetail,
                action = if (preferred != null && matter?.info?.threadCredentialsSet == false) CheckAction.SHARE_THREAD else null,
            )

            // Wi-Fi: credentials stored on the Matter server (sent by HA, never by this app).
            val wifiSet = matter?.info?.wifiCredentialsSet
            set(CheckId.WIFI, if (wifiSet == true) CheckStatus.OK else CheckStatus.WARN, action = if (wifiSet == true) null else CheckAction.SET_WIFI)
            matter?.close()
        } finally {
            ingress.close()
        }

        // Other BLE proxy clients: HA itself connects to /ble when it has Bluetooth adapters or
        // ESPHome Bluetooth proxies; the first client that reports a device owns it.
        val btEntries = runCatching { api.configEntries("bluetooth").count { it.str("state") == "loaded" } }.getOrDefault(0)
        set(CheckId.OTHER_PROXIES, if (btEntries == 0) CheckStatus.OK else CheckStatus.WARN, if (btEntries > 0) "$btEntries" else null)
    }

    private suspend fun scanForDevice(payload: SetupPayload) {
        if (!scanner.acquire()) {
            set(CheckId.DEVICE, CheckStatus.FAIL)
            return
        }
        try {
            val found = LinkedHashMap<String, ScanHit>()
            val others = HashSet<String>()
            withTimeoutOrNull(SCAN_WINDOW_MS) {
                scanner.hits.collect { hit ->
                    if (payload.matchesDiscriminator(hit.advertisement.discriminator)) found[hit.address] = hit else others += hit.address
                    val list = found.values.sortedByDescending { it.rssi }
                    _report.update { it.copy(nearby = NearbyDevices(list, others.size)) }
                    val best = list.firstOrNull() ?: return@collect
                    set(
                        CheckId.DEVICE,
                        if (best.rssi < WEAK_RSSI) CheckStatus.WARN else CheckStatus.OK,
                        "${best.rssi} dBm",
                    )
                    // A QR code identifies the device uniquely: stop early on a good signal.
                    if (payload.longDiscriminator != null && best.rssi >= WEAK_RSSI &&
                        SystemClock.elapsedRealtime() - best.receivedAt < 2000
                    ) throw StopScan()
                }
            }
        } catch (_: StopScan) {
        } finally {
            scanner.release()
        }
        if (_report.value[CheckId.DEVICE].status == CheckStatus.PENDING) set(CheckId.DEVICE, CheckStatus.FAIL)
    }

    private class StopScan : RuntimeException()

    private fun set(id: CheckId, status: CheckStatus, detail: String? = null, action: CheckAction? = null) =
        _report.update { r -> r.copy(items = r.items.map { if (it.id == id) CheckItem(id, status, detail, action) else it }) }

    companion object {
        private const val TAG = "EnvCheck"
        const val MIN_ADDON_MAJOR = 9
        /** Below this the UI suggests moving closer. */
        const val WEAK_RSSI = -80
        private const val SCAN_WINDOW_MS = 12_000L
    }
}
