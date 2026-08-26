package com.anserver.app.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object UbuntuInstaller {

    private const val PROOT_VERSION = "v5.3.0"
    private const val ROOTFS_VERSION = "22.04.5"
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 60000
    private const val MAX_REDIRECTS = 5

    fun isInstalled(workingDir: File): Boolean {
        val proot = File(workingDir, "proot")
        val rootfsDir = File(workingDir, "ubuntu-fs")
        val bashOk = File(rootfsDir, "usr/bin/bash").exists() || File(rootfsDir, "bin/bash").exists()
        return proot.exists() && proot.canExecute() && bashOk
    }

    fun install(context: Context, workingDir: File, cacheDir: File, log: (String) -> Unit) {
        val arch = detectArch()
        val (prootUrl, rootfsUrl) = resolveUrls(arch)
        workingDir.mkdirs()

        val prootFile = File(workingDir, "proot")
        var prootFromAssets = false
        try {
            context.assets.open("proot/proot-${arch.proot}-static").use { input ->
                FileOutputStream(prootFile).use { output -> input.copyTo(output, 64 * 1024) }
            }
            log("Using built-in proot (${arch.proot})...")
            prootFromAssets = true
        } catch (_: IOException) {
            log("Downloading proot ($PROOT_VERSION)...")
            download(prootUrl, prootFile)
        }
        if (!prootFile.setExecutable(true, false)) {
            throw IllegalStateException("Failed to make proot executable")
        }
        log("proot installed: ${prootFile.length() / 1024} KB" + if (prootFromAssets) " (built-in)" else "")

        val rootfsDir = File(workingDir, "ubuntu-fs")
        if (rootfsDir.exists()) {
            log("Cleaning up previous incomplete installation...")
            rootfsDir.deleteRecursively()
        }
        rootfsDir.mkdirs()

        val archive = File(cacheDir, "ubuntu-rootfs.tar.gz")
        var usedBuiltInRootfs = false
        try {
            try {
                context.assets.open("ubuntu/ubuntu-base-${arch.rootfs}.tar.gz").use { input ->
                    log("Using built-in Ubuntu $ROOTFS_VERSION rootfs (${arch.rootfs})...")
                    FileOutputStream(archive).use { output -> input.copyTo(output, 64 * 1024) }
                }
                usedBuiltInRootfs = true
            } catch (_: IOException) {
                log("Downloading Ubuntu $ROOTFS_VERSION base rootfs (~26 MB)...")
                download(rootfsUrl, archive)
            }
            log((if (usedBuiltInRootfs) "Built-in" else "Download") + " finished (${archive.length() / (1024 * 1024)} MB), extracting...")
            val result = extractRootfs(archive, rootfsDir)
            log("Extracted ${result.fileCount} files, ${result.dirCount} dirs")
            if (result.failedLinks.isNotEmpty()) {
                log("Retrying ${result.failedLinks.size} links...")
                val repaired = TarExtractor.repairLinks(rootfsDir, result.failedLinks)
                log("Repaired $repaired links, remaining ${result.failedLinks.size - repaired}")
            }
        } finally {
            archive.delete()
        }

        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        if (!resolvConf.exists()) {
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }

        listOf("dev", "proc", "sys", "tmp", "root").forEach {
            File(rootfsDir, it).mkdirs()
        }

        if (!File(rootfsDir, "usr/bin/bash").exists() && !File(rootfsDir, "bin/bash").exists()) {
            throw IllegalStateException("Rootfs extraction failed: /bin/bash not found")
        }

        log("Ubuntu base installed successfully")
    }

    private fun extractRootfs(archive: File, rootfsDir: File): TarExtractor.ExtractResult {
        GZIPInputStream(archive.inputStream().buffered(64 * 1024)).use { input ->
            return TarExtractor.extract(input, rootfsDir)
        }
    }

    fun isFullServerInstalled(workingDir: File): Boolean {
        return File(workingDir, "ubuntu-fs/.full-server-installed").exists()
    }

    fun installFullServer(workingDir: File, log: (String) -> Unit) {
        val prootFile = File(workingDir, "proot")
        val rootfs = File(workingDir, "ubuntu-fs").absolutePath
        val marker = File(workingDir, "ubuntu-fs/.full-server-installed")
        if (marker.exists()) {
            log("Full server already installed, skipping apt upgrade")
            return
        }
        log("Checking proot: ${if (prootFile.exists()) "exists" else "MISSING"} ${prootFile.length()} bytes, executable=${prootFile.canExecute()}")
        log("Checking rootfs: ${if (File(rootfs, "bin/bash").exists() || File(rootfs, "usr/bin/bash").exists()) "bash found" else "bash MISSING"}")
        val baseArgs = listOf("./proot", "--link2symlink", "-0", "-r", "ubuntu-fs", "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root")

        fun runProot(args: List<String>, env: Map<String, String> = emptyMap()): Int {
            val prootCmd = (baseArgs + args).joinToString(" ")
            log("→ ${args.joinToString(" ")}")
            val pb = ProcessBuilder("/system/bin/sh", "-c", prootCmd)
            pb.directory(workingDir)
            pb.redirectErrorStream(true)
            pb.environment()["DEBIAN_FRONTEND"] = "noninteractive"
            pb.environment()["APT_LISTCHANGES_FRONTEND"] = "none"
            pb.environment()["PROOT_NO_SECCOMP"] = "1"
            pb.environment()["PROOT_TMP_DIR"] = workingDir.absolutePath
            pb.environment()["TMPDIR"] = workingDir.absolutePath
            env.forEach { (k, v) -> pb.environment()[k] = v }
            val proc = pb.start()
            val reader = proc.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                log(line!!.take(600))
            }
            val code = proc.waitFor()
            log("exit code: $code")
            return code
        }

        log("Diagnostics:")
        runProot(listOf("/bin/cat", "/etc/os-release"))
        runProot(listOf("/bin/cat", "/etc/apt/sources.list"))
        runProot(listOf("/bin/cat", "/etc/resolv.conf"))
        runProot(listOf("/bin/ping", "-c", "1", "8.8.8.8"))
        runProot(listOf("/usr/bin/apt-get", "update", "-o", "Debug::Acquire::http=true"))

        val updateCode = runProot(listOf("/usr/bin/apt-get", "update"))
        if (updateCode != 0) throw IllegalStateException("apt update failed: $updateCode")

        val installCode = runProot(listOf("/usr/bin/apt-get", "install", "-y", "ubuntu-server"))
        if (installCode != 0) throw IllegalStateException("apt install ubuntu-server failed: $installCode")

        try { marker.writeText("installed ${System.currentTimeMillis()}") } catch (_: Exception) {}
        log("Full Ubuntu Server installed")
    }

    private sealed class Arch(val proot: String, val rootfs: String) {
        object Arm64 : Arch("aarch64", "arm64")
        object Arm32 : Arch("arm", "armhf")
        object X64 : Arch("x86_64", "amd64")
    }

    private fun detectArch(): Arch {
        return when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> Arch.Arm64
            "armeabi-v7a", "armeabi" -> Arch.Arm32
            "x86_64" -> Arch.X64
            else -> throw UnsupportedOperationException("Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    private fun resolveUrls(arch: Arch): Pair<String, String> {
        return Pair(
            "https://github.com/proot-me/proot/releases/download/$PROOT_VERSION/proot-$PROOT_VERSION-${arch.proot}-static",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-$ROOTFS_VERSION-base-${arch.rootfs}.tar.gz"
        )
    }

    private fun download(url: String, target: File) {
        var current = url
        var redirects = 0
        while (true) {
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "PocketHost/1.0")

                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect without Location header")
                    if (++redirects > MAX_REDIRECTS) {
                        throw IllegalStateException("Too many redirects")
                    }
                    current = location
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("HTTP $code for $current")
                }

                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
    }
}
