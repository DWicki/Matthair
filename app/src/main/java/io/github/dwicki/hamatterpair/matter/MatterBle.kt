package io.github.dwicki.hamatterpair.matter

import java.util.UUID

/** Matter-over-BLE constants (Matter Core Spec §4.18 / §5.4.2.5). */
object MatterBle {
    /** 16-bit Matter service UUID 0xFFF6 in its 128-bit Bluetooth base form. */
    val SERVICE_UUID: UUID = UUID.fromString("0000FFF6-0000-1000-8000-00805F9B34FB")
    /** BTP write characteristic (client → device). */
    val C1_UUID: UUID = UUID.fromString("18EE2EF5-263D-4559-959F-4F9C429F9D11")
    /** BTP indication characteristic (device → client). */
    val C2_UUID: UUID = UUID.fromString("18EE2EF5-263D-4559-959F-4F9C429F9D12")
    /** Optional "additional commissioning data" characteristic (read only). */
    val C3_UUID: UUID = UUID.fromString("18EE2EF5-263D-4559-959F-4F9C429F9D13")
    /** Client Characteristic Configuration Descriptor. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /**
     * Render a UUID the way the proxy protocol prefers: 16-bit short form (`"fff6"`) for UUIDs
     * in the Bluetooth base range, uppercase canonical form otherwise.
     */
    fun toProtocolString(uuid: UUID): String {
        val s = uuid.toString().lowercase()
        return if (s.startsWith("0000") && s.endsWith(BASE_SUFFIX)) s.substring(4, 8) else s.uppercase()
    }

    /** Accepts short (`fff6`), canonical and compact (no dashes) forms, any case. */
    fun parseUuid(value: String): UUID {
        val v = value.trim().lowercase()
        return when (v.length) {
            4 -> UUID.fromString("0000$v$BASE_SUFFIX")
            8 -> UUID.fromString("$v$BASE_SUFFIX")
            32 -> UUID.fromString(
                "${v.substring(0, 8)}-${v.substring(8, 12)}-${v.substring(12, 16)}-${v.substring(16, 20)}-${v.substring(20)}",
            )
            else -> UUID.fromString(v)
        }
    }
}

/**
 * The 8-byte Matter commissionable advertisement carried as service data under UUID 0xFFF6.
 *
 * | byte | content                                                     |
 * |------|-------------------------------------------------------------|
 * | 0    | opcode, 0x00 = commissionable                                |
 * | 1-2  | LE: bits 0..11 discriminator, bits 12..15 advertisement ver. |
 * | 3-4  | vendor id (LE)                                              |
 * | 5-6  | product id (LE)                                             |
 * | 7    | bit 0: additional data (C3) present, bit 1: extended announce|
 */
data class MatterAdvertisement(
    val discriminator: Int,
    val version: Int,
    val vendorId: Int,
    val productId: Int,
    val hasAdditionalData: Boolean,
    val extendedAnnouncement: Boolean,
) {
    companion object {
        fun parse(serviceData: ByteArray?): MatterAdvertisement? {
            if (serviceData == null || serviceData.size < 7) return null
            if (serviceData[0].toInt() != 0x00) return null
            val u = { i: Int -> serviceData[i].toInt() and 0xFF }
            val discAndVer = u(1) or (u(2) shl 8)
            val flags = if (serviceData.size > 7) u(7) else 0
            return MatterAdvertisement(
                discriminator = discAndVer and 0x0FFF,
                version = discAndVer ushr 12,
                vendorId = u(3) or (u(4) shl 8),
                productId = u(5) or (u(6) shl 8),
                hasAdditionalData = flags and 0x01 != 0,
                extendedAnnouncement = flags and 0x02 != 0,
            )
        }
    }
}
