package io.github.dwicki.hamatterpair.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.dwicki.hamatterpair.R
import io.github.dwicki.hamatterpair.matter.PayloadError
import io.github.dwicki.hamatterpair.matter.PayloadException
import io.github.dwicki.hamatterpair.matter.SetupPayloadParser
import io.github.dwicki.hamatterpair.ui.QrCodes
import io.github.dwicki.hamatterpair.ui.components.Banner
import io.github.dwicki.hamatterpair.ui.components.BannerKind
import io.github.dwicki.hamatterpair.ui.components.PrimaryButton
import io.github.dwicki.hamatterpair.ui.components.StepIndicator
import io.github.dwicki.hamatterpair.ui.payloadErrorLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Step 1: get the setup code. Live camera scanning is the default; typing the code and importing
 * a photo are one tap away. NFC tags are handled by the activity and land here too.
 *
 * @param accept parses the code; returns the parse error or null when the wizard can continue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    title: String,
    showSteps: Boolean,
    accept: (String) -> PayloadException?,
    onBack: () -> Unit,
    onAccepted: () -> Unit,
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var manual by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<PayloadError?>(null) }
    var noCodeInImage by remember { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera && !manual) cameraPermission.launch(Manifest.permission.CAMERA) }

    fun handle(text: String) {
        val e = accept(text)
        if (e == null) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onAccepted()
        } else error = e.error
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                runCatching {
                    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                    val bmp = ImageDecoder.decodeBitmap(src) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                    QrCodes.decode(bmp)
                }.getOrNull()
            }
            if (text == null) noCodeInImage = true else handle(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (manual) stringResource(R.string.manual_title) else title) },
                navigationIcon = { IconButton({ if (manual) manual = false else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (showSteps) { StepIndicator(0); Spacer(Modifier.height(16.dp)) }
            if (manual) {
                ManualEntry(error, onChange = { error = null }, onSubmit = ::handle)
            } else {
                Column(Modifier.weight(1f).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (hasCamera) QrCameraPreview(onCode = { scope.launch { handle(it) } })
                        else FilledTonalButton({ cameraPermission.launch(Manifest.permission.CAMERA) }) {
                            Icon(Icons.Rounded.PhotoCamera, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_grant_camera))
                        }
                        // Viewfinder frame
                        Box(
                            Modifier.fillMaxSize(0.7f).border(BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)), RoundedCornerShape(28.dp)),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(stringResource(R.string.scan_hint), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    if (!hasCamera) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.scan_camera_denied), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    error?.let { Spacer(Modifier.height(12.dp)); Banner(stringResource(payloadErrorLabel(it)), BannerKind.ERROR) }
                    if (noCodeInImage) { Spacer(Modifier.height(12.dp)); Banner(stringResource(R.string.scan_no_code_in_image), BannerKind.WARN) }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Nfc, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.scan_nfc_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton({ manual = true; error = null }, Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Rounded.Dialpad, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_manual))
                    }
                    FilledTonalButton(
                        { noCodeInImage = false; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        Modifier.weight(1f).height(52.dp),
                    ) {
                        Icon(Icons.Rounded.Image, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.scan_from_photo))
                    }
                }
            }
        }
    }
}

/** Live camera preview that reports the first QR code accepted by [accept]. Shared by the setup-code and Wi-Fi screens. */
@Composable
internal fun QrCameraPreview(accept: (String) -> Boolean = { it.contains("MT:", ignoreCase = true) }, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            val previewView = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val providerFuture = ProcessCameraProvider.getInstance(c)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, QrCodes.Analyzer(accept) { text -> previewView.post { onCode(text) } }) }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** Digit entry with live grouping (`1234-567-8901`) and check-digit validation. */
@Composable
private fun ManualEntry(error: PayloadError?, onChange: () -> Unit, onSubmit: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val digits = SetupPayloadParser.normalizeManual(value.text)
    val complete = digits.length == 11 || digits.length == 21
    val liveError = when {
        error != null -> error
        complete -> runCatching { SetupPayloadParser.parseManual(digits); null }.getOrElse { (it as? PayloadException)?.error }
        else -> null
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.manual_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value,
            onValueChange = { v ->
                val d = v.text.filter(Char::isDigit).take(21)
                val formatted = SetupPayloadParser.formatManual(d).let { if (d.length == 11 || d.length == 21) it else groupLive(d) }
                value = TextFieldValue(formatted, TextRange(formatted.length))
                onChange()
            },
            label = { Text(stringResource(R.string.manual_label)) },
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
            singleLine = true,
            isError = liveError != null,
            supportingText = {
                if (liveError != null) Text(stringResource(payloadErrorLabel(liveError)))
                else Text(stringResource(R.string.manual_digits_left, digits.length))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (complete && liveError == null) onSubmit(digits) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.action_continue), { onSubmit(digits) }, enabled = complete && liveError == null)
    }
}

/** Group digits 4-3-4-4-4-… while typing. */
private fun groupLive(d: String): String {
    val sizes = intArrayOf(4, 3, 4, 4, 4, 2)
    val sb = StringBuilder()
    var i = 0
    for (s in sizes) {
        if (i >= d.length) break
        if (sb.isNotEmpty()) sb.append('-')
        sb.append(d.substring(i, minOf(d.length, i + s)))
        i += s
    }
    return sb.toString()
}
