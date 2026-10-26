package com.nil.mediadownloader

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.text.Html
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

// Spotify's audio is copy-protected, so (like desktop's Spotify tab) every song is matched to its
// closest equivalent on YouTube and downloaded from there. No Spotify login is used anywhere here.

private const val EXPORTIFY_URL = "https://exportify.net"

/** A song to find on YouTube: from a Spotify page, an Exportify file, or typed text (title only). */
data class SpotifyTrack(val title: String, val artists: String = "", val durationMs: Long = 0, val link: String = "") {
    /** "Artist - Title", or just the title. */
    val label: String get() = if (artists.isNotBlank()) "$artists - $title" else title
}

data class SpotifyPlaylist(val title: String, val tracks: List<SpotifyTrack>)

// ---------------------------------------------------------------- Links + pages

object SpotifyLinks {
    // open.spotify.com/track|playlist|album/<id>, with or without the "/intl-xx/" prefix shared links can have
    private val LINK = Regex("""open\.spotify\.com/(?:intl-[a-z]{2}(?:-[a-z]{2})?/)?(?:embed/)?(track|playlist|album)/([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    fun isSpotify(link: String): Boolean {
        val host = try { Uri.parse(link.trim()).host?.lowercase() ?: "" } catch (e: Exception) { "" }
        return host.endsWith("spotify.com") || host == "spotify.link"
    }

    /** (kind, id), e.g. ("playlist", "37i9dQZF1..."), or null. */
    fun parse(link: String): Pair<String, String>? =
        LINK.find(link)?.let { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** spotify:track:ID (Exportify, embed pages) -> https://open.spotify.com/track/ID */
    fun fromUri(uri: String): String {
        val parts = uri.split(':')
        return if (parts.size == 3 && parts[0] == "spotify") "https://open.spotify.com/${parts[1]}/${parts[2]}" else ""
    }
}

object SpotifyPages {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    // Link-preview bots (Slack, Discord, Facebook) get the page's meta tags straight away, no JavaScript.
    // That's what these tags are for, so this is the intended way to read them (same as desktop).
    private const val PREVIEW_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private fun get(url: String, userAgent: String): Pair<String, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException(tr(R.string.err_spotify_http, conn.responseCode))
            return conn.inputStream.bufferedReader().use { it.readText() } to conn.url.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** spotify.link short links open the app's link page; find the open.spotify.com link behind it. */
    fun resolve(link: String): String {
        if (SpotifyLinks.parse(link) != null) return link
        val (body, finalUrl) = get(link, BROWSER_UA)
        if (SpotifyLinks.parse(finalUrl) != null) return finalUrl
        return Regex("""https://open\.spotify\.com/(?:track|playlist|album)/[A-Za-z0-9]+""").find(body)?.value ?: link
    }

    /**
     * Reads a track, playlist or album from Spotify's public embed page (the player other sites
     * embed). It carries the song list with titles, artists and lengths, and needs no login.
     */
    fun read(kind: String, id: String): SpotifyPlaylist {
        val (body, _) = get("https://open.spotify.com/embed/$kind/$id", BROWSER_UA)
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: throw IllegalStateException(tr(R.string.err_no_song_data))
        val entity = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        val name = entity.optString("name").ifBlank { entity.optString("title") }

        if (kind == "track") {
            val artists = entity.optJSONArray("artists")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            }.orEmpty().joinToString(", ")
            if (name.isBlank()) throw IllegalStateException(tr(R.string.err_no_title))
            val track = SpotifyTrack(name, artists, entity.optLong("duration"), "https://open.spotify.com/track/$id")
            return SpotifyPlaylist(track.label, listOf(track))
        }

        val list = entity.optJSONArray("trackList") ?: throw IllegalStateException(tr(R.string.err_no_song_list))
        val tracks = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val title = t.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SpotifyTrack(
                title = title,
                artists = t.optString("subtitle").replace(' ', ' ').trim(),
                durationMs = t.optLong("duration"),
                link = SpotifyLinks.fromUri(t.optString("uri")),
            )
        }
        return SpotifyPlaylist(name.ifBlank { "Spotify $kind" }, tracks)
    }

    private val META = Regex("""<meta\s+(?:property|name)="([^"]+)"\s+content="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val LISTEN_TO = Regex("""^Listen to (.+?) on Spotify""", RegexOption.IGNORE_CASE)
    private val PLAYLIST_DESC = Regex("""^Playlist\s*[·:]\s*(.+?)\s*[·:]""", RegexOption.IGNORE_CASE)
    private val TITLE_SUFFIX = Regex("""\s*[|-]\s*(Album by|song and lyrics by|playlist by).*$""", RegexOption.IGNORE_CASE)
    private val TITLE_SITE = Regex("""\s*\|\s*Spotify\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Backup when the embed page can't be read: the link-preview meta tags, as desktop does.
     * Gives a title always, and artist + length for a track; a playlist or album is just its name.
     */
    fun readPreview(link: String): SpotifyPlaylist {
        val (body, _) = get(link, PREVIEW_UA)
        val tags = mutableMapOf<String, String>()
        META.findAll(body).forEach { m ->
            tags.putIfAbsent(m.groupValues[1].lowercase(), Html.fromHtml(m.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString())
        }
        val description = tags["description"] ?: tags["og:description"] ?: ""
        val title = LISTEN_TO.find(description)?.groupValues?.get(1)?.trim()
            ?: PLAYLIST_DESC.find(description)?.groupValues?.get(1)?.trim()
            ?: (tags["og:title"] ?: tags["twitter:title"])?.replace(TITLE_SUFFIX, "")?.replace(TITLE_SITE, "")?.trim()
        if (title.isNullOrBlank()) throw IllegalStateException(tr(R.string.err_no_preview_title))
        val artist = tags["music:musician_description"]?.trim().orEmpty()
        val durationMs = ((tags["music:duration"]?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        val track = SpotifyTrack(title, artist, durationMs, link)
        return SpotifyPlaylist(track.label, listOf(track))
    }
}

// ---------------------------------------------------------------- Exportify

/**
 * Reads a playlist exported from Exportify (exportify.net) as a CSV, or a ZIP of several from its
 * "Export All". Exportify does the Spotify login itself, outside this app; this only reads its file.
 */
object Exportify {
    private val TITLE_COLUMNS = listOf("Track Name")
    private val ARTIST_COLUMNS = listOf("Artist Name(s)", "Artist Name")
    private val DURATION_COLUMNS = listOf("Duration (ms)", "Track Duration (ms)")
    private val URI_COLUMNS = listOf("Track URI")
    private val BOM = 0xFEFF.toChar().toString()

    fun read(context: Context, uri: Uri): List<SpotifyPlaylist> {
        val name = DocumentFile.fromSingleUri(context, uri)?.name ?: "playlist.csv"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException(tr(R.string.err_open_file))
        val isZip = bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val playlists = if (isZip) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }
                    .filter { !it.isDirectory && it.name.endsWith(".csv", ignoreCase = true) }
                    .map { entry -> parse(zip.readBytes().toString(Charsets.UTF_8), titleFrom(entry.name)) }
                    .filter { it.tracks.isNotEmpty() }
                    .toList()
            }
        } else {
            listOf(parse(bytes.toString(Charsets.UTF_8), titleFrom(name))).filter { it.tracks.isNotEmpty() }
        }
        if (playlists.isEmpty()) throw IllegalStateException(tr(if (isZip) R.string.err_zip_empty else R.string.err_csv_empty))
        return playlists
    }

    /** Exportify names each CSV after its playlist (spaces as underscores); the CSV itself has no name column. */
    private fun titleFrom(fileName: String): String =
        fileName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { tr(R.string.imported_playlist) }

    private fun parse(text: String, title: String): SpotifyPlaylist {
        val rows = csvRows(text.removePrefix(BOM)) // Exportify's CSVs start with one
        val header = rows.firstOrNull() ?: return SpotifyPlaylist(title, emptyList())
        fun column(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val titleCol = column(TITLE_COLUMNS) ?: return SpotifyPlaylist(title, emptyList())
        val artistCol = column(ARTIST_COLUMNS)
        val durationCol = column(DURATION_COLUMNS)
        val uriCol = column(URI_COLUMNS)
        val tracks = rows.drop(1).mapNotNull { row ->
            val name = row.getOrNull(titleCol)?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            // Exportify separates several artists with ";"
            val artists = artistCol?.let { row.getOrNull(it) }.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            SpotifyTrack(
                title = name,
                artists = artists,
                durationMs = durationCol?.let { row.getOrNull(it)?.trim()?.toDoubleOrNull()?.toLong() } ?: 0L,
                link = uriCol?.let { row.getOrNull(it) }?.let { SpotifyLinks.fromUri(it.trim()) }.orEmpty(),
            )
        }
        return SpotifyPlaylist(title, tracks)
    }

    /** A small CSV reader: quoted fields can hold commas, quotes ("") and line breaks. */
    private fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\r', '\n' -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}

// ---------------------------------------------------------------- Matching

/**
 * Finds the best YouTube video for a song, like desktop's spotify_match.py: several searches
 * ("Title Artist <tag>" and "Artist Title <tag>"), every result pooled, each scored 0–100 on
 * how close the title is and how close the length is.
 */
object SpotifyMatcher {
    private const val RESULTS_PER_QUERY = 3
    private const val DURATION_TOLERANCE_SEC = 12.0

    fun tags(): List<String> =
        AppSettings.spotifySearchTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { AppSettings.DEFAULT_SPOTIFY_TAGS.split(",").map { it.trim() } }

    fun queries(track: SpotifyTrack, tags: List<String>): List<String> {
        val title = track.title.trim()
        val artist = track.artists.trim()
        // Title only (typed text, or a playlist's name): nothing to reorder
        if (artist.isEmpty() || artist.equals(title, ignoreCase = true)) return tags.map { "$title $it" }
        return tags.flatMap { listOf("$title $artist $it", "$artist $title $it") }
    }

    fun score(track: SpotifyTrack, result: SearchResult): Int {
        val titleScore = similarity(normalize("${track.artists} ${track.title}"), normalize(result.title))
        val targetSec = track.durationMs / 1000.0
        // A "Live" or "10 hour loop" version can match the title perfectly but has the wrong length
        val durationPenalty = if (targetSec > 0 && result.seconds > 0) {
            minOf(abs(targetSec - result.seconds) / DURATION_TOLERANCE_SEC, 1.0) * 0.4
        } else 0.1 // unknown length: a little less sure either way
        return ((titleScore - durationPenalty) * 100).roundToInt().coerceIn(0, 100)
    }

    /** Runs every search and returns the best result and its score (null when nothing came back). */
    suspend fun findBest(task: TaskState, track: SpotifyTrack, log: (String) -> Unit): Pair<SearchResult?, Int> {
        val seen = mutableSetOf<String>()
        val pooled = mutableListOf<SearchResult>()
        for (query in queries(track, tags())) {
            if (task.cancelled) throw YoutubeDL.CanceledException()
            val results = try {
                withContext(Dispatchers.IO) { Downloader.search(task, query, RESULTS_PER_QUERY) }
            } catch (e: YoutubeDL.CanceledException) {
                throw e
            } catch (e: Exception) {
                // Without this, a broken YouTube search in yt-dlp looks the same as "nothing found"
                log(tr(R.string.log_search_failed, query, errorReason(e)))
                continue
            }
            if (results.isEmpty()) log(tr(R.string.log_search_empty, query))
            results.filter { seen.add(it.id) }.let { pooled += it }
        }
        val best = pooled.maxByOrNull { score(track, it) } ?: return null to 0
        return best to score(track, best)
    }

    private fun normalize(text: String) =
        text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    /** Same as Python's difflib.SequenceMatcher(None, a, b).ratio(), which desktop uses. */
    private fun similarity(a: String, b: String): Double =
        if (a.isEmpty() && b.isEmpty()) 1.0 else 2.0 * matchingChars(a, b) / (a.length + b.length)

    /** Longest common run, then the same on each side of it (Ratcliff/Obershelp). */
    private fun matchingChars(a: String, b: String): Int {
        var bestLen = 0
        var bestA = 0
        var bestB = 0
        var prev = IntArray(b.length + 1)
        for (i in a.indices) {
            val cur = IntArray(b.length + 1)
            for (j in b.indices) {
                if (a[i] == b[j]) {
                    cur[j + 1] = prev[j] + 1
                    if (cur[j + 1] > bestLen) {
                        bestLen = cur[j + 1]
                        bestA = i - bestLen + 1
                        bestB = j - bestLen + 1
                    }
                }
            }
            prev = cur
        }
        if (bestLen == 0) return 0
        return bestLen +
            matchingChars(a.substring(0, bestA), b.substring(0, bestB)) +
            matchingChars(a.substring(bestA + bestLen), b.substring(bestB + bestLen))
    }
}

// ---------------------------------------------------------------- State

enum class MatchAction { CONTINUE, SKIP, RETRY }

/** No confident match for a song: download the closest anyway, skip it, or search again. */
class MatchPrompt(
    val label: String,
    val bestTitle: String?,
    val score: Int,
    val answer: CompletableDeferred<Pair<MatchAction, Boolean>> = CompletableDeferred(),
)

/** An Exportify ZIP with several playlists: which one to import. */
class ChoicePrompt(val playlists: List<SpotifyPlaylist>, val answer: CompletableDeferred<Int?> = CompletableDeferred())

/** Runs Spotify downloads and holds what that tab shows. */
class SpotifyState {
    var urlText by mutableStateOf("")
    val task = TaskState(R.string.tab_spotify)
    var tracksPrompt by mutableStateOf<PlaylistPrompt?>(null)
    var choicePrompt by mutableStateOf<ChoicePrompt?>(null)
    var matchPrompt by mutableStateOf<MatchPrompt?>(null)

    /** Only one "no confident match" question on screen at once, even with several downloading. */
    private val promptLock = Mutex()
    /** "Do this for the rest of this batch" */
    private var remembered: MatchAction? = null

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("spotify_tab", Context.MODE_PRIVATE)

    /** The Download button: every link or song name in the box. */
    fun start(context: Context) {
        val lines = linesOf(urlText)
        if (lines.isEmpty()) { task.status = tr(R.string.spotify_paste); return }
        begin(context) { fromLines(it, lines) }
    }

    /** Import CSV/ZIP... */
    fun importFile(context: Context, uri: Uri) = begin(context) { fromFile(it, uri) }

    /** A link shared from another app, the clipboard, or History. Waits its turn if a download is running. */
    fun enqueue(context: Context, link: String) {
        if (task.running) {
            task.queued += link
            task.addLog(tr(R.string.log_queued, link))
        } else {
            task.addLog(tr(R.string.log_link_received))
            begin(context) { fromLines(it, listOf(link)) }
        }
    }

    fun cancel() {
        task.queued.clear()
        tracksPrompt?.answer?.complete(null)
        choicePrompt?.answer?.complete(null)
        matchPrompt?.answer?.complete(MatchAction.SKIP to false)
        Downloader.cancel(task)
        if (task.running) task.status = tr(R.string.status_cancelling)
    }

    private fun begin(context: Context, work: suspend (Context) -> Unit) {
        if (task.running) return
        val app = context.applicationContext
        startJob(app, task)
        AppState.scope.launch {
            if (prepare(app, task)) {
                work(app)
                while (!task.cancelled && task.queued.isNotEmpty()) {
                    val next = task.queued.toList()
                    task.queued.clear()
                    fromLines(app, next)
                }
            }
            endJob(app, task)
        }
    }

    // ---- 1. Work out the songs

    private suspend fun fromLines(context: Context, lines: List<String>) {
        val tracks = mutableListOf<SpotifyTrack>()
        for ((i, line) in lines.withIndex()) {
            if (task.cancelled) break
            val tag = if (lines.size > 1) "[${i + 1}/${lines.size}]" else "[1/1]"
            task.addLog("$tag $line")
            task.status = tr(R.string.status_reading, tag)
            read(line, tag)?.let { tracks += it }
        }
        when {
            task.cancelled -> task.status = tr(R.string.status_cancelled)
            tracks.isEmpty() -> task.status = tr(R.string.status_nothing)
            else -> download(context, tracks)
        }
    }

    /** One line of the box: a Spotify link (track, playlist, album) or text to search for. */
    private suspend fun read(line: String, tag: String): List<SpotifyTrack>? {
        if (!SpotifyLinks.isSpotify(line)) {
            task.addLog(tr(R.string.log_not_spotify, tag))
            return listOf(SpotifyTrack(line))
        }
        val link = try { withContext(Dispatchers.IO) { SpotifyPages.resolve(line) } } catch (e: Exception) { line }
        val (kind, id) = SpotifyLinks.parse(link) ?: run {
            task.addLog(tr(R.string.log_not_spotify_kind, tag))
            return listOf(SpotifyTrack(line))
        }
        val playlist = try {
            withContext(Dispatchers.IO) { SpotifyPages.read(kind, id) }
        } catch (e: Exception) {
            task.addLog(tr(R.string.log_embed_failed, tag, e.message ?: ""))
            try {
                withContext(Dispatchers.IO) { SpotifyPages.readPreview(link) }
            } catch (e2: Exception) {
                task.addLog(tr(R.string.log_preview_failed, tag, e2.message ?: ""))
                return listOf(SpotifyTrack(line))
            }
        }
        if (kind == "track" || playlist.tracks.size <= 1) {
            // The link-preview backup only has a playlist's or album's name, with no artist
            if (kind != "track" && playlist.tracks.all { it.artists.isBlank() }) {
                task.addLog(tr(R.string.log_only_name, tag, playlist.title))
            }
            return playlist.tracks.ifEmpty { listOf(SpotifyTrack(playlist.title, link = link)) }
        }
        task.addLog(tr(R.string.log_playlist_songs, tag, playlist.title, playlist.tracks.size))
        if (playlist.tracks.size >= 100) task.addLog(tr(R.string.log_100_limit, tag))
        return pick(playlist, tag)
    }

    private suspend fun fromFile(context: Context, uri: Uri) {
        task.status = tr(R.string.status_reading_file)
        val playlists = try {
            withContext(Dispatchers.IO) { Exportify.read(context, uri) }
        } catch (e: Exception) {
            task.addLog(tr(R.string.log_file_failed, e.message ?: ""))
            task.status = tr(R.string.status_file_failed)
            return
        }
        val playlist = if (playlists.size == 1) playlists[0] else {
            val prompt = ChoicePrompt(playlists)
            choicePrompt = prompt
            task.status = tr(R.string.status_waiting_choice)
            val index = prompt.answer.await()
            choicePrompt = null
            if (index == null) {
                task.addLog(tr(R.string.log_import_cancelled))
                task.status = tr(if (task.cancelled) R.string.status_cancelled else R.string.log_import_cancelled)
                return
            }
            playlists[index]
        }
        task.addLog(tr(R.string.log_importing, playlist.title, playlist.tracks.size))
        val picked = pick(playlist, "") ?: run {
            task.status = tr(if (task.cancelled) R.string.status_cancelled else R.string.log_import_cancelled)
            return
        }
        download(context, picked)
    }

    /** Tick which songs of a playlist/album to download. null = skipped. */
    private suspend fun pick(playlist: SpotifyPlaylist, tag: String): List<SpotifyTrack>? {
        val entries = playlist.tracks.map { t ->
            VideoEntry(t.link, t.title, detail = listOfNotNull(t.artists.ifBlank { null }, duration(t.durationMs)).joinToString(" · "))
        }
        val prompt = PlaylistPrompt(playlist.title, entries, entries.indices.toSet(), countText = R.string.picker_count_songs)
        tracksPrompt = prompt
        task.status = tr(R.string.status_waiting_songs)
        val picked = prompt.answer.await()
        tracksPrompt = null
        if (picked == null) {
            if (!task.cancelled) task.addLog(tr(R.string.log_skipped_playlist, tag, playlist.title).trim())
            return null
        }
        task.addLog(tr(R.string.log_songs_picked, tag, picked.size, playlist.tracks.size).trim())
        return picked.map { playlist.tracks[it] }
    }

    // ---- 2. Match and download, several at once if set in Settings

    private suspend fun download(context: Context, tracks: List<SpotifyTrack>) {
        val prefs = prefs(context)
        val o = savedOptions(context, prefs, "mp3", MUSIC_FORMATS)
        val existing = try {
            withContext(Dispatchers.IO) { Downloader.existingNames(context, o.folder) }.toMutableSet()
        } catch (e: Exception) { mutableSetOf() }
        remembered = null

        val tracker = BatchTracker(tracks.size)
        val workRoot = File(context.cacheDir, "work-spotify")
        val atOnce = o.atOnce.coerceAtMost(tracks.size)
        if (atOnce > 1) task.addLog(tr(R.string.log_at_once, atOnce))
        val label = { n: Int -> "[${n + 1}/${tracks.size}]" }
        tracker.show(task, label)
        runLimited(task, tracks, atOnce) { n, track ->
            downloadOne(context, track, n, o, existing, tracker, File(workRoot, "$n"), label)
        }

        workRoot.deleteRecursively()
        task.progress = 0f
        task.status = if (task.cancelled) tr(R.string.status_yt_cancelled, tracker.done)
        else tr(R.string.status_yt_finished, tracker.done, tracker.skipped, tracker.failed)
    }

    /** One song: skip check, YouTube search, (maybe) ask, download, save, history. */
    private suspend fun downloadOne(
        context: Context,
        track: SpotifyTrack,
        n: Int,
        o: DownloadOptions,
        existing: MutableSet<String>,
        tracker: BatchTracker,
        workDir: File,
        label: (Int) -> String,
    ) {
        val tag = label(n)
        val source = task.name
        val fileBase = safeFileName(track.label)
        val name = "$fileBase.${o.format}"
        if (o.skipExisting && name in existing) {
            task.addLog(tr(R.string.log_skipped_exists, tag, track.label))
            HistoryStore.add(source, track.label, track.link, HistoryStatus.SKIPPED, detail = tr(R.string.already_in_folder), fileName = name)
            tracker.skipped++
            tracker.finished++
            tracker.show(task, label)
            return
        }

        tracker.active[n] = 0f to tr(R.string.status_searching)
        tracker.show(task, label)
        var videoUrl = ""
        try {
            task.addLog(tr(R.string.log_searching, tag, track.label))
            val log = { msg: String -> task.addLog("$tag   $msg") }
            var (best, score) = SpotifyMatcher.findBest(task, track, log)
            while (best == null || score < AppSettings.spotifyConfidence) {
                if (task.cancelled) throw YoutubeDL.CanceledException()
                val action = ask(tag, track, best, score)
                if (action == MatchAction.RETRY) {
                    task.addLog(tr(R.string.log_search_again, tag, track.label))
                    val again = SpotifyMatcher.findBest(task, track, log)
                    best = again.first
                    score = again.second
                } else if (action == MatchAction.CONTINUE && best != null) {
                    task.addLog(tr(R.string.log_low_confidence, tag, score, best.title))
                    break
                } else {
                    if (task.cancelled) throw YoutubeDL.CanceledException()
                    task.addLog(tr(R.string.log_skipped_song, tag, track.label))
                    HistoryStore.add(
                        source, track.label, track.link, HistoryStatus.SKIPPED,
                        detail = if (best != null) tr(R.string.no_confident_match, score) else tr(R.string.no_match_found),
                    )
                    tracker.skipped++
                    return
                }
            }
            val match = best ?: return
            videoUrl = "https://www.youtube.com/watch?v=${match.id}"
            tracker.update(task, n, 0f, tr(R.string.downloading), label)

            val file = withRetries(task, tag) {
                withContext(Dispatchers.IO) {
                    Downloader.downloadSpotify(
                        task, videoUrl, fileBase, track.title, track.artists, o, workDir,
                        progressCallback(AppState.scope) { f, text -> tracker.update(task, n, f, text, label) },
                    )
                }
            }
            val saved = withContext(Dispatchers.IO) { Downloader.save(context, file, o.folder) }
            existing += file.name
            task.addLog(tr(R.string.log_spotify_done, tag, track.label, match.title, score))
            HistoryStore.add(
                source, track.label, track.link.ifEmpty { videoUrl }, HistoryStatus.DONE,
                detail = tr(R.string.matched_detail, score, match.title), fileName = file.name, fileUri = saved,
            )
            tracker.done++
        } catch (e: YoutubeDL.CanceledException) {
            task.addLog(tr(R.string.log_cancelled, tag))
            HistoryStore.add(source, track.label, track.link.ifEmpty { videoUrl }, HistoryStatus.CANCELLED)
        } catch (e: Exception) {
            if (task.cancelled) {
                task.addLog(tr(R.string.log_cancelled, tag))
                HistoryStore.add(source, track.label, track.link.ifEmpty { videoUrl }, HistoryStatus.CANCELLED)
            } else {
                logFailure(context, task, tag, e)
                HistoryStore.add(source, track.label, track.link.ifEmpty { videoUrl }, HistoryStatus.FAILED, detail = errorReason(e))
                tracker.failed++
            }
        } finally {
            tracker.active.remove(n)
            tracker.finished++
            tracker.show(task, label)
            workDir.deleteRecursively()
        }
    }

    private suspend fun ask(tag: String, track: SpotifyTrack, best: SearchResult?, score: Int): MatchAction =
        promptLock.withLock {
            if (task.cancelled) return@withLock MatchAction.SKIP
            // A remembered "continue" still needs asking when there's nothing to continue with
            remembered?.let { r -> if (!(r == MatchAction.CONTINUE && best == null)) return@withLock r }
            val prompt = MatchPrompt("$tag ${track.label}", best?.title, score)
            matchPrompt = prompt
            val (action, remember) = prompt.answer.await()
            matchPrompt = null
            if (remember && action != MatchAction.RETRY && best != null) {
                remembered = action
                task.addLog(tr(if (action == MatchAction.SKIP) R.string.log_remember_skip else R.string.log_remember_continue, tag))
            }
            action
        }
}

private fun safeFileName(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(150).ifEmpty { "track" }

private fun duration(ms: Long): String? {
    if (ms <= 0) return null
    val sec = ms / 1000
    return "%d:%02d".format(sec / 60, sec % 60)
}

// ---------------------------------------------------------------- Screen

@Composable
fun SpotifyTab(state: SpotifyState, scope: CoroutineScope, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val task = state.task
    val prefs = remember { state.prefs(context) }
    var folderUri by remember { mutableStateOf(loadSavedFolder(context, prefs.getString("folder", null))) }
    val idle = !task.running
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importFile(context, uri)
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        UrlHeader(stringResource(R.string.spotify_links_label), idle, onPaste = { state.urlText = appendLine(state.urlText, it) })
        UrlBox(state.urlText, { state.urlText = it }, enabled = idle, placeholder = stringResource(R.string.spotify_placeholder))
        Text(
            stringResource(R.string.spotify_note),
            color = colors.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        Column(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(stringResource(R.string.exportify_note), color = colors.onSurfaceVariant, fontSize = 12.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.go_to_exportify)) { Reports.openLink(context, EXPORTIFY_URL) }
                SecondaryButton(stringResource(R.string.import_file), enabled = idle) {
                    importer.launch(arrayOf("text/*", "application/zip", "application/vnd.ms-excel", "application/octet-stream"))
                }
            }
        }

        SaveFolderRow(folderUri, enabled = idle) { uri ->
            folderUri = uri
            prefs.edit().apply { if (uri == null) remove("folder") else putString("folder", uri.toString()) }.apply()
        }

        FormatOptions(prefs, "mp3", idle, m4aNote = "", formats = MUSIC_FORMATS)

        ActionButtons(task, folderUri, onDownload = { state.start(context) }, onCancel = { state.cancel() })
        ProgressAndLog(task, scope)
    }
}

/** The Spotify tab's questions. Shown from MainActivity so they appear whichever tab is open. */
@Composable
fun SpotifyPrompts(state: SpotifyState) {
    state.tracksPrompt?.let { prompt ->
        PlaylistDialog(prompt = prompt, onConfirm = { prompt.answer.complete(it) }, onCancel = { prompt.answer.complete(null) })
    }
    state.choicePrompt?.let { ChoiceDialog(it) }
    state.matchPrompt?.let { MatchDialog(it) }
}

@Composable
private fun ChoiceDialog(prompt: ChoicePrompt) {
    AlertDialog(
        onDismissRequest = { prompt.answer.complete(null) },
        title = { Text(stringResource(R.string.pick_playlist)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.pick_playlist_text, prompt.playlists.size))
                prompt.playlists.forEachIndexed { i, p ->
                    TextButton(onClick = { prompt.answer.complete(i) }) {
                        Text(stringResource(R.string.playlist_songs, p.title, p.tracks.size))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { prompt.answer.complete(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MatchDialog(prompt: MatchPrompt) {
    val colors = MaterialTheme.colorScheme
    var rememberChoice by remember(prompt) { mutableStateOf(false) }
    fun answer(action: MatchAction) = prompt.answer.complete(action to rememberChoice)
    AlertDialog(
        onDismissRequest = { answer(MatchAction.SKIP) },
        title = { Text(stringResource(R.string.match_title)) },
        text = {
            Column {
                Text(prompt.label, fontWeight = FontWeight.Bold)
                Text(
                    if (prompt.bestTitle != null) stringResource(R.string.match_best, prompt.bestTitle, prompt.score, AppSettings.spotifyConfidence)
                    else stringResource(R.string.match_none),
                    color = colors.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(stringResource(if (prompt.bestTitle != null) R.string.match_question else R.string.match_question_none))
                if (prompt.bestTitle != null) {
                    CheckRow(stringResource(R.string.match_remember), rememberChoice, true) { rememberChoice = it }
                }
            }
        },
        confirmButton = {
            if (prompt.bestTitle != null) {
                TextButton(onClick = { answer(MatchAction.CONTINUE) }) { Text(stringResource(R.string.continue_anyway)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { answer(MatchAction.SKIP) }) { Text(stringResource(R.string.skip)) }
                TextButton(onClick = { answer(MatchAction.RETRY) }) { Text(stringResource(R.string.retry_search)) }
            }
        },
    )
}
