package app.matterpair.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.matterpair.R
import app.matterpair.ui.MainViewModel
import app.matterpair.ui.QrCodes
import app.matterpair.ui.components.Banner
import app.matterpair.ui.components.BannerKind
import app.matterpair.ui.components.PrimaryButton
import app.matterpair.util.ConnectedWifi
import app.matterpair.util.CurrentWifi
import app.matterpair.util.WifiQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hand Wi-Fi credentials to Home Assistant's Matter server (only needed for Wi-Fi devices, and
 * only once). Android never lets apps read saved Wi-Fi passwords, so the app offers the two
 * next-best things: fill in the connected network's name, and scan a `WIFI:` QR code
 * (router sticker, or Android's own *Share* QR shown on another phone) to get name + password.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(vm: MainViewModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<ConnectedWifi?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var fromQr by remember { mutableStateOf(false) }
    var noQr by remember { mutableStateOf(false) }

    suspend fun fillCurrent() {
        CurrentWifi.read(ctx)?.let { c ->
            current = c
            if (ssid.isBlank()) ssid = c.ssid
        }
    }

    fun applyQr(text: String): Boolean {
        val c = WifiQr.parse(text) ?: return false
        ssid = c.ssid
        password = c.password
        fromQr = true
        scanning = false
        noQr = false
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        return true
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { fillCurrent() }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    QrCodes.decode(bmp)
                }.getOrNull()
            }
            if (text == null || !applyQr(text)) noQr = true
        }
    }
    // Pre-fill silently if the permission was granted before; never prompt on open.
    LaunchedEffect(Unit) { fillCurrent() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.wifi_title)) },
            navigationIcon = { IconButton(onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Text(stringResource(R.string.wifi_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wifi_only_for_wifi), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            // --- Option 1: Wi-Fi QR code ------------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    {
                        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                        if (granted) scanning = !scanning else cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    Modifier.weight(1f).height(52.dp),
                ) { Icon(Icons.Rounded.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_scan_qr)) }
                FilledTonalButton(
                    { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    Modifier.weight(1f).height(52.dp),
                ) { Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_from_photo)) }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wifi_qr_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(scanning) {
                Box(
                    Modifier.padding(top = 12.dp).fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCameraPreview(accept = { it.startsWith("WIFI:", ignoreCase = true) }, onCode = { applyQr(it) })
                    Box(Modifier.fillMaxSize(0.7f).border(BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)), RoundedCornerShape(28.dp)))
                }
            }
            if (noQr) { Spacer(Modifier.height(12.dp)); Banner(stringResource(R.string.wifi_no_qr), BannerKind.WARN) }
            if (fromQr) { Spacer(Modifier.height(12.dp)); Banner(stringResource(R.string.wifi_from_qr)) }

            Spacer(Modifier.height(24.dp))

            // --- Option 2: type it, with the network name pre-filled -------------------------
            OutlinedTextField(
                ssid, { ssid = it },
                label = { Text(stringResource(R.string.wifi_ssid)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    if (current == null && !CurrentWifi.hasPermission(ctx)) Text(stringResource(R.string.wifi_fill_hint))
                },
                trailingIcon = {
                    IconButton({
                        if (CurrentWifi.hasPermission(ctx)) scope.launch { current = null; ssid = ""; fillCurrent() }
                        else locationPermission.launch(arrayOf(CurrentWifi.PERMISSION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }) { Icon(Icons.Rounded.MyLocation, stringResource(R.string.wifi_use_current)) }
                },
            )
            current?.takeIf { !it.is24GHz && it.ssid == ssid }?.let {
                Banner(stringResource(R.string.wifi_5ghz_note), BannerKind.WARN)
                Spacer(Modifier.height(8.dp))
            }
            if (current == null && !CurrentWifi.hasPermission(ctx)) {
                TextButton({ locationPermission.launch(arrayOf(CurrentWifi.PERMISSION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                    Icon(Icons.Rounded.MyLocation, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.wifi_use_current))
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                password, { password = it },
                label = { Text(stringResource(R.string.wifi_password)) },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton({ showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButton(stringResource(R.string.action_save), { vm.setWifi(ssid.trim(), password); onDone() }, enabled = ssid.isNotBlank())
            Spacer(Modifier.height(8.dp))
            TextButton(onDone, Modifier.fillMaxWidth()) { Text(stringResource(R.string.wifi_skip)) }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}
