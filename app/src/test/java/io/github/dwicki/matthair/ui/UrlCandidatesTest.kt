package io.github.dwicki.matthair.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlCandidatesTest {

    @Test fun `typed https without port also tries http and 8123`() {
        val c = MainViewModel.urlCandidates("https://192.168.1.11")
        assertEquals("https://192.168.1.11", c.first())
        assertTrue("http://192.168.1.11" in c)
        assertTrue("http://192.168.1.11:8123" in c)
        assertTrue("https://192.168.1.11:8123" in c)
        assertEquals(c.size, c.distinct().size)
    }

    @Test fun `explicit port only flips the scheme`() {
        assertEquals(
            listOf("http://ha.local:8123", "https://ha.local:8123"),
            MainViewModel.urlCandidates("http://ha.local:8123"),
        )
    }

    @Test fun `bare host gets http prefix`() {
        assertEquals("http://homeassistant.local", MainViewModel.normalizeUrl(" homeassistant.local/ "))
    }
}
