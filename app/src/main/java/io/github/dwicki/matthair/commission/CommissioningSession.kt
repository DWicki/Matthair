package io.github.dwicki.matthair.commission

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.dwicki.matthair.ble.BleProxyClient
import io.github.dwicki.matthair.ble.BleScanner
import io.github.dwicki.matthair.ble.ScanHit
import io.github.dwicki.matthair.ha.HaConnector
import io.github.dwicki.matthair.ha.HaDevice
import io.github.dwicki.matthair.ha.HaSession
import io.github.dwicki.matthair.ha.IngressSession
import io.github.dwicki.matthair.ha.MatterServerClient
import io.github.dwicki.matthair.matter.BtpObserver
import io.github.dwicki.matthair.matter.SetupPayload
import io.github.dwicki.matthair.util.AppLog
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** User-visible commissioning phases, in order. */
enum class Phase { PREPARING, SEARCHING, BLE_CONNECTED, PASE, ATTESTATION, NETWORK, CASE, DONE }

/** Why a run failed. Each kind has its own help text and suggested actions in the UI. */
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
    /** Signal strength of the device during this attempt, for the "move closer" hint. */
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
 * Runs one "add device" flow:
 *
 *  1. Connect to HA, find the Matter Server app and open an ingress session.
 *  2. Make sure the server knows the preferred Thread network (copied within HA, never from the phone).
 *  3. Connect the phone to `/ble` as Bluetooth proxy.
 *  4. Call `matter/commission`. The server now drives the phone's radio through the proxy
 *     protocol, and we watch the traffic to show progress.
 *  5. Retry Bluetooth problems, see [commissionWithRetries].
 *  6. Find the new HA device so the user can name it and pick a room.
 *
 * The HA command cannot be cancelled. On cancel the Bluetooth side is torn down at once (so the
 * server's attempt fails quickly) and the next run waits until that attempt has ended, so the
 * device's fail-safe timer is not triggered twice.
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

    /** The `matter/commission` call of an earlier run that may still be running on the server. */
    private var danglingCall: Deferred<*>? = null

    fun start(payload: SetupPayload) {
        if (job?.isActive == true) return
        AppLog.addSecret(payload.rawCode)
        _state.value = CommissioningState(running = true, startedAtMillis = System.currentTimeMillis())
        CommissioningService.start(context)
        job = scope.launch {
            val stopNetworkWatch = watchNetwork()
            try {
                run(payload)
            } catch (e: CancellationException) {
                AppLog.i(TAG, "Commissioning cancelled")
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "Commissioning failed", e)
                fail(FailureKind.HA_ERROR, e.message)
            } finally {
                stopNetworkWatch()
                closeProxy()
                AppLog.removeSecret(payload.rawCode)
                _state.update { it.copy(running = false, finishedAtMillis = System.currentTimeMillis()) }
                CommissioningService.stop(context)
            }
        }
    }

    /** Aborts the run and frees Bluetooth right away. */
    fun cancel() {
        job?.cancel()
        closeProxy()
        _state.update { it.copy(running = false) }
        CommissioningService.stop(context)
    }

    fun reset() {
        if (job?.isActive == true) return
        _state.value = CommissioningState()
    }

    private fun closeProxy() {
        proxy?.close()
        proxy = null
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
                val succeeded = commissionWithRetries(session, ingress, payload) { newNodeAppeared(matter, nodesBefore) }
                if (succeeded) finishSuccess(session, devicesBefore)
            } finally {
                matter.close()
            }
        } finally {
            ingress.close()
        }
    }

    /**
     * Retries Bluetooth problems that happen before PASE. Once PASE succeeded the device may be
     * commissioned although HA reported an error (e.g. a timeout while looking for it on the
     * network), so then we look for a new node instead of retrying.
     */
    private suspend fun commissionWithRetries(
        session: HaSession,
        ingress: IngressSession,
        payload: SetupPayload,
        newNodeAppeared: suspend () -> Boolean,
    ): Boolean {
        for (attempt in 1..MAX_BLE_ATTEMPTS) {
            _state.update { it.copy(attempt = attempt, phase = Phase.PREPARING, failure = null, warnOtherProxy = false) }
            val outcome = attemptOnce(session, ingress, payload)
            if (outcome !is Outcome.Failed) return true

            if (outcome.paseDone) {
                if (newNodeAppeared()) {
                    AppLog.i(TAG, "A new node appeared despite the error, treating it as success")
                    return true
                }
                fail(outcome.kind, outcome.detail)
                return false
            }
            val retryable = outcome.kind == FailureKind.BLE_ERROR ||
                (outcome.kind == FailureKind.DEVICE_NOT_FOUND && attempt == 1)
            if (!retryable || attempt == MAX_BLE_ATTEMPTS) {
                fail(outcome.kind, outcome.detail)
                return false
            }
            AppLog.i(TAG, "Attempt $attempt failed with ${outcome.kind}, retrying")
            delay(RETRY_PAUSE_MS)
        }
        return false
    }

    private suspend fun newNodeAppeared(matter: MatterServerClient, nodesBefore: Set<Long>): Boolean =
        pollFor(timeoutMs = 15_000, intervalMs = 2_000) { matter.nodes().firstOrNull { it.nodeId !in nodesBefore } } != null

    private sealed interface Outcome {
        data object Success : Outcome
        data class Failed(val kind: FailureKind, val detail: String?, val paseDone: Boolean) : Outcome
    }

    /** One commissioning attempt with a fresh proxy connection. */
    private suspend fun attemptOnce(session: HaSession, ingress: IngressSession, payload: SetupPayload): Outcome {
        val client = BleProxyClient(context, session.http, scanner) { hit -> payload.matches(hit) }
        proxy = client
        try {
            client.connect(ingress.webSocketRequest("ble"))
        } catch (e: Exception) {
            closeProxy()
            return Outcome.Failed(FailureKind.PROXY_UNAVAILABLE, e.message, paseDone = false)
        }

        val tracker = AttemptTracker(client)
        val watchers = listOf(scope.launch { tracker.follow() }, scope.launch { tracker.watchForOtherProxy() })
        val call = scope.async { runCatching { session.api.commission(payload.rawCode, networkOnly = false) } }
        danglingCall = call
        val result = call.await()
        watchers.forEach { it.cancel() }
        val paseDone = tracker.paseDone
        closeProxy()

        if (result.isSuccess) return Outcome.Success
        val message = result.exceptionOrNull()?.message
        AppLog.w(TAG, "matter/commission failed: $message")
        val detail = listOfNotNull(message, tracker.connectFailed).joinToString(" · ")
        return Outcome.Failed(tracker.classify(message), detail, paseDone)
    }

    /** Manual codes carry no vendor id, and some devices advertise 0. */
    private fun SetupPayload.matches(hit: ScanHit): Boolean {
        val advertised = hit.advertisement
        return matchesDiscriminator(advertised.discriminator) &&
            (vendorId == null || vendorId == advertised.vendorId || advertised.vendorId == 0)
    }

    /** Follows the proxy events of one attempt: moves the visible phase on and explains failures. */
    private inner class AttemptTracker(private val client: BleProxyClient) {
        private var reportedAt = 0L
        private var connectRequested = false
        private var unexpectedDisconnect = false
        private var paseFailed = false
        var connectFailed: String? = null
            private set

        val paseDone get() = client.btp.isPaseDone

        suspend fun follow(): Nothing = client.events.collect { event ->
            when (event) {
                is BleProxyClient.Event.ServerScanning -> if (event.active) setPhase(Phase.SEARCHING)
                is BleProxyClient.Event.DeviceReported -> {
                    if (reportedAt == 0L) reportedAt = System.currentTimeMillis()
                    _state.update { it.copy(lastRssi = event.hit.rssi) }
                }
                is BleProxyClient.Event.ConnectRequested -> connectRequested = true
                is BleProxyClient.Event.Connected -> setPhase(Phase.BLE_CONNECTED)
                is BleProxyClient.Event.ConnectFailed -> connectFailed = "${event.code} (GATT ${event.gattStatus})"
                is BleProxyClient.Event.Btp -> onBtp(event.event)
                is BleProxyClient.Event.PeripheralDisconnected -> when {
                    event.unexpected -> unexpectedDisconnect = true
                    // The server closes Bluetooth after handing over the network credentials
                    // and then looks for the device on the IP network.
                    paseDone -> setPhase(Phase.CASE)
                }
                else -> Unit
            }
        }

        private fun onBtp(event: BtpObserver.Event) {
            when (event) {
                BtpObserver.Event.HandshakeDone, BtpObserver.Event.PaseStarted -> setPhase(Phase.PASE)
                BtpObserver.Event.PaseSucceeded -> setPhase(Phase.ATTESTATION)
                is BtpObserver.Event.PaseFailed -> paseFailed = true
                BtpObserver.Event.AttestationLikelyDone -> setPhase(Phase.NETWORK)
            }
        }

        /**
         * Another Bluetooth proxy (ESPHome, HA's own adapter) owns the device if it reported it
         * first. If the server never asks us to connect, warn instead of waiting silently.
         */
        suspend fun watchForOtherProxy(): Nothing {
            while (true) {
                delay(1000)
                val waitingMs = System.currentTimeMillis() - reportedAt
                if (reportedAt != 0L && !connectRequested && waitingMs > OTHER_PROXY_GRACE_MS) {
                    _state.update { it.copy(warnOtherProxy = true) }
                }
            }
        }

        fun classify(errorMessage: String?): FailureKind {
            val text = errorMessage.orEmpty().lowercase()
            val mentionsAttestation = "attest" in text || "dac" in text || "paa" in text
            return when {
                paseFailed || client.btp.isPaseFailed -> FailureKind.PASE_FAILED
                paseDone && mentionsAttestation -> FailureKind.ATTESTATION_FAILED
                paseDone -> FailureKind.NETWORK_FAILED
                reportedAt != 0L && !connectRequested -> FailureKind.OTHER_PROXY
                connectFailed != null || unexpectedDisconnect || connectRequested -> FailureKind.BLE_ERROR
                reportedAt == 0L -> FailureKind.DEVICE_NOT_FOUND
                else -> FailureKind.HA_ERROR
            }
        }
    }

    private suspend fun finishSuccess(session: HaSession, devicesBefore: Set<String>) {
        setPhase(Phase.CASE)
        // HA creates the device entry shortly after the node was added.
        val device = pollFor(timeoutMs = 30_000, intervalMs = 1_500) {
            session.api.matterDevices().firstOrNull { it.id !in devicesBefore }
        }
        _state.update { it.copy(phase = Phase.DONE, failure = null, newDevice = device) }
        AppLog.i(TAG, "Commissioning succeeded (device=${device?.id})")
    }

    /** Calls [probe] every [intervalMs] until it returns something, for at most [timeoutMs]. */
    private suspend fun <T : Any> pollFor(timeoutMs: Long, intervalMs: Long, probe: suspend () -> T?): T? =
        withTimeoutOrNull(timeoutMs) {
            var found = probe()
            while (found == null) {
                delay(intervalMs)
                found = probe()
            }
            found
        }

    private suspend fun ensureThreadDataset(session: HaSession, serverHasThread: Boolean) {
        if (serverHasThread) return
        val preferred = session.api.threadDatasets().firstOrNull { it.preferred } ?: return
        runCatching { session.api.setThreadDataset(session.api.threadDatasetTlv(preferred.id)) }
            .onSuccess { AppLog.i(TAG, "Handed the preferred Thread network '${preferred.networkName}' to the Matter Server") }
            .onFailure { AppLog.w(TAG, "Could not set the Thread dataset", it) }
    }

    /** Waits (at most 3 minutes) until a cancelled earlier attempt has ended on the server. */
    private suspend fun previousAttemptBarrier() {
        val previous = danglingCall ?: return
        if (previous.isCompleted) return
        _state.update { it.copy(waitingForPreviousAttempt = true) }
        withTimeoutOrNull(180_000) { previous.await() }
        _state.update { it.copy(waitingForPreviousAttempt = false) }
    }

    /** Phases only move forward. Each step is logged so a diagnostic bundle shows how far a run got. */
    private fun setPhase(phase: Phase) = _state.update {
        if (phase.ordinal <= it.phase.ordinal) return@update it
        val elapsedSeconds = (System.currentTimeMillis() - it.startedAtMillis) / 1000
        AppLog.i(TAG, "Phase ${it.phase} -> $phase after ${elapsedSeconds}s (attempt ${it.attempt})")
        it.copy(phase = phase)
    }

    private fun fail(kind: FailureKind, detail: String? = null) {
        AppLog.w(TAG, "Failure: $kind ${detail.orEmpty()}")
        _state.update { it.copy(failure = kind, failureDetail = detail) }
    }

    /** Warns when the phone falls back to mobile data mid-run, which can make HA unreachable. */
    private fun watchNetwork(): () -> Unit {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return {}
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val cellularOnly = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                _state.update { it.copy(warnCellular = cellularOnly) }
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
        return { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private companion object {
        const val TAG = "Commissioning"
        const val RETRY_PAUSE_MS = 5_000L
        const val OTHER_PROXY_GRACE_MS = 15_000L
    }
}
