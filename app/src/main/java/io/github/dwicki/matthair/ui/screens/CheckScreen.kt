package io.github.dwicki.matthair.ui.screens

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
import androidx.compose.ui.unit.dp
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.commission.CheckAction
import io.github.dwicki.matthair.commission.CheckId
import io.github.dwicki.matthair.commission.CheckItem
import io.github.dwicki.matthair.commission.CheckStatus
import io.github.dwicki.matthair.matter.CommissioningFlow
import io.github.dwicki.matthair.matter.SetupPayload
import io.github.dwicki.matthair.matter.hex
import io.github.dwicki.matthair.ui.MainViewModel
import io.github.dwicki.matthair.ui.checkHelp
import io.github.dwicki.matthair.ui.checkLabel
import io.github.dwicki.matthair.ui.components.Banner
import io.github.dwicki.matthair.ui.components.BannerKind
import io.github.dwicki.matthair.ui.components.PrimaryButton
import io.github.dwicki.matthair.ui.components.StatusIcon
import io.github.dwicki.matthair.ui.components.StepIndicator
import io.github.dwicki.matthair.ui.flowLabel

/** Step 2: shows what was scanned and runs the traffic-light pre-flight check. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(vm: MainViewModel, onBack: () -> Unit, onStart: () -> Unit, onWifi: () -> Unit) {
    val payload by vm.payload.collectAsState()
    val report by vm.environment.collectAsState()
    val details by vm.showDetails.collectAsState()
    val context = LocalContext.current
    var confirmProxy by remember { mutableStateOf(false) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.runCheck() }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.runCheck() }
    LaunchedEffect(Unit) { vm.runCheck() }

    fun act(action: CheckAction) = when (action) {
        CheckAction.ENABLE_BLUETOOTH -> enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        CheckAction.GRANT_PERMISSIONS -> permissions.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS),
        )
        CheckAction.BATTERY_SETTINGS -> context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        CheckAction.ENABLE_BLE_PROXY -> confirmProxy = true
        CheckAction.SET_WIFI -> onWifi()
        CheckAction.SHARE_THREAD -> vm.shareThreadDataset()
    }

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
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StepIndicator(1, Modifier.padding(bottom = 8.dp)) }
            payload?.let { item { PayloadCard(it, details) } }
            if (payload?.flow == CommissioningFlow.CUSTOM) item { Banner(stringResource(R.string.flow_custom_warning), BannerKind.WARN) }
            if (payload?.flow == CommissioningFlow.USER_INTENT) item { Banner(stringResource(R.string.flow_user_intent_hint)) }

            val nearby = report.nearby
            // A manual code only carries 4 of the 12 discriminator bits, so several devices can match.
            if (payload?.longDiscriminator == null && nearby.matching.size > 1) {
                item { Banner(stringResource(R.string.check_multiple_candidates, nearby.matching.size), BannerKind.WARN) }
            }
            if (nearby.matching.isEmpty() && nearby.others > 0 && !report.running) {
                item { Banner(stringResource(R.string.check_other_matter_devices, nearby.others)) }
            }
            item { CheckList(report.items, details, ::act) }
            if (details) {
                items(nearby.matching, key = { it.address }) { hit ->
                    val ad = hit.advertisement
                    Text(
                        "${hit.address} · ${hit.rssi} dBm · disc ${ad.discriminator} · ${ad.vendorId.hex()}/${ad.productId.hex()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmProxy) {
        AlertDialog(
            onDismissRequest = { confirmProxy = false },
            title = { Text(stringResource(R.string.enable_proxy_title)) },
            text = { Text(stringResource(R.string.enable_proxy_body)) },
            confirmButton = { TextButton({ confirmProxy = false; vm.enableBleProxy() }) { Text(stringResource(R.string.enable_proxy_confirm)) } },
            dismissButton = { TextButton({ confirmProxy = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Problems first; the green items collapse into one "n / m" row so only what needs attention stands out. */
@Composable
private fun CheckList(items: List<CheckItem>, details: Boolean, onAction: (CheckAction) -> Unit) {
    var showOk by rememberSaveable { mutableStateOf(false) }
    val problems = items.filter { it.status != CheckStatus.OK }
    val ok = items.filter { it.status == CheckStatus.OK }

    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.animateContentSize()) {
            problems.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                CheckRow(item, details, onAction)
            }
            if (ok.isEmpty()) return@Column
            if (problems.isNotEmpty()) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(CheckStatus.OK)
                Spacer(Modifier.width(16.dp))
                Text("${ok.size} / ${items.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton({ showOk = !showOk }) { Icon(if (showOk) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
            }
            AnimatedVisibility(showOk) {
                Column { ok.forEach { CheckRow(it, details, onAction) } }
            }
        }
    }
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
private fun PayloadCard(payload: SetupPayload, details: Boolean) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Hub, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.device_card_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(flowLabel(payload.flow)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            if (details) {
                Spacer(Modifier.height(12.dp))
                Field(stringResource(R.string.device_vendor), payload.vendorId?.hex() ?: "–")
                Field(stringResource(R.string.device_product), payload.productId?.hex() ?: "–")
                Field(
                    stringResource(R.string.device_discriminator),
                    payload.longDiscriminator?.toString() ?: stringResource(R.string.device_discriminator_short, payload.shortDiscriminator),
                )
                Field(stringResource(R.string.device_passcode), payload.maskedPasscode())
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
