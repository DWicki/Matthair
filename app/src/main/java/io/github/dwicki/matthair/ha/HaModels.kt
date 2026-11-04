package io.github.dwicki.matthair.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Matter Server app ("add-on") as reported by the Supervisor. */
data class MatterAddon(
    val slug: String,
    val name: String,
    val version: String,
    val state: String,
    /** `/api/hassio_ingress/<token>/` */
    val ingressUrl: String?,
    val options: JsonObject,
) {
    val isRunning get() = state == "started"
    val bleProxyEnabled get() = (options["ble_proxy"] as? JsonPrimitive)?.content == "true"
    val majorVersion: Int get() = version.substringBefore('.').toIntOrNull() ?: 0
}

/** An entry of HA's device registry. */
data class HaDevice(
    val id: String,
    val name: String,
    val nameByUser: String?,
    val areaId: String?,
    val manufacturer: String?,
    val model: String?,
    val configEntries: List<String>,
    /** `deviceid_<fabric>-<nodeIdHex>-MatterNodeDevice` for Matter devices, otherwise null. */
    val matterIdentifier: String?,
    val swVersion: String? = null,
    val hwVersion: String? = null,
    val serialNumber: String? = null,
) {
    val displayName get() = nameByUser ?: name

    val matterNodeId: Long?
        get() = matterIdentifier?.substringAfter('-', "")?.substringBefore('-')?.toULongOrNull(16)?.toLong()
}

/** One ecosystem (fabric) a node belongs to, e.g. Home Assistant, Google, Apple or Alexa. */
data class FabricInfo(val fabricIndex: Int, val vendorId: Int, val vendorName: String?, val label: String?) {
    val displayName: String get() = vendorName ?: label ?: "0x%04X".format(vendorId)
}

/** Result of `matter/node_diagnostics`. */
data class NodeDiagnostics(
    val nodeId: Long,
    /** "thread", "wifi", "ethernet" or "unknown" */
    val networkType: String,
    /** "end_device", "sleepy_end_device", "routing_end_device", "bridge" or "unknown" */
    val nodeType: String,
    val networkName: String?,
    val ipAddresses: List<String>,
    val macAddress: String?,
    val available: Boolean,
    val fabrics: List<FabricInfo>,
    /** Index of Home Assistant's own fabric in [fabrics]. */
    val ownFabricIndex: Int?,
)

data class HaArea(val id: String, val name: String)

data class ThreadDataset(val id: String, val networkName: String, val preferred: Boolean, val channel: Int?)
