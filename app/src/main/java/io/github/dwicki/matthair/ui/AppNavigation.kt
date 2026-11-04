package io.github.dwicki.matthair.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.ui.screens.AuthScreen
import io.github.dwicki.matthair.ui.screens.CheckScreen
import io.github.dwicki.matthair.ui.screens.DeviceScreen
import io.github.dwicki.matthair.ui.screens.DevicesScreen
import io.github.dwicki.matthair.ui.screens.DoneScreen
import io.github.dwicki.matthair.ui.screens.HomeScreen
import io.github.dwicki.matthair.ui.screens.MultiAdminIntroScreen
import io.github.dwicki.matthair.ui.screens.MultiAdminProgressScreen
import io.github.dwicki.matthair.ui.screens.OAuthWebViewScreen
import io.github.dwicki.matthair.ui.screens.PairingScreen
import io.github.dwicki.matthair.ui.screens.ScanScreen
import io.github.dwicki.matthair.ui.screens.ServerScreen
import io.github.dwicki.matthair.ui.screens.SettingsScreen
import io.github.dwicki.matthair.ui.screens.WelcomeScreen
import io.github.dwicki.matthair.ui.screens.WifiScreen
import io.github.dwicki.matthair.util.Diagnostics

/** Navigation routes. The add-device wizard is `scan → check → pairing → done`. */
internal object Routes {
    const val WELCOME = "welcome"
    const val SERVER = "server"
    const val AUTH = "auth"
    const val OAUTH = "oauth"
    const val HOME = "home"
    const val SCAN = "add/scan"
    const val CHECK = "add/check"
    const val WIFI = "add/wifi"
    const val PAIRING = "add/pairing"
    const val DONE = "add/done"
    const val DEVICES = "devices"
    const val DEVICE = "device/{id}"
    const val SETTINGS = "settings"
    const val MULTI_INTRO = "multi/intro"
    const val MULTI_SCAN = "multi/scan"
    const val MULTI_RUN = "multi/run"

    fun device(id: String) = "device/$id"
}

/** The whole app: navigation graph plus the snackbar for [UiResult]s. */
@Composable
internal fun MatthairApp(vm: MainViewModel, nav: NavHostController) {
    // Computed once: saving the server address must not jump past the sign-in screen.
    val startRoute = remember {
        when {
            vm.config.value == null -> Routes.WELCOME
            !vm.isSignedIn -> Routes.AUTH
            else -> Routes.HOME
        }
    }
    val status by vm.status.collectAsState()
    // The token is missing or was revoked in HA: sign in again.
    LaunchedEffect(status) {
        if (status == HaStatus.SignedOut) nav.navigate(Routes.AUTH) { popUpTo(0) }
    }

    val context = LocalContext.current
    val shareDiagnostics = { shareDiagnostics(vm, context) }

    Box(Modifier.fillMaxSize()) {
        NavHost(
            nav,
            startDestination = startRoute,
            enterTransition = { slideInHorizontally { it / 4 } + fadeIn() },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { slideOutHorizontally { it / 4 } + fadeOut() },
        ) {
            onboarding(vm, nav)
            home(vm, nav, shareDiagnostics)
            addDevice(vm, nav, shareDiagnostics)
            multiAdmin(vm, nav)
        }
        ResultSnackbar(vm, Modifier.align(Alignment.BottomCenter))
    }
}

private fun shareDiagnostics(vm: MainViewModel, context: Context) {
    vm.buildDiagnostics { file -> context.startActivity(Intent.createChooser(Diagnostics.shareIntent(context, file), null)) }
}

@Composable
private fun ResultSnackbar(vm: MainViewModel, modifier: Modifier) {
    val result by vm.result.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val text = when (val r = result) {
        is UiResult.Error -> stringResource(R.string.error_generic, r.message)
        is UiResult.Message -> stringResource(r.resId)
        null -> null
    }
    LaunchedEffect(result) {
        text?.let { snackbar.showSnackbar(it) }
        vm.consumeResult()
    }
    SnackbarHost(snackbar, modifier.navigationBarsPadding().padding(16.dp)) { Snackbar(it) }
}

private fun NavHostController.goHome() = navigate(Routes.HOME) { popUpTo(0) }

private fun NavGraphBuilder.onboarding(vm: MainViewModel, nav: NavHostController) {
    composable(Routes.WELCOME) { WelcomeScreen { nav.navigate(Routes.SERVER) } }
    composable(Routes.SERVER) {
        ServerScreen(vm, onBack = { nav.popBackStack() }) { nav.navigate(Routes.AUTH) }
    }
    composable(Routes.AUTH) {
        AuthScreen(
            vm,
            // AUTH can be the start screen when signed out; "back" then means "change server".
            onBack = { if (!nav.popBackStack()) nav.navigate(Routes.SERVER) },
            onOAuth = { nav.navigate(Routes.OAUTH) },
            onSignedIn = { nav.goHome() },
        )
    }
    composable(Routes.OAUTH) {
        OAuthWebViewScreen(vm, onBack = { nav.popBackStack() }) { nav.goHome() }
    }
}

private fun NavGraphBuilder.home(vm: MainViewModel, nav: NavHostController, shareDiagnostics: () -> Unit) {
    composable(Routes.HOME) {
        HomeScreen(
            vm,
            onAdd = { vm.clearPayload(); nav.navigate(Routes.SCAN) },
            onMultiAdmin = { vm.clearPayload(); nav.navigate(Routes.MULTI_INTRO) },
            onDevices = { nav.navigate(Routes.DEVICES) },
            onOpenDevice = { nav.navigate(Routes.device(it)) },
            onSettings = { nav.navigate(Routes.SETTINGS) },
        )
    }
    composable(Routes.DEVICES) {
        DevicesScreen(vm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(Routes.device(it)) })
    }
    composable(Routes.DEVICE) { entry ->
        DeviceScreen(vm, entry.arguments?.getString("id").orEmpty()) { nav.popBackStack() }
    }
    composable(Routes.SETTINGS) {
        SettingsScreen(
            vm,
            onBack = { nav.popBackStack() },
            onSignedOut = { nav.navigate(Routes.WELCOME) { popUpTo(0) } },
            onShareDiagnostics = shareDiagnostics,
        )
    }
}

private fun NavGraphBuilder.addDevice(vm: MainViewModel, nav: NavHostController, shareDiagnostics: () -> Unit) {
    composable(Routes.SCAN) {
        ScanScreen(
            title = stringResource(R.string.scan_title),
            showSteps = true,
            accept = vm::acceptCode,
            onBack = { nav.popBackStack() },
            onAccepted = { nav.navigate(Routes.CHECK) { popUpTo(Routes.SCAN) { inclusive = true } } },
        )
    }
    composable(Routes.CHECK) {
        CheckScreen(
            vm,
            onBack = { nav.popBackStack(Routes.HOME, false) },
            onStart = { vm.startCommissioning(); nav.navigate(Routes.PAIRING) },
            onWifi = { nav.navigate(Routes.WIFI) },
        )
    }
    composable(Routes.WIFI) { WifiScreen(vm) { nav.popBackStack() } }
    composable(Routes.PAIRING) {
        PairingScreen(
            vm,
            onSuccess = { nav.navigate(Routes.DONE) { popUpTo(Routes.HOME) } },
            onBackToCheck = { vm.cancelCommissioning(); nav.popBackStack() },
            onShareDiagnostics = shareDiagnostics,
        )
    }
    composable(Routes.DONE) { DoneScreen(vm) { nav.popBackStack(Routes.HOME, false) } }
}

/** Adding a device that already belongs to another ecosystem, over the network only. */
private fun NavGraphBuilder.multiAdmin(vm: MainViewModel, nav: NavHostController) {
    composable(Routes.MULTI_INTRO) {
        MultiAdminIntroScreen(onBack = { nav.popBackStack() }) { nav.navigate(Routes.MULTI_SCAN) }
    }
    composable(Routes.MULTI_SCAN) {
        ScanScreen(
            title = stringResource(R.string.multiadmin_title),
            showSteps = false,
            accept = vm::acceptCode,
            onBack = { nav.popBackStack() },
            onAccepted = { nav.navigate(Routes.MULTI_RUN) { popUpTo(Routes.MULTI_INTRO) { inclusive = true } } },
        )
    }
    composable(Routes.MULTI_RUN) { MultiAdminProgressScreen(vm) { nav.popBackStack(Routes.HOME, false) } }
}
