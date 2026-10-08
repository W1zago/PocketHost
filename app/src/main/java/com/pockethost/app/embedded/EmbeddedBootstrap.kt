package com.pockethost.app.embedded

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Built-in bootstrap: JRE + Ubuntu rootfs download
 * into the app private dir. No external Termux needed.
 *
 * Everything runs over plain INTERNET (already in Manifest),
 * no RUN_COMMAND / SAF / storage permissions.
 */
object EmbeddedBootstrap {

    private const val TAG = "EmbeddedBootstrap"

    // URL comes from the single source EmbeddedJava.jreDownloadUrl() to avoid drift.
    // Light Android-Bionic JRE (aarch64). Shell servers work even without Java.

    data class BootstrapState(
        val javaPath: String?,
        val javaOk: Boolean,
        val ubuntuInstalled: Boolean,
        val toolsReady: Boolean
    )

    fun getState(context: Context): BootstrapState {
        val java = EmbeddedJava.findJava(context)
        val ubuntuMarker = File(EmbeddedPaths.ubuntuRootfsDir(context), ".installed")
        return BootstrapState(
            javaPath = java,
            javaOk = java != null,
            ubuntuInstalled = ubuntuMarker.exists(),
            toolsReady = true
        )
    }

    suspend fun ensureServerDirReady(workDir: File): File =
        withContext(Dispatchers.IO) {
            workDir.mkdirs()
            val log = File(workDir, "server.log")
            if (!log.exists()) {
                try { log.createNewFile() } catch (_: Exception) {}
            }
            // input.txt no longer needed (stdin pipe), kept for old-log compatibility
            workDir
        }

    /**
     * Guarantees Java for Minecraft. Returns the java path or null.
     * If no JRE - tries downloading in background and reports progress to log().
     * Never throws, requires no permissions.
     * @param minecraftVersion for warning: 1.21+ needs Java 21, bundled JRE17 will not run it.
     */
    suspend fun ensureJava(
        context: Context,
        minecraftVersion: String? = null,
        log: (String) -> Unit = {}
    ): String? =
        withContext(Dispatchers.IO) {
            val existing = EmbeddedJava.findJavaForVersion(context, minecraftVersion)
            if (existing != null) {
                val ver = EmbeddedJava.javaVersion(existing) ?: "?"
                log("Java: $existing ($ver)")
                // For 1.20.5+ verify it is really 21+: JRE17 will not launch here.
                // findJavaForVersion already returned jre21 if present; if not -
                // honestly say where to get it, prepare below refuses with Definitive.
                if (minecraftVersion != null) {
                    try {
                        val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
                        if (need > 17) {
                            val home = EmbeddedJava.runtimeHomeFor(existing)
                            val have = if (home != null) com.pockethost.app.jvm.PocketJvm.readJreMajor(home) else null
                            if (have != null && have < need) {
                                log("Java 21 not found (only Java $have). Drop JRE21 manually via Files into tools/jre21/ (so tools/jre21/bin/java appears) and press Start again. No stable JRE21 auto-download - Pojav release archived.")
                            } else {
                                warnIfJavaTooOld(existing, minecraftVersion, log)
                            }
                        } else {
                            warnIfJavaTooOld(existing, minecraftVersion, log)
                        }
                    } catch (_: Exception) {
                        warnIfJavaTooOld(existing, minecraftVersion, log)
                    }
                }
                return@withContext existing
            }
            log(EmbeddedJava.installHint())
            val ok = tryDownloadAndExtractJre(context, log)
            if (ok) {
                val now = EmbeddedJava.findJava(context)
                if (now != null) {
                    log("Bundled JRE installed: $now (${EmbeddedJava.javaVersion(now) ?: "?"})")
                    warnIfJavaTooOld(now, minecraftVersion, log)
                    return@withContext now
                }
            }
            log("Java unavailable yet. Shell servers work, Minecraft needs JRE.")
            log("Workaround: drop your own JRE manually via Files into tools/jre/ (so tools/jre/bin/java appears) and press Start again.")
            null
        }

    private fun warnIfJavaTooOld(javaPath: String, minecraftVersion: String?, log: (String) -> Unit) {
        if (minecraftVersion == null) return
        val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
        if (need <= 17) return
        val have = try { EmbeddedJava.javaMajorVersion(javaPath) } catch (_: Exception) { null }
        if (have != null && have < need) {
            log("WARNING: Minecraft $minecraftVersion needs Java $need+, but Java $have was found.")
            log("Bundled JRE17 runs only Minecraft <=1.20.4.")
            log("Options: pick 1.20.4 (VANILLA/PAPER), or import JRE21 manually into tools/jre21/ via Files.")
        }
    }

    private suspend fun tryDownloadAndExtractJre(context: Context, log: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!EmbeddedJava.isJreAutoInstallSupported()) {
                    log("Arch ${EmbeddedJava.abi()}: JRE auto-install not supported. Trying system java...")
                    return@withContext false
                }
                val toolsDir = EmbeddedPaths.toolsDir(context)
                val tmpFile = File(toolsDir, "jre-download.tar.xz")
                // Bitten/foreign file instead of archive - re-download, not resume.
                // Whole archive is kept: download/re-extract retries go without fetching.
                if (tmpFile.exists() && !TarExtractor.looksLikeXz(tmpFile)) {
                    log("Cached archive is damaged - downloading fresh...")
                    try { tmpFile.delete() } catch (_: Exception) {}
                }
                val urls = EmbeddedJava.jreDownloadUrls()
                var downloaded = false
                var lastError = ""
                for (url in urls) {
                    if (tmpFile.exists() && tmpFile.length() > 0) {
                        log("Resuming JRE download from ${tmpFile.length() / 1024 / 1024} MB (~40MB total)...")
                    } else {
                        log("Downloading bundled JRE (~40MB)...")
                    }
                    log("URL: $url")
                    // Flaky networks drop (stalls, timeouts) - retry with resume,
                    // so the user does not tap Start over the last megabyte.
                    var err: String? = null
                    var attempt = 0
                    while (attempt < MAX_JRE_ATTEMPTS) {
                        attempt++
                        err = downloadFileDetailed(url, tmpFile) { pct ->
                            if (pct % 10 == 0) log("Downloading JRE: $pct%")
                        }
                        if (err == null) break
                        if (!isRetryableDownloadError(err)) break
                        if (attempt < MAX_JRE_ATTEMPTS) {
                            val keptMb = try { tmpFile.length() / 1024 / 1024 } catch (_: Exception) { 0L }
                            log("Attempt $attempt/$MAX_JRE_ATTEMPTS interrupted ($err). Kept $keptMb MB - resuming...")
                            try { delay(3000) } catch (_: Exception) {}
                        }
                    }
                    if (err == null) {
                        downloaded = true
                        break
                    } else {
                        lastError = err
                        log("Attempt failed: $err")
                        // Partial file is NOT deleted - next Start resumes via Range.
                    }
                }
                if (!downloaded) {
                    val keptMb = try { tmpFile.length() / 1024 / 1024 } catch (_: Exception) { 0L }
                    log("Failed to download JRE ($lastError).")
                    if (keptMb > 0) log("Partial file kept ($keptMb MB) - press Start again, download will resume.")
                    log("Check Console above: 404 = dead link, other code = network/VPN block.")
                    return@withContext false
                }
                log("Extracting JRE...")
                val dest = EmbeddedJava.bundledJreDir(context)
                // Clear smelly leftovers: partial bin/java could
                // look working on next Java lookup.
                try {
                    if (dest.exists() && (dest.listFiles()?.isNotEmpty() == true)) {
                        log("Clearing unfinished previous JRE install...")
                        dest.deleteRecursively()
                    }
                    dest.mkdirs()
                } catch (_: Exception) {}
                val extracted = extractTarXz(tmpFile, dest, log)
                // Delete the archive only after SUCCESSFUL extraction,
                // so a retry does not fetch 37MB again.
                if (extracted) {
                    try { tmpFile.delete() } catch (_: Exception) {}
                } else {
                    log("Archive kept - retry will go without downloading.")
                }
                if (!extracted) {
                    log("Failed to extract JRE. Details above.")
                    return@withContext false
                }
                // +x on binaries
                try {
                    File(dest, "bin/java").setExecutable(true)
                    File(dest, "bin").listFiles()?.forEach { it.setExecutable(true) }
                    File(dest, "lib").walkTopDown().filter { it.isFile && (it.name.endsWith(".so") || !it.extension.isNotEmpty()) }
                        .forEach { try { it.setExecutable(true) } catch (_: Exception) {} }
                } catch (_: Exception) {}
                EmbeddedJava.findJava(context) != null
            } catch (e: Exception) {
                Log.e(TAG, "JRE install failed", e)
                log("JRE install error: ${e.message}")
                false
            }
        }

    /**



     */
    /**
     * Ubuntu rootfs for UBUNTU_SERVER. Returns true when the full Ubuntu
     * (proot + 22.04 rootfs) is ready. Otherwise false: the server still
     * starts as the built-in shell (sh -i), and the full Ubuntu is fetched
     * on demand via installUbuntuFull() (auto on Start or by UI button).
     */
    suspend fun ensureUbuntu(context: Context, log: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (EmbeddedUbuntu.isInstalled(context)) return@withContext true
            log("Full Ubuntu is not installed. Built-in shell works; Ubuntu 22.04 (~30MB) can be fetched on Start or from server settings.")
            false
        }

    /**
     * Downloads the full Ubuntu 22.04: 3 Termux .debs (proot + libtalloc +
     * libandroid-shmem) plus the official ubuntu-base rootfs tarball.
     * Resume via Range; archives are deleted only after SUCCESSFUL
     * extraction, so a retry continues without re-downloading.
     * Returns true when EmbeddedUbuntu.isInstalled() afterwards.
     */
    suspend fun installUbuntuFull(context: Context, log: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (EmbeddedUbuntu.isInstalled(context)) {
                    log("Ubuntu ${EmbeddedUbuntu.UBUNTU_VERSION} is already installed.")
                    return@withContext true
                }
                if (!EmbeddedUbuntu.isSupported()) {
                    log(EmbeddedUbuntu.supportHint())
                    return@withContext false
                }
                // Space: unpacked rootfs ~150MB + 27MB archive + headroom.
                try {
                    val free = android.os.StatFs(context.filesDir.absolutePath).availableBytes
                    if (free < 300L * 1024 * 1024) {
                        log("ERROR: low space (${free / 1024 / 1024}MB free, ~300MB needed for Ubuntu).")
                        return@withContext false
                    }
                } catch (_: Exception) {
                }

                val toolsDir = EmbeddedPaths.toolsDir(context)
                val debs = EmbeddedUbuntu.prootDebs()
                    ?: return@withContext false.also { log(EmbeddedUbuntu.supportHint()) }
                var idx = 0
                for ((fileName, url) in debs) {
                    idx++
                    val tmp = File(toolsDir, fileName)
                    if (tmp.exists() && !looksLikeDeb(tmp)) {
                        log("Cached $fileName is damaged, re-downloading...")
                        try {
                            tmp.delete()
                        } catch (_: Exception) {
                        }
                    }
                    if (!(tmp.exists() && looksLikeDeb(tmp) && tmp.length() > 4 * 1024)) {
                        log("Downloading proot ($idx/${debs.size}): $fileName...")
                        log("URL: $url")
                        var err: String? = null
                        var attempt = 0
                        while (attempt < MAX_UBUNTU_ATTEMPTS) {
                            attempt++
                            err = downloadFileDetailed(url, tmp, minBytes = 4 * 1024) { pct ->
                                if (pct % 25 == 0) log("proot $idx/${debs.size}: $pct%")
                            }
                            if (err == null) break
                            if (!isRetryableDownloadError(err)) break
                            if (attempt < MAX_UBUNTU_ATTEMPTS) {
                                log("Attempt $attempt/$MAX_UBUNTU_ATTEMPTS interrupted ($err). Retrying...")
                                try {
                                    delay(3000)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        if (err != null) {
                            log("Failed to download $fileName ($err). Press Start again, download will resume.")
                            return@withContext false
                        }
                    } else {
                        log("proot ($idx/${debs.size}): $fileName already downloaded, extracting...")
                    }
                    val prefix = EmbeddedUbuntu.prootPrefix(context)
                    if (!DebExtractor.extractDataTar(tmp, prefix, 6, log)) {
                        log("Failed to extract $fileName. See details above.")
                        return@withContext false
                    }
                    try {
                        tmp.delete()
                    } catch (_: Exception) {
                    }
                }
                try {
                    EmbeddedUbuntu.prootBin(context).setExecutable(true)
                    EmbeddedUbuntu.prootLoader(context).setExecutable(true)
                    EmbeddedUbuntu.prootLoader32(context).setExecutable(true)
                } catch (_: Exception) {
                }

                val rootfsUrl = EmbeddedUbuntu.rootfsUrl()
                    ?: return@withContext false.also { log(EmbeddedUbuntu.supportHint()) }
                val rootfsTmp = File(toolsDir, "ubuntu-rootfs.tar.gz")
                if (rootfsTmp.exists() && !TarExtractor.looksLikeGz(rootfsTmp)) {
                    log("Cached rootfs archive is damaged, re-downloading...")
                    try {
                        rootfsTmp.delete()
                    } catch (_: Exception) {
                    }
                }
                if (!(rootfsTmp.exists() && rootfsTmp.length() > 10L * 1024 * 1024)) {
                    log("Downloading Ubuntu ${EmbeddedUbuntu.UBUNTU_VERSION} rootfs (~27MB)...")
                    log("URL: $rootfsUrl")
                    var err: String? = null
                    var attempt = 0
                    while (attempt < MAX_UBUNTU_ATTEMPTS) {
                        attempt++
                        err = downloadFileDetailed(rootfsUrl, rootfsTmp) { pct ->
                            if (pct % 10 == 0) log("Downloading Ubuntu: $pct%")
                        }
                        if (err == null) break
                        if (!isRetryableDownloadError(err)) break
                        if (attempt < MAX_UBUNTU_ATTEMPTS) {
                            val keptMb = try {
                                rootfsTmp.length() / 1024 / 1024
                            } catch (_: Exception) {
                                0L
                            }
                            log("Attempt $attempt/$MAX_UBUNTU_ATTEMPTS interrupted ($err). Kept $keptMb MB, retrying...")
                            try {
                                delay(3000)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (err != null) {
                        log("Failed to download rootfs ($err). Press Start again, download will resume.")
                        return@withContext false
                    }
                } else {
                    log("Rootfs archive already downloaded, extracting...")
                }
                log("Extracting Ubuntu rootfs (~150MB, a few minutes)...")
                val ubuntuRoot = EmbeddedUbuntu.rootfsDir(context)
                try {
                    if (ubuntuRoot.exists() && !EmbeddedUbuntu.isInstalled(context)) {
                        ubuntuRoot.deleteRecursively()
                    }
                    ubuntuRoot.mkdirs()
                } catch (_: Exception) {
                }
                if (!TarExtractor.extractGzTar(rootfsTmp, ubuntuRoot, log)) {
                    log("Failed to extract rootfs. Archive kept, retry will skip downloading.")
                    return@withContext false
                }
                try {
                    rootfsTmp.delete()
                } catch (_: Exception) {
                }
                EmbeddedUbuntu.applyGuestFixups(context, log)
                try {
                    File(ubuntuRoot, "usr/bin/bash").setExecutable(true)
                    // The actual proot exec target (`env -u ... bash`):
                    // without +r/+x here the first boot dies with
                    // `execve("/usr/bin/env"): Permission denied`.
                    File(ubuntuRoot, "usr/bin/env").setReadable(true, false)
                    File(ubuntuRoot, "usr/bin/env").setExecutable(true, false)
                    EmbeddedUbuntu.prootBin(context).setExecutable(true)
                } catch (_: Exception) {
                }
                if (EmbeddedUbuntu.isInstalled(context)) {
                    log("Ubuntu ${EmbeddedUbuntu.UBUNTU_VERSION} installed.")
                    true
                } else {
                    log("Install finished but verification failed. See details above.")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ubuntu install failed", e)
                log("Ubuntu install error: ${e.message}")
                false
            }
        }

    /**
     * Background first-start provision of [EmbeddedUbuntu.ESSENTIAL_PACKAGES].
     * Runs apt in its own one-shot proot process (separate PROOT_TMPDIR so it
     * never shares a socket dir with the live interactive shell) and streams
     * guest output raw into the server log. Returns true when the marker was
     * written; false keeps the marker absent so the next Start retries.
     */
    suspend fun ensureEssentialPackages(
        context: Context,
        workDir: File,
        log: (String) -> Unit
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!EmbeddedUbuntu.isInstalled(context)) return@withContext false
                if (!EmbeddedUbuntu.needsEssentialPackages(context)) return@withContext true
                try {
                    val free = android.os.StatFs(context.filesDir.absolutePath).availableBytes
                    if (free < ESSENTIAL_PACKAGES_MIN_FREE_BYTES) {
                        log("Essential packages skipped: low space (${free / 1024 / 1024}MB free, ~800MB needed). Free space and restart the server to retry.")
                        return@withContext false
                    }
                } catch (_: Exception) {
                }
                val tmpDir = File(context.cacheDir, "proot-tmp-apt")
                val launch = EmbeddedUbuntu.buildOneShot(
                    context, workDir, EmbeddedUbuntu.essentialAptCommand(), tmpDir
                ) { msg -> log(msg) } ?: return@withContext false
                log("First start: installing essential packages in background (${EmbeddedUbuntu.ESSENTIAL_PACKAGES.joinToString(" ")}) (~300-400MB, several minutes - do not run apt manually meanwhile)...")
                val pb = ProcessBuilder(launch.argv)
                    .directory(workDir)
                    .redirectErrorStream(true)
                try {
                    val env = pb.environment()
                    for ((k, v) in launch.env) env[k] = v
                } catch (_: Exception) {}
                val process = try {
                    pb.start()
                } catch (e: Exception) {
                    log("Essential packages: launch failed (${e.message}) - will retry on next Start.")
                    return@withContext false
                }
                val pump = Thread({
                    try {
                        process.inputStream.bufferedReader().use { reader ->
                            val buf = CharArray(8192)
                            while (true) {
                                val n = try { reader.read(buf) } catch (_: Exception) { -1 }
                                if (n <= 0) break
                                try {
                                    EmbeddedProcessManager.appendLogToDir(workDir, String(buf, 0, n))
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {
                    }
                }, "essential-apt-pump").apply { isDaemon = true; start() }
                val finished = try {
                    process.waitFor(ESSENTIAL_PACKAGES_TIMEOUT_MIN, java.util.concurrent.TimeUnit.MINUTES)
                } catch (_: Exception) {
                    false
                }
                if (!finished) {
                    try { process.destroyForcibly() } catch (_: Exception) {}
                    log("Essential packages: timed out after $ESSENTIAL_PACKAGES_TIMEOUT_MIN min - will retry on next Start.")
                    return@withContext false
                }
                try { pump.join(10_000) } catch (_: Exception) {}
                val code = try { process.exitValue() } catch (_: Exception) { -1 }
                if (code == 0) {
                    EmbeddedUbuntu.markEssentialPackagesInstalled(context)
                    log("Essential packages installed (${EmbeddedUbuntu.ESSENTIAL_PACKAGES.size} pkgs).")
                    true
                } else {
                    log("Essential packages: apt exited with code $code - will retry on next Start. See output above.")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "essential packages failed", e)
                try { log("Essential packages error: ${e.message} - will retry on next Start.") } catch (_: Exception) {}
                false
            }
        }

    /** Quick ar-magic check (`!<arch>`) for .deb files. */
    private fun looksLikeDeb(file: File): Boolean {
        return try {
            if (!file.isFile || file.length() < 1024) return false
            java.io.FileInputStream(file).use { fis ->
                val magic = ByteArray(8)
                var off = 0
                while (off < magic.size) {
                    val n = fis.read(magic, off, magic.size - off)
                    if (n == -1) return false
                    if (n == 0) continue
                    off += n
                }
                String(magic, Charsets.US_ASCII).startsWith("!<arch>")
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- io helpers ----------

    /** How many times to retry an interrupted JRE download (with resume) per Start. */
    private const val MAX_JRE_ATTEMPTS = 3

    /** How many times to retry an interrupted Ubuntu download (with resume) per Start. */
    private const val MAX_UBUNTU_ATTEMPTS = 3

    /** Free space required for the max essential set (download + install + headroom). */
    private const val ESSENTIAL_PACKAGES_MIN_FREE_BYTES = 800L * 1024 * 1024

    /** apt update + install of the max set on a phone can take a while. */
    private const val ESSENTIAL_PACKAGES_TIMEOUT_MIN = 30L

    /**
     * Whether a download error is worth retrying.
     * 404/403/navigation errors are fatal (retry will not help),
     * everything else (timeout, drop, 5xx, 429) is transient.
     */
    private fun isRetryableDownloadError(err: String): Boolean {
        if (err.contains("HTTP 404") || err.contains("HTTP 403") || err.contains("HTTP 401")) return false
        if (err.contains("without Location") || err.contains("too many redirects")) return false
        return true
    }

    /**
     * Downloads a file. Returns null on success, or error text with HTTP code.
     * GitHub releases answer 302 to objects.githubusercontent.com - redirects handled manually.
     * Supports resume (Range): an interrupted file continues instead of restarting.
     */
    private fun downloadFileDetailed(
        url: String,
        dest: File,
        minBytes: Long = 1024 * 1024,
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
                    connectTimeout = 30000
                    // Short readTimeout: hanging stalls surface fast,
                    // since drops auto-retry with resume (see above).
                    readTimeout = 60000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "PocketHost/1.0 (Android; +https://github.com/PocketHost/pockethost)")
                    setRequestProperty("Accept", "*/*")
                    // No keep-alive: reusing lukewarm OkHttp connections gives
                    // "unexpected end of stream" on slow networks.
                    setRequestProperty("Connection", "close")
                    if (startAt > 0) setRequestProperty("Range", "bytes=$startAt-")
                }
                val code = try {
                    conn.connect()
                    conn.responseCode
                } catch (e: Exception) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    return "network error: ${e.message} (for $current)"
                }
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty()) return "HTTP $code redirect without Location (for $current)"
                    if (++redirects > 5) return "too many redirects (for $url)"
                    current = try { URL(URL(current), loc).toString() } catch (_: Exception) { loc }
                    continue
                }
                if (code == 416) {
                    // Range unsatisfiable: local file >= remote. Accept if not tiny.
                    conn.disconnect()
                    if (dest.exists() && dest.length() >= minBytes) return null
                    try { dest.delete() } catch (_: Exception) {}
                    if (rangeRestarted) return "HTTP 416, local file is bad - deleted, try again"
                    rangeRestarted = true
                    current = url
                    redirects = 0
                    continue
                }
                if (code !in 200..299) {
                    val body = try {
                        (conn.errorStream ?: conn.inputStream).bufferedReader().readText().take(200)
                    } catch (_: Exception) { "" }
                    conn.disconnect()
                    return "HTTP $code for $current. $body".trim()
                }
                // 206 = server resumed from startAt (append), 200 = full file (overwrite).
                val append = (code == HttpURLConnection.HTTP_PARTIAL && startAt > 0)
                val bodyTotal = conn.contentLengthLong
                val expectedTotal = if (append && bodyTotal > 0) startAt + bodyTotal else bodyTotal
                var done = if (append) startAt else 0L
                var lastPct = -1
                if (append && startAt > 0 && expectedTotal > 0) {
                    lastPct = ((done * 100) / expectedTotal).toInt().coerceIn(0, 100)
                }
                if (!append) {
                    try { if (dest.exists()) dest.delete() } catch (_: Exception) {}
                }
                try {
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(dest, append).use { output ->
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
                                        try { onProgress(pct) } catch (_: Exception) {}
                                    }
                                }
                            }
                            output.flush()
                            try { output.fd.sync() } catch (_: Exception) {}
                        }
                    }
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
                val finalLen = dest.length()
                if (expectedTotal > 0 && finalLen < expectedTotal) {
                    // Silent stream cut (no exception): do NOT touch the file - next Start resumes.
                    return "download interrupted (${finalLen / 1024 / 1024} of ${expectedTotal / 1024 / 1024} MB) - press Start again, resume will continue"
                }
                if (!dest.exists() || finalLen < minBytes) {
                    val kb = finalLen / 1024
                    try { dest.delete() } catch (_: Exception) {}
                    return "truncated file ($kb KB)"
                }
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "download failed $url", e)
            return "exception: ${e.message}"
        }
    }

    private fun extractTarXz(archive: File, dest: File, log: (String) -> Unit): Boolean {
        return try {
            // Args as a list to avoid shell-quoting problems with spaces in paths.
            // toybox-tar often lacks -J (xz), so try -xJ, then -xjf, then plain tar.
            val attempts = listOf(
                listOf("tar", "-xJf", archive.absolutePath, "-C", dest.absolutePath),
                listOf("tar", "-xjf", archive.absolutePath, "-C", dest.absolutePath),
                listOf("tar", "-xf", archive.absolutePath, "-C", dest.absolutePath)
            )
            for (args in attempts) {
                try {
                    val proc = ProcessBuilder(args)
                        .directory(dest)
                        .redirectErrorStream(true)
                        .start()
                    val out = proc.inputStream.bufferedReader().readText()
                    val code = proc.waitFor()
                    if (out.isNotBlank()) log(out.take(500))
                    if (code == 0) {
                        normalizeJreTopLevel(dest, log)
                        return true
                    }
                    log("tar ${args[1]} failed (code $code), trying next...")
                } catch (e: Exception) {
                    log("tar launch failed: ${e.message}")
                }
            }
            // No system tar with xz support - try the built-in
            // Java unpacker (Tukaani XZ + own tar parser, no external binaries).
            log("System tar lacks xz support. Trying built-in unpacker...")
            try {
                if (TarExtractor.extractXzTar(archive, dest, log)) {
                    normalizeJreTopLevel(dest, log)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "embedded extractor failed", e)
                log("Built-in unpacker: ${e.message}")
            }
            log("Failed to unpack JRE. Try system java or manual install into tools/jre/ via Files.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
            log("Extract error: ${e.message}")
            false
        }
    }

    /** If the archive has a top-level folder (jre17/.../bin/java), lift contents into dest. */
    private fun normalizeJreTopLevel(dest: File, log: (String) -> Unit) {
        try {
            if (File(dest, "bin/java").exists()) return
            val children = dest.listFiles()?.toList() ?: return
            if (children.size == 1 && children[0].isDirectory) {
                val top = children[0]
                if (File(top, "bin/java").exists()) {
                    log("Normalizing JRE layout (removing ${top.name}/)...")
                    top.listFiles()?.forEach { child ->
                        val target = File(dest, child.name)
                        if (!child.renameTo(target)) {
                            // fallback copy for different mounts
                            if (child.isDirectory) child.copyRecursively(target, overwrite = true)
                            else child.copyTo(target, overwrite = true)
                        }
                    }
                    try { top.deleteRecursively() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "normalize JRE failed", e)
        }
    }
}
