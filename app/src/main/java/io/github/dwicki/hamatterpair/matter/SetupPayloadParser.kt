package io.github.dwicki.hamatterpair.matter

/**
 * Parses Matter onboarding payloads. Pure Kotlin, no Android dependencies, fully unit-tested.
 *
 * See `docs/matter-protocol.md` for the bit layouts implemented here.
 */
object SetupPayloadParser {

    private const val QR_PREFIX = "MT:"

    /**
     * Parse anything a user may scan or type: a QR payload (`MT:...`, optionally wrapped in a
     * URI as some NFC tags do) or a manual code with arbitrary spaces/dashes.
     */
    fun parse(input: String): SetupPayload {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw PayloadException(PayloadError.EMPTY, "Empty code")
        val mtIndex = trimmed.uppercase().indexOf(QR_PREFIX)
        return if (mtIndex >= 0) {
            parseQr(trimmed.substring(mtIndex))
        } else {
            parseManual(trimmed)
        }
    }

    // ---------------------------------------------------------------------------------------
    // QR code
    // ---------------------------------------------------------------------------------------

    /**
     * QR layout (88 bits, little-endian bit order, LSB first):
     *
     * | bits | field                   |
     * |------|-------------------------|
     * | 3    | version (must be 0)     |
     * | 16   | vendor id               |
     * | 16   | product id              |
     * | 2    | custom flow             |
     * | 8    | discovery capabilities  |
     * | 12   | discriminator           |
     * | 27   | passcode                |
     * | 4    | padding                 |
     *
     * Optional TLV data may follow; it is ignored. A QR code may contain several payloads joined
     * by `*` (concatenated QR codes); the first one is used.
     */
    fun parseQr(code: String): SetupPayload {
        if (!code.startsWith(QR_PREFIX, ignoreCase = true)) {
            throw PayloadException(PayloadError.BAD_PREFIX, "QR payload must start with MT:")
        }
        val body = code.substring(QR_PREFIX.length).substringBefore('*').trim()
        val bytes = Base38.decode(body)
        if (bytes.size < 11) throw PayloadException(PayloadError.TOO_SHORT, "QR payload too short")

        val reader = BitReader(bytes)
        val version = reader.read(3).toInt()
        val vendorId = reader.read(16).toInt()
        val productId = reader.read(16).toInt()
        val flowBits = reader.read(2).toInt()
        val capabilities = reader.read(8).toInt()
        val discriminator = reader.read(12).toInt()
        val passcode = reader.read(27)

        if (version != 0) throw PayloadException(PayloadError.BAD_VERSION, "Unsupported payload version $version")
        validatePasscode(passcode)

        return SetupPayload(
            rawCode = QR_PREFIX + code.substring(QR_PREFIX.length).trim(),
            source = SetupPayload.Source.QR,
            version = version,
            vendorId = vendorId,
            productId = productId,
            flow = flowFromBits(flowBits),
            discoveryCapabilities = capabilities,
            longDiscriminator = discriminator,
            shortDiscriminator = discriminator ushr 8,
            passcode = passcode,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Manual pairing code
    // ---------------------------------------------------------------------------------------

    /**
     * Manual code layout (decimal digit chunks):
     *
     * | digits | content                                                                |
     * |--------|------------------------------------------------------------------------|
     * | 1      | bit 2: VID/PID present (21-digit code), bits 1..0: discriminator[11:10]  |
     * | 5      | bits 15..14: discriminator[9:8], bits 13..0: passcode[13:0]            |
     * | 4      | passcode[26:14]                                                        |
     * | 5      | vendor id (21-digit form only)                                          |
     * | 5      | product id (21-digit form only)                                         |
     * | 1      | Verhoeff check digit over all preceding digits                         |
     *
     * Only the upper four discriminator bits are transported ("short discriminator").
     */
    fun parseManual(code: String): SetupPayload {
        val digits = normalizeManual(code)
        if (digits.any { !it.isDigit() }) throw PayloadException(PayloadError.NOT_DIGITS, "Manual code must contain only digits")
        if (digits.length != 11 && digits.length != 21) {
            throw PayloadException(PayloadError.BAD_LENGTH, "Manual code must have 11 or 21 digits, got ${digits.length}")
        }
        if (!Verhoeff.validate(digits)) throw PayloadException(PayloadError.BAD_CHECK_DIGIT, "Check digit mismatch")

        val chunk1 = digits.substring(0, 1).toInt()
        val chunk2 = digits.substring(1, 6).toInt()
        val chunk3 = digits.substring(6, 10).toInt()
        val vidPidPresent = (chunk1 and 0b100) != 0
        if (chunk1 > 7) throw PayloadException(PayloadError.BAD_VERSION, "Unsupported manual code version")

        val longForm = digits.length == 21
        if (vidPidPresent != longForm) {
            throw PayloadException(PayloadError.BAD_LENGTH, "VID/PID flag does not match code length")
        }

        val shortDiscriminator = ((chunk1 and 0b11) shl 2) or ((chunk2 ushr 14) and 0b11)
        val passcode = (chunk2 and 0x3FFF).toLong() or (chunk3.toLong() shl 14)
        validatePasscode(passcode)

        val vendorId = if (longForm) digits.substring(10, 15).toInt() else null
        val productId = if (longForm) digits.substring(15, 20).toInt() else null

        return SetupPayload(
            rawCode = digits,
            source = if (longForm) SetupPayload.Source.MANUAL_21 else SetupPayload.Source.MANUAL_11,
            version = 0,
            vendorId = vendorId,
            productId = productId,
            // The 21-digit form is used for devices with a non-standard flow; the exact flow is
            // not encoded, so "user intent" is the safest assumption there.
            flow = if (longForm) CommissioningFlow.USER_INTENT else CommissioningFlow.STANDARD,
            discoveryCapabilities = null,
            longDiscriminator = null,
            shortDiscriminator = shortDiscriminator,
            passcode = passcode,
        )
    }

    /** Strip the separators people commonly type or that are printed on labels. */
    fun normalizeManual(code: String): String =
        code.filterNot { it == '-' || it == ' ' || it == ' ' || it == '.' || it == '\t' || it == '\n' }

    /** Pretty-print a manual code like the labels do: `3497-011-2332` / `7497-011-2332-6552-1327-6`. */
    fun formatManual(digits: String): String {
        val d = normalizeManual(digits)
        return when (d.length) {
            11 -> "${d.substring(0, 4)}-${d.substring(4, 7)}-${d.substring(7)}"
            21 -> "${d.substring(0, 4)}-${d.substring(4, 7)}-${d.substring(7, 11)}-${d.substring(11, 15)}-${d.substring(15, 19)}-${d.substring(19)}"
            else -> d
        }
    }

    // ---------------------------------------------------------------------------------------
    // Validation helpers
    // ---------------------------------------------------------------------------------------

    /** Passcodes the spec forbids because they are trivial to guess (§5.1.7.1). */
    private val FORBIDDEN_PASSCODES = setOf(
        0L, 11111111L, 22222222L, 33333333L, 44444444L, 55555555L,
        66666666L, 77777777L, 88888888L, 99999999L, 12345678L, 87654321L,
    )

    fun isValidPasscode(passcode: Long): Boolean =
        passcode in 1..99_999_998 && passcode !in FORBIDDEN_PASSCODES

    private fun validatePasscode(passcode: Long) {
        if (!isValidPasscode(passcode)) throw PayloadException(PayloadError.INVALID_PASSCODE, "Invalid setup passcode")
    }

    private fun flowFromBits(bits: Int) = when (bits) {
        0 -> CommissioningFlow.STANDARD
        1 -> CommissioningFlow.USER_INTENT
        2 -> CommissioningFlow.CUSTOM
        else -> CommissioningFlow.RESERVED
    }

    /** Reads little-endian bit fields, LSB first, as the Matter QR format requires. */
    private class BitReader(private val bytes: ByteArray) {
        private var bitPos = 0
        fun read(count: Int): Long {
            var value = 0L
            for (i in 0 until count) {
                val byte = bytes[bitPos / 8].toInt() and 0xFF
                val bit = (byte ushr (bitPos % 8)) and 1
                value = value or (bit.toLong() shl i)
                bitPos++
            }
            return value
        }
    }
}

/** Base38 as used by Matter QR codes: 3 bytes ↔ 5 chars, little-endian. */
object Base38 {
    const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-."

    fun decode(input: String): ByteArray {
        val s = input.uppercase()
        val out = ArrayList<Byte>(s.length * 3 / 5 + 3)
        var i = 0
        while (i < s.length) {
            val remaining = s.length - i
            val chunkLen = when {
                remaining >= 5 -> 5
                remaining == 4 -> 4
                remaining == 2 -> 2
                else -> throw PayloadException(PayloadError.BAD_BASE38, "Invalid Base38 length")
            }
            var value = 0L
            for (j in chunkLen - 1 downTo 0) {
                val idx = ALPHABET.indexOf(s[i + j])
                if (idx < 0) throw PayloadException(PayloadError.BAD_BASE38, "Invalid Base38 character '${s[i + j]}'")
                value = value * 38 + idx
            }
            val bytesInChunk = when (chunkLen) { 5 -> 3; 4 -> 2; else -> 1 }
            if (value >= (1L shl (8 * bytesInChunk))) {
                throw PayloadException(PayloadError.BAD_BASE38, "Base38 chunk out of range")
            }
            for (b in 0 until bytesInChunk) out.add(((value ushr (8 * b)) and 0xFF).toByte())
            i += chunkLen
        }
        return out.toByteArray()
    }

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(3, bytes.size - i)
            var value = 0L
            for (b in 0 until n) value = value or ((bytes[i + b].toLong() and 0xFF) shl (8 * b))
            val chars = when (n) { 3 -> 5; 2 -> 4; else -> 2 }
            repeat(chars) {
                sb.append(ALPHABET[(value % 38).toInt()])
                value /= 38
            }
            i += n
        }
        return sb.toString()
    }
}

/** Verhoeff check digit (dihedral group D5), used by Matter manual pairing codes. */
object Verhoeff {
    private val D = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
        intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
        intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
        intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
        intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val P = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
        intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
        intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
        intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
        intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )
    private val INV = intArrayOf(0, 4, 3, 2, 1, 5, 6, 7, 8, 9)

    /** Check digit to append to [digits]. */
    fun compute(digits: String): Int {
        var c = 0
        digits.reversed().forEachIndexed { i, ch -> c = D[c][P[(i + 1) % 8][ch - '0']] }
        return INV[c]
    }

    /** Validate a number whose last digit is the check digit. */
    fun validate(digitsWithCheck: String): Boolean {
        var c = 0
        digitsWithCheck.reversed().forEachIndexed { i, ch -> c = D[c][P[i % 8][ch - '0']] }
        return c == 0
    }
}
