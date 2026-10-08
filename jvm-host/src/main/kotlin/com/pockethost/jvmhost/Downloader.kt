package com.pockethost.jvmhost

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable file downloader over plain JDK HTTP.
 *
 * Mirrors the Android port's download logic (Range resume, manual redirect
 * handling, retry-friendly error strings) without any Android dependency.
 */
object Downloader {

    const val USER_AGENT = "PocketHost/1.0 (JVM; +https://github.com/PocketHost/pockethost)"
    private const val MAX_REDIRECTS = 5

    /** Quick check: does the file look like .gz at all (magic bytes 1F 8B). */
    fun looksLikeGz(file: File): Boolean {
        return try {
            if (!file.isFile || file.length() < 16) return false
            FileInputStream(file).use { fis ->
                val magic = ByteArray(2)
                var off = 0
                while (off < magic.size) {
                    val n = fis.read(magic, off, magic.size - off)
                    if (n == -1) return false
                    if (n == 0) continue
                    off += n
                }
                magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads [url] to [dest], resuming a partial file via Range.
     * @return null on success, or an English error description otherwise.
     * A partially downloaded file is kept so the next run can resume.
     */
    fun download(
        url: String,
        dest: File,
        minBytes: Long = 1024 * 1024,
        log: (String) -> Unit = {},
        onProgress: (Int) -> Unit = {}
    ): String? {
        try {
            dest.parentFile?.mkdirs()
            var current = url
            var redirects = 0
            var rangeRestarted = false
            while (true) {
                val startAt = if (dest.exists()) dest.length() else 0L
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Connection", "close")
                    if (startAt > 0) setRequestProperty("Range", "bytes=$startAt-")
                }
                val code = try {
                    conn.connect()
                    conn.responseCode
                } catch (e: Exception) {
                    try {
                        conn.disconnect()
                    } catch (_: Exception) {
                    }
                    return "network error: ${e.message} (for $current)"
                }
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty()) return "HTTP $code redirect without Location (for $current)"
                    if (++redirects > MAX_REDIRECTS) return "too many redirects (for $url)"
                    current = try {
                        URL(URL(current), loc).toString()
                    } catch (_: Exception) {
                        loc
                    }
                    continue
                }
                if (code == 416) {
                    // Range unsatisfiable: local file covers the remote one.
                    conn.disconnect()
                    if (dest.exists() && dest.length() >= minBytes) return null
                    try {
                        dest.delete()
                    } catch (_: Exception) {
                    }
                    if (rangeRestarted) return "HTTP 416, local file is bad - deleted, try again"
                    rangeRestarted = true
                    current = url
                    redirects = 0
                    continue
                }
                if (code !in 200..299) {
                    val body = try {
                        (conn.errorStream ?: conn.inputStream).bufferedReader().readText().take(200)
                    } catch (_: Exception) {
                        ""
                    }
                    conn.disconnect()
                    return "HTTP $code for $current. $body".trim()
                }
                // 206 = server resumed from startAt (append), 200 = full file (overwrite).
                val append = code == HttpURLConnection.HTTP_PARTIAL && startAt > 0
                val bodyTotal = conn.contentLengthLong
                val expectedTotal = if (append && bodyTotal > 0) startAt + bodyTotal else bodyTotal
                var done = if (append) startAt else 0L
                var lastPct = -1
                if (append && startAt > 0 && expectedTotal > 0) {
                    lastPct = ((done * 100) / expectedTotal).toInt().coerceIn(0, 100)
                }
                if (!append) {
                    try {
                        if (dest.exists()) dest.delete()
                    } catch (_: Exception) {
                    }
                }
                try {
                    conn.inputStream.use { input ->
                        FileOutputStream(dest, append).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                if (n == 0) continue
                                output.write(buf, 0, n)
                                done += n
                                if (expectedTotal > 0) {
                                    val pct = ((done * 100) / expectedTotal).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        try {
                                            onProgress(pct)
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                            output.flush()
                            try {
                                output.fd.sync()
                            } catch (_: Exception) {
                            }
                        }
                    }
                } finally {
                    try {
                        conn.disconnect()
                    } catch (_: Exception) {
                    }
                }
                val finalLen = dest.length()
                if (expectedTotal > 0 && finalLen < expectedTotal) {
                    // Silent stream cut: keep the file, the next run resumes.
                    return "download interrupted " +
                        "(${finalLen / 1024 / 1024} of ${expectedTotal / 1024 / 1024} MB) - retry to resume"
                }
                if (!dest.exists() || finalLen < minBytes) {
                    val kb = finalLen / 1024
                    try {
                        dest.delete()
                    } catch (_: Exception) {
                    }
                    return "truncated file ($kb KB)"
                }
                log("Downloaded ${finalLen / 1024 / 1024} MB")
                return null
            }
        } catch (e: Exception) {
            return "exception: ${e.message}"
        }
    }

    /**
     * Whether a download error is worth retrying. 401/403/404 and redirect
     * problems are fatal; timeouts, drops, 5xx and 429 are transient.
     */
    fun isRetryable(err: String): Boolean {
        if (err.contains("HTTP 404") || err.contains("HTTP 403") || err.contains("HTTP 401")) return false
        if (err.contains("without Location") || err.contains("too many redirects")) return false
        return true
    }
}
