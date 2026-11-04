package app.matterpair.ha

import app.matterpair.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/** Matter Server app ("add-on") as reported by the Supervisor. */
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

data class HaDevice(
    val id: String,
    val name: String,
    val nameByUser: String?,
    val areaId: String?,
    val manufacturer: String?,
    val model: String?,
    val configEntries: List<String>,
    val matterIdentifier: String?,
    val swVersion: String? = null,
    val hwVersion: String? = null,
    val serialNumber: String? = null,
) {
    val displayName get() = nameByUser ?: name
}

/** One fabric (ecosystem) a node belongs to, e.g. Home Assistant, Google, Apple, Alexa. */
data class FabricInfo(val fabricIndex: Int, val vendorId: Int, val vendorName: String?, val label: String?)

/**
 * `matter/node_diagnostics` result (python-matter-server `NodeDiagnostics`, also produced by the
 * matter.js server). `ip_adresses` is spelled that way upstream; both spellings are accepted.
 */
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
    val ownFabricIndex: Int?,
)

data class HaArea(val id: String, val name: String)

data class ThreadDataset(val id: String, val networkName: String, val preferred: Boolean, val channel: Int?)

/**
 * Typed wrapper around the Home Assistant WebSocket commands this app uses. Every command used
 * here is listed with its source in `docs/architecture.md#home-assistant-api`.
 */
class HaApi(val ws: HaWebSocket, private val http: OkHttpClient, private val baseUrl: String, private val token: String) {

    // --- user -------------------------------------------------------------------------------

    suspend fun isAdmin(): Boolean = ws.call("auth/current_user").obj.bool("is_admin") == true

    // --- Supervisor / Matter Server app --------------------------------------------------------

    private suspend fun supervisor(endpoint: String, method: String = "get", data: JsonObject? = null, timeoutMs: Long = 30_000): JsonElement =
        ws.call(
            "supervisor/api",
            buildMap {
                put("endpoint", JsonPrimitive(endpoint))
                put("method", JsonPrimitive(method))
                if (data != null) put("data", data)
            },
            timeoutMs,
        )

    /** Returns `null` on installations without Supervisor (Container/Core) or without the app. */
    suspend fun findMatterAddon(): MatterAddon? {
        val addons = runCatching { supervisor("/addons").obj["addons"]?.jsonArray }.getOrElse {
            AppLog.w(TAG, "Supervisor not available", it)
            return null
        } ?: return null
        val candidate = addons.map { it.obj }.firstOrNull { a ->
            val slug = a.str("slug").orEmpty()
            slug == "core_matter_server" || slug.endsWith("_matter_server") || slug.endsWith("matter_server_beta")
        } ?: return null
        val slug = candidate.str("slug")!!
        val info = supervisor("/addons/$slug/info").obj
        return MatterAddon(
            slug = slug,
            name = info.str("name") ?: slug,
            version = info.str("version") ?: "0",
            state = info.str("state") ?: "unknown",
            ingressUrl = info.str("ingress_url"),
            options = info["options"] as? JsonObject ?: JsonObject(emptyMap()),
        )
    }

    /** Enable `ble_proxy` in the app options and restart it. Only called after user consent. */
    suspend fun enableBleProxy(addon: MatterAddon) {
        val newOptions = JsonObject(addon.options + ("ble_proxy" to JsonPrimitive(true)))
        supervisor("/addons/${addon.slug}/options", "post", buildJsonObject { put("options", newOptions) })
        supervisor("/addons/${addon.slug}/restart", "post", timeoutMs = 120_000)
    }

    /** Create an ingress session; the value goes into the `ingress_session` cookie. */
    suspend fun createIngressSession(): String =
        supervisor("/ingress/session", "post").obj.str("session") ?: throw HaException("ingress", "No ingress session")

    suspend fun validateIngressSession(session: String) {
        supervisor("/ingress/validate_session", "post", buildJsonObject { put("session", session) })
    }

    /** Last lines of the Matter Server app log (for the diagnostic bundle). */
    suspend fun addonLog(slug: String, maxLines: Int = 400): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/hassio/addons/$slug/logs")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/plain")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HaException("logs", "HTTP ${r.code}")
            r.body?.string().orEmpty().lines().takeLast(maxLines).joinToString("\n")
        }
    }

    // --- Matter integration --------------------------------------------------------------------

    /**
     * Commission a device with its setup code. `network_only = false` lets the Matter server use
     * BLE (and therefore our proxy). HA's default is `true`!
     */
    suspend fun commission(code: String, networkOnly: Boolean, timeoutMs: Long = 6 * 60_000) {
        ws.call(
            "matter/commission",
            mapOf("code" to JsonPrimitive(code), "network_only" to JsonPrimitive(networkOnly)),
            timeoutMs,
        )
    }

    suspend fun setThreadDataset(tlv: String) {
        ws.call("matter/set_thread", mapOf("thread_operation_dataset" to JsonPrimitive(tlv)))
    }

    suspend fun setWifiCredentials(ssid: String, password: String) {
        ws.call("matter/set_wifi_credentials", mapOf("network_name" to JsonPrimitive(ssid), "password" to JsonPrimitive(password)))
    }

    /** Returns `(manualCode, qrCode)` of the newly opened window. */
    suspend fun openCommissioningWindow(deviceId: String): Pair<String, String> {
        val r = ws.call("matter/open_commissioning_window", mapOf("device_id" to JsonPrimitive(deviceId)), 60_000).obj
        return (r.str("setup_manual_code") ?: "") to (r.str("setup_qr_code") ?: "")
    }

    suspend fun nodeDiagnostics(deviceId: String): NodeDiagnostics {
        val o = ws.call("matter/node_diagnostics", mapOf("device_id" to JsonPrimitive(deviceId))).obj
        val ips = (o["ip_adresses"] ?: o["ip_addresses"]) as? JsonArray
        return NodeDiagnostics(
            nodeId = o.long("node_id") ?: -1,
            networkType = o.str("network_type") ?: "unknown",
            nodeType = o.str("node_type") ?: "unknown",
            networkName = o.str("network_name"),
            ipAddresses = ips.orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            macAddress = o.str("mac_address"),
            available = o.bool("available") == true,
            fabrics = (o["active_fabrics"] as? JsonArray).orEmpty().mapNotNull { e ->
                val f = e as? JsonObject ?: return@mapNotNull null
                FabricInfo(f.int("fabric_index") ?: 0, f.int("vendor_id") ?: 0, f.str("vendor_name"), f.str("fabric_label"))
            },
            ownFabricIndex = o.int("active_fabric_index"),
        )
    }

    /**
     * Remove another ecosystem's fabric from a device (e.g. the "Android"/Google fabric left behind
     * when a device was paired through Google Play Services). Never call this for HA's own fabric.
     */
    suspend fun removeFabric(deviceId: String, fabricIndex: Int) {
        ws.call(
            "matter/remove_matter_fabric",
            mapOf("device_id" to JsonPrimitive(deviceId), "fabric_index" to JsonPrimitive(fabricIndex)),
            60_000,
        )
    }

    suspend fun matterConfigEntryId(): String? = configEntries("matter").firstOrNull()?.str("entry_id")

    suspend fun configEntries(domain: String): List<JsonObject> =
        ws.call("config_entries/get", mapOf("domain" to JsonPrimitive(domain))).jsonArray.map { it.obj }

    // --- Registries ---------------------------------------------------------------------------

    suspend fun devices(): List<HaDevice> = ws.call("config/device_registry/list").jsonArray.map { e ->
        val d = e.obj
        val identifiers = (d["identifiers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonArray }
        HaDevice(
            id = d.str("id")!!,
            name = d.str("name") ?: d.str("model") ?: "?",
            nameByUser = d.str("name_by_user"),
            areaId = d.str("area_id"),
            manufacturer = d.str("manufacturer"),
            model = d.str("model"),
            configEntries = (d["config_entries"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            matterIdentifier = identifiers.firstOrNull { (it.getOrNull(0) as? JsonPrimitive)?.content == "matter" }
                ?.getOrNull(1)?.let { (it as? JsonPrimitive)?.content },
            swVersion = d.str("sw_version"),
            hwVersion = d.str("hw_version"),
            serialNumber = d.str("serial_number"),
        )
    }

    suspend fun matterDevices(): List<HaDevice> = devices().filter { it.matterIdentifier != null }

    suspend fun areas(): List<HaArea> = ws.call("config/area_registry/list").jsonArray.map {
        HaArea(it.obj.str("area_id")!!, it.obj.str("name") ?: "?")
    }

    suspend fun createArea(name: String): HaArea {
        val r = ws.call("config/area_registry/create", mapOf("name" to JsonPrimitive(name))).obj
        return HaArea(r.str("area_id")!!, r.str("name") ?: name)
    }

    suspend fun updateDevice(deviceId: String, nameByUser: String?, areaId: String?) {
        ws.call(
            "config/device_registry/update",
            mapOf(
                "device_id" to JsonPrimitive(deviceId),
                "name_by_user" to (nameByUser?.let { JsonPrimitive(it) } ?: JsonNull),
                "area_id" to (areaId?.let { JsonPrimitive(it) } ?: JsonNull),
            ),
        )
    }

    /** Removes a Matter device from HA, which makes the Matter server remove the node. */
    suspend fun removeDevice(deviceId: String, configEntryId: String) {
        ws.call(
            "config/device_registry/remove_config_entry",
            mapOf("device_id" to JsonPrimitive(deviceId), "config_entry_id" to JsonPrimitive(configEntryId)),
            60_000,
        )
    }

    // --- Thread ------------------------------------------------------------------------------

    suspend fun threadDatasets(): List<ThreadDataset> = runCatching {
        ws.call("thread/list_datasets").obj["datasets"]?.jsonArray.orEmpty().map {
            val o = it.obj
            ThreadDataset(o.str("dataset_id")!!, o.str("network_name") ?: "?", o.bool("preferred") == true, o.int("channel"))
        }
    }.getOrElse { emptyList() }

    suspend fun threadDatasetTlv(datasetId: String): String =
        ws.call("thread/get_dataset_tlv", mapOf("dataset_id" to JsonPrimitive(datasetId))).obj.str("tlv")
            ?: throw HaException("thread", "No TLV")

    /** Number of Thread border routers seen via mDNS within [windowMs]. */
    suspend fun countBorderRouters(windowMs: Long = 4000): Int {
        val seen = mutableSetOf<String>()
        withTimeoutOrNull(windowMs) {
            ws.subscribe("thread/discover_routers").collect { ev ->
                val o = ev as? JsonObject ?: return@collect
                if (o.str("type") == "router_discovered") o.str("key")?.let { seen += it }
            }
        }
        return seen.size
    }

    // --- Zigbee (optional extra) ---------------------------------------------------------------

    suspend fun zhaPermitJoin(seconds: Int) {
        withTimeoutOrNull(10_000) {
            ws.subscribe("zha/devices/permit", mapOf("duration" to JsonPrimitive(seconds))).take(1).toList()
        }
    }

    suspend fun z2mPermitJoin(seconds: Int, baseTopic: String = "zigbee2mqtt") {
        ws.call(
            "call_service",
            mapOf(
                "domain" to JsonPrimitive("mqtt"),
                "service" to JsonPrimitive("publish"),
                "service_data" to buildJsonObject {
                    put("topic", "$baseTopic/bridge/request/permit_join")
                    put("payload", "{\"time\":$seconds}")
                },
            ),
        )
    }

    private companion object {
        const val TAG = "HaApi"
    }
}
