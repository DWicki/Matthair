package app.matterpair.ui

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.matterpair.R
import app.matterpair.ui.screens.AuthScreen
import app.matterpair.ui.screens.CheckScreen
import app.matterpair.ui.screens.DevicesScreen
import app.matterpair.ui.screens.DoneScreen
import app.matterpair.ui.screens.HomeScreen
import app.matterpair.ui.screens.MultiAdminIntroScreen
import app.matterpair.ui.screens.MultiAdminProgressScreen
import app.matterpair.ui.screens.OAuthWebViewScreen
import app.matterpair.ui.screens.PairingScreen
import app.matterpair.ui.screens.ScanScreen
import app.matterpair.ui.screens.ServerScreen
import app.matterpair.ui.screens.SettingsScreen
import app.matterpair.ui.screens.WelcomeScreen
import app.matterpair.ui.theme.MatterPairTheme
import app.matterpair.util.Diagnostics

/** Navigation routes. The add-device wizard is `scan → check → pairing → done`. */
private object Routes {
    const val WELCOME = "welcome"
    const val SERVER = "server"
    const val AUTH = "auth"
    const val OAUTH = "oauth"
    const val HOME = "home"
    const val SCAN = "add/scan"
    const val CHECK = "add/check"
    const val PAIRING = "add/pairing"
    const val DONE = "add/done"
    const val DEVICES = "devices"
    const val SETTINGS = "settings"
    const val MULTI_INTRO = "multi/intro"
    const val MULTI_SCAN = "multi/scan"
    const val MULTI_RUN = "multi/run"
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var nav: NavHostController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MatterPairTheme {
                Surface(color = MaterialTheme.colorScheme.background) { App() }
            }
        }
        handleNfc(intent)
    }

    override fun onResume() {
        super.onResume()
        // Foreground dispatch: while the app is open, any NDEF tag comes to us.
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE,
        )
        runCatching { adapter.enableForegroundDispatch(this, pi, null, null) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { NfcAdapter.getDefaultAdapter(this)?.disableForegroundDispatch(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfc(intent)
    }

    /** Matter setup payload on an NFC tag (text or URI record containing `MT:`). */
    private fun handleNfc(intent: Intent?) {
        if (intent == null) return
        val messages = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()?.toTypedArray()
        } ?: return
        val text = messages.flatMap { it.records.toList() }
            .mapNotNull { r -> r.toUri()?.toString() ?: runCatching { String(r.payload, Charsets.UTF_8) }.getOrNull() }
            .firstOrNull { it.contains("MT:", ignoreCase = true) } ?: return
        if (vm.acceptCode(text) == null && vm.config.value != null) {
            nav?.navigate(Routes.CHECK) { launchSingleTop = true }
        }
    }

    @androidx.compose.runtime.Composable
    private fun App() {
        val navController = rememberNavController().also { nav = it }
        val config by vm.config.collectAsState()
        val result by vm.result.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        val ctx = LocalContext.current
        val errorText = (result as? UiResult.Error)?.message?.let { stringResource(R.string.error_generic, it) }
        val messageText = (result as? UiResult.Message)?.let { stringResource(it.resId) }
        LaunchedEffect(result) {
            (errorText ?: messageText)?.let { snackbar.showSnackbar(it) }
            vm.consumeResult()
        }
        val shareDiagnostics: () -> Unit = {
            vm.buildDiagnostics { f -> ctx.startActivity(Intent.createChooser(Diagnostics.shareIntent(ctx, f), null)) }
        }

        Box(Modifier.fillMaxSize()) {
            NavHost(
                navController,
                startDestination = if (config == null) Routes.WELCOME else Routes.HOME,
                enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
                exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() },
                popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
            ) {
                composable(Routes.WELCOME) { WelcomeScreen { navController.navigate(Routes.SERVER) } }
                composable(Routes.SERVER) {
                    ServerScreen(vm, onBack = { navController.popBackStack() }) { navController.navigate(Routes.AUTH) }
                }
                composable(Routes.AUTH) {
                    AuthScreen(
                        vm, onBack = { navController.popBackStack() },
                        onOAuth = { navController.navigate(Routes.OAUTH) },
                        onSignedIn = { navController.goHome() },
                    )
                }
                composable(Routes.OAUTH) {
                    OAuthWebViewScreen(vm, onBack = { navController.popBackStack() }) { navController.goHome() }
                }
                composable(Routes.HOME) {
                    HomeScreen(
                        vm,
                        onAdd = { vm.clearPayload(); navController.navigate(Routes.SCAN) },
                        onMultiAdmin = { vm.clearPayload(); navController.navigate(Routes.MULTI_INTRO) },
                        onDevices = { navController.navigate(Routes.DEVICES) },
                        onSettings = { navController.navigate(Routes.SETTINGS) },
                    )
                }
                composable(Routes.SCAN) {
                    ScanScreen(
                        title = stringResource(R.string.scan_title), showSteps = true,
                        accept = vm::acceptCode,
                        onBack = { navController.popBackStack() },
                        onAccepted = { navController.navigate(Routes.CHECK) { popUpTo(Routes.SCAN) { inclusive = true } } },
                    )
                }
                composable(Routes.CHECK) {
                    CheckScreen(
                        vm,
                        onBack = { navController.popBackStack(Routes.HOME, false) },
                        onStart = { vm.startCommissioning(); navController.navigate(Routes.PAIRING) },
                    )
                }
                composable(Routes.PAIRING) {
                    PairingScreen(
                        vm,
                        onSuccess = { navController.navigate(Routes.DONE) { popUpTo(Routes.HOME) } },
                        onBackToCheck = { vm.cancelCommissioning(); navController.popBackStack() },
                        onShareDiagnostics = shareDiagnostics,
                    )
                }
                composable(Routes.DONE) { DoneScreen(vm) { navController.popBackStack(Routes.HOME, false) } }
                composable(Routes.DEVICES) { DevicesScreen(vm) { navController.popBackStack() } }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        vm, onBack = { navController.popBackStack() },
                        onSignedOut = { navController.navigate(Routes.WELCOME) { popUpTo(0) } },
                        onShareDiagnostics = shareDiagnostics,
                    )
                }
                composable(Routes.MULTI_INTRO) {
                    MultiAdminIntroScreen(onBack = { navController.popBackStack() }) { navController.navigate(Routes.MULTI_SCAN) }
                }
                composable(Routes.MULTI_SCAN) {
                    ScanScreen(
                        title = stringResource(R.string.multiadmin_title), showSteps = false,
                        accept = vm::acceptCode,
                        onBack = { navController.popBackStack() },
                        onAccepted = { navController.navigate(Routes.MULTI_RUN) { popUpTo(Routes.MULTI_INTRO) { inclusive = true } } },
                    )
                }
                composable(Routes.MULTI_RUN) { MultiAdminProgressScreen(vm) { navController.popBackStack(Routes.HOME, false) } }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) { Snackbar(it) }
        }
    }

    private fun NavHostController.goHome() = navigate(Routes.HOME) { popUpTo(0) }
}
