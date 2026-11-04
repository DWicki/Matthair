package io.github.dwicki.hamatterpair.matter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MatterBleTest {

    @Test fun `parses advertisement from protocol doc example`() {
        // service_data example from the BLE proxy protocol: {"fff6": "AAAPoff/AYA="}
        val bytes = Base64.getDecoder().decode("AAAPoff/AYA=")
        val adv = MatterAdvertisement.parse(bytes)!!
        assertEquals(0xF00, adv.discriminator)
        assertEquals(0, adv.version)
        assertEquals(0xF7A1, adv.vendorId)
        assertEquals(0x01FF, adv.productId)
        assertFalse(adv.hasAdditionalData)
    }

    @Test fun `rejects non commissionable opcode`() {
        assertNull(MatterAdvertisement.parse(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(MatterAdvertisement.parse(byteArrayOf(0, 0)))
    }

    @Test fun `uuid short form round trip`() {
        assertEquals("fff6", MatterBle.toProtocolString(MatterBle.SERVICE_UUID))
        assertEquals(MatterBle.SERVICE_UUID, MatterBle.parseUuid("FFF6"))
        assertEquals(MatterBle.C1_UUID, MatterBle.parseUuid("18ee2ef5263d4559959f4f9c429f9d11"))
        assertEquals("18EE2EF5-263D-4559-959F-4F9C429F9D11", MatterBle.toProtocolString(MatterBle.C1_UUID))
    }

    @Test fun `btp observer detects PASE success and failure`() {
        val events = mutableListOf<BtpObserver.Event>()
        val obs = BtpObserver { events += it }

        obs.onFrame(BtpObserver.Direction.FROM_DEVICE, byteArrayOf(0x65, 0x6C, 0x05, 0xF4.toByte(), 0x00, 0x05))
        obs.onFrame(BtpObserver.Direction.TO_DEVICE, btpSingle(unsecured(opcode = 0x20)))
        obs.onFrame(BtpObserver.Direction.FROM_DEVICE, btpSingle(unsecured(opcode = 0x40, status = intArrayOf(0, 0))))
        assertEquals(
            listOf(BtpObserver.Event.HandshakeDone, BtpObserver.Event.PaseStarted, BtpObserver.Event.PaseSucceeded),
            events,
        )
        assertTrue(obs.isPaseDone)

        events.clear(); obs.reset()
        obs.onFrame(BtpObserver.Direction.TO_DEVICE, btpSingle(unsecured(opcode = 0x22)))
        obs.onFrame(BtpObserver.Direction.FROM_DEVICE, btpSingle(unsecured(opcode = 0x40, status = intArrayOf(1, 2))))
        assertEquals(BtpObserver.Event.PaseFailed(1, 2), events.last())
    }

    @Test fun `btp observer reassembles multi-frame messages`() {
        val events = mutableListOf<BtpObserver.Event>()
        val obs = BtpObserver { events += it }
        obs.onFrame(BtpObserver.Direction.TO_DEVICE, btpSingle(unsecured(opcode = 0x20)))
        val msg = unsecured(opcode = 0x40, status = intArrayOf(0, 0))
        val first = msg.copyOfRange(0, 6)
        val rest = msg.copyOfRange(6, msg.size)
        obs.onFrame(BtpObserver.Direction.FROM_DEVICE, byteArrayOf(0x01, 0x01, msg.size.toByte(), 0) + first) // BEGIN
        obs.onFrame(BtpObserver.Direction.FROM_DEVICE, byteArrayOf(0x04, 0x02) + rest) // END
        assertTrue(obs.isPaseDone)
    }

    @Test fun `btp observer treats encrypted message as PASE done`() {
        val obs = BtpObserver { }
        val encrypted = byteArrayOf(0x00, 0x34, 0x12, 0x00, 1, 0, 0, 0) + ByteArray(20)
        obs.onFrame(BtpObserver.Direction.TO_DEVICE, btpSingle(encrypted))
        assertTrue(obs.isPaseDone)
    }

    /** BTP data frame with BEGIN|END, sequence 0. */
    private fun btpSingle(payload: ByteArray): ByteArray =
        byteArrayOf(0x05, 0x00, (payload.size and 0xFF).toByte(), (payload.size ushr 8).toByte()) + payload

    /** Minimal unsecured Matter message for the secure channel protocol. */
    private fun unsecured(opcode: Int, status: IntArray? = null): ByteArray {
        val header = byteArrayOf(0x04, 0x00, 0x00, 0x00, 1, 0, 0, 0) + ByteArray(8) // S flag + source node id
        val proto = byteArrayOf(0x05, opcode.toByte(), 0x01, 0x00, 0x00, 0x00) // flags I|R, opcode, xchg, proto 0
        val body = if (status != null) {
            byteArrayOf(status[0].toByte(), 0, 0, 0, 0, 0, status[1].toByte(), 0)
        } else ByteArray(4)
        return header + proto + body
    }
}
