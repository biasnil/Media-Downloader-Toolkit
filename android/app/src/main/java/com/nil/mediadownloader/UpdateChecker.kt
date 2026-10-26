package com.nil.mediadownloader

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Settings → About → Version: checks GitHub for a newer Android release, only when tapped.
 * The repo also holds the Windows app's releases (tagged "1.1.0" etc.), so only tags
 * starting with "Androidv" (e.g. "Androidv1.2.0") count.
 */
object UpdateChecker {
    private const val RELEASES_API = "https://api.github.com/repos/biasnil/Media-Downloader-Toolkit/releases?per_page=50"
    private const val TAG_PREFIX = "Androidv"

    sealed class Result {
        /** [url] is the attached .apk, or the release page when there isn't one. */
        class Newer(val version: String, val notes: String, val url: String) : Result()
        class UpToDate(val version: String) : Result()
        object NoRelease : Result()
        object Busy : Result()
        class Failed(val reason: String) : Result()
    }

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    /** Blocking — run on Dispatchers.IO. */
    fun check(context: Context): Result {
        val current = currentVersion(context)
        val body = try {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MediaDownloader-Android") // GitHub refuses requests without one
            try {
                // 403/429: GitHub's limit of 60 checks an hour without an account
                if (conn.responseCode == 403 || conn.responseCode == 429) return Result.Busy
                if (conn.responseCode !in 200..299) return Result.Failed("HTTP ${conn.responseCode}")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }

        val releases = JSONArray(body)
        var best: Result.Newer? = null
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            val tag = r.optString("tag_name")
            if (r.optBoolean("draft") || r.optBoolean("prerelease") || !tag.startsWith(TAG_PREFIX, ignoreCase = true)) continue
            val version = tag.substring(TAG_PREFIX.length).trimStart('v', 'V')
            if (best != null && compare(version, best.version) <= 0) continue
            val assets = r.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            best = Result.Newer(
                version = version,
                notes = r.optString("body").trim(),
                url = apk?.optString("browser_download_url")?.takeIf { it.isNotBlank() } ?: r.optString("html_url"),
            )
        }
        val newest = best ?: return Result.NoRelease
        return if (compare(newest.version, current) > 0) newest else Result.UpToDate(current)
    }

    /** Compares "1.10.0" with "1.9.2" number by number (so 1.10 is newer than 1.9). */
    fun compare(a: String, b: String): Int {
        val x = a.split('.').map { part -> part.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { part -> part.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
