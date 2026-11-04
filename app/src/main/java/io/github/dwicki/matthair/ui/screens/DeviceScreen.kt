package io.github.dwicki.matthair.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.ha.FabricInfo
import io.github.dwicki.matthair.ha.HaDevice
import io.github.dwicki.matthair.ha.NodeDiagnostics
import io.github.dwicki.matthair.ui.MainViewModel
import io.github.dwicki.matthair.ui.theme.LocalStatusColors

/**
 * Overview of one Matter device: a header card with status chips, quick actions,
 * grouped info cards and a remove button at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: MainViewModel, deviceId: String, onBack: () -> Unit) {
    val rows by vm.devices.collectAsState()
    val areas by vm.areas.collectAsState()
    val detail by vm.detail.collectAsState()
    val showDetails by vm.showDetails.collectAsState()
    val row = rows.firstOrNull { it.device.id == deviceId }

    var editing by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var sharedCodes by remember { mutableStateOf<SharedCodes?>(null) }
    var fabricToRemove by remember { mutableStateOf<FabricInfo?>(null) }

    LaunchedEffect(deviceId) { vm.loadDetail(deviceId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        if (row == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val device = row.device
        val diagnostics = detail?.diagnostics
        val areaName = areas.firstOrNull { it.id == device.areaId }?.name

        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DeviceHeader(
                    device = device,
                    online = diagnostics?.available ?: row.available,
                    areaName = areaName,
                    networkType = diagnostics?.networkType,
                    onEditRoom = { editing = true },
                )
            }
            item {
                QuickActions(
                    haUrl = vm.haDeviceUrl(device.id),
                    onEdit = { editing = true },
                    onShare = { vm.shareDevice(device) { manual, qr -> sharedCodes = SharedCodes(manual, qr) } },
                )
            }
            item { ConnectionSection(loading = detail == null, diagnostics, showDetails) }
            if (diagnostics != null && diagnostics.fabrics.isNotEmpty()) {
                item { EcosystemSection(diagnostics) { fabricToRemove = it } }
            }
            item { DeviceInfoSection(device, diagnostics) }
            item { RemoveDeviceButton { confirmRemove = true } }
        }

        if (editing) {
            EditDeviceSheet(device, areas, onDismiss = { editing = false }) { name, areaId, newArea ->
                vm.updateDevice(device, name, areaId, newArea)
                editing = false
            }
        }
        if (confirmRemove) {
            ConfirmDialog(
                icon = Icons.Rounded.Delete,
                title = stringResource(R.string.devices_remove_title, device.displayName),
                body = stringResource(R.string.devices_remove_body),
                onDismiss = { confirmRemove = false },
                onConfirm = { vm.removeDevice(device); onBack() },
            )
        }
        fabricToRemove?.let { fabric ->
            ConfirmDialog(
                icon = Icons.Rounded.LinkOff,
                title = stringResource(R.string.fabric_remove_title, fabric.displayName),
                body = stringResource(R.string.fabric_remove_body, fabric.displayName, device.displayName),
                onDismiss = { fabricToRemove = null },
                onConfirm = { vm.removeFabric(device, fabric) },
            )
        }
        sharedCodes?.let { ShareSheet(device, it.manual, it.qr) { sharedCodes = null } }
    }
}

private data class SharedCodes(val manual: String, val qr: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceHeader(device: HaDevice, online: Boolean?, areaName: String?, networkType: String?, onEditRoom: () -> Unit) {
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val status = LocalStatusColors.current

    Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .background(Brush.verticalGradient(listOf(container, container.copy(alpha = 0.55f))))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Hub, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = onContainer,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(device.manufacturer, device.model).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = onContainer.copy(alpha = 0.8f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (online != null) {
                    StatusPill(
                        stringResource(if (online) R.string.device_online else R.string.device_offline),
                        dot = if (online) status.ok else status.fail,
                    )
                }
                StatusPill(areaName ?: stringResource(R.string.done_no_area), icon = Icons.Rounded.MeetingRoom, onClick = onEditRoom)
                if (networkType != null) StatusPill(networkLabel(networkType), icon = networkIcon(networkType))
            }
        }
    }
}

@Composable
private fun QuickActions(haUrl: String?, onEdit: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        QuickAction(Icons.Rounded.Edit, stringResource(R.string.device_action_edit), onEdit)
        QuickAction(Icons.Rounded.Share, stringResource(R.string.device_action_share), onShare)
        if (haUrl != null) {
            QuickAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.device_action_open)) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, haUrl.toUri())) }
            }
        }
    }
}

@Composable
private fun ConnectionSection(loading: Boolean, diagnostics: NodeDiagnostics?, showDetails: Boolean) {
    Section(stringResource(R.string.device_section_connection)) {
        when {
            loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
            }
            diagnostics == null -> Text(
                stringResource(R.string.device_diag_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            else -> InfoRows(connectionRows(diagnostics, showDetails))
        }
    }
}

@Composable
private fun connectionRows(d: NodeDiagnostics, showDetails: Boolean): List<InfoRow> = buildList {
    val network = networkLabel(d.networkType) + (d.networkName?.let { " · $it" } ?: "")
    add(InfoRow(networkIcon(d.networkType), stringResource(R.string.device_network), network))
    add(InfoRow(Icons.Rounded.Router, stringResource(R.string.device_node_type), nodeTypeLabel(d.nodeType)))
    if (d.ipAddresses.isNotEmpty()) {
        val ips = if (showDetails) d.ipAddresses.joinToString("\n") else d.ipAddresses.first()
        add(InfoRow(Icons.Rounded.Lan, stringResource(R.string.device_ip), ips))
    }
    if (showDetails && d.macAddress != null) {
        add(InfoRow(Icons.Rounded.Tag, stringResource(R.string.device_mac), d.macAddress))
    }
}

@Composable
private fun EcosystemSection(diagnostics: NodeDiagnostics, onRemove: (FabricInfo) -> Unit) {
    Section(stringResource(R.string.device_section_ecosystems)) {
        diagnostics.fabrics.forEachIndexed { index, fabric ->
            if (index > 0) RowDivider()
            FabricRow(fabric, own = fabric.fabricIndex == diagnostics.ownFabricIndex) { onRemove(fabric) }
        }
    }
}

@Composable
private fun DeviceInfoSection(device: HaDevice, diagnostics: NodeDiagnostics?) {
    val rows = buildList {
        device.manufacturer?.let { add(InfoRow(Icons.Rounded.Business, stringResource(R.string.device_vendor), it)) }
        device.model?.let { add(InfoRow(Icons.Rounded.Category, stringResource(R.string.device_model), it)) }
        device.swVersion?.let { add(InfoRow(Icons.Rounded.SystemUpdate, stringResource(R.string.device_firmware), it)) }
        device.hwVersion?.let { add(InfoRow(Icons.Rounded.Memory, stringResource(R.string.device_hardware), it)) }
        device.serialNumber?.let { add(InfoRow(Icons.Rounded.Numbers, stringResource(R.string.device_serial), it)) }
        if (device.nameByUser != null) add(InfoRow(Icons.Rounded.Badge, stringResource(R.string.device_original_name), device.name))
        diagnostics?.let { add(InfoRow(Icons.Rounded.Hub, stringResource(R.string.device_node_id), it.nodeId.toString())) }
    }
    if (rows.isNotEmpty()) Section(stringResource(R.string.device_section_info)) { InfoRows(rows) }
}

@Composable
private fun RemoveDeviceButton(onClick: () -> Unit) {
    val fail = LocalStatusColors.current.fail
    OutlinedButton(
        onClick,
        Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, fail.copy(alpha = 0.6f)),
    ) {
        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = fail)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.devices_remove), color = fail)
    }
}

@Composable
private fun ConfirmDialog(
    icon: ImageVector,
    title: String,
    body: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, null) },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text(stringResource(R.string.action_remove)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
