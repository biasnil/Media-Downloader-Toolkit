package com.nil.mediadownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CompletableDeferred

/** One tile in the grid picker: a video of a post, or a picture. */
data class PickItem(val title: String, val thumbnail: String?, val isImage: Boolean)

/** A post with several photos/videos, waiting for the user to tick which ones they want. */
class MediaPrompt(
    val title: String,
    val items: List<PickItem>,
    val preselected: Set<Int>,
    val answer: CompletableDeferred<List<Int>?> = CompletableDeferred(),
)

/**
 * iOS-style picker for Other Sites: a grid of tiles to tick, with Photos only / Videos only
 * when a post has both. YouTube playlists and Spotify keep the list picker (better for long lists).
 */
@Composable
fun MediaPickerDialog(prompt: MediaPrompt, onConfirm: (List<Int>) -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val items = prompt.items
    val selected = remember(prompt) { mutableStateListOf<Int>().apply { addAll(prompt.preselected.sorted()) } }
    val photos = items.count { it.isImage }
    val videos = items.size - photos
    fun pick(filter: (PickItem) -> Boolean) {
        selected.clear()
        selected.addAll(items.indices.filter { filter(items[it]) })
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize().systemBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onCancel) {
                                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.skip))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(prompt.title, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        photos > 0 && videos > 0 -> stringResource(R.string.picker_mix, photos, videos)
                                        photos > 0 -> stringResource(R.string.picker_count_photos, photos)
                                        else -> stringResource(R.string.picker_count_videos, videos)
                                    },
                                    color = colors.onSurfaceVariant,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(
                                stringResource(R.string.download_count, selected.size),
                                Modifier.weight(1f).heightIn(min = 52.dp),
                                enabled = selected.isNotEmpty(),
                                icon = R.drawable.ic_download,
                            ) { onConfirm(selected.sorted()) }
                            SecondaryButton(stringResource(R.string.skip), Modifier.heightIn(min = 52.dp), onClick = onCancel)
                        }
                        Row(
                            Modifier.padding(top = 10.dp, bottom = 4.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ChoiceChip(stringResource(R.string.select_all), selected.size == items.size, true) { pick { true } }
                            // Only useful when the post mixes both
                            if (photos > 0 && videos > 0) {
                                ChoiceChip(stringResource(R.string.photos_only), false, true) { pick { it.isImage } }
                                ChoiceChip(stringResource(R.string.videos_only), false, true) { pick { !it.isImage } }
                            }
                            ChoiceChip(stringResource(R.string.select_none), selected.isEmpty(), true) { selected.clear() }
                        }
                    }
                }
                itemsIndexed(items) { i, item ->
                    val on = i in selected
                    Tile(item, on) { if (on) selected.remove(i) else selected.add(i) }
                }
            }
        }
    }
}

@Composable
private fun Tile(item: PickItem, on: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val art = Brush.linearGradient(LocalPalette.current.art)
    // Decoded at tile size, so a 60-photo post doesn't fill memory with full-size pictures
    val request = remember(item.thumbnail) { item.thumbnail?.let { ImageRequest.Builder(context).data(it).size(400, 400).build() } }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(art)
            .border(if (on) 3.dp else 1.dp, if (on) colors.primary else colors.outline, RoundedCornerShape(14.dp))
            .toggleable(value = on, role = Role.Checkbox) { onToggle() }
            .alpha(if (on) 1f else 0.6f),
    ) {
        if (request != null) {
            AsyncImage(model = request, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else {
            Text(
                item.title,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        }
        // Tick, top right
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (on) colors.primary else Color.Black.copy(alpha = 0.35f))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Text("✓", color = colors.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        // Photo or video, bottom left
        Text(
            stringResource(if (item.isImage) R.string.tile_photo else R.string.tile_video),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
