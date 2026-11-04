package app.matterpair.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.matterpair.MatterPairApp
import app.matterpair.data.AuthMethod
import app.matterpair.data.ServerConfig
import app.matterpair.ha.HaArea
import app.matterpair.ha.HaAuth
import app.matterpair.ha.HaDevice
import app.matterpair.ha.HttpClients
import app.matterpair.ha.IngressSession
import app.matterpair.ha.MatterServerClient
import app.matterpair.matter.PayloadException
import app.matterpair.matter.SetupPayload
import app.matterpair.matter.SetupPayloadParser
import app.matterpair.util.AppLog
import app.matterpair.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.net.ssl.SSLException

/** Connection state of the HA instance shown on the home screen. */
sealed interface HaStatus {
    data object Connecting : HaStatus
    data class Connected(val version: String) : HaStatus
    data class Offline(val reason: String?) : HaStatus
    /** Server configured, but no (valid) credentials: the user must sign in. */
    data object SignedOut : HaStatus
}

data class DeviceRow(val device: HaDevice, val available: Boolean?)

/** One-shot result of an async UI action, shown as snackbar or dialog. */
sealed interface UiResult {
    data class Error(val message: String) : UiResult
    data class Message(val resId: Int) : UiResult
}

/**
 * Single ViewModel for the whole app. The domain objects (commissioning session, environment
 * check) live in the process-wide [app.matterpair.AppContainer]; this class adapts them for the UI
 * and holds wizard state such as the scanned payload.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as MatterPairApp).container

    val config: StateFlow<ServerConfig?> = c.config.config
    val session = c.session.state
    val environment = c.environment.report

    private val _status = MutableStateFlow<HaStatus>(HaStatus.Connecting)
    val status: StateFlow<HaStatus> = _status.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceRow>>(emptyList())
    val devices: StateFlow<List<DeviceRow>> = _devices.asStateFlow()

    private val _areas = MutableStateFlow<List<HaArea>>(emptyList())
    val areas: StateFlow<List<HaArea>> = _areas.asStateFlow()

    /** The payload of the device currently being added. Held in memory only. */
    private val _payload = MutableStateFlow<SetupPayload?>(null)
    val payload: StateFlow<SetupPayload?> = _payload.asStateFlow()

    private val _result = MutableStateFlow<UiResult?>(null)
    val result: StateFlow<UiResult?> = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val showDetails = MutableStateFlow(c.config.showDetails)

    fun consumeResult() { _result.value = null }

    /** Decided once at start-up; see MainActivity. */
    val isSignedIn: Boolean get() = c.config.isSignedIn

    // --- Onboarding --------------------------------------------------------------------------

    /** Certificate the user must confirm before we continue (self-signed HA). */
    data class CertPrompt(val sha256: String, val pending: ServerConfig)

    private val _certPrompt = MutableStateFlow<CertPrompt?>(null)
    val certPrompt: StateFlow<CertPrompt?> = _certPrompt.asStateFlow()

    /** Problem shown inline on the server screen; null when there is none. */
    sealed interface ServerProblem {
        data class Unreachable(val tried: List<String>) : ServerProblem
    }

    private val _serverProblem = MutableStateFlow<ServerProblem?>(null)
    val serverProblem: StateFlow<ServerProblem?> = _serverProblem.asStateFlow()

    /** Set when the URL that works differs from what the user typed (shown as info). */
    private val _correctedUrl = MutableStateFlow<String?>(null)
    val correctedUrl: StateFlow<String?> = _correctedUrl.asStateFlow()

    fun clearServerProblem() { _serverProblem.value = null }

    /**
     * Validate the address before going on to sign in. If the address as typed does not answer
     * like Home Assistant, the usual variants are tried (http/https, port 8123), because
     * "https://<ip>" for a plain-HTTP HA on port 80/8123 is a very common mix-up.
     */
    fun submitServer(local: String, external: String?, onReady: () -> Unit) = viewModelScope.launch {
        _busy.value = true
        _serverProblem.value = null
        _correctedUrl.value = null
        val typed = normalizeUrl(local)
        val candidates = urlCandidates(typed)
        var found: String? = null
        var tlsPin: String? = null
        withContext(Dispatchers.IO) {
            for (url in candidates) {
                when (val r = probe(url)) {
                    is Probe.Ok -> { found = url; break }
                    is Probe.UntrustedCert -> { found = url; tlsPin = r.sha256; break }
                    Probe.NotHa -> Unit
                }
            }
        }
        _busy.value = false
        val url = found
        if (url == null) {
            _serverProblem.value = ServerProblem.Unreachable(candidates)
            return@launch
        }
        if (url != typed) _correctedUrl.value = url
        val cfg = ServerConfig(url, external?.takeIf { it.isNotBlank() }?.let(::normalizeUrl), AuthMethod.OAUTH, null)
        val pin = tlsPin
        if (pin != null) {
            _certPrompt.value = CertPrompt(pin, cfg)
        } else {
            c.config.save(cfg)
            onReady()
        }
    }

    fun trustCertificate(onReady: () -> Unit) {
        val p = _certPrompt.value ?: return
        c.config.save(p.pending.copy(pinnedCertSha256 = p.sha256))
        _certPrompt.value = null
        onReady()
    }

    fun dismissCert() { _certPrompt.value = null }

    private sealed interface Probe {
        data object Ok : Probe
        data class UntrustedCert(val sha256: String) : Probe
        data object NotHa : Probe
    }

    /** Does [url] serve Home Assistant? HA's `/manifest.json` names itself "Home Assistant". */
    private fun probe(url: String): Probe {
        val client = HttpClients.create(null).newBuilder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return try {
            client.newCall(okhttp3.Request.Builder().url("$url/manifest.json").build()).execute().use { r ->
                if (r.isSuccessful && r.body?.string().orEmpty().contains("Home Assistant")) Probe.Ok else Probe.NotHa
            }
        } catch (e: SSLException) {
            val uri = URI(url)
            HttpClients.fetchServerCertificate(uri.host, if (uri.port > 0) uri.port else 443)
                ?.let { Probe.UntrustedCert(HttpClients.sha256(it)) } ?: Probe.NotHa
        } catch (e: Exception) {
            AppLog.i("ViewModel", "Probe $url failed: ${e.javaClass.simpleName}")
            Probe.NotHa
        }
    }

    fun authorizeUrl(state: String): String = HaAuth.authorizeUrl(c.config.config.value!!.localUrl, state)

    fun completeOAuth(code: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        _busy.value = true
        val cfg = c.config.config.value!!
        val ok = runCatching {
            val http = HttpClients.create(cfg.pinnedCertSha256)
            c.config.oauthTokens = c.auth.exchangeCode(http, cfg.localUrl, code)
            c.config.save(cfg.copy(authMethod = AuthMethod.OAUTH))
            verifyLogin()
        }.onFailure { _result.value = UiResult.Error(it.message ?: "?") }.getOrDefault(false)
        _busy.value = false
        onDone(ok)
    }

    fun useToken(token: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        _busy.value = true
        val cfg = c.config.config.value!!
        c.config.longLivedToken = token.trim()
        c.config.save(cfg.copy(authMethod = AuthMethod.LONG_LIVED_TOKEN))
        val ok = runCatching { verifyLogin() }
            .onFailure { _result.value = UiResult.Error(it.message ?: "?") }.getOrDefault(false)
        _busy.value = false
        onDone(ok)
    }

    private suspend fun verifyLogin(): Boolean {
        c.connector.invalidate()
        val s = c.connector.session()
        if (!s.api.isAdmin()) {
            _result.value = UiResult.Message(app.matterpair.R.string.auth_admin_required)
            return false
        }
        refresh()
        return true
    }

    fun signOut() {
        c.connector.invalidate()
        c.config.signOut()
        _devices.value = emptyList()
    }

    // --- Home / devices ----------------------------------------------------------------------

    fun refresh() = viewModelScope.launch {
        if (c.config.config.value == null) return@launch
        _status.value = HaStatus.Connecting
        try {
            val s = c.connector.session()
            _status.value = HaStatus.Connected(s.api.ws.haVersion)
            val devices = s.api.matterDevices()
            _devices.value = devices.map { DeviceRow(it, null) }
            _areas.value = runCatching { s.api.areas() }.getOrDefault(emptyList())
            // Node availability comes from the Matter server itself.
            runCatching {
                val addon = s.api.findMatterAddon() ?: return@runCatching
                val ingress = IngressSession(s.api, s.baseUrl, addon).apply { open(viewModelScope) }
                val matter = MatterServerClient.connect(s.http, ingress)
                val nodes = matter.nodes().associateBy { it.nodeId }
                matter.close(); ingress.close()
                _devices.value = devices.map { d -> DeviceRow(d, nodeIdOf(d)?.let { nodes[it]?.available }) }
            }
        } catch (e: Exception) {
            AppLog.w("ViewModel", "Refresh failed", e)
            val code = (e as? app.matterpair.ha.HaException)?.code
            _status.value = if (code == "no_token" || code == "auth_invalid" || code == "auth_failed") HaStatus.SignedOut
            else HaStatus.Offline(e.message)
        }
    }

    /** HA Matter identifiers look like `deviceid_<fabric>-<nodeIdHex>-MatterNodeDevice`. */
    private fun nodeIdOf(d: HaDevice): Long? =
        d.matterIdentifier?.substringAfter('-', "")?.substringBefore('-')?.toULongOrNull(16)?.toLong()

    fun removeDevice(device: HaDevice) = viewModelScope.launch {
        runCatching {
            val s = c.connector.session()
            val entry = s.api.matterConfigEntryId() ?: error("Matter integration not found")
            s.api.removeDevice(device.id, entry)
        }.onFailure { _result.value = UiResult.Error(it.message ?: "?") }
        refresh()
    }

    /**
     * Rename a device and/or move it to another room. An empty name restores the name the device
     * reports itself (HA `name_by_user = null`); [newAreaName] creates the room first.
     */
    fun updateDevice(device: HaDevice, name: String, areaId: String?, newAreaName: String?) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val api = c.connector.session().api
            val area = if (!newAreaName.isNullOrBlank()) api.createArea(newAreaName.trim()).id else areaId
            val custom = name.trim().takeIf { it.isNotEmpty() && it != device.name }
            api.updateDevice(device.id, custom, area)
        }.onSuccess { _result.value = UiResult.Message(app.matterpair.R.string.devices_saved) }
            .onFailure { _result.value = UiResult.Error(it.message ?: "?") }
        _busy.value = false
        refresh()
    }

    /** Data for the device overview screen; diagnostics may fail for offline nodes. */
    data class DeviceDetail(val diagnostics: app.matterpair.ha.NodeDiagnostics?, val error: String?)

    private val _detail = MutableStateFlow<DeviceDetail?>(null)
    val detail: StateFlow<DeviceDetail?> = _detail.asStateFlow()

    fun loadDetail(deviceId: String) = viewModelScope.launch {
        _detail.value = null
        _detail.value = runCatching { c.connector.session().api.nodeDiagnostics(deviceId) }
            .fold({ DeviceDetail(it, null) }, { DeviceDetail(null, it.message) })
    }

    /** URL of the device page in the HA web UI. */
    fun haDeviceUrl(deviceId: String): String? = c.config.config.value?.localUrl?.let { "$it/config/devices/device/$deviceId" }

    /** Opens a commissioning window so another ecosystem can add the device (multi-admin). */
    fun shareDevice(device: HaDevice, onCodes: (manual: String, qr: String) -> Unit) = viewModelScope.launch {
        _busy.value = true
        runCatching { c.connector.session().api.openCommissioningWindow(device.id) }
            .onSuccess { (manual, qr) -> onCodes(manual, qr) }
            .onFailure { _result.value = UiResult.Error(it.message ?: "?") }
        _busy.value = false
    }

    // --- Add-device wizard -------------------------------------------------------------------

    /** Parse a scanned/typed code. Returns the error for inline display, or null on success. */
    fun acceptCode(raw: String): PayloadException? = try {
        _payload.value = SetupPayloadParser.parse(raw)
        null
    } catch (e: PayloadException) {
        e
    }

    fun clearPayload() { _payload.value = null }

    fun runCheck() = c.environment.run(_payload.value)

    fun startCommissioning() {
        val p = _payload.value ?: return
        c.session.start(p)
    }

    fun cancelCommissioning() = c.session.cancel()

    fun finishWizard() {
        c.session.reset()
        _payload.value = null // forget the passcode
        refresh()
    }

    fun saveNewDevice(deviceId: String, name: String?, areaId: String?, newAreaName: String?, onDone: () -> Unit) = viewModelScope.launch {
        _busy.value = true
        runCatching {
            val api = c.connector.session().api
            val area = if (!newAreaName.isNullOrBlank()) api.createArea(newAreaName.trim()).id else areaId
            api.updateDevice(deviceId, name?.trim()?.takeIf { it.isNotEmpty() }, area)
        }.onFailure { _result.value = UiResult.Error(it.message ?: "?") }
        _busy.value = false
        finishWizard()
        onDone()
    }

    /** Multi-admin: add a device that is already in another ecosystem, network only. */
    fun commissionOnNetwork(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val p = _payload.value ?: return@launch
        _busy.value = true
        AppLog.addSecret(p.rawCode)
        val ok = runCatching { c.connector.session().api.commission(p.rawCode, networkOnly = true) }
            .onFailure { _result.value = UiResult.Error(it.message ?: "?") }.isSuccess
        AppLog.removeSecret(p.rawCode)
        _payload.value = null
        _busy.value = false
        refresh()
        onDone(ok)
    }

    // --- Fix-it actions from the check list ---------------------------------------------------

    fun enableBleProxy() = viewModelScope.launch {
        val addon = environment.value.addon ?: return@launch
        _busy.value = true
        runCatching { c.connector.session().api.enableBleProxy(addon) }
            .onFailure { _result.value = UiResult.Error(it.message ?: "?") }
        _busy.value = false
        runCheck()
    }

    fun setWifi(ssid: String, password: String) = viewModelScope.launch {
        runCatching { c.connector.session().api.setWifiCredentials(ssid, password) }
            .onFailure { _result.value = UiResult.Error(it.message ?: "?") }
        runCheck()
    }

    fun shareThreadDataset() = viewModelScope.launch {
        runCatching {
            val api = c.connector.session().api
            val ds = api.threadDatasets().first { it.preferred }
            api.setThreadDataset(api.threadDatasetTlv(ds.id))
        }.onSuccess { _result.value = UiResult.Message(app.matterpair.R.string.thread_shared) }
            .onFailure { _result.value = UiResult.Error(it.message ?: "?") }
        runCheck()
    }

    // --- Settings ------------------------------------------------------------------------------

    fun setShowDetails(v: Boolean) { c.config.showDetails = v; showDetails.value = v }

    fun permitZigbee(z2m: Boolean) = viewModelScope.launch {
        runCatching {
            val api = c.connector.session().api
            if (z2m) api.z2mPermitJoin(240) else api.zhaPermitJoin(240)
        }.onSuccess { _result.value = UiResult.Message(app.matterpair.R.string.settings_zigbee_started) }
            .onFailure { _result.value = UiResult.Error(it.message ?: "?") }
    }

    fun buildDiagnostics(onReady: (File) -> Unit) = viewModelScope.launch {
        _busy.value = true
        val f = runCatching { Diagnostics.buildBundle(getApplication(), c.connector) }.getOrNull()
        _busy.value = false
        f?.let(onReady)
    }

    companion object {
        /** The typed URL first, then http/https × (default port, 8123) variants. */
        fun urlCandidates(typed: String): List<String> {
            val uri = URI(typed)
            val host = uri.host ?: return listOf(typed)
            val hostPart = if (host.contains(':')) "[$host]" else host
            val path = (uri.rawPath ?: "").trimEnd('/')
            val variants = buildList {
                add(typed)
                if (uri.port == -1) {
                    for (scheme in listOf("http", "https")) {
                        add("$scheme://$hostPart:8123$path")
                        add("$scheme://$hostPart$path")
                    }
                } else {
                    val other = if (uri.scheme.equals("https", true)) "http" else "https"
                    add("$other://$hostPart:${uri.port}$path")
                }
            }
            return variants.distinct()
        }

        fun normalizeUrl(input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return s
        }

        fun isValidUrl(input: String): Boolean = runCatching {
            val u = URI(normalizeUrl(input))
            !u.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
