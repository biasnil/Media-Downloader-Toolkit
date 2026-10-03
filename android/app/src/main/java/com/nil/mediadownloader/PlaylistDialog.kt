package com.nil.mediadownloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Mobile version of desktop's playlist_dialog.py: tick videos, or pick a numbered range. */
@Composable
fun PlaylistDialog(prompt: PlaylistPrompt, onConfirm: (List<Int>) -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val count = prompt.entries.size
    val checked = remember(prompt) {
        mutableStateListOf<Boolean>().apply { addAll(List(count) { it in prompt.preselected }) }
    }
    var from by remember(prompt) { mutableStateOf("1") }
    var to by remember(prompt) { mutableStateOf(count.toString()) }
    val selectedCount = checked.count { it }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(prompt.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$count videos. Tick the ones you want.", color = colors.onSurfaceVariant, fontSize = 13.sp)

                // Quick range select
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("#")
                    NumberField(from) { from = it }
                    Text("to #")
                    NumberField(to) { to = it }
                    Spacer(Modifier.width(8.dp))
                    PrimaryButton("Apply") {
                        val a = from.toIntOrNull()?.coerceIn(1, count) ?: return@PrimaryButton
                        val b = to.toIntOrNull()?.coerceIn(1, count) ?: return@PrimaryButton
                        val range = minOf(a, b)..maxOf(a, b)
                        for (i in 0 until count) checked[i] = (i + 1) in range
                    }
                }
                Row {
                    TextButton(onClick = { for (i in 0 until count) checked[i] = true }) { Text("Select all", color = colors.primary) }
                    TextButton(onClick = { for (i in 0 until count) checked[i] = false }) { Text("Select none", color = colors.primary) }
                }

                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    itemsIndexed(prompt.entries) { i, entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { checked[i] = !checked[i] }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                            Column {
                                Text("${i + 1}. ${entry.title}", fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(entry.url, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Skip playlist", Modifier.weight(1f), onClick = onCancel)
                    PrimaryButton(
                        "Download ($selectedCount)",
                        Modifier.weight(1f),
                        enabled = selectedCount > 0,
                    ) { onConfirm(checked.indices.filter { checked[it] }) }
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(72.dp).padding(horizontal = 4.dp),
    )
}
