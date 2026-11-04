package io.github.dwicki.matthair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.ha.FabricInfo
import io.github.dwicki.matthair.ha.HaArea
import io.github.dwicki.matthair.ha.HaDevice
import io.github.dwicki.matthair.matter.SetupPayloadParser
import io.github.dwicki.matthair.ui.components.Banner
import io.github.dwicki.matthair.ui.components.PrimaryButton
import io.github.dwicki.matthair.ui.components.QrImage
import io.github.dwicki.matthair.ui.components.RoomPicker
import io.github.dwicki.matthair.ui.theme.LocalStatusColors

// Building blocks of the device overview (DeviceScreen.kt).

/** One line in an info card: icon, small label and the value below it. */
internal data class InfoRow(val icon: ImageVector, val label: String, val value: String)

/** Titled card. */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
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

/**
 * Label above value rather than side by side, so long values (IPv6 addresses, model names)
 * can never squeeze the label away.
 */
@Composable
internal fun InfoRows(rows: List<InfoRow>) {
    rows.forEachIndexed { index, row ->
        if (index > 0) RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(row.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(row.value, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

/** One ecosystem (fabric) the device belongs to. Only foreign ones can be removed. */
@Composable
internal fun FabricRow(fabric: FabricInfo, own: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Workspaces, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (own) R.string.device_fabric_this else R.string.device_fabric_other),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(fabric.displayName, style = MaterialTheme.typography.bodyLarge)
            if (!own && !fabric.label.isNullOrBlank() && fabric.label != fabric.vendorName) {
                Text(fabric.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!own) TextButton(onRemove) { Text(stringResource(R.string.action_remove), color = LocalStatusColors.current.fail) }
    }
}

/** Small rounded chip in the header: a status dot or an icon, plus text. */
@Composable
internal fun StatusPill(text: String, dot: Color? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
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

/** Round tonal button with a label below. */
@Composable
internal fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
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

@Composable
internal fun networkLabel(type: String) = stringResource(
    when (type) {
        "thread" -> R.string.net_thread
        "wifi" -> R.string.net_wifi
        "ethernet" -> R.string.net_ethernet
        else -> R.string.net_unknown
    },
)

internal fun networkIcon(type: String): ImageVector = when (type) {
    "wifi" -> Icons.Rounded.Wifi
    "ethernet" -> Icons.Rounded.Lan
    else -> Icons.Rounded.Hub
}

@Composable
internal fun nodeTypeLabel(type: String) = stringResource(
    when (type) {
        "end_device" -> R.string.node_end_device
        "sleepy_end_device" -> R.string.node_sleepy
        "routing_end_device" -> R.string.node_router
        "bridge" -> R.string.node_bridge
        else -> R.string.net_unknown
    },
)

/** Rename a device and move it to another room. */
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
            RoomPicker(areas, areaId, newArea, onSelect = { id, typed -> areaId = id; newArea = typed })
            Spacer(Modifier.height(24.dp))
            PrimaryButton(stringResource(R.string.action_save), { onSave(name, areaId, newArea) })
        }
    }
}

/** Shows the codes of a freshly opened commissioning window, for adding the device to another ecosystem. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareSheet(device: HaDevice, manual: String, qr: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.share_title, device.displayName), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.share_body),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
