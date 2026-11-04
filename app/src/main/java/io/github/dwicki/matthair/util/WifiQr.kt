package io.github.dwicki.matthair.util

/** Wi-Fi network credentials as found in a `WIFI:` QR code. */
data class WifiCredentials(val ssid: String, val password: String, val security: String?, val hidden: Boolean) {
    override fun toString() = "WifiCredentials(ssid=$ssid, security=$security, hidden=$hidden, password=<redacted>)"
}

/**
 * Parser for the de-facto standard Wi-Fi QR format (ZXing "WIFI:" / Wi-Fi Alliance Easy Connect
 * fallback), as printed on router stickers and shown by Android under
 * *Settings → Wi-Fi → network → Share*:
 *
 * ```
 * WIFI:T:WPA;S:MyNetwork;P:secret;H:false;;
 * ```
 *
 * Fields may come in any order. `\`, `;`, `,`, `:` and `"` are escaped with a backslash.
 * Android cannot hand Wi-Fi passwords to apps, so this QR code is the easiest way to avoid
 * typing it (see req42 F-2.5).
 */
object WifiQr {
    fun parse(text: String): WifiCredentials? {
        val t = text.trim()
        if (!t.startsWith("WIFI:", ignoreCase = true)) return null
        val fields = HashMap<String, String>()
        var i = 5
        while (i < t.length) {
            val colon = t.indexOf(':', i)
            if (colon < 0) break
            val key = t.substring(i, colon).uppercase()
            val value = StringBuilder()
            var j = colon + 1
            while (j < t.length && t[j] != ';') {
                if (t[j] == '\\' && j + 1 < t.length) { value.append(t[j + 1]); j += 2 } else { value.append(t[j]); j++ }
            }
            if (key.isNotEmpty()) fields[key] = value.toString()
            i = j + 1
            if (i < t.length && t[i] == ';') break // ";;" terminator
        }
        val ssid = fields["S"]?.takeIf { it.isNotEmpty() } ?: return null
        return WifiCredentials(
            ssid = unquote(ssid),
            password = unquote(fields["P"].orEmpty()),
            security = fields["T"]?.takeIf { it.isNotEmpty() },
            hidden = fields["H"].equals("true", ignoreCase = true),
        )
    }

    /** Some generators wrap values in double quotes. */
    private fun unquote(s: String) = if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s.substring(1, s.length - 1) else s
}
