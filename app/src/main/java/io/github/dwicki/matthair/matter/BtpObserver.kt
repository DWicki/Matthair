package io.github.dwicki.matthair.matter

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
    var framesToDevice = 0; private set
    var framesFromDevice = 0; private set

    fun reset() {
        reassembly.values.forEach { it.reset() }
        handshakeSeen = false; paseStarted = false; paseDone = false; paseFailed = false
        largeDeviceMessages = 0; attestationSignalled = false
        framesToDevice = 0; framesFromDevice = 0
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
        var idx = 1
        if (flags and FLAG_MANAGEMENT != 0) idx++ // management opcode
        if (flags and FLAG_ACK != 0) idx++ // ack number
        idx++ // sequence number
        if (idx > frame.size) return
        val r = reassembly.getValue(direction)
        if (flags and FLAG_BEGIN != 0) {
            if (idx + 2 > frame.size) return
            idx += 2 // message length (LE16); we reassemble by END flag instead
            r.begin()
        }
        if (!r.active) return
        r.append(frame, idx)
        if (flags and FLAG_END != 0) {
            val message = r.finish()
            onMessage(direction, message)
        }
    }

    private fun onMessage(direction: Direction, msg: ByteArray) {
        if (msg.size < 8) return
        val sessionId = (msg[1].toInt() and 0xFF) or ((msg[2].toInt() and 0xFF) shl 8)

        if (sessionId != 0) {
            if (!paseDone && !paseFailed) {
                paseDone = true
                listener(Event.PaseSucceeded)
            }
            if (direction == Direction.FROM_DEVICE && msg.size >= LARGE_MESSAGE_BYTES) {
                largeDeviceMessages++
                // DAC + PAI certificate responses and the attestation response are the three
                // large messages a device sends right after PASE.
                if (largeDeviceMessages >= 3 && !attestationSignalled) {
                    attestationSignalled = true
                    listener(Event.AttestationLikelyDone)
                }
            }
            return
        }

        // Unsecured message: parse message header to find the protocol header.
        val msgFlags = msg[0].toInt() and 0xFF
        var idx = 8 // flags(1) + session id(2) + security flags(1) + counter(4)
        if (msgFlags and 0x04 != 0) idx += 8 // source node id
        when (msgFlags and 0x03) {
            1 -> idx += 8
            2 -> idx += 2
        }
        if (idx + 6 > msg.size) return
        val exchangeFlags = msg[idx].toInt() and 0xFF
        val opcode = msg[idx + 1].toInt() and 0xFF
        var p = idx + 4 // exchange flags, opcode, exchange id(2)
        if (exchangeFlags and 0x10 != 0) p += 2 // vendor id
        if (p + 2 > msg.size) return
        val protocolId = (msg[p].toInt() and 0xFF) or ((msg[p + 1].toInt() and 0xFF) shl 8)
        p += 2
        if (exchangeFlags and 0x02 != 0) p += 4 // acknowledged message counter
        if (exchangeFlags and 0x08 != 0 && p + 2 <= msg.size) {
            val extLen = (msg[p].toInt() and 0xFF) or ((msg[p + 1].toInt() and 0xFF) shl 8)
            p += 2 + extLen
        }
        if (protocolId != PROTOCOL_SECURE_CHANNEL) return

        when (opcode) {
            OP_PBKDF_PARAM_REQUEST, OP_PAKE1 -> if (!paseStarted) {
                paseStarted = true
                listener(Event.PaseStarted)
            }
            OP_STATUS_REPORT -> if (p + 8 <= msg.size) {
                val general = (msg[p].toInt() and 0xFF) or ((msg[p + 1].toInt() and 0xFF) shl 8)
                val protoCode = (msg[p + 6].toInt() and 0xFF) or ((msg[p + 7].toInt() and 0xFF) shl 8)
                if (paseStarted && !paseDone) {
                    if (general == 0 && protoCode == 0) {
                        paseDone = true
                        listener(Event.PaseSucceeded)
                    } else {
                        paseFailed = true
                        listener(Event.PaseFailed(general, protoCode))
                    }
                }
            }
        }
    }

    private class Reassembler {
        private var buffer = java.io.ByteArrayOutputStream()
        var active = false; private set
        fun begin() { buffer = java.io.ByteArrayOutputStream(); active = true }
        fun append(src: ByteArray, from: Int) { if (from < src.size) buffer.write(src, from, src.size - from) }
        fun finish(): ByteArray { active = false; return buffer.toByteArray() }
        fun reset() { active = false; buffer = java.io.ByteArrayOutputStream() }
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
