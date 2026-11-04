package app.matterpair.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.matterpair.R
import app.matterpair.ha.HaArea
import app.matterpair.ha.HaDevice
import app.matterpair.matter.SetupPayloadParser
import app.matterpair.matter.hex
import app.matterpair.ui.MainViewModel
import app.matterpair.ui.components.Banner
import app.matterpair.ui.components.HeroIcon
import app.matterpair.ui.components.PrimaryButton
import app.matterpair.ui.components.QrImage
import app.matterpair.ui.components.RoomPicker
import app.matterpair.ui.theme.LocalStatusColors

/**
 * Overview of one Matter device: status, connection, device info and the ecosystems (fabrics)
 * it belongs to, with all actions in one place: rename/room, share, open in HA, remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: MainViewModel, deviceId: String, onBack: () -> Unit) {
    val rows by vm.devices.collectAsState()
    val areas by vm.areas.collectAsState()
    val detail by vm.detail.collectAsState()
    val showDetails by vm.showDetails.collectAsState()
    val ctx = LocalContext.current
    val row = rows.firstOrNull { it.device.id == deviceId }
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(deviceId) { vm.loadDetail(deviceId) }

    Scaffold(topBar = {
        TopAppBar(
            title = {},
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
            actions = { IconButton({ edit = true }, enabled = row != null) { Icon(Icons.Rounded.Edit, stringResource(R.string.devices_edit)) } },
        )
    }) { pad ->
        if (row == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val d = row.device
        val diag = detail?.diagnostics
        val online = diag?.available ?: row.available
        val c = LocalStatusColors.current
        val area = areas.firstOrNull { it.id == d.areaId }?.name

        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // --- Header ---------------------------------------------------------------------
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    HeroIcon(Icons.Rounded.Hub, size = 112.dp, tint = if (online == false) c.fail else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(d.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(
                        area ?: stringResource(R.string.done_no_area),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (online != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) c.ok else c.fail))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (online) R.string.device_online else R.string.device_offline), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            // --- Quick actions ---------------------------------------------------------------
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ edit = true }, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.device_action_edit))
                    }
                    FilledTonalButton({ vm.shareDevice(d) { m, q -> shared = m to q } }, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.device_action_share))
                    }
                }
            }
            // --- Connection ------------------------------------------------------------------
            item {
                InfoCard(stringResource(R.string.device_section_connection)) {
                    when {
                        detail == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.loading))
                        }
                        diag == null -> Text(stringResource(R.string.device_diag_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            InfoRow(stringResource(R.string.device_network), networkLabel(diag.networkType) + (diag.networkName?.let { " · $it" } ?: ""))
                            InfoRow(stringResource(R.string.device_node_type), nodeTypeLabel(diag.nodeType))
                            diag.ipAddresses.firstOrNull()?.let { InfoRow(stringResource(R.string.device_ip), if (showDetails) diag.ipAddresses.joinToString("\n") else it) }
                            if (showDetails) diag.macAddress?.let { InfoRow(stringResource(R.string.device_mac), it) }
                        }
                    }
                }
            }
            // --- Ecosystems (multi-admin) ----------------------------------------------------
            if (diag != null && diag.fabrics.isNotEmpty()) {
                item {
                    InfoCard(stringResource(R.string.device_section_ecosystems)) {
                        diag.fabrics.forEach { f ->
                            val own = f.fabricIndex == diag.ownFabricIndex
                            InfoRow(
                                f.vendorName ?: f.label ?: f.vendorId.hex(),
                                if (own) stringResource(R.string.device_fabric_this) else (f.label ?: ""),
                            )
                        }
                    }
                }
            }
            // --- Device info -----------------------------------------------------------------
            item {
                InfoCard(stringResource(R.string.device_section_info)) {
                    d.manufacturer?.let { InfoRow(stringResource(R.string.device_vendor), it) }
                    d.model?.let { InfoRow(stringResource(R.string.device_model), it) }
                    d.swVersion?.let { InfoRow(stringResource(R.string.device_firmware), it) }
                    d.hwVersion?.let { InfoRow(stringResource(R.string.device_hardware), it) }
                    d.serialNumber?.let { InfoRow(stringResource(R.string.device_serial), it) }
                    if (d.nameByUser != null) InfoRow(stringResource(R.string.device_original_name), d.name)
                    diag?.let { InfoRow(stringResource(R.string.device_node_id), "${it.nodeId}") }
                }
            }
            // --- More ------------------------------------------------------------------------
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.haDeviceUrl(d.id)?.let { url ->
                        OutlinedButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }, Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.device_open_in_ha))
                        }
                    }
                    TextButton({ remove = true }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = c.fail); Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.devices_remove), color = c.fail)
                    }
                }
            }
        }

        if (edit) {
            EditDeviceSheet(d, areas, onDismiss = { edit = false }) { name, areaId, newArea ->
                vm.updateDevice(d, name, areaId, newArea); edit = false
            }
        }
        if (remove) {
            AlertDialog(
                onDismissRequest = { remove = false },
                icon = { Icon(Icons.Rounded.Delete, null) },
                title = { Text(stringResource(R.string.devices_remove_title, d.displayName)) },
                text = { Text(stringResource(R.string.devices_remove_body)) },
                confirmButton = { TextButton({ remove = false; vm.removeDevice(d); onBack() }) { Text(stringResource(R.string.action_remove)) } },
                dismissButton = { TextButton({ remove = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        shared?.let { (manual, qr) -> ShareSheet(d, manual, qr) { shared = null } }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        SelectionContainer(Modifier.weight(0.55f)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun networkLabel(type: String) = stringResource(
    when (type) {
        "thread" -> R.string.net_thread
        "wifi" -> R.string.net_wifi
        "ethernet" -> R.string.net_ethernet
        else -> R.string.net_unknown
    },
)

@Composable
private fun nodeTypeLabel(type: String) = stringResource(
    when (type) {
        "end_device" -> R.string.node_end_device
        "sleepy_end_device" -> R.string.node_sleepy
        "routing_end_device" -> R.string.node_router
        "bridge" -> R.string.node_bridge
        else -> R.string.net_unknown
    },
)

/** Bottom sheet to rename a device and move it to another room (HA device registry). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDeviceSheet(
    device: HaDevice,
    areas: List<HaArea>,
    onDismiss: () -> Unit,
    onSave: (name: String, areaId: String?, newArea: String?) -> Unit,
) {
    var name by rememberSaveable(device.id) { mutableStateOf(device.displayName) }
    var areaId by rememberSaveable(device.id) { mutableStateOf(device.areaId) }
    var newArea by rememberSaveable(device.id) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.devices_edit), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                name, { name = it },
                label = { Text(stringResource(R.string.done_name)) },
                supportingText = { Text(stringResource(R.string.devices_name_hint, device.name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.done_area), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            RoomPicker(areas, areaId, newArea, onSelect = { a, n -> areaId = a; newArea = n })
            Spacer(Modifier.height(24.dp))
            PrimaryButton(stringResource(R.string.action_save), { onSave(name, areaId, newArea) })
        }
    }
}

/** Shows the code of a freshly opened commissioning window (share with another ecosystem). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(device: HaDevice, manual: String, qr: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.share_title, device.displayName), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.share_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (qr.isNotBlank()) QrImage(qr, Modifier.fillMaxWidth(0.7f))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.share_manual), style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(SetupPayloadParser.formatManual(manual), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace))
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Banner(stringResource(R.string.share_hint_other_app))
            Spacer(Modifier.height(16.dp))
        }
    }
}
