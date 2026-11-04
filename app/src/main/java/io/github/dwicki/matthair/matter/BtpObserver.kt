package io.github.dwicki.matthair.matter

import java.io.ByteArrayOutputStream

/**
 * Passive observer of the BTP (BLE Transport Protocol) stream that the phone relays between the
 * Matter server and the device. It never alters data. Its only purpose is to tell the user which
 * commissioning phase is running, and to tell the retry logic whether PASE already succeeded.
 *
 * What can be seen:
 *  * BTP handshake (first write / first indication).
 *  * Unencrypted secure-channel messages during PASE (session id 0): PBKDFParamRequest/Response,
 *    Pake1/2/3 and the closing StatusReport. A failed StatusReport means a wrong passcode.
 *  * The first message with a non-zero session id: PASE succeeded, everything after is encrypted.
 *  * After PASE the payloads are opaque. Large device→server messages are certificate / attestation
 *    responses, so we use them as a heuristic for "attestation done → network setup".
 *
 * See `docs/matter-protocol.md` (sections "BTP" and "Message layer") for the formats.
 */
class BtpObserver(private val listener: (Event) -> Unit) {

    sealed interface Event {
        data object HandshakeDone : Event
        data object PaseStarted : Event
        data object PaseSucceeded : Event
        data class PaseFailed(val generalCode: Int, val protocolCode: Int) : Event
        data object AttestationLikelyDone : Event
    }

    enum class Direction { TO_DEVICE, FROM_DEVICE }

    private val reassembly = mapOf(
        Direction.TO_DEVICE to Reassembler(),
        Direction.FROM_DEVICE to Reassembler(),
    )

    private var handshakeSeen = false
    private var paseStarted = false
    private var paseDone = false
    private var paseFailed = false
    private var largeDeviceMessages = 0
    private var attestationSignalled = false

    val isPaseDone: Boolean get() = paseDone
    val isPaseFailed: Boolean get() = paseFailed
    var framesToDevice = 0
        private set
    var framesFromDevice = 0
        private set

    fun reset() {
        reassembly.values.forEach { it.reset() }
        handshakeSeen = false
        paseStarted = false
        paseDone = false
        paseFailed = false
        largeDeviceMessages = 0
        attestationSignalled = false
        framesToDevice = 0
        framesFromDevice = 0
    }

    /** Feed one raw BTP frame. Never throws: malformed data is simply ignored. */
    fun onFrame(direction: Direction, frame: ByteArray) {
        if (direction == Direction.TO_DEVICE) framesToDevice++ else framesFromDevice++
        runCatching { handleFrame(direction, frame) }
    }

    private fun handleFrame(direction: Direction, frame: ByteArray) {
        if (frame.isEmpty()) return
        val flags = frame[0].toInt() and 0xFF
        if (flags and FLAG_HANDSHAKE != 0) {
            if (direction == Direction.FROM_DEVICE && !handshakeSeen) {
                handshakeSeen = true
                listener(Event.HandshakeDone)
            }
            return
        }
        var index = 1
        if (flags and FLAG_MANAGEMENT != 0) index++ // management opcode
        if (flags and FLAG_ACK != 0) index++ // ack number
        index++ // sequence number
        if (index > frame.size) return
        val reassembler = reassembly.getValue(direction)
        if (flags and FLAG_BEGIN != 0) {
            if (index + 2 > frame.size) return
            index += 2 // message length; we rely on the END flag instead
            reassembler.begin()
        }
        if (!reassembler.active) return
        reassembler.append(frame, index)
        if (flags and FLAG_END != 0) onMessage(direction, reassembler.finish())
    }

    private fun onMessage(direction: Direction, message: ByteArray) {
        if (message.size < 8) return
        val sessionId = message.le16(1)
        if (sessionId != 0) onEncryptedMessage(direction, message) else onUnsecuredMessage(message)
    }

    /** The first message with a session id means PASE succeeded; everything after it is opaque. */
    private fun onEncryptedMessage(direction: Direction, message: ByteArray) {
        if (!paseDone && !paseFailed) {
            paseDone = true
            listener(Event.PaseSucceeded)
        }
        if (direction != Direction.FROM_DEVICE || message.size < LARGE_MESSAGE_BYTES) return
        // The DAC and PAI certificates and the attestation response are the three large
        // messages a device sends right after PASE.
        largeDeviceMessages++
        if (largeDeviceMessages >= 3 && !attestationSignalled) {
            attestationSignalled = true
            listener(Event.AttestationLikelyDone)
        }
    }

    private fun onUnsecuredMessage(message: ByteArray) {
        val header = ProtocolHeader.parse(message) ?: return
        if (header.protocolId != PROTOCOL_SECURE_CHANNEL) return
        when (header.opcode) {
            OP_PBKDF_PARAM_REQUEST, OP_PAKE1 -> if (!paseStarted) {
                paseStarted = true
                listener(Event.PaseStarted)
            }
            OP_STATUS_REPORT -> onStatusReport(message, header.payloadOffset)
        }
    }

    /** StatusReport: general code (2 bytes), protocol id (4), protocol code (2). 0/0 means success. */
    private fun onStatusReport(message: ByteArray, offset: Int) {
        if (offset + 8 > message.size || !paseStarted || paseDone) return
        val generalCode = message.le16(offset)
        val protocolCode = message.le16(offset + 6)
        if (generalCode == 0 && protocolCode == 0) {
            paseDone = true
            listener(Event.PaseSucceeded)
        } else {
            paseFailed = true
            listener(Event.PaseFailed(generalCode, protocolCode))
        }
    }

    /** The parts of an unsecured Matter message header we need. */
    private class ProtocolHeader(val opcode: Int, val protocolId: Int, val payloadOffset: Int) {
        companion object {
            fun parse(message: ByteArray): ProtocolHeader? {
                // Message header: flags(1), session id(2), security flags(1), counter(4), optional node ids.
                val messageFlags = message[0].toInt() and 0xFF
                var index = 8
                if (messageFlags and 0x04 != 0) index += 8 // source node id
                when (messageFlags and 0x03) {
                    1 -> index += 8 // destination node id
                    2 -> index += 2 // destination group id
                }
                // Protocol header: exchange flags(1), opcode(1), exchange id(2), [vendor id(2)], protocol id(2), ...
                if (index + 6 > message.size) return null
                val exchangeFlags = message[index].toInt() and 0xFF
                val opcode = message[index + 1].toInt() and 0xFF
                index += 4
                if (exchangeFlags and 0x10 != 0) index += 2 // vendor id
                if (index + 2 > message.size) return null
                val protocolId = message.le16(index)
                index += 2
                if (exchangeFlags and 0x02 != 0) index += 4 // acknowledged message counter
                if (exchangeFlags and 0x08 != 0 && index + 2 <= message.size) index += 2 + message.le16(index) // extensions
                return ProtocolHeader(opcode, protocolId, index)
            }
        }
    }

    /** Joins BTP segments into one Matter message, from the BEGIN flag to the END flag. */
    private class Reassembler {
        private var buffer = ByteArrayOutputStream()
        var active = false
            private set

        fun begin() {
            buffer = ByteArrayOutputStream()
            active = true
        }

        fun append(source: ByteArray, from: Int) {
            if (from < source.size) buffer.write(source, from, source.size - from)
        }

        fun finish(): ByteArray {
            active = false
            return buffer.toByteArray()
        }

        fun reset() {
            active = false
            buffer = ByteArrayOutputStream()
        }
    }

    companion object {
        const val FLAG_HANDSHAKE = 0x40
        const val FLAG_MANAGEMENT = 0x20
        const val FLAG_ACK = 0x08
        const val FLAG_END = 0x04
        const val FLAG_CONTINUE = 0x02
        const val FLAG_BEGIN = 0x01

        const val PROTOCOL_SECURE_CHANNEL = 0x0000
        const val OP_PBKDF_PARAM_REQUEST = 0x20
        const val OP_PBKDF_PARAM_RESPONSE = 0x21
        const val OP_PAKE1 = 0x22
        const val OP_PAKE2 = 0x23
        const val OP_PAKE3 = 0x24
        const val OP_STATUS_REPORT = 0x40

        private const val LARGE_MESSAGE_BYTES = 400
    }
}

/** Little-endian unsigned 16-bit value at [offset]. */
private fun ByteArray.le16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
