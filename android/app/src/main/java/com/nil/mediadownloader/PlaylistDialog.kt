package com.nil.mediadownloader

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.CompletableDeferred

/**
 * A list waiting for the user to tick which items they want: a YouTube playlist, a Spotify
 * playlist/album, or a post with several videos. [countText] is the "N videos/songs/items..." line.
 */
class PlaylistPrompt(
    val title: String,
    val entries: List<VideoEntry>,
    val preselected: Set<Int>,
    @param:StringRes val countText: Int = R.string.picker_count_videos,
    val answer: CompletableDeferred<List<Int>?> = CompletableDeferred(),
)

/**
 * Mobile version of desktop's playlist_dialog.py, as a full page: cover, Download button,
 * then every item with a tick box. A numbered range can be picked too.
 */
@Composable
fun PlaylistDialog(prompt: PlaylistPrompt, onConfirm: (List<Int>) -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalPalette.current
    val count = prompt.entries.size
    val checked = remember(prompt) {
        mutableStateListOf<Boolean>().apply { addAll(List(count) { it in prompt.preselected }) }
    }
    var from by remember(prompt) { mutableStateOf("1") }
    var to by remember(prompt) { mutableStateOf(count.toString()) }
    val selectedCount = checked.count { it }
    val cover = prompt.entries.firstNotNullOfOrNull { it.thumbnail }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            LazyColumn(Modifier.fillMaxSize().systemBarsPadding()) {
                // Cover: the first thumbnail fading into the page, or the theme's colors
                item {
                    Box(Modifier.fillMaxWidth().height(240.dp)) {
                        if (cover != null) {
                            AsyncImage(
                                model = cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().background(colors.surfaceVariant),
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(Brush.linearGradient(palette.art)))
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(0.2f to Color.Transparent, 1f to colors.background))
                        )
                        IconButton(
                            onClick = onCancel,
                            modifier = Modifier.padding(8.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.35f),
                                contentColor = Color.White,
                            ),
                        ) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.skip)) }
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 10.dp)) {
                            Text(
                                prompt.title,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 26.sp,
                                lineHeight = 30.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(stringResource(prompt.countText, count), color = colors.onSurfaceVariant, fontSize = 13.sp)
                        }
                    }
                }

                item {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(
                                stringResource(R.string.download_count, selectedCount),
                                Modifier.weight(1f).heightIn(min = 52.dp),
                                enabled = selectedCount > 0,
                                icon = R.drawable.ic_download,
                            ) { onConfirm(checked.indices.filter { checked[it] }) }
                            SecondaryButton(stringResource(R.string.skip), Modifier.heightIn(min = 52.dp), onClick = onCancel)
                        }

                        // Quick range select
                        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#", fontWeight = FontWeight.Bold)
                            NumberField(from) { from = it }
                            Text(stringResource(R.string.range_to))
                            NumberField(to) { to = it }
                            Spacer(Modifier.width(4.dp))
                            SecondaryButton(stringResource(R.string.apply)) {
                                val a = from.toIntOrNull()?.coerceIn(1, count) ?: return@SecondaryButton
                                val b = to.toIntOrNull()?.coerceIn(1, count) ?: return@SecondaryButton
                                val range = minOf(a, b)..maxOf(a, b)
                                for (i in 0 until count) checked[i] = (i + 1) in range
                            }
                        }
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("$selectedCount / $count", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            SmallLink(stringResource(R.string.select_all)) { for (i in 0 until count) checked[i] = true }
                            SmallLink(stringResource(R.string.select_none)) { for (i in 0 until count) checked[i] = false }
                        }
                    }
                }

                itemsIndexed(prompt.entries, key = { i, _ -> i }) { i, entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = checked[i], role = Role.Checkbox) { checked[i] = it }
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .alpha(if (checked[i]) 1f else 0.6f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%02d".format(i + 1),
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(28.dp),
                        )
                        // Only rows on screen exist (LazyColumn), so only they load; see Thumbnail's listRow
                        Thumbnail(entry.thumbnail, Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(10.dp)), listRow = true)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val subtitle = entry.detail ?: entry.url
                            if (subtitle.isNotBlank()) {
                                Text(subtitle, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Checkbox(checked = checked[i], onCheckedChange = null)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
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
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(72.dp).padding(horizontal = 4.dp),
    )
}
