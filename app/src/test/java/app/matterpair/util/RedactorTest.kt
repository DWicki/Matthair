package app.matterpair.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostics must never leak credentials or setup codes (req42 Q-2, F-6.5). */
class RedactorTest {

    @Test fun `removes JWT access tokens`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiIxMjM0NTY3OCJ9.c2lnbmF0dXJlX3NpZ25hdHVyZQ"
        val out = Redactor.redact("auth with $jwt failed")
        assertFalse(out.contains(jwt))
    }

    @Test fun `removes bearer headers and json token fields`() {
        assertFalse(Redactor.redact("Authorization: Bearer abc.def-123").contains("abc.def-123"))
        assertFalse(Redactor.redact("""{"access_token":"s3cr3tvalue","x":1}""").contains("s3cr3tvalue"))
        assertFalse(Redactor.redact("Cookie: ingress_session=deadbeefcafe").contains("deadbeefcafe"))
    }

    @Test fun `removes QR payloads and manual codes`() {
        assertFalse(Redactor.redact("code MT:Y.K9042C00KA0648G00 scanned").contains("Y.K9042C00KA0648G00"))
        assertFalse(Redactor.redact("manual 3497-011-2332").contains("3497-011-2332"))
        assertFalse(Redactor.redact("manual 34970112332").contains("34970112332"))
    }

    @Test fun `removes registered secrets`() {
        val out = Redactor.redact("passcode is 20202021", setOf("20202021"))
        assertTrue(out.contains("<secret>"))
    }

    @Test fun `keeps harmless content`() {
        val line = "Connected (attempt 2), mtu=247, rssi -61 dBm"
        assertTrue(Redactor.redact(line) == line)
    }
}
