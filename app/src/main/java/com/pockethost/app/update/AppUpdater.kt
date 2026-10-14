package com.pockethost.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.pockethost.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional in-app updater (universal APK only, never forced).
 *
 * Source: GitHub Releases `latest` of W1zago/PocketHost.
 * Expected asset name: `PocketHost-vX.Y.Z-universal.apk` (single APK with
 * all ABIs: arm64-v8a + armeabi-v7a + x86_64). If no `*universal*.apk`
 * asset is found, the first `*.apk` asset is used as fallback.
 *
 * Install goes through PackageInstaller Session API (no ACTION_VIEW),
 * so the user always sees the system install consent dialog.
 * No auto-download, no auto-install, no blocking dialogs.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    const val REPO = "W1zago/PocketHost"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    data class UpdateInfo(
        val tag: String,
        val versionName: String,
        val body: String,
        val downloadUrl: String,
        val assetName: String,
        val assetSize: Long
    )

    fun currentVersionName(): String = BuildConfig.VERSION_NAME
    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE
    fun currentLabel(): String = "v${currentVersionName()} (${currentVersionCode()})"

    /**
     * Returns UpdateInfo when a newer release exists, null when up-to-date.
     * Never throws — returns null + logs on any network/parse error.
     * Call from Dispatchers.IO.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val json = get(LATEST_URL) ?: return@withContext null
            val root = JSONObject(json)
            val tag = root.optString("tag_name", "").trim()
            if (tag.isEmpty()) return@withContext null
            val body = root.optString("body", "")
            val assets = root.optJSONArray("assets") ?: return@withContext null

            var pickUrl = ""
            var pickName = ""
            var pickSize = 0L
            var fallbackUrl = ""
            var fallbackName = ""
            var fallbackSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val url = a.optString("browser_download_url", "")
                if (url.isEmpty()) continue
                val size = a.optLong("size", 0L)
                if (fallbackUrl.isEmpty()) {
                    fallbackUrl = url; fallbackName = name; fallbackSize = size
                }
                if (name.contains("universal", ignoreCase = true) && pickUrl.isEmpty()) {
                    pickUrl = url; pickName = name; pickSize = size
                }
            }
            val url = pickUrl.ifEmpty { fallbackUrl }
            if (url.isEmpty()) return@withContext null
            val remoteName = pickName.ifEmpty { fallbackName }
            val remoteSize = if (pickUrl.isNotEmpty()) pickSize else fallbackSize

            val remoteVersion = tag.trimStart('v', 'V')
            return@withContext if (isNewer(remoteVersion, currentVersionName())) {
                UpdateInfo(tag, remoteVersion, body, url, remoteName, remoteSize)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed", e)
            null
        }
    }

    /** True when [remote] semver is newer than [local]. Non-numeric suffixes ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String): List<Int> = v.split(".", "-", "+").mapNotNull {
            it.filter { c -> c.isDigit() }.toIntOrNull()
        }
        val r = parts(remote)
        val l = parts(local)
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Downloads the APK to cacheDir/updates/. Returns the file or null.
     * Fresh download (no resume — APK is replaced on retry).
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        onProgress: (pct: Int, doneMb: Long, totalMb: Long) -> Unit = { _, _, _ -> }
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val dest = File(dir, "pockethost-update.apk")
            // Drop stale partial from a previous attempt.
            try { if (dest.exists()) dest.delete() } catch (_: Exception) {}

            var current = info.downloadUrl
            var redirects = 0
            var result: File? = null
            var finished = false
            while (!finished) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "PocketHost/${currentVersionName()} (Android)")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Connection", "close")
                }
                val code: Int? = try {
                    conn.connect(); conn.responseCode
                } catch (e: Exception) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    Log.w(TAG, "download connect failed", e)
                    null
                }
                if (code == null) {
                    result = null
                    finished = true
                    continue
                }
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty() || ++redirects > 5) {
                        result = null
                        finished = true
                        continue
                    }
                    current = try { URL(URL(current), loc).toString() } catch (_: Exception) { loc }
                    continue
                }
                if (code !in 200..299) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    Log.w(TAG, "download HTTP $code for $current")
                    result = null
                    finished = true
                    continue
                }
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -1
                try {
                    conn.inputStream.use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                if (n == 0) continue
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        try { onProgress(pct, done / 1024 / 1024, total / 1024 / 1024) } catch (_: Exception) {}
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
                if (!dest.exists() || dest.length() < 1024 * 1024) {
                    try { dest.delete() } catch (_: Exception) {}
                    Log.w(TAG, "downloaded file too small")
                    result = null
                } else {
                    result = dest
                }
                finished = true
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            null
        }
    }

    fun canRequestInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    /**
     * Commits the APK via PackageInstaller Session API.
     * The system shows its own consent dialog — install is never silent.
     * Returns false only when the session could not be created.
     */
    suspend fun installViaSession(context: Context, apk: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val pm: PackageManager = context.packageManager
                val installer: PackageInstaller = pm.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL
                ).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(
                            PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                        )
                    }
                }
                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)
                session.use { s ->
                    s.openWrite("apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { input ->
                            input.copyTo(out)
                        }
                        s.fsync(out)
                    }
                    val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
                        action = UpdateInstallReceiver.ACTION_INSTALL_COMMIT
                    }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                    s.commit(pi.intentSender)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "session install failed", e)
                false
            }
        }

    private fun get(url: String): String? {
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "PocketHost/${currentVersionName()} (Android)")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Connection", "close")
            }
            try {
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty() || ++redirects > 5) return null
                    current = try { URL(URL(current), loc).toString() } catch (_: Exception) { loc }
                    continue
                }
                if (code !in 200..299) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    return null
                }
                val text = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                return text
            } catch (_: Exception) {
                try { conn.disconnect() } catch (_: Exception) {}
                return null
            }
        }
    }
}
