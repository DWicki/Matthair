package io.github.dwicki.matthair.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dwicki.matthair.R
import io.github.dwicki.matthair.ui.MainViewModel
import io.github.dwicki.matthair.ui.components.HeroIcon
import io.github.dwicki.matthair.ui.components.PrimaryButton
import io.github.dwicki.matthair.ui.theme.LocalStatusColors

/** Intro for multi-admin: explains where to get a code from the other ecosystem. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiAdminIntroScreen(onBack: () -> Unit, onScan: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = {}, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } })
    }) { padding ->
        Column(Modifier.padding(padding).padding(24.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            HeroIcon(Icons.Rounded.SyncAlt, size = 140.dp)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.multiadmin_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.multiadmin_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            PrimaryButton(stringResource(R.string.action_continue), onScan)
        }
    }
}

/** Runs the network-only commissioning (no BLE) and shows the outcome. */
@Composable
fun MultiAdminProgressScreen(vm: MainViewModel, onDone: () -> Unit) {
    var finished by remember { mutableStateOf<Boolean?>(null) }
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.commissionOnNetwork { finished = it } }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        when (finished) {
            null -> { CircularProgressIndicator(Modifier.height(64.dp)); Spacer(Modifier.height(24.dp)); Text(stringResource(R.string.multiadmin_progress), style = MaterialTheme.typography.titleMedium) }
            true -> {
                Icon(Icons.Rounded.CheckCircle, null, tint = LocalStatusColors.current.ok, modifier = Modifier.height(96.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.multiadmin_success), style = MaterialTheme.typography.headlineSmall)
            }
            false -> Text(stringResource(R.string.fail_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        if (finished != null && !busy) PrimaryButton(stringResource(R.string.action_done), onDone)
    }
}
