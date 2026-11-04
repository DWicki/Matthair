package io.github.dwicki.matthair.ui

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import io.github.dwicki.matthair.ui.theme.MatthairTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var nav: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MatthairTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    MatthairApp(vm, rememberNavController().also { nav = it })
                }
            }
        }
        handleNfc(intent)
    }

    override fun onResume() {
        super.onResume()
        // Foreground dispatch: while the app is open, every NDEF tag comes to us.
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE,
        )
        runCatching { adapter.enableForegroundDispatch(this, pendingIntent, null, null) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { NfcAdapter.getDefaultAdapter(this)?.disableForegroundDispatch(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfc(intent)
    }

    /** A Matter setup code on an NFC tag (text or URI record containing `MT:`) starts the wizard. */
    private fun handleNfc(intent: Intent?) {
        val text = intent?.ndefMessages()
            ?.flatMap { it.records.toList() }
            ?.mapNotNull { record -> record.toUri()?.toString() ?: runCatching { String(record.payload, Charsets.UTF_8) }.getOrNull() }
            ?.firstOrNull { it.contains("MT:", ignoreCase = true) }
            ?: return
        if (vm.acceptCode(text) == null && vm.config.value != null) {
            nav?.navigate(Routes.CHECK) { launchSingleTop = true }
        }
    }

    private fun Intent.ndefMessages(): List<NdefMessage>? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.toList()
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()
        }
}
