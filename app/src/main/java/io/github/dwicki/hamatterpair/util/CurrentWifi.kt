package io.github.dwicki.hamatterpair.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The Wi-Fi the phone is connected to right now. */
data class ConnectedWifi(val ssid: String, val frequencyMhz: Int) {
    /** Matter Wi-Fi devices only support 2.4 GHz. */
    val is24GHz get() = frequencyMhz in 2400..2500
}

/**
 * Reads the SSID of the current Wi-Fi connection.
 *
 * Android only reveals the SSID to apps holding `ACCESS_FINE_LOCATION` with location services
 * switched on, and since API 31 only via a network callback registered with
 * [ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO]. The permission is requested
 * only when the user opens the Wi-Fi screen; the app never uses location for anything else.
 * The **password** can never be read by apps (that is why the Wi-Fi QR scan exists).
 */
object CurrentWifi {
    const val PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION
    private const val UNKNOWN = "<unknown ssid>"

    fun hasPermission(context: Context) =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    suspend fun read(context: Context): ConnectedWifi? {
        if (!hasPermission(context)) return null
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return withTimeoutOrNull(2000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val info = caps.transportInfo as? WifiInfo ?: return
                        val ssid = info.ssid?.removeSurrounding("\"")
                        runCatching { cm.unregisterNetworkCallback(this) }
                        if (cont.isActive) {
                            cont.resume(ssid?.takeIf { it.isNotBlank() && it != UNKNOWN }?.let { ConnectedWifi(it, info.frequency) })
                        }
                    }
                }
                val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
                runCatching { cm.registerNetworkCallback(request, cb) }.onFailure { if (cont.isActive) cont.resume(null) }
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        }
    }
}
