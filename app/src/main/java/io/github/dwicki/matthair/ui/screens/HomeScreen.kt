package io.github.dwicki.matthair.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.ui.DeviceRow
import io.github.dwicki.matthair.ui.HaStatus
import io.github.dwicki.matthair.ui.MainViewModel
import io.github.dwicki.matthair.ui.components.HeroIcon
import io.github.dwicki.matthair.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAdd: () -> Unit,
    onMultiAdmin: () -> Unit,
    onDevices: () -> Unit,
    onOpenDevice: (deviceId: String) -> Unit,
    onSettings: () -> Unit,
) {
    val status by vm.status.collectAsState()
    val devices by vm.devices.collectAsState()
    val areas by vm.areas.collectAsState()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = { IconButton(onSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title)) } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(stringResource(R.string.home_add_device)) },
            )
        },
    ) { pad ->
        PullToRefreshBox(isRefreshing = status is HaStatus.Connecting, onRefresh = { vm.refresh() }, modifier = Modifier.padding(pad)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { StatusChip(status) }
                item { AddHero(onAdd) }
                item {
                    Card(onClick = onMultiAdmin, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
                        ListItem(
                            leadingContent = { Icon(Icons.Rounded.SyncAlt, null) },
                            headlineContent = { Text(stringResource(R.string.home_from_other_app)) },
                            supportingContent = { Text(stringResource(R.string.home_from_other_app_desc)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        )
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.home_devices), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (devices.size > 5) TextButton(onDevices) { Text(stringResource(R.string.home_more_devices, devices.size)) }
                    }
                }
                if (devices.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Devices, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource(R.string.home_no_devices), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(devices.take(5), key = { it.device.id }) { row ->
                        DeviceListItem(row, areaName = areas.firstOrNull { it.id == row.device.areaId }?.name, onClick = { onOpenDevice(row.device.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: HaStatus) {
    val c = LocalStatusColors.current
    val (text, color) = when (status) {
        HaStatus.Connecting -> stringResource(R.string.home_status_connecting) to MaterialTheme.colorScheme.outline
        is HaStatus.Connected -> stringResource(R.string.home_status_connected, status.version) to c.ok
        is HaStatus.Offline -> stringResource(R.string.home_status_offline) to c.fail
        HaStatus.SignedOut -> stringResource(R.string.home_status_signed_out) to c.warn
    }
    AssistChip(
        onClick = {},
        label = { Text(text) },
        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) },
        colors = AssistChipDefaults.assistChipColors(),
    )
}

@Composable
private fun AddHero(onAdd: () -> Unit) {
    Card(
        onClick = onAdd,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_add_hero_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.home_add_hero_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            HeroIcon(Icons.Rounded.QrCodeScanner, size = 88.dp)
        }
    }
}

@Composable
fun DeviceListItem(row: DeviceRow, onClick: () -> Unit, areaName: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalStatusColors.current
    Card(onClick = onClick, shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer)) {
        ListItem(
            leadingContent = {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Icon(Icons.Rounded.Hub, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    if (row.available != null) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (row.available) c.ok else c.fail))
                    }
                }
            },
            headlineContent = { Text(row.device.displayName) },
            supportingContent = {
                val parts = listOfNotNull(
                    areaName, row.device.manufacturer, row.device.model,
                    row.available?.let { stringResource(if (it) R.string.device_online else R.string.device_offline) },
                )
                Text(parts.joinToString(" · "))
            },
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
    }
}
