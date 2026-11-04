package io.github.dwicki.matthair.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.ui.MainViewModel
import io.github.dwicki.matthair.ui.QrCodes
import io.github.dwicki.matthair.ui.components.Banner
import io.github.dwicki.matthair.ui.components.BannerKind
import io.github.dwicki.matthair.ui.components.PrimaryButton
import io.github.dwicki.matthair.util.ConnectedWifi
import io.github.dwicki.matthair.util.CurrentWifi
import io.github.dwicki.matthair.util.WifiQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the user entered or scanned on the Wi-Fi screen. */
private class WifiForm {
    var ssid by mutableStateOf("")
    var password by mutableStateOf("")
    var connected by mutableStateOf<ConnectedWifi?>(null)
    var scanning by mutableStateOf(false)
    var filledFromQr by mutableStateOf(false)
    var noQrFound by mutableStateOf(false)

    /** Fills in the network the phone is on, if the location permission allows reading it. */
    suspend fun fillConnected(context: Context) {
        val wifi = CurrentWifi.read(context) ?: return
        connected = wifi
        if (ssid.isBlank()) ssid = wifi.ssid
    }

    /** Returns false when [text] is not a `WIFI:` QR code. */
    fun applyQr(text: String): Boolean {
        val credentials = WifiQr.parse(text) ?: return false
        ssid = credentials.ssid
        password = credentials.password
        filledFromQr = true
        scanning = false
        noQrFound = false
        return true
    }

    /** 5 GHz-only networks do not work for most Matter devices. */
    val showFiveGhzNote get() = connected?.let { !it.is24GHz && it.ssid == ssid } == true
}

/**
 * Hands Wi-Fi credentials to the Matter Server. Only Wi-Fi devices need this, and only once.
 *
 * Android never lets apps read saved Wi-Fi passwords, so the screen offers the next-best
 * things: fill in the connected network's name, or scan a `WIFI:` QR code (router sticker, or
 * Android's own *Share* QR on another phone) to get name and password at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiScreen(vm: MainViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val form = remember { WifiForm() }

    fun onQrText(text: String?) {
        if (text != null && form.applyQr(text)) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else form.noQrFound = true
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { form.fillConnected(context) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        form.scanning = granted
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { onQrText(decodeQr(context, uri)) }
    }
    val askLocation = { locationPermission.launch(arrayOf(CurrentWifi.PERMISSION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    // Pre-fill silently if the permission was granted earlier; never prompt when the screen opens.
    LaunchedEffect(Unit) { form.fillConnected(context) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.wifi_title)) },
            navigationIcon = { IconButton(onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Text(stringResource(R.string.wifi_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.wifi_only_for_wifi), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            QrOptions(
                form,
                onScan = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    if (granted) form.scanning = !form.scanning else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                onPickPhoto = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onQrText = ::onQrText,
            )
            Spacer(Modifier.height(24.dp))
            NetworkFields(
                form,
                canReadNetwork = CurrentWifi.hasPermission(context),
                onUseCurrent = {
                    if (CurrentWifi.hasPermission(context)) {
                        scope.launch { form.ssid = ""; form.fillConnected(context) }
                    } else {
                        askLocation()
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButton(stringResource(R.string.action_save), { vm.setWifi(form.ssid.trim(), form.password); onDone() }, enabled = form.ssid.isNotBlank())
            Spacer(Modifier.height(8.dp))
            TextButton(onDone, Modifier.fillMaxWidth()) { Text(stringResource(R.string.wifi_skip)) }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

/** Option 1: scan a Wi-Fi QR code with the camera or from a photo. */
@Composable
private fun QrOptions(form: WifiForm, onScan: () -> Unit, onPickPhoto: () -> Unit, onQrText: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTextButton(Icons.Rounded.QrCodeScanner, stringResource(R.string.wifi_scan_qr), onScan, Modifier.weight(1f))
        IconTextButton(Icons.Rounded.Image, stringResource(R.string.scan_from_photo), onPickPhoto, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.wifi_qr_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AnimatedVisibility(form.scanning) {
        Box(
            Modifier.padding(top = 12.dp).fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            QrCameraPreview(accept = { it.startsWith("WIFI:", ignoreCase = true) }, onCode = onQrText)
            Box(Modifier.fillMaxSize(0.7f).border(BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)), RoundedCornerShape(28.dp)))
        }
    }
    if (form.noQrFound) {
        Spacer(Modifier.height(12.dp))
        Banner(stringResource(R.string.wifi_no_qr), BannerKind.WARN)
    }
    if (form.filledFromQr) {
        Spacer(Modifier.height(12.dp))
        Banner(stringResource(R.string.wifi_from_qr))
    }
}

/** Option 2: type name and password, with the connected network's name pre-filled. */
@Composable
private fun NetworkFields(form: WifiForm, canReadNetwork: Boolean, onUseCurrent: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    val needsPermission = form.connected == null && !canReadNetwork

    OutlinedTextField(
        form.ssid, { form.ssid = it },
        label = { Text(stringResource(R.string.wifi_ssid)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { if (needsPermission) Text(stringResource(R.string.wifi_fill_hint)) },
        trailingIcon = { IconButton(onUseCurrent) { Icon(Icons.Rounded.MyLocation, stringResource(R.string.wifi_use_current)) } },
    )
    if (form.showFiveGhzNote) {
        Banner(stringResource(R.string.wifi_5ghz_note), BannerKind.WARN)
        Spacer(Modifier.height(8.dp))
    }
    if (needsPermission) {
        TextButton(onUseCurrent) {
            Icon(Icons.Rounded.MyLocation, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wifi_use_current))
        }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        form.password, { form.password = it },
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
}

@Composable
private fun IconTextButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick, modifier.height(52.dp)) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

private suspend fun decodeQr(context: Context, uri: Uri): String? = withContext(Dispatchers.Default) {
    runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        QrCodes.decode(bitmap)
    }.getOrNull()
}
