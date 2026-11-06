package io.github.akaiikitsune.plsshare

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the GitHub releases of this repository for a newer build and installs it.
 *
 * Every CI build carries a monotonic build number in its version code and in the APK's file name
 * (`…-b<number>.apk`), so "newer" is just a bigger number, whether the release is a nightly or a tag.
 * All releases are signed with the same key (see keystore/README.md), which Android requires to
 * install an update over the existing app.
 */
class Updater(private val activity: Activity, private val s: Strings, private val journal: (String) -> Unit) {
    companion object {
        const val REPO = "AkaiiKitsune/pls-share-sp3"
        private const val API = "https://api.github.com/repos/$REPO/releases?per_page=15"
        private val ASSET = Regex("""-b(\d+)\.apk$""")
        private const val CHECK_INTERVAL_MS = 24 * 3600 * 1000L
        private const val PREF_LAST_CHECK = "lastUpdateCheck"
    }

    data class Release(val name: String, val build: Int, val url: String, val fileName: String, val notes: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var downloadId = -1L

    val currentBuild: Int
        get() = activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode.toInt()

    /** Check at most once a day, silently. Skipped on local development builds (build 1). */
    fun checkDaily() {
        if (currentBuild <= 1) return
        val prefs = activity.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(PREF_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
        check(interactive = false)
    }

    /** Look for a newer build; with [interactive] the result is always reported through [onMessage]. */
    fun check(interactive: Boolean, onMessage: (String) -> Unit = {}) {
        if (interactive) onMessage(s.updateChecking)
        Thread {
            val result = runCatching { fetchLatest() }
            mainHandler.post {
                result.onSuccess { latest ->
                    if (latest != null && latest.build > currentBuild) offer(latest, onMessage)
                    else if (interactive) onMessage(s.updateNone.format(currentBuild))
                }.onFailure { e ->
                    journal("update check failed: $e")
                    if (interactive) onMessage(s.updateError.format(e.message ?: e.javaClass.simpleName))
                }
            }
        }.start()
    }

    private fun fetchLatest(): Release? {
        val connection = URL(API).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "pls-share-sp3")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val releases = JSONArray(body)
        var best: Release? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                val build = ASSET.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                if (best == null || build > best.build) {
                    best = Release(release.optString("name").ifEmpty { release.optString("tag_name") }, build, asset.optString("browser_download_url"), name, release.optString("body"))
                }
            }
        }
        journal("update check: latest build ${best?.build ?: "none"}, current $currentBuild")
        return best
    }

    private fun offer(release: Release, onMessage: (String) -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(s.updateTitle)
            .setMessage(s.updateBody.format(release.name, release.build, currentBuild) + changelog(release.notes))
            .setNegativeButton(s.updateLater, null)
            .setPositiveButton(s.updateInstall) { _, _ -> download(release, onMessage) }
            .show()
    }

    /** The release notes, trimmed to what fits a dialog, after a blank line; nothing if there are none. */
    private fun changelog(notes: String): String {
        val text = notes.replace("\r", "").trim()
        if (text.isEmpty()) return ""
        val short = if (text.length > 1500) text.take(1500).substringBeforeLast('\n') + "\n…" else text
        return "\n\n" + short
    }

    private fun download(release: Release, onMessage: (String) -> Unit) {
        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(release.url))
            .setTitle(release.fileName)
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, release.fileName)
        release()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                release()
                val uri = manager.getUriForDownloadedFile(downloadId)
                if (uri == null) {
                    onMessage(s.updateFailed)
                    return
                }
                journal("update downloaded: $uri")
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        this.receiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
        downloadId = manager.enqueue(request)
        journal("update download started: ${release.url}")
        onMessage(s.updateDownloading)
    }

    /** Stop listening for a download; safe to call any time. */
    fun release() {
        receiver?.let { runCatching { activity.unregisterReceiver(it) } }
        receiver = null
    }
}
