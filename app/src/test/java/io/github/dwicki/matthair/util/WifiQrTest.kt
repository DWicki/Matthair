package io.github.dwicki.matthair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrTest {

    @Test fun `parses Android share QR`() {
        val c = WifiQr.parse("WIFI:S:MyHome;T:WPA;P:s3cret pass;H:false;;")!!
        assertEquals("MyHome", c.ssid)
        assertEquals("s3cret pass", c.password)
        assertEquals("WPA", c.security)
        assertFalse(c.hidden)
    }

    @Test fun `handles escapes and any field order`() {
        val c = WifiQr.parse("""WIFI:T:WPA;P:a\;b\:c\\d;S:Net\,1;H:true;;""")!!
        assertEquals("Net,1", c.ssid)
        assertEquals("""a;b:c\d""", c.password)
        assertTrue(c.hidden)
    }

    @Test fun `open network without password`() {
        val c = WifiQr.parse("WIFI:S:Guest;T:nopass;P:;;")!!
        assertEquals("Guest", c.ssid)
        assertEquals("", c.password)
    }

    @Test fun `strips quotes`() {
        assertEquals("Home", WifiQr.parse("WIFI:S:\"Home\";P:\"pw\";;")!!.ssid)
    }

    @Test fun `rejects other content`() {
        assertNull(WifiQr.parse("MT:Y.K9042C00KA0648G00"))
        assertNull(WifiQr.parse("WIFI:T:WPA;P:x;;"))
    }

    @Test fun `password not in toString`() {
        assertFalse(WifiQr.parse("WIFI:S:a;P:topsecret;;").toString().contains("topsecret"))
    }
}
