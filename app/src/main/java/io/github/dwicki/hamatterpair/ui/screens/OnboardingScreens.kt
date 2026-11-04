package io.github.dwicki.hamatterpair.ui.screens

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.dwicki.hamatterpair.R
import io.github.dwicki.hamatterpair.ha.HaAuth
import io.github.dwicki.hamatterpair.ha.HttpClients
import io.github.dwicki.hamatterpair.ui.MainViewModel
import io.github.dwicki.hamatterpair.ui.components.Banner
import io.github.dwicki.hamatterpair.ui.components.BannerKind
import io.github.dwicki.hamatterpair.ui.components.HeroIcon
import io.github.dwicki.hamatterpair.ui.components.PrimaryButton
import java.util.UUID

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        HeroIcon(Icons.Rounded.Hub, size = 160.dp, pulse = true)
        Spacer(Modifier.height(32.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Point(Icons.Rounded.CloudOff, stringResource(R.string.welcome_point_private))
        Point(Icons.Rounded.Lock, stringResource(R.string.welcome_point_secure))
        Point(Icons.Rounded.Home, stringResource(R.string.welcome_point_ha))
        Spacer(Modifier.weight(1f).height(32.dp))
        PrimaryButton(stringResource(R.string.welcome_start), onStart)
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(vm: MainViewModel, onBack: () -> Unit, onReady: () -> Unit) {
    val existing by vm.config.collectAsState()
    var local by rememberSaveable { mutableStateOf(existing?.localUrl ?: "") }
    var external by rememberSaveable { mutableStateOf(existing?.externalUrl ?: "") }
    var touched by rememberSaveable { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()
    val cert by vm.certPrompt.collectAsState()
    val problem by vm.serverProblem.collectAsState()
    val valid = MainViewModel.isValidUrl(local)

    Scaffold(topBar = {
        TopAppBar(title = {}, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 24.dp).fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.server_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                local, { local = it; touched = true; vm.clearServerProblem() },
                label = { Text(stringResource(R.string.server_local_label)) },
                placeholder = { Text(stringResource(R.string.server_local_hint)) },
                singleLine = true,
                isError = touched && local.isNotBlank() && !valid,
                supportingText = { if (touched && local.isNotBlank() && !valid) Text(stringResource(R.string.server_invalid_url)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                external, { external = it },
                label = { Text(stringResource(R.string.server_external_label)) },
                supportingText = { Text(stringResource(R.string.server_external_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            (problem as? MainViewModel.ServerProblem.Unreachable)?.let { p ->
                Spacer(Modifier.height(16.dp))
                Banner(stringResource(R.string.server_unreachable, p.tried.drop(1).joinToString("\n")), BannerKind.ERROR)
            }
            if (valid && MainViewModel.normalizeUrl(local).startsWith("http://")) {
                Spacer(Modifier.height(16.dp))
                Banner(stringResource(R.string.server_http_warning), BannerKind.INFO)
            }
            Spacer(Modifier.weight(1f).height(24.dp))
            PrimaryButton(
                if (busy) stringResource(R.string.auth_checking) else stringResource(R.string.action_continue),
                { vm.submitServer(local, external, onReady) },
                enabled = valid && !busy,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    cert?.let { prompt ->
        AlertDialog(
            onDismissRequest = vm::dismissCert,
            icon = { Icon(Icons.Rounded.Lock, null) },
            title = { Text(stringResource(R.string.cert_title)) },
            text = { Text(stringResource(R.string.cert_body, HttpClients.prettyFingerprint(prompt.sha256))) },
            confirmButton = { TextButton({ vm.trustCertificate(onReady) }) { Text(stringResource(R.string.cert_trust)) } },
            dismissButton = { TextButton(vm::dismissCert) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(vm: MainViewModel, onBack: () -> Unit, onOAuth: () -> Unit, onSignedIn: () -> Unit) {
    var showToken by rememberSaveable { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsState()
    val corrected by vm.correctedUrl.collectAsState()

    Scaffold(topBar = {
        TopAppBar(title = {}, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 24.dp).fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.auth_title), style = MaterialTheme.typography.headlineMedium)
            Text(vm.config.collectAsState().value?.localUrl.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            corrected?.let { Spacer(Modifier.height(12.dp)); Banner(stringResource(R.string.server_corrected, it)) }
            Spacer(Modifier.height(24.dp))
            AuthOption(Icons.Rounded.Login, stringResource(R.string.auth_oauth), stringResource(R.string.auth_oauth_desc), highlighted = true, onClick = onOAuth)
            Spacer(Modifier.height(12.dp))
            AuthOption(Icons.Rounded.Key, stringResource(R.string.auth_token), stringResource(R.string.auth_token_desc), highlighted = false) { showToken = true }
            if (showToken) {
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    token, { token = it },
                    label = { Text(stringResource(R.string.auth_token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    if (busy) stringResource(R.string.auth_checking) else stringResource(R.string.action_continue),
                    { vm.useToken(token) { ok -> if (ok) onSignedIn() } },
                    enabled = token.length > 20 && !busy,
                )
            }
            Spacer(Modifier.height(16.dp))
            Banner(stringResource(R.string.auth_admin_required))
        }
    }
}

@Composable
private fun AuthOption(icon: ImageVector, title: String, desc: String, highlighted: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Hosts Home Assistant's own login page. When HA redirects to our loopback redirect URI we grab
 * the `code` and stop: the redirect is never actually loaded, so nothing listens on 127.0.0.1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OAuthWebViewScreen(vm: MainViewModel, onBack: () -> Unit, onSignedIn: () -> Unit) {
    val state = remember { UUID.randomUUID().toString() }
    val url = remember { vm.authorizeUrl(state) }
    val pin = vm.config.collectAsState().value?.pinnedCertSha256
    val busy by vm.busy.collectAsState()

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.auth_oauth)) }, navigationIcon = {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
        })
    }) { pad ->
        if (busy) {
            Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.auth_checking))
            }
        } else {
            AndroidView(
                modifier = Modifier.padding(pad).fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url
                                if (u.toString().startsWith(HaAuth.REDIRECT_URI)) {
                                    val code = u.getQueryParameter("code")
                                    if (code != null && u.getQueryParameter("state") == state) {
                                        vm.completeOAuth(code) { ok -> if (ok) onSignedIn() }
                                    }
                                    return true
                                }
                                return false
                            }

                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                // Only accept the exact certificate the user pinned earlier.
                                val x509 = error.certificate.x509Certificate
                                if (pin != null && x509 != null && HttpClients.sha256(x509).equals(pin, true)) handler.proceed()
                                else handler.cancel()
                            }
                        }
                        loadUrl(url)
                    }
                },
            )
        }
    }
}
