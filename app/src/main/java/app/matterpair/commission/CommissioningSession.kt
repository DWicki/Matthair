package app.matterpair.commission

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.matterpair.ble.BleProxyClient
import app.matterpair.ble.BleScanner
import app.matterpair.ha.HaConnector
import app.matterpair.ha.HaDevice
import app.matterpair.ha.HaException
import app.matterpair.ha.HaSession
import app.matterpair.ha.IngressSession
import app.matterpair.ha.MatterServerClient
import app.matterpair.matter.BtpObserver
import app.matterpair.matter.SetupPayload
import app.matterpair.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** User-visible commissioning phases, in order. */
enum class Phase { PREPARING, SEARCHING, BLE_CONNECTED, PASE, ATTESTATION, NETWORK, CASE, DONE }

/** Classified failure; each maps to a help text and suggested actions in the UI. */
enum class FailureKind {
    NOT_ADMIN,
    NO_MATTER_APP,
    PROXY_UNAVAILABLE,
    DEVICE_NOT_FOUND,
    OTHER_PROXY,
    BLE_ERROR,
    PASE_FAILED,
    ATTESTATION_FAILED,
    NETWORK_FAILED,
    HA_ERROR,
}

data class CommissioningState(
    val running: Boolean = false,
    val phase: Phase = Phase.PREPARING,
    val attempt: Int = 1,
    val maxAttempts: Int = MAX_BLE_ATTEMPTS,
    val failure: FailureKind? = null,
    val failureDetail: String? = null,
    /** Device seen by the phone during this attempt (RSSI, for the "move closer" hint). */
    val lastRssi: Int? = null,
    val warnOtherProxy: Boolean = false,
    val warnCellular: Boolean = false,
    val waitingForPreviousAttempt: Boolean = false,
    val newDevice: HaDevice? = null,
    val startedAtMillis: Long = 0,
    val finishedAtMillis: Long = 0,
) {
    val isSuccess get() = phase == Phase.DONE && failure == null
}

const val MAX_BLE_ATTEMPTS = 3

/**
 * Orchestrates one "add device" run:
 *
 *  1. Connect to HA, locate the Matter Server app, open an ingress session.
 *  2. Make sure the server has the preferred Thread dataset (HA → server; nothing from the phone).
 *  3. Connect the phone to `/ble` as BLE proxy client.
 *  4. Call `matter/commission` with `network_only = false`. The server now drives the phone's
 *     radio through the proxy protocol; we observe the traffic to show progress.
 *  5. On BLE-level errors before PASE: retry (up to [MAX_BLE_ATTEMPTS]) with a pause.
 *     After PASE: first check whether a node appeared (it may already be commissioned) instead of
 *     blindly retrying.
 *  6. Find the new HA device so the user can name it and assign an area.
 *
 * The HA command cannot be cancelled server-side. If the user cancels, the BLE side is torn down
 * immediately (the server's attempt then fails quickly) and the next run waits until the server
 * reported the end of the previous attempt, so the device's fail-safe is not hit twice.
 */
class CommissioningSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val connector: HaConnector,
    private val scanner: BleScanner,
) {
    private val _state = MutableStateFlow(CommissioningState())
    val state: StateFlow<CommissioningState> = _state.asStateFlow()

    private var job: Job? = null
    private var proxy: BleProxyClient? = null
    /** HA `matter/commission` call of the previous run that may still be running server-side. */
    private var danglingCall: Deferred<*>? = null

    fun start(payload: SetupPayload) {
        if (job?.isActive == true) return
        AppLog.addSecret(payload.rawCode)
        _state.value = CommissioningState(running = true, startedAtMillis = System.currentTimeMillis())
        CommissioningService.start(context)
        job = scope.launch {
            val netWatch = watchNetwork()
            try {
                run(payload)
            } catch (e: CancellationException) {
                AppLog.i(TAG, "Commissioning cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Commissioning failed", e)
                fail(FailureKind.HA_ERROR, e.message)
            } finally {
                netWatch()
                proxy?.close(); proxy = null
                AppLog.removeSecret(payload.rawCode)
                _state.update { it.copy(running = false, finishedAtMillis = System.currentTimeMillis()) }
                CommissioningService.stop(context)
            }
        }
    }

    /** Abort: closes the proxy connection and frees BLE right away. */
    fun cancel() {
        job?.cancel()
        proxy?.close(); proxy = null
        _state.update { it.copy(running = false) }
        CommissioningService.stop(context)
    }

    fun reset() {
        if (job?.isActive == true) return
        _state.value = CommissioningState()
    }

    private suspend fun run(payload: SetupPayload) {
        val session = connector.session()
        val api = session.api
        if (!api.isAdmin()) return fail(FailureKind.NOT_ADMIN)
        val addon = api.findMatterAddon() ?: return fail(FailureKind.NO_MATTER_APP)
        if (!addon.isRunning) return fail(FailureKind.NO_MATTER_APP, "state=${addon.state}")
        if (!addon.bleProxyEnabled) return fail(FailureKind.PROXY_UNAVAILABLE, "ble_proxy disabled")

        val ingress = IngressSession(api, session.baseUrl, addon).apply { open(scope) }
        try {
            val matter = MatterServerClient.connect(session.http, ingress)
            try {
                val nodesBefore = matter.startListening().map { it.nodeId }.toSet()
                val devicesBefore = api.matterDevices().map { it.id }.toSet()
                ensureThreadDataset(session, matter.info.threadCredentialsSet)

                previousAttemptBarrier()

                for (attempt in 1..MAX_BLE_ATTEMPTS) {
                    _state.update { it.copy(attempt = attempt, phase = Phase.PREPARING, failure = null, warnOtherProxy = false) }
                    val outcome = attemptOnce(session, ingress, payload)
                    when (outcome) {
                        Outcome.Success -> return finishSuccess(session, devicesBefore)
                        is Outcome.Failed -> {
                            // After PASE the device may have been commissioned even though HA
                            // reported an error (e.g. timeout during operational discovery).
                            if (outcome.paseDone) {
                                val newNode = withTimeoutOrNull(15_000) {
                                    var found: Long? = null
                                    while (found == null) {
                                        found = matter.nodes().map { it.nodeId }.firstOrNull { it !in nodesBefore }
                                        if (found == null) delay(2000)
                                    }
                                    found
                                }
                                if (newNode != null) {
                                    AppLog.i(TAG, "Node $newNode appeared despite error → treating as success")
                                    return finishSuccess(session, devicesBefore)
                                }
                                return fail(outcome.kind, outcome.detail)
                            }
                            val retryable = outcome.kind == FailureKind.BLE_ERROR ||
                                (outcome.kind == FailureKind.DEVICE_NOT_FOUND && attempt == 1)
                            if (!retryable || attempt == MAX_BLE_ATTEMPTS) return fail(outcome.kind, outcome.detail)
                            AppLog.i(TAG, "Attempt $attempt failed with ${outcome.kind}, retrying")
                            delay(RETRY_PAUSE_MS)
                        }
                    }
                }
            } finally {
                matter.close()
            }
        } finally {
            ingress.close()
        }
    }

    private sealed interface Outcome {
        data object Success : Outcome
        data class Failed(val kind: FailureKind, val detail: String?, val paseDone: Boolean) : Outcome
    }

    /** One commissioning attempt with a fresh proxy connection. */
    private suspend fun attemptOnce(session: HaSession, ingress: IngressSession, payload: SetupPayload): Outcome {
        val client = BleProxyClient(context, session.http, scanner) { hit ->
            payload.matchesDiscriminator(hit.advertisement.discriminator) &&
                (payload.vendorId == null || payload.vendorId == hit.advertisement.vendorId || hit.advertisement.vendorId == 0)
        }
        proxy = client
        try {
            client.connect(ingress.webSocketRequest("ble"))
        } catch (e: Exception) {
            client.close()
            return Outcome.Failed(FailureKind.PROXY_UNAVAILABLE, e.message, paseDone = false)
        }

        var reportedAt = 0L
        var connectRequested = false
        var connectFailed: String? = null
        var unexpectedDisconnect = false
        var paseFailed = false
        var attestationDone = false

        val watcher = scope.launch {
            client.events.collect { ev ->
                when (ev) {
                    is BleProxyClient.Event.ServerScanning -> if (ev.active) setPhase(Phase.SEARCHING)
                    is BleProxyClient.Event.DeviceReported -> {
                        if (reportedAt == 0L) reportedAt = System.currentTimeMillis()
                        _state.update { it.copy(lastRssi = ev.hit.rssi) }
                    }
                    is BleProxyClient.Event.ConnectRequested -> connectRequested = true
                    is BleProxyClient.Event.Connected -> setPhase(Phase.BLE_CONNECTED)
                    is BleProxyClient.Event.ConnectFailed -> connectFailed = "${ev.code} (GATT ${ev.gattStatus})"
                    is BleProxyClient.Event.Btp -> when (ev.event) {
                        BtpObserver.Event.HandshakeDone, BtpObserver.Event.PaseStarted -> setPhase(Phase.PASE)
                        BtpObserver.Event.PaseSucceeded -> setPhase(Phase.ATTESTATION)
                        is BtpObserver.Event.PaseFailed -> paseFailed = true
                        BtpObserver.Event.AttestationLikelyDone -> { attestationDone = true; setPhase(Phase.NETWORK) }
                    }
                    is BleProxyClient.Event.PeripheralDisconnected -> {
                        if (ev.unexpected) unexpectedDisconnect = true
                        // The server closes BLE after handing over network credentials; from here
                        // on it looks for the device on the IP network (CASE).
                        else if (client.btp.isPaseDone) setPhase(Phase.CASE)
                    }
                    else -> Unit
                }
            }
        }
        // Another BLE proxy (ESPHome, HA's own adapter) may "own" the device if it reported it
        // first. Detect that instead of waiting silently.
        val ownerWatch = scope.launch {
            while (isActive) {
                delay(1000)
                if (reportedAt != 0L && !connectRequested && System.currentTimeMillis() - reportedAt > OTHER_PROXY_GRACE_MS) {
                    _state.update { it.copy(warnOtherProxy = true) }
                }
            }
        }

        val call = scope.async { runCatching { session.api.commission(payload.rawCode, networkOnly = false) } }
        danglingCall = call
        val result = call.await()
        watcher.cancel(); ownerWatch.cancel()
        val paseDone = client.btp.isPaseDone
        client.close()
        proxy = null

        result.onSuccess { return Outcome.Success }
        val err = result.exceptionOrNull()
        val msg = (err as? HaException)?.message ?: err?.message
        AppLog.w(TAG, "matter/commission failed: $msg")
        val lower = msg.orEmpty().lowercase()

        val kind = when {
            paseFailed || client.btp.isPaseFailed -> FailureKind.PASE_FAILED
            paseDone && ("attest" in lower || "dac" in lower || "paa" in lower) -> FailureKind.ATTESTATION_FAILED
            paseDone && attestationDone -> FailureKind.NETWORK_FAILED
            paseDone -> if ("attest" in lower) FailureKind.ATTESTATION_FAILED else FailureKind.NETWORK_FAILED
            reportedAt != 0L && !connectRequested -> FailureKind.OTHER_PROXY
            connectFailed != null || unexpectedDisconnect || connectRequested -> FailureKind.BLE_ERROR
            reportedAt == 0L -> FailureKind.DEVICE_NOT_FOUND
            else -> FailureKind.HA_ERROR
        }
        return Outcome.Failed(kind, listOfNotNull(msg, connectFailed).joinToString(" · "), paseDone)
    }

    private suspend fun finishSuccess(session: HaSession, devicesBefore: Set<String>) {
        setPhase(Phase.CASE)
        // HA creates the device shortly after the node was added; wait for it.
        val device = withTimeoutOrNull(30_000) {
            var found: HaDevice? = null
            while (found == null) {
                found = session.api.matterDevices().firstOrNull { it.id !in devicesBefore }
                if (found == null) delay(1500)
            }
            found
        }
        _state.update { it.copy(phase = Phase.DONE, failure = null, newDevice = device) }
        AppLog.i(TAG, "Commissioning succeeded (device=${device?.id})")
    }

    private suspend fun ensureThreadDataset(session: HaSession, serverHasThread: Boolean) {
        if (serverHasThread) return
        val preferred = session.api.threadDatasets().firstOrNull { it.preferred } ?: return
        runCatching { session.api.setThreadDataset(session.api.threadDatasetTlv(preferred.id)) }
            .onSuccess { AppLog.i(TAG, "Handed preferred Thread dataset '${preferred.networkName}' to the Matter server") }
            .onFailure { AppLog.w(TAG, "Could not set Thread dataset", it) }
    }

    /** Wait (max. 3 min) until a previous, cancelled server-side attempt has finished. */
    private suspend fun previousAttemptBarrier() {
        val prev = danglingCall ?: return
        if (prev.isCompleted) return
        _state.update { it.copy(waitingForPreviousAttempt = true) }
        withTimeoutOrNull(180_000) { prev.await() }
        _state.update { it.copy(waitingForPreviousAttempt = false) }
    }

    private fun setPhase(p: Phase) = _state.update {
        if (p.ordinal > it.phase.ordinal) {
            // Logged so that a diagnostic bundle shows how far a failed attempt got.
            val elapsed = (System.currentTimeMillis() - it.startedAtMillis) / 1000
            AppLog.i(TAG, "Phase ${it.phase} -> $p after ${elapsed}s (attempt ${it.attempt})")
            it.copy(phase = p)
        } else it
    }

    private fun fail(kind: FailureKind, detail: String? = null) {
        AppLog.w(TAG, "Failure: $kind ${detail.orEmpty()}")
        _state.update { it.copy(failure = kind, failureDetail = detail) }
    }

    /** Warn when the phone falls back to mobile data mid-run (HA might become unreachable). */
    private fun watchNetwork(): () -> Unit {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return {}
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                _state.update { it.copy(warnCellular = cellular) }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        return { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    private companion object {
        const val TAG = "Commissioning"
        const val RETRY_PAUSE_MS = 5_000L
        const val OTHER_PROXY_GRACE_MS = 15_000L
    }
}
