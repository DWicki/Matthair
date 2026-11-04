package io.github.dwicki.hamatterpair.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer log. Everything that enters it is redacted first, so the buffer can be
 * exported as a diagnostic bundle without leaking tokens or setup codes. Nothing is written to
 * disk unless the user explicitly exports a bundle.
 */
object AppLog {
    private const val MAX_LINES = 4000
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    /** Extra values (the active passcode, the active code string) to scrub from every line. */
    @Volatile private var secrets: Set<String> = emptySet()

    fun addSecret(value: String) { if (value.length >= 4) secrets = secrets + value }
    fun removeSecret(value: String) { secrets = secrets - value }

    fun d(tag: String, msg: String) = add("D", tag, msg, null)
    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        var text = Redactor.redact(msg, secrets)
        if (t != null) text += " | ${t.javaClass.simpleName}: ${Redactor.redact(t.message ?: "", secrets)}"
        when (level) {
            "E" -> Log.e(tag, text)
            "W" -> Log.w(tag, text)
            "I" -> Log.i(tag, text)
            else -> Log.d(tag, text)
        }
        val line = "${time.format(Date())} $level/$tag: $text"
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }
}

/** Scrubs credentials and Matter setup codes from free text. */
object Redactor {
    private val patterns = listOf(
        // JWT-like access/refresh tokens (HA long-lived tokens are JWTs).
        Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}") to "<token>",
        // HA refresh tokens are long hex strings.
        Regex("\\b[0-9a-f]{64,}\\b") to "<token>",
        // Bearer headers and access_token fields.
        Regex("(?i)(bearer\\s+)[A-Za-z0-9._-]+") to "$1<token>",
        Regex("(?i)(\"?(access_token|refresh_token|token|password|session)\"?\\s*[:=]\\s*\"?)[^\",\\s}]+") to "$1<redacted>",
        Regex("(?i)(ingress_session=)[^;\\s]+") to "$1<redacted>",
        // Matter QR payloads.
        Regex("(?i)MT:[0-9A-Z.\\-]{8,}") to "MT:<redacted>",
        // Manual pairing codes (11 or 21 digits, optionally grouped with dashes/spaces).
        Regex("\\b\\d{4}[- ]?\\d{3}[- ]?\\d{4}(?:[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d)?\\b") to "<setup-code>",
        // Passcode / pin fields in JSON.
        Regex("(?i)(\"(setup_pin_code|pin|passcode|setup_manual_code|setup_qr_code|code)\"\\s*:\\s*\"?)[^\",}]+") to "$1<redacted>",
    )

    fun redact(text: String, extraSecrets: Set<String> = emptySet()): String {
        var out = text
        for (s in extraSecrets) out = out.replace(s, "<secret>")
        for ((re, repl) in patterns) out = re.replace(out, repl)
        return out
    }
}
