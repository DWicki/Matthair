package io.github.dwicki.hamatterpair.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MeetingRoom
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.dwicki.hamatterpair.R
import io.github.dwicki.hamatterpair.ha.FabricInfo
import io.github.dwicki.hamatterpair.ha.HaArea
import io.github.dwicki.hamatterpair.ha.HaDevice
import io.github.dwicki.hamatterpair.matter.SetupPayloadParser
import io.github.dwicki.hamatterpair.matter.hex
import io.github.dwicki.hamatterpair.ui.MainViewModel
import io.github.dwicki.hamatterpair.ui.components.Banner
import io.github.dwicki.hamatterpair.ui.components.PrimaryButton
import io.github.dwicki.hamatterpair.ui.components.QrImage
import io.github.dwicki.hamatterpair.ui.components.RoomPicker
import io.github.dwicki.hamatterpair.ui.theme.LocalStatusColors

/**
 * Overview of one Matter device, in the style of a smart-home app device page:
 *  * a tinted header card (icon, name, chips for room / status / network),
 *  * round quick actions (rename, share, open in HA),
 *  * grouped info cards with compact two-line rows (icon, small label, value),
 *  * remove at the bottom.
 *
 * Rows stack label above value instead of side by side, so long values (IPv6 addresses, model
 * names) can never squeeze the label away.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var removeFabric by remember { mutableStateOf<FabricInfo?>(null) }
    LaunchedEffect(deviceId) { vm.loadDetail(deviceId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        if (row == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val d = row.device
        val diag = detail?.diagnostics
        val online = diag?.available ?: row.available
        val status = LocalStatusColors.current
        val area = areas.firstOrNull { it.id == d.areaId }?.name

        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- Header card -----------------------------------------------------------------
            item {
                val pc = MaterialTheme.colorScheme.primaryContainer
                Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .background(Brush.verticalGradient(listOf(pc, pc.copy(alpha = 0.55f))))
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
                                    d.displayName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                listOfNotNull(d.manufacturer, d.model).takeIf { it.isNotEmpty() }?.let {
                                    Text(
                                        it.joinToString(" · "),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
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
                            StatusPill(area ?: stringResource(R.string.done_no_area), icon = Icons.Rounded.MeetingRoom, onClick = { edit = true })
                            diag?.let { StatusPill(networkLabel(it.networkType), icon = networkIcon(it.networkType)) }
                        }
                    }
                }
            }

            // --- Quick actions -----------------------------------------------------------------
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(Icons.Rounded.Edit, stringResource(R.string.device_action_edit)) { edit = true }
                    QuickAction(Icons.Rounded.Share, stringResource(R.string.device_action_share)) { vm.shareDevice(d) { m, q -> shared = m to q } }
                    vm.haDeviceUrl(d.id)?.let { url ->
                        QuickAction(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.device_action_open)) {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                        }
                    }
                }
            }

            // --- Connection --------------------------------------------------------------------
            item {
                Section(stringResource(R.string.device_section_connection)) {
                    when {
                        detail == null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
                        }
                        diag == null -> Text(
                            stringResource(R.string.device_diag_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                        else -> {
                            val items = buildList<Triple<ImageVector, String, String>> {
                                add(Triple(networkIcon(diag.networkType), stringResource(R.string.device_network),
                                    networkLabel(diag.networkType) + (diag.networkName?.let { " · $it" } ?: "")))
                                add(Triple(Icons.Rounded.Router, stringResource(R.string.device_node_type), nodeTypeLabel(diag.nodeType)))
                                if (diag.ipAddresses.isNotEmpty()) {
                                    add(Triple(Icons.Rounded.Lan, stringResource(R.string.device_ip),
                                        if (showDetails) diag.ipAddresses.joinToString("\n") else diag.ipAddresses.first()))
                                }
                                if (showDetails) diag.macAddress?.let { add(Triple(Icons.Rounded.Tag, stringResource(R.string.device_mac), it)) }
                            }
                            Rows(items)
                        }
                    }
                }
            }

            // --- Ecosystems ----------------------------------------------------------------------
            if (diag != null && diag.fabrics.isNotEmpty()) {
                item {
                    Section(stringResource(R.string.device_section_ecosystems)) {
                        diag.fabrics.forEachIndexed { i, f ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            FabricRow(f, own = f.fabricIndex == diag.ownFabricIndex) { removeFabric = f }
                        }
                    }
                }
            }

            // --- Device info ---------------------------------------------------------------------
            item {
                val items = buildList<Triple<ImageVector, String, String>> {
                    d.manufacturer?.let { add(Triple(Icons.Rounded.Business, stringResource(R.string.device_vendor), it)) }
                    d.model?.let { add(Triple(Icons.Rounded.Category, stringResource(R.string.device_model), it)) }
                    d.swVersion?.let { add(Triple(Icons.Rounded.SystemUpdate, stringResource(R.string.device_firmware), it)) }
                    d.hwVersion?.let { add(Triple(Icons.Rounded.Memory, stringResource(R.string.device_hardware), it)) }
                    d.serialNumber?.let { add(Triple(Icons.Rounded.Numbers, stringResource(R.string.device_serial), it)) }
                    if (d.nameByUser != null) add(Triple(Icons.Rounded.Badge, stringResource(R.string.device_original_name), d.name))
                    diag?.let { add(Triple(Icons.Rounded.Hub, stringResource(R.string.device_node_id), "${it.nodeId}")) }
                }
                if (items.isNotEmpty()) Section(stringResource(R.string.device_section_info)) { Rows(items) }
            }

            // --- Remove --------------------------------------------------------------------------
            item {
                OutlinedButton(
                    { remove = true },
                    Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, status.fail.copy(alpha = 0.6f)),
                ) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = status.fail)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.devices_remove), color = status.fail)
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
        removeFabric?.let { f ->
            val who = f.vendorName ?: f.label ?: f.vendorId.hex()
            AlertDialog(
                onDismissRequest = { removeFabric = null },
                icon = { Icon(Icons.Rounded.LinkOff, null) },
                title = { Text(stringResource(R.string.fabric_remove_title, who)) },
                text = { Text(stringResource(R.string.fabric_remove_body, who, d.displayName)) },
                confirmButton = { TextButton({ vm.removeFabric(d, f); removeFabric = null }) { Text(stringResource(R.string.action_remove)) } },
                dismissButton = { TextButton({ removeFabric = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}

/** One ecosystem a device belongs to; foreign ones can be removed. */
@Composable
private fun FabricRow(f: FabricInfo, own: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Workspaces, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (own) stringResource(R.string.device_fabric_this) else stringResource(R.string.device_fabric_other),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(f.vendorName ?: f.label ?: f.vendorId.hex(), style = MaterialTheme.typography.bodyLarge)
            if (!own && !f.label.isNullOrBlank() && f.label != f.vendorName) {
                Text(f.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!own) TextButton(onRemove) { Text(stringResource(R.string.action_remove), color = LocalStatusColors.current.fail) }
    }
}

/** Small rounded chip in the header (status dot or icon + text). */
@Composable
private fun StatusPill(text: String, dot: Color? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        modifier = if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick) else Modifier,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            if (icon != null) Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Round tonal button with a label below, like the quick actions in smart-home apps. */
@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Titled card containing rows. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp, top = 4.dp),
        )
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column { content() }
        }
    }
}

/** Compact rows: icon, small label, value below; thin dividers between rows. */
@Composable
private fun Rows(items: List<Triple<ImageVector, String, String>>) {
    items.forEachIndexed { i, (icon, label, value) ->
        if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
            }
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

private fun networkIcon(type: String): ImageVector = when (type) {
    "wifi" -> Icons.Rounded.Wifi
    "ethernet" -> Icons.Rounded.Lan
    else -> Icons.Rounded.Hub
}

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
            Spacer(Modifier.height(16.dp))
            Banner(stringResource(R.string.share_hint_other_app))
            Spacer(Modifier.height(16.dp))
        }
    }
}
