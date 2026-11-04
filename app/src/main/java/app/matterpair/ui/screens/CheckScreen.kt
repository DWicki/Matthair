package app.matterpair.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.matterpair.R
import app.matterpair.commission.CheckAction
import app.matterpair.commission.CheckId
import app.matterpair.commission.CheckItem
import app.matterpair.commission.CheckStatus
import app.matterpair.matter.CommissioningFlow
import app.matterpair.matter.SetupPayload
import app.matterpair.matter.hex
import app.matterpair.ui.MainViewModel
import app.matterpair.ui.checkHelp
import app.matterpair.ui.checkLabel
import app.matterpair.ui.components.Banner
import app.matterpair.ui.components.BannerKind
import app.matterpair.ui.components.PrimaryButton
import app.matterpair.ui.components.StatusIcon
import app.matterpair.ui.components.StepIndicator
import app.matterpair.ui.flowLabel

/**
 * Step 2: show what was scanned and run the traffic-light pre-flight check. Green items collapse
 * into a single "everything ok" row so that users only see what needs attention.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(vm: MainViewModel, onBack: () -> Unit, onStart: () -> Unit) {
    val payload by vm.payload.collectAsState()
    val report by vm.environment.collectAsState()
    val details by vm.showDetails.collectAsState()
    val ctx = LocalContext.current
    var showOk by rememberSaveable { mutableStateOf(false) }
    var proxyDialog by remember { mutableStateOf(false) }
    var wifiDialog by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.runCheck() }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.runCheck() }
    LaunchedEffect(Unit) { vm.runCheck() }

    fun act(a: CheckAction) = when (a) {
        CheckAction.ENABLE_BLUETOOTH -> enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        CheckAction.GRANT_PERMISSIONS -> permissions.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS),
        )
        CheckAction.BATTERY_SETTINGS -> ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        CheckAction.ENABLE_BLE_PROXY -> proxyDialog = true
        CheckAction.SET_WIFI -> wifiDialog = true
        CheckAction.SHARE_THREAD -> vm.shareThreadDataset()
    }

    val problems = report.items.filter { it.status != CheckStatus.OK }
    val ok = report.items.filter { it.status == CheckStatus.OK }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.check_title)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { IconButton({ vm.runCheck() }, enabled = !report.running) { Icon(Icons.Rounded.Refresh, null) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                    PrimaryButton(
                        stringResource(if (report.hasWarnings) R.string.check_start_anyway else R.string.check_start),
                        onStart,
                        enabled = report.canProceed,
                    )
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StepIndicator(1, Modifier.padding(bottom = 8.dp)) }
            payload?.let { p -> item { PayloadCard(p, details) } }
            if (payload?.flow == CommissioningFlow.CUSTOM) item { Banner(stringResource(R.string.flow_custom_warning), BannerKind.WARN) }
            if (payload?.flow == CommissioningFlow.USER_INTENT) item { Banner(stringResource(R.string.flow_user_intent_hint)) }

            val nearby = report.nearby
            if (payload?.longDiscriminator == null && nearby.matching.size > 1) {
                item { Banner(stringResource(R.string.check_multiple_candidates, nearby.matching.size), BannerKind.WARN) }
            }
            if (nearby.matching.isEmpty() && nearby.others > 0 && !report.running) {
                item { Banner(stringResource(R.string.check_other_matter_devices, nearby.others)) }
            }

            item {
                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.animateContentSize()) {
                        problems.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            CheckRow(item, details, ::act)
                        }
                        if (ok.isNotEmpty()) {
                            if (problems.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatusIcon(CheckStatus.OK)
                                Spacer(Modifier.width(16.dp))
                                Text("${ok.size} / ${report.items.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton({ showOk = !showOk }) { Icon(if (showOk) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
                            }
                            AnimatedVisibility(showOk) {
                                Column { ok.forEach { CheckRow(it, details, ::act) } }
                            }
                        }
                    }
                }
            }
            if (details && nearby.matching.isNotEmpty()) {
                items(nearby.matching, key = { it.address }) { hit ->
                    Text(
                        "${hit.address} · ${hit.rssi} dBm · disc ${hit.advertisement.discriminator} · " +
                            "${hit.advertisement.vendorId.hex()}/${hit.advertisement.productId.hex()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (proxyDialog) {
        AlertDialog(
            onDismissRequest = { proxyDialog = false },
            title = { Text(stringResource(R.string.enable_proxy_title)) },
            text = { Text(stringResource(R.string.enable_proxy_body)) },
            confirmButton = { TextButton({ proxyDialog = false; vm.enableBleProxy() }) { Text(stringResource(R.string.enable_proxy_confirm)) } },
            dismissButton = { TextButton({ proxyDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (wifiDialog) WifiDialog(onDismiss = { wifiDialog = false }) { ssid, pw -> wifiDialog = false; vm.setWifi(ssid, pw) }
}

@Composable
private fun CheckRow(item: CheckItem, details: Boolean, onAction: (CheckAction) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        StatusIcon(item.status)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(checkLabel(item.id)), style = MaterialTheme.typography.titleSmall)
            if (item.status == CheckStatus.WARN || item.status == CheckStatus.FAIL) {
                val weak = item.id == CheckId.DEVICE && item.status == CheckStatus.WARN
                checkHelp(item.id, weak)?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (item.detail != null && (details || item.id == CheckId.DEVICE || item.id == CheckId.MATTER_APP)) {
                Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (item.action != null) TextButton({ onAction(item.action) }) { Text(stringResource(R.string.action_fix)) }
    }
}

@Composable
private fun PayloadCard(p: SetupPayload, details: Boolean) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Hub, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.device_card_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(flowLabel(p.flow)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (details) {
                Spacer(Modifier.height(12.dp))
                Field(stringResource(R.string.device_vendor), p.vendorId?.hex() ?: "–")
                Field(stringResource(R.string.device_product), p.productId?.hex() ?: "–")
                Field(
                    stringResource(R.string.device_discriminator),
                    p.longDiscriminator?.toString() ?: stringResource(R.string.device_discriminator_short, p.shortDiscriminator),
                )
                Field(stringResource(R.string.device_passcode), p.maskedPasscode())
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun WifiDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var ssid by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wifi_title)) },
        text = {
            Column {
                Text(stringResource(R.string.wifi_body))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(ssid, { ssid = it }, label = { Text(stringResource(R.string.wifi_ssid)) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.wifi_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton({ onSave(ssid, pw) }, enabled = ssid.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

