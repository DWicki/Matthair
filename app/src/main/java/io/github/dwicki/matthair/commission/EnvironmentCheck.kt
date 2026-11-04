package io.github.dwicki.matthair.commission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.SystemClock
import io.github.dwicki.matthair.ble.BleProxyClient
import io.github.dwicki.matthair.ble.BleScanner
import io.github.dwicki.matthair.ble.ScanHit
import io.github.dwicki.matthair.ha.HaConnector
import io.github.dwicki.matthair.ha.HaSession
import io.github.dwicki.matthair.ha.IngressSession
import io.github.dwicki.matthair.ha.MatterAddon
import io.github.dwicki.matthair.ha.MatterServerClient
import io.github.dwicki.matthair.ha.str
import io.github.dwicki.matthair.matter.SetupPayload
import io.github.dwicki.matthair.util.AppLog
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
 * The traffic-light check shown before each commissioning. Runs the phone and HA checks in
 * parallel and scans for the device while the user waits.
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
            phone.join()
            device.join()
            _report.update { it.copy(running = false) }
        }
    }

    private fun phoneChecks() {
        val bluetoothOn = scanner.isBluetoothOn
        set(CheckId.BLUETOOTH, okOr(bluetoothOn, CheckStatus.FAIL), action = CheckAction.ENABLE_BLUETOOTH.unless(bluetoothOn))

        val permitted = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        set(CheckId.PERMISSIONS, okOr(permitted, CheckStatus.FAIL), action = CheckAction.GRANT_PERMISSIONS.unless(permitted))

        val unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        set(CheckId.BATTERY, okOr(unrestricted, CheckStatus.WARN), action = CheckAction.BATTERY_SETTINGS.unless(unrestricted))

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val onLan = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        set(CheckId.NETWORK, okOr(onLan, CheckStatus.WARN))
    }

    private suspend fun haChecks() {
        val session = connector.session()
        val api = session.api
        val admin = api.isAdmin()
        set(CheckId.HA_ADMIN, okOr(admin, CheckStatus.FAIL), "Home Assistant ${api.ws.haVersion}")
        if (!admin) return

        val addon = checkMatterApp(session) ?: return
        val ingress = IngressSession(api, session.baseUrl, addon).apply { open(scope) }
        try {
            val serverInfo = runCatching { MatterServerClient.connect(session.http, ingress) }.getOrNull()
                ?.also { it.close() }?.info
            if (addon.bleProxyEnabled) checkBleEndpoint(session, ingress)
            checkThread(session, threadOnServer = serverInfo?.threadCredentialsSet)
            // Wi-Fi credentials are stored on the Matter Server by HA, never by this app.
            val wifiSet = serverInfo?.wifiCredentialsSet == true
            set(CheckId.WIFI, okOr(wifiSet, CheckStatus.WARN), action = CheckAction.SET_WIFI.unless(wifiSet))
        } finally {
            ingress.close()
        }
        checkOtherProxies(session)
    }

    /** Returns the app only if it is installed and running, so the remaining checks can use it. */
    private suspend fun checkMatterApp(session: HaSession): MatterAddon? {
        val addon = session.api.findMatterAddon()
        _report.update { it.copy(addon = addon) }
        when {
            addon == null -> return null.also { set(CheckId.MATTER_APP, CheckStatus.FAIL) }
            !addon.isRunning -> return null.also { set(CheckId.MATTER_APP, CheckStatus.FAIL, "${addon.version} · ${addon.state}") }
            addon.majorVersion < MIN_ADDON_MAJOR -> set(CheckId.MATTER_APP, CheckStatus.WARN, addon.version)
            else -> set(CheckId.MATTER_APP, CheckStatus.OK, addon.version)
        }
        if (addon.bleProxyEnabled) {
            set(CheckId.BLE_PROXY_OPTION, CheckStatus.OK)
        } else {
            set(CheckId.BLE_PROXY_OPTION, CheckStatus.FAIL, action = CheckAction.ENABLE_BLE_PROXY)
            set(CheckId.BLE_ENDPOINT, CheckStatus.FAIL)
        }
        return addon
    }

    /** A real `hello` handshake on `/ble`, with a filter that reports no devices. */
    private suspend fun checkBleEndpoint(session: HaSession, ingress: IngressSession) {
        val probe = BleProxyClient(context, session.http, scanner) { false }
        val reachable = runCatching { probe.connect(ingress.webSocketRequest("ble")) }.isSuccess
        probe.close()
        set(CheckId.BLE_ENDPOINT, okOr(reachable, CheckStatus.FAIL))
    }

    /** Thread needs a preferred network in HA and at least one border router. */
    private suspend fun checkThread(session: HaSession, threadOnServer: Boolean?) {
        val preferred = session.api.threadDatasets().firstOrNull { it.preferred }
        val routers = if (preferred != null) session.api.countBorderRouters() else 0
        set(
            CheckId.THREAD,
            okOr(preferred != null && routers > 0, CheckStatus.WARN),
            preferred?.let { "${it.networkName} · ch ${it.channel ?: "?"} · $routers BR" },
            action = CheckAction.SHARE_THREAD.takeIf { preferred != null && threadOnServer == false },
        )
    }

    /**
     * HA itself connects to `/ble` when it has Bluetooth adapters or ESPHome Bluetooth proxies,
     * and the first proxy that reports a device owns it.
     */
    private suspend fun checkOtherProxies(session: HaSession) {
        val loaded = runCatching { session.api.configEntries("bluetooth").count { it.str("state") == "loaded" } }.getOrDefault(0)
        set(CheckId.OTHER_PROXIES, okOr(loaded == 0, CheckStatus.WARN), loaded.takeIf { it > 0 }?.toString())
    }

    private fun okOr(ok: Boolean, otherwise: CheckStatus) = if (ok) CheckStatus.OK else otherwise

    private fun CheckAction.unless(ok: Boolean) = takeUnless { ok }

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
