package app.matterpair.matter

/**
 * Decoded Matter onboarding payload (Matter Core Spec §5.1).
 *
 * Two sources exist:
 *  * **QR code** (`MT:` + Base38): carries everything, including the full 12-bit discriminator.
 *  * **Manual pairing code** (11 or 21 digits): carries only the upper 4 bits of the
 *    discriminator ([shortDiscriminator]) and optionally vendor/product id.
 *
 * The passcode is sensitive. It is kept only as long as the payload object lives and must never
 * be logged; [toString] masks it. The original code string is kept in [rawCode] because Home
 * Assistant's `matter/commission` command takes the code string, not the decoded fields.
 */
class SetupPayload(
    val rawCode: String,
    val source: Source,
    val version: Int,
    val vendorId: Int?,
    val productId: Int?,
    val flow: CommissioningFlow,
    /** Bitmask of [DiscoveryCapability]; `null` for manual codes (not encoded there). */
    val discoveryCapabilities: Int?,
    /** Full 12-bit discriminator, only present in QR codes. */
    val longDiscriminator: Int?,
    /** Upper 4 bits of the discriminator. Always present. */
    val shortDiscriminator: Int,
    val passcode: Long,
) {
    enum class Source { QR, MANUAL_11, MANUAL_21 }

    /** True when the payload states BLE as a discovery transport (QR only). */
    val advertisesBle: Boolean
        get() = discoveryCapabilities?.let { it and DiscoveryCapability.BLE != 0 } ?: true

    /** True when the payload states the device is already on an IP network ("on-network"). */
    val advertisesOnNetwork: Boolean
        get() = discoveryCapabilities?.let { it and DiscoveryCapability.ON_NETWORK != 0 } ?: false

    /**
     * Does a BLE advertisement with discriminator [advertised] belong to this payload?
     * With a manual code only the upper 4 bits can be compared, so several devices may match.
     */
    fun matchesDiscriminator(advertised: Int): Boolean =
        if (longDiscriminator != null) advertised == longDiscriminator
        else (advertised ushr 8) == shortDiscriminator

    /** Passcode as `****1234`-style string suitable for display. */
    fun maskedPasscode(): String {
        val s = passcode.toString().padStart(8, '0')
        return "••••" + s.takeLast(4)
    }

    override fun toString(): String =
        "SetupPayload(source=$source, vid=${vendorId?.hex()}, pid=${productId?.hex()}, flow=$flow, " +
            "disc=${longDiscriminator ?: "short:$shortDiscriminator"}, passcode=<redacted>)"
}

enum class CommissioningFlow {
    /** Device enters commissioning mode on power-up when not yet commissioned. */
    STANDARD,
    /** The user has to do something (e.g. press a button) to enter commissioning mode. */
    USER_INTENT,
    /** A vendor service/app is required before the device can be commissioned. */
    CUSTOM,
    RESERVED,
}

object DiscoveryCapability {
    const val SOFT_AP = 1 shl 0
    const val BLE = 1 shl 1
    const val ON_NETWORK = 1 shl 2
    const val WIFI_PAF = 1 shl 3
    const val NFC = 1 shl 4
}

/** Why a code was rejected. Mapped to user-facing strings by the UI. */
enum class PayloadError {
    EMPTY,
    BAD_PREFIX,
    BAD_BASE38,
    TOO_SHORT,
    BAD_LENGTH,
    NOT_DIGITS,
    BAD_CHECK_DIGIT,
    INVALID_PASSCODE,
    BAD_VERSION,
}

class PayloadException(val error: PayloadError, message: String) : IllegalArgumentException(message)

internal fun Int.hex(): String = "0x" + toString(16).uppercase().padStart(4, '0')
