package app.matterpair.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.matterpair.R
import app.matterpair.ha.HaDevice
import app.matterpair.matter.SetupPayloadParser
import app.matterpair.ui.HaStatus
import app.matterpair.ui.MainViewModel
import app.matterpair.ui.components.QrImage

/** All Matter devices (online and offline) with share and remove actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(vm: MainViewModel, onBack: () -> Unit) {
    val devices by vm.devices.collectAsState()
    val status by vm.status.collectAsState()
    var toRemove by remember { mutableStateOf<HaDevice?>(null) }
    var shared by remember { mutableStateOf<Triple<HaDevice, String, String>?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.devices_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
        )
    }) { pad ->
        PullToRefreshBox(status is HaStatus.Connecting, { vm.refresh() }, Modifier.padding(pad)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Offline devices first: they are the ones that need attention.
                items(devices.sortedWith(compareBy({ it.available != false }, { it.device.displayName })), key = { it.device.id }) { row ->
                    var menu by remember { mutableStateOf(false) }
                    DeviceListItem(row, onClick = { menu = true }) {
                        Box {
                            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.devices_share)) },
                                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                    onClick = { menu = false; vm.shareDevice(row.device) { m, q -> shared = Triple(row.device, m, q) } },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.devices_remove)) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                    onClick = { menu = false; toRemove = row.device },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    toRemove?.let { d ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text(stringResource(R.string.devices_remove_title, d.displayName)) },
            text = { Text(stringResource(R.string.devices_remove_body)) },
            confirmButton = { TextButton({ vm.removeDevice(d); toRemove = null }) { Text(stringResource(R.string.action_remove)) } },
            dismissButton = { TextButton({ toRemove = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    shared?.let { (device, manual, qr) ->
        ModalBottomSheet(onDismissRequest = { shared = null }) {
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
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
