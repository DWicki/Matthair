package app.matterpair.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.matterpair.R
import app.matterpair.ui.HaStatus
import app.matterpair.ui.MainViewModel

/** All Matter devices (online and offline). Tap one to open its overview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (deviceId: String) -> Unit) {
    val devices by vm.devices.collectAsState()
    val areas by vm.areas.collectAsState()
    val status by vm.status.collectAsState()

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
                    DeviceListItem(
                        row,
                        areaName = areas.firstOrNull { it.id == row.device.areaId }?.name,
                        onClick = { onOpen(row.device.id) },
                    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }
                }
            }
        }
    }
}
