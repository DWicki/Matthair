package io.github.dwicki.hamatterpair.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.github.dwicki.hamatterpair.BuildConfig
import io.github.dwicki.hamatterpair.ha.HaConnector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a plain-text diagnostic bundle: app version, device info, the app's redacted log and
 * the tail of the Matter Server app log (also redacted). The user decides where to share it;
 * the app itself never uploads anything.
 */
object Diagnostics {

    suspend fun buildBundle(context: Context, connector: HaConnector): File {
        val sb = StringBuilder()
        sb.appendLine("MatterPair diagnostics")
        sb.appendLine("Created: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())}")
        sb.appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ROM: ${Build.DISPLAY}")
        sb.appendLine()

        val server = runCatching {
            val session = connector.session()
            val addon = session.api.findMatterAddon()
            sb.appendLine("Home Assistant: ${session.api.ws.haVersion}, TLS=${session.isTls}")
            sb.appendLine("Matter Server app: ${addon?.slug} ${addon?.version} state=${addon?.state} ble_proxy=${addon?.bleProxyEnabled}")
            addon?.let { session.api.addonLog(it.slug) }
        }.getOrElse { "Could not fetch Matter Server log: ${it.message}" }

        sb.appendLine()
        sb.appendLine("===== App log =====")
        AppLog.snapshot().forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("===== Matter Server log (tail) =====")
        sb.appendLine(Redactor.redact(server.orEmpty()))

        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "matterpair-diagnostics.txt")
        file.writeText(sb.toString())
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
