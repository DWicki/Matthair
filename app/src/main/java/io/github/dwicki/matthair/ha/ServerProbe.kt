package io.github.dwicki.matthair.ha

import io.github.dwicki.matthair.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Finds the address under which Home Assistant really answers.
 *
 * Typing "https://<ip>" for a plain-HTTP HA on port 8123 is a very common mix-up, so if the
 * address as typed does not work, the usual variants (http/https, port 8123) are tried too.
 */
object ServerProbe {

    sealed interface Result {
        data class Found(val url: String, val untrustedCertSha256: String?) : Result
        data class NotFound(val tried: List<String>) : Result
    }

    suspend fun find(typed: String): Result = withContext(Dispatchers.IO) {
        val candidates = urlCandidates(normalizeUrl(typed))
        for (url in candidates) {
            when (val probe = probe(url)) {
                is Probe.Ok -> return@withContext Result.Found(url, null)
                is Probe.UntrustedCert -> return@withContext Result.Found(url, probe.sha256)
                Probe.NotHa -> Unit
            }
        }
        Result.NotFound(candidates)
    }

    /** The typed URL first, then the http/https × (default port, 8123) variants. */
    fun urlCandidates(typed: String): List<String> {
        val uri = URI(typed)
        val host = uri.host ?: return listOf(typed)
        val hostPart = if (':' in host) "[$host]" else host
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return buildList {
            add(typed)
            if (uri.port == -1) {
                for (scheme in listOf("http", "https")) {
                    add("$scheme://$hostPart:8123$path")
                    add("$scheme://$hostPart$path")
                }
            } else {
                val otherScheme = if (uri.scheme.equals("https", ignoreCase = true)) "http" else "https"
                add("$otherScheme://$hostPart:${uri.port}$path")
            }
        }.distinct()
    }

    /** Trims the input and adds `http://` if no scheme was typed. */
    fun normalizeUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        val hasScheme = trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)
        return if (hasScheme) trimmed else "http://$trimmed"
    }

    fun isValidUrl(input: String): Boolean = runCatching { !URI(normalizeUrl(input)).host.isNullOrBlank() }.getOrDefault(false)

    private sealed interface Probe {
        data object Ok : Probe
        data class UntrustedCert(val sha256: String) : Probe
        data object NotHa : Probe
    }

    /** HA's `/manifest.json` names itself "Home Assistant", which tells it apart from other web servers. */
    private fun probe(url: String): Probe {
        val client = HttpClients.create(null).newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        return try {
            client.newCall(Request.Builder().url("$url/manifest.json").build()).execute().use { response ->
                val isHa = response.isSuccessful && response.body?.string().orEmpty().contains("Home Assistant")
                if (isHa) Probe.Ok else Probe.NotHa
            }
        } catch (e: SSLException) {
            val uri = URI(url)
            val certificate = HttpClients.fetchServerCertificate(uri.host, if (uri.port > 0) uri.port else 443)
            certificate?.let { Probe.UntrustedCert(HttpClients.sha256(it)) } ?: Probe.NotHa
        } catch (e: Exception) {
            AppLog.i("ServerProbe", "Probe $url failed: ${e.javaClass.simpleName}")
            Probe.NotHa
        }
    }
}
