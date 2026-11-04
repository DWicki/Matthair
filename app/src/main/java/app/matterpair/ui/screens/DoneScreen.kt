package app.matterpair.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.matterpair.R
import app.matterpair.ui.MainViewModel
import app.matterpair.ui.components.Banner
import app.matterpair.ui.components.PrimaryButton
import app.matterpair.ui.components.StepIndicator
import app.matterpair.ui.theme.LocalStatusColors

/** Step 4: celebrate, then name the device and put it in a room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DoneScreen(vm: MainViewModel, onFinished: () -> Unit) {
    val s by vm.session.collectAsState()
    val areas by vm.areas.collectAsState()
    val busy by vm.busy.collectAsState()
    val device = s.newDevice
    var name by rememberSaveable { mutableStateOf(device?.displayName ?: "") }
    var areaId by rememberSaveable { mutableStateOf<String?>(device?.areaId) }
    var newArea by rememberSaveable { mutableStateOf<String?>(null) }
    var askArea by remember { mutableStateOf(false) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true; vm.refresh() }
    val scale by animateFloatAsState(if (appeared) 1f else 0.3f, spring(Spring.DampingRatioMediumBouncy), label = "pop")
    val seconds = ((s.finishedAtMillis - s.startedAtMillis) / 1000).toInt()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Spacer(Modifier.height(16.dp))
        StepIndicator(3)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.CheckCircle, null, tint = LocalStatusColors.current.ok, modifier = Modifier.size(112.dp).scale(scale))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.done_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(stringResource(R.string.done_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (seconds > 0) Text(stringResource(R.string.done_seconds, seconds), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))
            if (device == null) {
                Banner(stringResource(R.string.done_device_pending))
            } else {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.done_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.done_area), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(areaId == null && newArea == null, { areaId = null; newArea = null }, label = { Text(stringResource(R.string.done_no_area)) })
                    areas.forEach { a ->
                        FilterChip(areaId == a.id && newArea == null, { areaId = a.id; newArea = null }, label = { Text(a.name) })
                    }
                    newArea?.let { FilterChip(true, {}, label = { Text(it) }) }
                    FilterChip(false, { askArea = true }, label = { Text(stringResource(R.string.done_new_area)) }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                }
            }
        }
        Column(Modifier.padding(16.dp)) {
            PrimaryButton(
                stringResource(if (device == null) R.string.action_done else R.string.action_save),
                {
                    if (device == null) { vm.finishWizard(); onFinished() }
                    else vm.saveNewDevice(device.id, name.takeIf { it != device.displayName }, areaId, newArea, onFinished)
                },
                enabled = !busy,
            )
        }
    }

    if (askArea) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askArea = false },
            title = { Text(stringResource(R.string.done_new_area_title)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ newArea = text.trim(); askArea = false }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton({ askArea = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
