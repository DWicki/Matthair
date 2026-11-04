package io.github.dwicki.matthair.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dwicki.matthair.MatthairApp
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.data.AuthMethod
import io.github.dwicki.matthair.data.ServerConfig
import io.github.dwicki.matthair.ha.FabricInfo
import io.github.dwicki.matthair.ha.HaApi
import io.github.dwicki.matthair.ha.HaArea
import io.github.dwicki.matthair.ha.HaAuth
import io.github.dwicki.matthair.ha.HaDevice
import io.github.dwicki.matthair.ha.HaException
import io.github.dwicki.matthair.ha.HaSession
import io.github.dwicki.matthair.ha.HttpClients
import io.github.dwicki.matthair.ha.IngressSession
import io.github.dwicki.matthair.ha.MatterServerClient
import io.github.dwicki.matthair.ha.NodeDiagnostics
import io.github.dwicki.matthair.ha.ServerProbe
import io.github.dwicki.matthair.matter.PayloadException
import io.github.dwicki.matthair.matter.SetupPayload
import io.github.dwicki.matthair.matter.SetupPayloadParser
import io.github.dwicki.matthair.util.AppLog
import io.github.dwicki.matthair.util.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Connection state of the HA instance shown on the home screen. */
sealed interface HaStatus {
    data object Connecting : HaStatus
    data class Connected(val version: String) : HaStatus
    data class Offline(val reason: String?) : HaStatus
    /** A server is configured, but there are no valid credentials. */
    data object SignedOut : HaStatus
}

/** A Matter device plus its availability; null while still unknown. */
data class DeviceRow(val device: HaDevice, val available: Boolean?)

/** One-shot result of a UI action, shown as a snackbar. */
sealed interface UiResult {
    data class Error(val message: String) : UiResult
    data class Message(@StringRes val resId: Int) : UiResult
}

/**
 * The app's only ViewModel. The long-lived domain objects (commissioning session, environment
 * check, HA connection) live in [io.github.dwicki.matthair.AppContainer]; this class adapts them
 * for the UI and holds the state of the add-device wizard.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as MatthairApp).container

    val config: StateFlow<ServerConfig?> = container.config.config
    val session = container.session.state
    val environment = container.environment.report
    val showDetails = MutableStateFlow(container.config.showDetails)

    /** Decided once at start-up, see MainActivity. */
    val isSignedIn: Boolean get() = container.config.isSignedIn

    private val _status = MutableStateFlow<HaStatus>(HaStatus.Connecting)
    val status: StateFlow<HaStatus> = _status.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceRow>>(emptyList())
    val devices: StateFlow<List<DeviceRow>> = _devices.asStateFlow()

    private val _areas = MutableStateFlow<List<HaArea>>(emptyList())
    val areas: StateFlow<List<HaArea>> = _areas.asStateFlow()

    /** The code of the device being added. Kept in memory only, never persisted. */
    private val _payload = MutableStateFlow<SetupPayload?>(null)
    val payload: StateFlow<SetupPayload?> = _payload.asStateFlow()

    private val _result = MutableStateFlow<UiResult?>(null)
    val result: StateFlow<UiResult?> = _result.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun consumeResult() { _result.value = null }

    /**
     * Runs [block] in the background with the busy indicator on. Failures become an error
     * snackbar, success optionally shows [successMessage]. Returns whether it succeeded.
     */
    private suspend fun <T> action(@StringRes successMessage: Int? = null, block: suspend () -> T): Result<T> {
        _busy.value = true
        val result = runCatching { block() }
            .onSuccess { if (successMessage != null) _result.value = UiResult.Message(successMessage) }
            .onFailure { _result.value = UiResult.Error(it.message ?: it.javaClass.simpleName) }
        _busy.value = false
        return result
    }

    private suspend fun api(): HaApi = container.connector.session().api

    // --- Onboarding -------------------------------------------------------------------------------

    /** A self-signed certificate the user has to confirm before we continue. */
    data class CertPrompt(val sha256: String, val pending: ServerConfig)

    private val _certPrompt = MutableStateFlow<CertPrompt?>(null)
    val certPrompt: StateFlow<CertPrompt?> = _certPrompt.asStateFlow()

    /** Shown on the server screen when no address variant answered like Home Assistant. */
    data class ServerProblem(val tried: List<String>)

    private val _serverProblem = MutableStateFlow<ServerProblem?>(null)
    val serverProblem: StateFlow<ServerProblem?> = _serverProblem.asStateFlow()

    /** The working URL, when it differs from what the user typed. */
    private val _correctedUrl = MutableStateFlow<String?>(null)
    val correctedUrl: StateFlow<String?> = _correctedUrl.asStateFlow()

    fun clearServerProblem() { _serverProblem.value = null }

    fun submitServer(local: String, external: String?, onReady: () -> Unit) = viewModelScope.launch {
        _serverProblem.value = null
        _correctedUrl.value = null
        _busy.value = true
        val found = ServerProbe.find(local)
        _busy.value = false

        when (found) {
            is ServerProbe.Result.NotFound -> _serverProblem.value = ServerProblem(found.tried)
            is ServerProbe.Result.Found -> {
                if (found.url != ServerProbe.normalizeUrl(local)) _correctedUrl.value = found.url
                val externalUrl = external?.takeIf { it.isNotBlank() }?.let(ServerProbe::normalizeUrl)
                val config = ServerConfig(found.url, externalUrl, AuthMethod.OAUTH, null)
                val pin = found.untrustedCertSha256
                if (pin != null) {
                    _certPrompt.value = CertPrompt(pin, config)
                } else {
                    container.config.save(config)
                    onReady()
                }
            }
        }
    }

    fun trustCertificate(onReady: () -> Unit) {
        val prompt = _certPrompt.value ?: return
        container.config.save(prompt.pending.copy(pinnedCertSha256 = prompt.sha256))
        _certPrompt.value = null
        onReady()
    }

    fun dismissCert() { _certPrompt.value = null }

    fun authorizeUrl(state: String): String = HaAuth.authorizeUrl(container.config.config.value!!.localUrl, state)

    fun completeOAuth(code: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val config = container.config.config.value!!
        val signedIn = action {
            val http = HttpClients.create(config.pinnedCertSha256)
            container.config.oauthTokens = container.auth.exchangeCode(http, config.localUrl, code)
            container.config.save(config.copy(authMethod = AuthMethod.OAUTH))
            verifyLogin()
        }
        onDone(signedIn.getOrDefault(false))
    }

    fun useToken(token: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val config = container.config.config.value!!
        container.config.longLivedToken = token.trim()
        container.config.save(config.copy(authMethod = AuthMethod.LONG_LIVED_TOKEN))
        onDone(action { verifyLogin() }.getOrDefault(false))
    }

    /** Only administrators may commission devices, so everyone else is stopped right here. */
    private suspend fun verifyLogin(): Boolean {
        container.connector.invalidate()
        if (!api().isAdmin()) {
            _result.value = UiResult.Message(R.string.auth_admin_required)
            return false
        }
        refresh()
        return true
    }

    fun signOut() {
        container.connector.invalidate()
        container.config.signOut()
        _devices.value = emptyList()
    }

    // --- Devices ------------------------------------------------------------------------------------

    fun refresh() = viewModelScope.launch {
        if (container.config.config.value == null) return@launch
        _status.value = HaStatus.Connecting
        try {
            val session = container.connector.session()
            _status.value = HaStatus.Connected(session.api.ws.haVersion)
            val devices = session.api.matterDevices()
            _devices.value = devices.map { DeviceRow(it, null) }
            _areas.value = runCatching { session.api.areas() }.getOrDefault(emptyList())
            loadAvailability(session, devices)
        } catch (e: Exception) {
            AppLog.w(TAG, "Refresh failed", e)
            _status.value = if (isAuthError(e)) HaStatus.SignedOut else HaStatus.Offline(e.message)
        }
    }

    /** Whether a node is online is only known to the Matter Server itself. */
    private suspend fun loadAvailability(session: HaSession, devices: List<HaDevice>) = runCatching {
        val addon = session.api.findMatterAddon() ?: return@runCatching
        val ingress = IngressSession(session.api, session.baseUrl, addon).apply { open(viewModelScope) }
        val matter = MatterServerClient.connect(session.http, ingress)
        val nodes = matter.nodes().associateBy { it.nodeId }
        matter.close()
        ingress.close()
        _devices.value = devices.map { DeviceRow(it, it.matterNodeId?.let { id -> nodes[id]?.available }) }
    }

    private fun isAuthError(e: Exception) = (e as? HaException)?.code in setOf("no_token", "auth_invalid", "auth_failed")

    fun removeDevice(device: HaDevice) = viewModelScope.launch {
        action {
            val entry = api().matterConfigEntryId() ?: error("Matter integration not found")
            api().removeDevice(device.id, entry)
        }
        refresh()
    }

    /**
     * Rename a device and/or move it to another room. An empty name restores the device's own
     * name; a [newAreaName] creates that room first.
     */
    fun updateDevice(device: HaDevice, name: String, areaId: String?, newAreaName: String?) = viewModelScope.launch {
        action(R.string.devices_saved) {
            val customName = name.trim().takeIf { it.isNotEmpty() && it != device.name }
            api().updateDevice(device.id, customName, resolveArea(areaId, newAreaName))
        }
        refresh()
    }

    private suspend fun resolveArea(areaId: String?, newAreaName: String?): String? =
        if (newAreaName.isNullOrBlank()) areaId else api().createArea(newAreaName.trim()).id

    /** Data for the device screen. Diagnostics can fail for offline nodes, hence the error. */
    data class DeviceDetail(val diagnostics: NodeDiagnostics?, val error: String?)

    private val _detail = MutableStateFlow<DeviceDetail?>(null)
    val detail: StateFlow<DeviceDetail?> = _detail.asStateFlow()

    fun loadDetail(deviceId: String) = viewModelScope.launch {
        _detail.value = null
        _detail.value = runCatching { api().nodeDiagnostics(deviceId) }
            .fold(onSuccess = { DeviceDetail(it, null) }, onFailure = { DeviceDetail(null, it.message) })
    }

    /** Removes a foreign ecosystem from a device. HA's own fabric is never removed. */
    fun removeFabric(device: HaDevice, fabric: FabricInfo) = viewModelScope.launch {
        val ownIndex = _detail.value?.diagnostics?.ownFabricIndex
        if (ownIndex == null || fabric.fabricIndex == ownIndex) return@launch
        action(R.string.fabric_removed) {
            api().removeFabric(device.id, fabric.fabricIndex)
            AppLog.i(TAG, "Removed fabric ${fabric.fabricIndex} (${fabric.displayName}) from ${device.id}")
        }
        loadDetail(device.id)
    }

    fun haDeviceUrl(deviceId: String): String? = container.config.config.value?.localUrl?.let { "$it/config/devices/device/$deviceId" }

    /** Opens a commissioning window so another ecosystem can add the device too (multi-admin). */
    fun shareDevice(device: HaDevice, onCodes: (manual: String, qr: String) -> Unit) = viewModelScope.launch {
        action { api().openCommissioningWindow(device.id) }.onSuccess { (manual, qr) -> onCodes(manual, qr) }
    }

    // --- Add-device wizard ------------------------------------------------------------------------

    /** Parses a scanned or typed code. Returns the error to show inline, or null on success. */
    fun acceptCode(raw: String): PayloadException? = try {
        _payload.value = SetupPayloadParser.parse(raw)
        null
    } catch (e: PayloadException) {
        e
    }

    fun clearPayload() { _payload.value = null }

    fun runCheck() = container.environment.run(_payload.value)

    fun startCommissioning() {
        _payload.value?.let(container.session::start)
    }

    fun cancelCommissioning() = container.session.cancel()

    fun finishWizard() {
        container.session.reset()
        _payload.value = null // forget the passcode
        refresh()
    }

    fun saveNewDevice(deviceId: String, name: String?, areaId: String?, newAreaName: String?, onDone: () -> Unit) = viewModelScope.launch {
        action { api().updateDevice(deviceId, name?.trim()?.takeIf { it.isNotEmpty() }, resolveArea(areaId, newAreaName)) }
        finishWizard()
        onDone()
    }

    /** Multi-admin: adds a device that is already in another ecosystem, over the network only. */
    fun commissionOnNetwork(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val payload = _payload.value ?: return@launch
        AppLog.addSecret(payload.rawCode)
        val result = action { api().commission(payload.rawCode, networkOnly = true) }
        AppLog.removeSecret(payload.rawCode)
        _payload.value = null
        refresh()
        onDone(result.isSuccess)
    }

    // --- Fix-it actions of the environment check ----------------------------------------------------

    fun enableBleProxy() = viewModelScope.launch {
        val addon = environment.value.addon ?: return@launch
        action { api().enableBleProxy(addon) }
        runCheck()
    }

    fun setWifi(ssid: String, password: String) = viewModelScope.launch {
        action { api().setWifiCredentials(ssid, password) }
        runCheck()
    }

    fun shareThreadDataset() = viewModelScope.launch {
        action(R.string.thread_shared) {
            val preferred = api().threadDatasets().first { it.preferred }
            api().setThreadDataset(api().threadDatasetTlv(preferred.id))
        }
        runCheck()
    }

    // --- Settings ---------------------------------------------------------------------------------

    fun setShowDetails(enabled: Boolean) {
        container.config.showDetails = enabled
        showDetails.value = enabled
    }

    fun permitZigbee(zigbee2mqtt: Boolean) = viewModelScope.launch {
        action(R.string.settings_zigbee_started) {
            if (zigbee2mqtt) api().z2mPermitJoin(PERMIT_JOIN_SECONDS) else api().zhaPermitJoin(PERMIT_JOIN_SECONDS)
        }
    }

    fun buildDiagnostics(onReady: (File) -> Unit) = viewModelScope.launch {
        action { Diagnostics.buildBundle(getApplication(), container.connector) }.onSuccess(onReady)
    }

    private companion object {
        const val TAG = "ViewModel"
        const val PERMIT_JOIN_SECONDS = 240
    }
}
