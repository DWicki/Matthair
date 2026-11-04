package io.github.dwicki.hamatterpair.matter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SetupPayloadParserTest {

    // Default test payload of the Matter SDK example apps:
    // VID 0xFFF1, PID 0x8000, discriminator 3840, passcode 20202021, BLE discovery.
    private val sdkQr = "MT:Y.K9042C00KA0648G00"
    private val sdkManual = "34970112332"

    @Test fun `parses SDK example QR code`() {
        val p = SetupPayloadParser.parse(sdkQr)
        assertEquals(SetupPayload.Source.QR, p.source)
        assertEquals(0xFFF1, p.vendorId)
        assertEquals(0x8000, p.productId)
        assertEquals(3840, p.longDiscriminator)
        assertEquals(15, p.shortDiscriminator)
        assertEquals(20202021L, p.passcode)
        assertEquals(CommissioningFlow.STANDARD, p.flow)
        assertTrue(p.advertisesBle)
    }

    @Test fun `parses SDK example manual code`() {
        val p = SetupPayloadParser.parse(sdkManual)
        assertEquals(SetupPayload.Source.MANUAL_11, p.source)
        assertEquals(15, p.shortDiscriminator)
        assertNull(p.longDiscriminator)
        assertEquals(20202021L, p.passcode)
        assertNull(p.vendorId)
    }

    @Test fun `tolerates dashes and spaces in manual code`() {
        val p = SetupPayloadParser.parse(" 3497-011 2332 ")
        assertEquals(20202021L, p.passcode)
        assertEquals("34970112332", p.rawCode)
    }

    @Test fun `rejects wrong check digit`() {
        expectError(PayloadError.BAD_CHECK_DIGIT) { SetupPayloadParser.parse("34970112333") }
    }

    @Test fun `rejects wrong length`() {
        expectError(PayloadError.BAD_LENGTH) { SetupPayloadParser.parse("3497011233") }
    }

    @Test fun `rejects letters in manual code`() {
        expectError(PayloadError.NOT_DIGITS) { SetupPayloadParser.parse("3497O112332") }
    }

    @Test fun `round-trips generated QR payloads`() {
        val p = SetupPayloadParser.parse(buildQr(vid = 0x1234, pid = 0xABCD, flow = 2, caps = 0x06, disc = 0x5A5, passcode = 12348765))
        assertEquals(0x1234, p.vendorId)
        assertEquals(0xABCD, p.productId)
        assertEquals(CommissioningFlow.CUSTOM, p.flow)
        assertEquals(0x5A5, p.longDiscriminator)
        assertEquals(0x5, p.shortDiscriminator)
        assertEquals(12348765L, p.passcode)
        assertTrue(p.advertisesBle)
        assertTrue(p.advertisesOnNetwork)
    }

    @Test fun `generated manual codes match QR fields`() {
        for (disc in listOf(0, 0x0FF, 0x100, 0xF00, 0xFFF)) {
            for (pass in listOf(1L, 20202021L, 99_999_998L, 16383L, 16384L)) {
                val digits = buildManual(disc, pass)
                val p = SetupPayloadParser.parse(digits)
                assertEquals("disc=$disc pass=$pass", disc ushr 8, p.shortDiscriminator)
                assertEquals(pass, p.passcode)
            }
        }
    }

    @Test fun `parses 21 digit manual code with vendor and product`() {
        val digits = buildManual(0xF00, 20202021L, vid = 65521, pid = 32768)
        assertEquals(21, digits.length)
        val p = SetupPayloadParser.parse(digits)
        assertEquals(SetupPayload.Source.MANUAL_21, p.source)
        assertEquals(65521, p.vendorId)
        assertEquals(32768, p.productId)
    }

    @Test fun `rejects forbidden passcodes`() {
        for (bad in listOf(11111111L, 12345678L, 87654321L, 99999999L)) {
            assertFalse(SetupPayloadParser.isValidPasscode(bad))
        }
        expectError(PayloadError.INVALID_PASSCODE) {
            SetupPayloadParser.parse(buildQr(passcode = 12345678))
        }
    }

    @Test fun `extracts MT payload from a URI`() {
        val p = SetupPayloadParser.parse("mt:Y.K9042C00KA0648G00")
        assertEquals(3840, p.longDiscriminator)
    }

    @Test fun `matches discriminators correctly`() {
        val qr = SetupPayloadParser.parse(sdkQr)
        assertTrue(qr.matchesDiscriminator(3840))
        assertFalse(qr.matchesDiscriminator(3841))
        val manual = SetupPayloadParser.parse(sdkManual)
        assertTrue(manual.matchesDiscriminator(3840))
        assertTrue(manual.matchesDiscriminator(3841)) // only upper 4 bits are known
        assertFalse(manual.matchesDiscriminator(0x0E00))
    }

    @Test fun `masks passcode in toString`() {
        val p = SetupPayloadParser.parse(sdkQr)
        assertFalse(p.toString().contains("20202021"))
        assertEquals("••••2021", p.maskedPasscode())
    }

    @Test fun `formats manual codes like labels`() {
        assertEquals("3497-011-2332", SetupPayloadParser.formatManual(sdkManual))
    }

    @Test fun `base38 round trip`() {
        val data = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, -1, -128)
        assertTrue(data.contentEquals(Base38.decode(Base38.encode(data))))
    }

    @Test fun `verhoeff known value`() {
        // Classic Verhoeff example: 236 → check digit 3.
        assertEquals(3, Verhoeff.compute("236"))
        assertTrue(Verhoeff.validate("2363"))
    }

    // --- helpers that build payloads the same way a device manufacturer would ---------------

    private fun buildQr(vid: Int = 0xFFF1, pid: Int = 0x8000, flow: Int = 0, caps: Int = 2, disc: Int = 3840, passcode: Long = 20202021): String {
        val fields = listOf(0L to 3, vid.toLong() to 16, pid.toLong() to 16, flow.toLong() to 2, caps.toLong() to 8, disc.toLong() to 12, passcode to 27, 0L to 4)
        val bytes = ByteArray(11)
        var pos = 0
        for ((value, bits) in fields) {
            for (i in 0 until bits) {
                if ((value ushr i) and 1L == 1L) bytes[pos / 8] = (bytes[pos / 8].toInt() or (1 shl (pos % 8))).toByte()
                pos++
            }
        }
        return "MT:" + Base38.encode(bytes)
    }

    private fun buildManual(disc: Int, passcode: Long, vid: Int? = null, pid: Int? = null): String {
        val vidPid = vid != null
        val c1 = ((if (vidPid) 1 else 0) shl 2) or (disc ushr 10)
        val c2 = (((disc ushr 8) and 0b11) shl 14) or (passcode and 0x3FFF).toInt()
        val c3 = (passcode ushr 14).toInt()
        val sb = StringBuilder()
        sb.append(c1).append(c2.toString().padStart(5, '0')).append(c3.toString().padStart(4, '0'))
        if (vidPid) sb.append(vid.toString().padStart(5, '0')).append(pid.toString().padStart(5, '0'))
        sb.append(Verhoeff.compute(sb.toString()))
        return sb.toString()
    }

    private fun expectError(expected: PayloadError, block: () -> Unit) {
        try {
            block()
            fail("Expected $expected")
        } catch (e: PayloadException) {
            assertEquals(expected, e.error)
        }
    }
}
