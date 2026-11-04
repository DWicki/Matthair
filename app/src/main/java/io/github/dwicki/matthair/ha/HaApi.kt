package io.github.dwicki.matthair.ha

import io.github.dwicki.matthair.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Typed wrapper around the Home Assistant commands this app uses.
 * The full list with sources is in `docs/architecture/arc42.md`, section 3.
 */
class HaApi(val ws: HaWebSocket, private val http: OkHttpClient, private val baseUrl: String, private val token: String) {

    suspend fun isAdmin(): Boolean = ws.call("auth/current_user").obj.bool("is_admin") == true

    // --- Supervisor and the Matter Server app ---------------------------------------------------

    /** Returns null on installations without Supervisor (Container/Core) or without the app. */
    suspend fun findMatterAddon(): MatterAddon? {
        val addons = runCatching { supervisor("/addons").obj.array("addons") }
            .onFailure { AppLog.w(TAG, "Supervisor not available", it) }
            .getOrNull() ?: return null
        val slug = addons.map { it.obj.str("slug").orEmpty() }.firstOrNull(::isMatterServerSlug) ?: return null
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

    /** Turns on `ble_proxy` in the app options and restarts the app. Only called after the user agreed. */
    suspend fun enableBleProxy(addon: MatterAddon) {
        val options = JsonObject(addon.options + ("ble_proxy" to true.json))
        supervisor("/addons/${addon.slug}/options", "post", buildJsonObject { put("options", options) })
        supervisor("/addons/${addon.slug}/restart", "post", timeoutMs = 120_000)
    }

    /** The returned value goes into the `ingress_session` cookie. */
    suspend fun createIngressSession(): String =
        supervisor("/ingress/session", "post").obj.str("session") ?: throw HaException("ingress", "No ingress session")

    suspend fun validateIngressSession(session: String) {
        supervisor("/ingress/validate_session", "post", buildJsonObject { put("session", session) })
    }

    /** Last lines of the Matter Server app log, for the diagnostic bundle. */
    suspend fun addonLog(slug: String, maxLines: Int = 400): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/hassio/addons/$slug/logs")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/plain")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HaException("logs", "HTTP ${response.code}")
            response.body?.string().orEmpty().lines().takeLast(maxLines).joinToString("\n")
        }
    }

    private suspend fun supervisor(endpoint: String, method: String = "get", data: JsonObject? = null, timeoutMs: Long = 30_000): JsonElement {
        val args = buildMap {
            put("endpoint", endpoint.json)
            put("method", method.json)
            if (data != null) put("data", data)
        }
        return ws.call("supervisor/api", args, timeoutMs)
    }

    private fun isMatterServerSlug(slug: String) =
        slug == "core_matter_server" || slug.endsWith("_matter_server") || slug.endsWith("matter_server_beta")

    // --- Matter integration ---------------------------------------------------------------------

    /**
     * Commission a device with its setup code. `networkOnly = false` lets the Matter Server use
     * Bluetooth, and therefore our proxy. Note that HA's own default is `true`.
     */
    suspend fun commission(code: String, networkOnly: Boolean, timeoutMs: Long = 6 * 60_000) {
        ws.call("matter/commission", mapOf("code" to code.json, "network_only" to networkOnly.json), timeoutMs)
    }

    suspend fun setThreadDataset(tlv: String) {
        ws.call("matter/set_thread", mapOf("thread_operation_dataset" to tlv.json))
    }

    suspend fun setWifiCredentials(ssid: String, password: String) {
        ws.call("matter/set_wifi_credentials", mapOf("network_name" to ssid.json, "password" to password.json))
    }

    /** Opens a commissioning window and returns its `(manualCode, qrCode)`. */
    suspend fun openCommissioningWindow(deviceId: String): Pair<String, String> {
        val result = ws.call("matter/open_commissioning_window", mapOf("device_id" to deviceId.json), 60_000).obj
        return result.str("setup_manual_code").orEmpty() to result.str("setup_qr_code").orEmpty()
    }

    suspend fun nodeDiagnostics(deviceId: String): NodeDiagnostics =
        parseDiagnostics(ws.call("matter/node_diagnostics", mapOf("device_id" to deviceId.json)).obj)

    /**
     * Removes another ecosystem from a device, e.g. the Google fabric left behind when a device
     * was paired through Google Play Services. Never call this for HA's own fabric.
     */
    suspend fun removeFabric(deviceId: String, fabricIndex: Int) {
        ws.call("matter/remove_matter_fabric", mapOf("device_id" to deviceId.json, "fabric_index" to fabricIndex.json), 60_000)
    }

    suspend fun matterConfigEntryId(): String? = configEntries("matter").firstOrNull()?.str("entry_id")

    suspend fun configEntries(domain: String): List<JsonObject> =
        ws.call("config_entries/get", mapOf("domain" to domain.json)).jsonArray.map { it.obj }

    // --- Device and area registry ---------------------------------------------------------------

    suspend fun matterDevices(): List<HaDevice> =
        ws.call("config/device_registry/list").jsonArray.map { parseDevice(it.obj) }.filter { it.matterIdentifier != null }

    suspend fun areas(): List<HaArea> =
        ws.call("config/area_registry/list").jsonArray.map { HaArea(it.obj.str("area_id")!!, it.obj.str("name") ?: "?") }

    suspend fun createArea(name: String): HaArea {
        val result = ws.call("config/area_registry/create", mapOf("name" to name.json)).obj
        return HaArea(result.str("area_id")!!, result.str("name") ?: name)
    }

    /** A null [nameByUser] restores the name the device reports itself. */
    suspend fun updateDevice(deviceId: String, nameByUser: String?, areaId: String?) {
        ws.call(
            "config/device_registry/update",
            mapOf("device_id" to deviceId.json, "name_by_user" to nameByUser.json, "area_id" to areaId.json),
        )
    }

    /** Removing a Matter device from HA makes the Matter Server remove the node as well. */
    suspend fun removeDevice(deviceId: String, configEntryId: String) {
        ws.call(
            "config/device_registry/remove_config_entry",
            mapOf("device_id" to deviceId.json, "config_entry_id" to configEntryId.json),
            60_000,
        )
    }

    // --- Thread ---------------------------------------------------------------------------------

    suspend fun threadDatasets(): List<ThreadDataset> = runCatching {
        ws.call("thread/list_datasets").obj.array("datasets").map {
            val o = it.obj
            ThreadDataset(o.str("dataset_id")!!, o.str("network_name") ?: "?", o.bool("preferred") == true, o.int("channel"))
        }
    }.getOrDefault(emptyList())

    suspend fun threadDatasetTlv(datasetId: String): String =
        ws.call("thread/get_dataset_tlv", mapOf("dataset_id" to datasetId.json)).obj.str("tlv")
            ?: throw HaException("thread", "No TLV")

    /** Number of Thread border routers HA sees via mDNS within [windowMs]. */
    suspend fun countBorderRouters(windowMs: Long = 4000): Int {
        val seen = mutableSetOf<String>()
        withTimeoutOrNull(windowMs) {
            ws.subscribe("thread/discover_routers").collect { event ->
                val o = event as? JsonObject ?: return@collect
                if (o.str("type") == "router_discovered") o.str("key")?.let { seen += it }
            }
        }
        return seen.size
    }

    // --- Zigbee (optional extra) ----------------------------------------------------------------

    suspend fun zhaPermitJoin(seconds: Int) {
        withTimeoutOrNull(10_000) {
            ws.subscribe("zha/devices/permit", mapOf("duration" to seconds.json)).take(1).toList()
        }
    }

    suspend fun z2mPermitJoin(seconds: Int, baseTopic: String = "zigbee2mqtt") {
        val serviceData = buildJsonObject {
            put("topic", "$baseTopic/bridge/request/permit_join")
            put("payload", "{\"time\":$seconds}")
        }
        ws.call("call_service", mapOf("domain" to "mqtt".json, "service" to "publish".json, "service_data" to serviceData))
    }

    private companion object {
        const val TAG = "HaApi"

        fun parseDevice(d: JsonObject): HaDevice {
            val identifiers = d.array("identifiers").mapNotNull { it as? JsonArray }
            val matter = identifiers.firstOrNull { (it.getOrNull(0) as? JsonPrimitive)?.content == "matter" }
            return HaDevice(
                id = d.str("id")!!,
                name = d.str("name") ?: d.str("model") ?: "?",
                nameByUser = d.str("name_by_user"),
                areaId = d.str("area_id"),
                manufacturer = d.str("manufacturer"),
                model = d.str("model"),
                configEntries = d.strings("config_entries"),
                matterIdentifier = (matter?.getOrNull(1) as? JsonPrimitive)?.content,
                swVersion = d.str("sw_version"),
                hwVersion = d.str("hw_version"),
                serialNumber = d.str("serial_number"),
            )
        }

        /** `ip_adresses` is misspelled upstream; the correct spelling is accepted too. */
        fun parseDiagnostics(o: JsonObject): NodeDiagnostics {
            val fabrics = o.array("active_fabrics").mapNotNull { it as? JsonObject }.map {
                FabricInfo(it.int("fabric_index") ?: 0, it.int("vendor_id") ?: 0, it.str("vendor_name"), it.str("fabric_label"))
            }
            return NodeDiagnostics(
                nodeId = o.long("node_id") ?: -1,
                networkType = o.str("network_type") ?: "unknown",
                nodeType = o.str("node_type") ?: "unknown",
                networkName = o.str("network_name"),
                ipAddresses = if ("ip_adresses" in o) o.strings("ip_adresses") else o.strings("ip_addresses"),
                macAddress = o.str("mac_address"),
                available = o.bool("available") == true,
                fabrics = fabrics,
                ownFabricIndex = o.int("active_fabric_index"),
            )
        }
    }
}
