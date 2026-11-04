package io.github.dwicki.matthair.ha

import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds OkHttp clients for talking to Home Assistant.
 *
 * Trust model:
 *  1. The platform trust store, which on Android includes user-installed CAs because of
 *     `network_security_config.xml` (the recommended way for a private CA).
 *  2. Optionally one pinned leaf certificate (SHA-256 of the DER encoding) that the user
 *     explicitly accepted in the app — for self-signed certificates without a CA. When the pin
 *     matches, hostname verification is skipped for that one certificate, because self-signed
 *     certificates rarely carry the right SAN for every way HA is reached (IP, .local, ...).
 */
object HttpClients {

    fun create(pinnedSha256: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // WebSockets are long-lived; commands use their own timeouts.
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (pinnedSha256 != null) {
            val tm = PinningTrustManager(platformTrustManager(), pinnedSha256)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
            builder.sslSocketFactory(ctx.socketFactory, tm)
            val defaultVerifier = okhttp3.internal.tls.OkHostnameVerifier
            builder.hostnameVerifier { host, session ->
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                (leaf != null && sha256(leaf).equals(pinnedSha256, ignoreCase = true)) ||
                    defaultVerifier.verify(host, session)
            }
        }
        return builder.build()
    }

    /**
     * Connects once with a trust-everything manager purely to *read* the server certificate, so
     * the user can inspect its fingerprint and decide whether to trust it. No data is sent.
     */
    fun fetchServerCertificate(host: String, port: Int): X509Certificate? {
        var captured: X509Certificate? = null
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                captured = chain?.firstOrNull()
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        return runCatching {
            (ctx.socketFactory.createSocket(host, port) as SSLSocket).use { s ->
                s.soTimeout = 5000
                s.startHandshake()
            }
            captured
        }.getOrNull() ?: captured
    }

    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    /** Human-friendly fingerprint: `AB:CD:...`. */
    fun prettyFingerprint(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

    private fun platformTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class PinningTrustManager(
        private val delegate: X509TrustManager,
        private val pin: String,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Empty chain")
            if (sha256(leaf).equals(pin, ignoreCase = true)) return
            delegate.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }
}
