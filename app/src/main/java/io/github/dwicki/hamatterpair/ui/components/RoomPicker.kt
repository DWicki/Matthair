package io.github.dwicki.hamatterpair.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.dwicki.hamatterpair.R
import io.github.dwicki.hamatterpair.ha.HaArea

/**
 * Room (HA "area") selection as chips: "No room", every existing area, and "New room…".
 * A new room is only created in HA when the caller saves, so cancelling leaves nothing behind.
 *
 * @param areaId selected existing area, or null
 * @param newArea name of a room still to be created, or null
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomPicker(
    areas: List<HaArea>,
    areaId: String?,
    newArea: String?,
    onSelect: (areaId: String?, newArea: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var ask by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        FilterChip(areaId == null && newArea == null, { onSelect(null, null) }, label = { Text(stringResource(R.string.done_no_area)) })
        areas.sortedBy { it.name.lowercase() }.forEach { a ->
            FilterChip(areaId == a.id && newArea == null, { onSelect(a.id, null) }, label = { Text(a.name) })
        }
        newArea?.let { FilterChip(true, {}, label = { Text(it) }) }
        FilterChip(false, { ask = true }, label = { Text(stringResource(R.string.done_new_area)) }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
    }
    if (ask) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text(stringResource(R.string.done_new_area_title)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ onSelect(null, text.trim()); ask = false }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton({ ask = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
