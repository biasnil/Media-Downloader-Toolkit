package com.nil.mediadownloader

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** What a pasted link's card shows. [title] null = not looked up (or the lookup failed). */
data class LinkPreview(
    val site: String,
    val title: String? = null,
    val author: String? = null,
    val thumbnail: String? = null,
    val isPlaylist: Boolean = false,
)

/**
 * Titles and thumbnails for the "Ready to download" cards. YouTube and Spotify links use
 * the sites' public oEmbed pages (one small request, no yt-dlp), so cards fill in almost
 * at once and never get in the way of a download. Other sites just show their name.
 */
object LinkPreviews {
    private val cache = mutableStateMapOf<String, LinkPreview>()
    private val asked = mutableSetOf<String>()

    fun cached(url: String): LinkPreview? = cache[url]

    /** What's known without the network: the site, and for YouTube the thumbnail. */
    fun basic(url: String): LinkPreview {
        val isYouTube = YouTubeLinks.isYouTube(url)
        val videoId = if (isYouTube) YouTubeLinks.videoId(url) else null
        return LinkPreview(
            site = siteName(url),
            thumbnail = videoId?.let { YouTubeLinks.thumbnail(it) },
            isPlaylist = (isYouTube && videoId == null && YouTubeLinks.playlistId(url) != null) ||
                (SpotifyLinks.isSpotify(url) && ("/playlist/" in url || "/album/" in url)),
        )
    }

    /** "youtube.com", "tiktok.com"... without www. or m. */
    fun siteName(url: String): String {
        val host = try { Uri.parse(url).host.orEmpty() } catch (e: Exception) { "" }
        return host.removePrefix("www.").removePrefix("m.").ifEmpty { url }
    }

    /** Looks [url] up once; later calls do nothing. */
    suspend fun load(url: String) {
        if (!asked.add(url)) return
        val base = basic(url)
        cache[url] = base
        val endpoint = when {
            YouTubeLinks.isYouTube(url) -> "https://www.youtube.com/oembed?format=json&url="
            SpotifyLinks.isSpotify(url) -> "https://open.spotify.com/oembed?url="
            else -> return
        }
        val json = withContext(Dispatchers.IO) {
            try {
                val conn = URL(endpoint + URLEncoder.encode(url, "UTF-8")).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                try {
                    if (conn.responseCode == 200) JSONObject(conn.inputStream.bufferedReader().readText()) else null
                } finally { conn.disconnect() }
            } catch (e: Exception) { null }
        } ?: return
        cache[url] = base.copy(
            title = json.optString("title").ifBlank { null },
            author = json.optString("author_name").ifBlank { null },
            thumbnail = json.optString("thumbnail_url").ifBlank { null } ?: base.thumbnail,
        )
    }
}

/**
 * "Ready to download": a card per link in the box, under it. ✕ takes that line out of [text].
 * Tablets get a 3-across grid like the mockup; phones a list.
 */
@Composable
fun LinkCards(text: String, enabled: Boolean, onChange: (String) -> Unit) {
    val links = remember(text) { linesOf(text).filter { it.startsWith("http", ignoreCase = true) }.distinct() }
    if (links.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val wide = LocalWideLayout.current
    val scope = rememberCoroutineScope()
    // Wait for typing to settle before looking anything up
    LaunchedEffect(links) {
        delay(500)
        links.take(30).forEach { scope.launch { LinkPreviews.load(it) } }
    }
    val remove = { url: String -> onChange(linesOf(text).filter { it != url }.joinToString("\n")) }

    Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ready_to_download), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(pluralStringResource(R.plurals.link_count, links.size, links.size), color = colors.onSurfaceVariant, fontSize = 13.sp)
    }
    if (wide) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            links.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { url -> GridCard(url, enabled, Modifier.weight(1f)) { remove(url) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            links.forEach { url -> ListCard(url, enabled) { remove(url) } }
        }
    }
}

@Composable
private fun GridCard(url: String, enabled: Boolean, modifier: Modifier, onRemove: () -> Unit) {
    val p = LinkPreviews.cached(url) ?: LinkPreviews.basic(url)
    Column(modifier.clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Thumbnail(p.thumbnail, Modifier.fillMaxSize())
            if (p.isPlaylist) PlaylistBadge(Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        CardText(url, p, enabled, Modifier.padding(start = 14.dp, top = 8.dp, bottom = 10.dp), onRemove)
    }
}

@Composable
private fun ListCard(url: String, enabled: Boolean, onRemove: () -> Unit) {
    val p = LinkPreviews.cached(url) ?: LinkPreviews.basic(url)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 104.dp, height = 58.dp).clip(RoundedCornerShape(12.dp))) {
            Thumbnail(p.thumbnail, Modifier.fillMaxSize())
            if (p.isPlaylist) PlaylistBadge(Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        CardText(url, p, enabled, Modifier.weight(1f).padding(start = 12.dp), onRemove)
    }
}

@Composable
private fun CardText(url: String, p: LinkPreview, enabled: Boolean, modifier: Modifier, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.title ?: url, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(p.author, p.site).distinct().joinToString(" · "),
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.remove_link), modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * A thumbnail with a spinner while it loads, or the theme's color block when there isn't one.
 * [listRow]: for long lists (playlists). Fetches the small version, decodes it at row size, and
 * keeps it out of the in-memory cache, so a row's picture is freed once it scrolls off screen
 * (scrolling back reads it from the disk cache, not the network).
 */
@Composable
fun Thumbnail(url: String?, modifier: Modifier, listRow: Boolean = false) {
    val art = Brush.linearGradient(LocalPalette.current.art)
    if (url == null) {
        Box(modifier.background(art))
        return
    }
    val context = LocalContext.current
    var loading by remember(url) { mutableStateOf(true) }
    val request = remember(url, listRow) {
        ImageRequest.Builder(context)
            .data(if (listRow) YouTubeLinks.smallThumbnail(url) else url)
            .apply {
                if (listRow) {
                    size(320, 180)
                    memoryCachePolicy(CachePolicy.DISABLED)
                }
            }
            .build()
    }
    Box(modifier.background(art), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onState = { loading = it is AsyncImagePainter.State.Loading },
            modifier = Modifier.matchParentSize(),
        )
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White.copy(alpha = 0.85f), strokeWidth = 2.dp)
    }
}

@Composable
private fun PlaylistBadge(modifier: Modifier) {
    Text(
        stringResource(R.string.playlist_default),
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
