package app.matterpair.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.matterpair.BuildConfig
import app.matterpair.R
import app.matterpair.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onSignedOut: () -> Unit, onShareDiagnostics: () -> Unit) {
    val config by vm.config.collectAsState()
    val details by vm.showDetails.collectAsState()
    val ctx = LocalContext.current

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.settings_server))
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Home, null) },
                headlineContent = { Text(config?.localUrl ?: "–") },
                supportingContent = { config?.externalUrl?.let { Text(it) } },
            )
            ListItem(
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
                headlineContent = { Text(stringResource(R.string.settings_sign_out)) },
                modifier = Modifier.clickableItem { vm.signOut(); onSignedOut() },
            )
            HorizontalDivider()
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Tune, null) },
                headlineContent = { Text(stringResource(R.string.settings_show_details)) },
                supportingContent = { Text(stringResource(R.string.settings_show_details_desc)) },
                trailingContent = { Switch(details, vm::setShowDetails) },
            )
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Language, null) },
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                modifier = Modifier.clickableItem {
                    // Per-app language picker (Android 13+); falls back to system settings.
                    val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${ctx.packageName}".toUri())
                    runCatching { ctx.startActivity(intent) }.onFailure { ctx.startActivity(Intent(Settings.ACTION_LOCALE_SETTINGS)) }
                },
            )
            ListItem(
                leadingContent = { Icon(Icons.Rounded.BugReport, null) },
                headlineContent = { Text(stringResource(R.string.settings_diagnostics)) },
                supportingContent = { Text(stringResource(R.string.settings_diagnostics_desc)) },
                modifier = Modifier.clickableItem(onShareDiagnostics),
            )
            HorizontalDivider()
            Section(stringResource(R.string.settings_zigbee))
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Wifi, null) },
                headlineContent = { Text(stringResource(R.string.settings_zigbee_desc)) },
                supportingContent = {
                    Column {
                        OutlinedButton({ vm.permitZigbee(z2m = false) }, Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.settings_zigbee_zha)) }
                        OutlinedButton({ vm.permitZigbee(z2m = true) }) { Text(stringResource(R.string.settings_zigbee_z2m)) }
                    }
                },
            )
            HorizontalDivider()
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Info, null) },
                headlineContent = { Text("${stringResource(R.string.settings_about)} · ${BuildConfig.VERSION_NAME}") },
                supportingContent = { Text(stringResource(R.string.settings_about_desc)) },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
}

private fun Modifier.clickableItem(onClick: () -> Unit) = this.clickable(onClick = onClick)
