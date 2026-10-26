package com.nil.mediadownloader

import android.net.Uri
import android.text.Html
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A picture found on a page. [preferred]: the post's own photo(s), ticked in the picker at first. */
data class PageImage(val url: String, val preferred: Boolean)

/** The pictures at a link, and a name for their files ("Instagram - user"). */
class FoundImages(val site: String, val title: String, val images: List<PageImage>)

/**
 * Other Sites' fallback when yt-dlp finds no video: the pictures at the link, without any login.
 * Instagram is read from the post's public embed page, Facebook from its page data (like iOS),
 * and any other site from its share-preview pictures plus the large images on the page.
 */
object PageImages {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    // Link-preview bots get a page's share picture without JavaScript (same as the Spotify tab)
    private const val PREVIEW_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    private const val MAX_IMAGES = 60
    private const val MAX_PAGE = 6 * 1024 * 1024

    /** Blocking — run on Dispatchers.IO. null when nothing was found. */
    fun find(url: String): FoundImages? {
        val host = try { Uri.parse(url).host?.lowercase().orEmpty() } catch (e: Exception) { "" }
        val found = when {
            host.endsWith("instagram.com") || host == "instagr.am" -> instagram(url)
            host.endsWith("facebook.com") || host == "fb.watch" -> facebook(url)
            else -> anyPage(url)
        }
        return found?.takeIf { it.images.isNotEmpty() }
    }

    fun isInstagramOrFacebook(url: String): Boolean {
        val host = try { Uri.parse(url).host?.lowercase().orEmpty() } catch (e: Exception) { "" }
        return host.endsWith("instagram.com") || host == "instagr.am" || host.endsWith("facebook.com") || host == "fb.watch"
    }

    // ---------------------------------------------------------------- Instagram

    private fun instagram(url: String): FoundImages? {
        var code = shortcode(url)
        if (code == null) {
            // Share links (instagram.com/share/...) redirect to the post
            code = try { shortcode(get(url, BROWSER_UA).second) } catch (e: Exception) { null }
        }
        if (code == null) return preview(url, "Instagram")

        val images = mutableListOf<String>()
        var user: String? = null
        try {
            val (html, _) = get("https://www.instagram.com/p/$code/embed/captioned/", BROWSER_UA)
            // The embed page's data lists every photo of a carousel as "display_url" (often JSON inside a string)
            Regex("""display_url\\*"\s*:\s*\\*"(https?:.*?)\\*"""").findAll(html).forEach { images += unescape(it.groupValues[1]) }
            if (images.isEmpty()) {
                // Single photo posts sometimes only have the picture itself
                Regex("""class="EmbeddedMediaImage"[^>]*?src="([^"]+)"""").find(html)?.let { images += decodeHtml(it.groupValues[1]) }
                Regex("""<img[^>]+class="EmbeddedMediaImage"[^>]*>""").find(html)?.value
                    ?.let { tag -> attr(tag, "src")?.let { images += decodeHtml(it) } }
            }
            user = Regex("""username\\*"\s*:\s*\\*"([A-Za-z0-9._]+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""class="UsernameText"[^>]*>([^<]+)<""").find(html)?.groupValues?.get(1)?.trim()
        } catch (e: Exception) {
            // Falls through to the preview picture
        }
        val unique = dedupe(images)
        if (unique.isEmpty()) return preview(url, "Instagram")
        return FoundImages("Instagram", "Instagram - ${user ?: code}", unique.map { PageImage(it, true) })
    }

    /** /p/CODE, /reel/CODE, /reels/CODE, /tv/CODE, also with a username in front. */
    private fun shortcode(url: String): String? {
        val parts = try { Uri.parse(url).pathSegments } catch (e: Exception) { return null }
        val i = parts.indexOfFirst { it in listOf("p", "reel", "reels", "tv") }
        return if (i >= 0 && i + 1 < parts.size) parts[i + 1] else null
    }

    // ---------------------------------------------------------------- Facebook

    private fun facebook(url: String): FoundImages? {
        val images = mutableListOf<String>()
        var title = "Facebook"
        try {
            var (html, _) = get(url, BROWSER_UA)
            // "Redirecting..." pages point somewhere else with a meta refresh
            refreshTarget(html)?.let { html = get(it, BROWSER_UA).first }
            // Full-size photos in the page data: "viewer_image" on photo pages, "photo_image" in posts
            for (key in listOf("\"viewer_image\":{", "\"photo_image\":{")) {
                var from = 0
                while (true) {
                    val start = html.indexOf(key, from).takeIf { it >= 0 } ?: break
                    val window = html.substring(start, minOf(html.length, start + 400))
                    Regex(""""uri"\s*:\s*"(https?:[^"]+)"""").find(window)?.let { images += unescape(it.groupValues[1]) }
                    from = start + key.length
                }
            }
            meta(html)["og:title"]?.let { title = it.take(80) }
        } catch (e: Exception) {
            // Falls through to the preview picture
        }
        val unique = dedupe(images)
        if (unique.isEmpty()) return preview(url, "Facebook")
        return FoundImages("Facebook", title, unique.map { PageImage(it, true) })
    }

    // ---------------------------------------------------------------- Any other page

    private fun anyPage(url: String): FoundImages? {
        val (html, finalUrl) = try { get(url, BROWSER_UA) } catch (e: Exception) { return preview(url, LinkPreviews.siteName(url)) }
        val tags = meta(html)
        val preferred = dedupe(
            listOfNotNull(tags["og:image:secure_url"], tags["og:image"], tags["twitter:image"], tags["twitter:image:src"])
                .mapNotNull { absolute(finalUrl, it) }
        )
        // The page's other pictures, for the user to tick if they want them. Icons, logos and sprites are left out.
        val others = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { m -> (attr(m.value, "data-src") ?: attr(m.value, "src"))?.let { absolute(finalUrl, decodeHtml(it)) } }
            .filter { link ->
                val path = link.substringBefore('?').lowercase()
                !path.endsWith(".svg") && !path.endsWith(".ico") && !path.endsWith(".gif") &&
                    listOf("logo", "icon", "sprite", "avatar", "emoji", "pixel", "badge").none { it in path }
            }
            .toList()
        val all = dedupe(preferred + others).take(MAX_IMAGES)
        if (all.isEmpty()) return null
        val title = (tags["og:title"] ?: Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?.let { decodeHtml(it).trim() })?.take(80)?.ifBlank { null } ?: LinkPreviews.siteName(url)
        val wanted = preferred.toSet()
        return FoundImages(LinkPreviews.siteName(url), title, all.map { PageImage(it, it in wanted || preferred.isEmpty()) })
    }

    /** The share picture only (what chat apps show for the link). */
    private fun preview(url: String, site: String): FoundImages? = try {
        val (html, finalUrl) = get(url, PREVIEW_UA)
        val tags = meta(html)
        val image = listOfNotNull(tags["og:image"], tags["twitter:image"]).firstNotNullOfOrNull { absolute(finalUrl, it) }
        image?.let { FoundImages(site, tags["og:title"]?.take(80) ?: site, listOf(PageImage(it, true))) }
    } catch (e: Exception) { null }

    // ---------------------------------------------------------------- Downloading

    /**
     * Saves one picture into [dir] as "[base].[ext]", the extension from what the site sends.
     * Returns the file. Blocking — run on Dispatchers.IO.
     */
    fun download(task: TaskState, url: String, referer: String, dir: File, base: String, onProgress: (Float) -> Unit): File {
        dir.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        conn.setRequestProperty("Referer", referer)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val ext = when (conn.contentType?.substringBefore(';')?.trim()?.lowercase()) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/heic", "image/heif" -> "heic"
                "image/avif" -> "avif"
                else -> "jpg"
            }
            val file = File(dir, "$base.$ext")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        if (task.cancelled) throw com.yausername.youtubedl_android.YoutubeDL.CanceledException()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            return file
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- Helpers

    /** The page's text and where it ended up after redirects. */
    private fun get(url: String, userAgent: String): Pair<String, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (out.size() < MAX_PAGE) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8) to conn.url.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** <meta property|name="..." content="..."> in either attribute order, lower-case keys, first one wins. */
    private fun meta(html: String): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val key = (attr(m.value, "property") ?: attr(m.value, "name"))?.lowercase() ?: return@forEach
            val content = attr(m.value, "content") ?: return@forEach
            tags.putIfAbsent(key, decodeHtml(content))
        }
        return tags
    }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE).find(tag)
            ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }?.ifBlank { null }

    private fun refreshTarget(html: String): String? =
        Regex("""http-equiv="refresh"[^>]*url=([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { decodeHtml(it) }

    private fun absolute(base: String, link: String): String? = try {
        if (link.startsWith("data:")) null
        else URL(URL(base), link).toString().takeIf { it.startsWith("http") }
    } catch (e: Exception) { null }

    /** JSON escapes, also doubled ones from JSON inside a string: \/ \\/ & \\u0026 */
    private fun unescape(text: String): String {
        var s = text
        repeat(3) {
            s = s.replace("\\\\", "\\").replace("\\/", "/")
                .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
        }
        return s
    }

    private fun decodeHtml(text: String): String = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()

    /** One of each picture; Instagram/Facebook give the same photo at several addresses that only differ after "?". */
    private fun dedupe(links: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return links.filter { link -> seen.add(link.substringBefore('?').substringAfterLast('/')) }
    }
}
