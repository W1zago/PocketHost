package com.anserver.app.util

import android.os.Build
import java.io.File
import java.io.FileOutputStream
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
        return proot.exists() && proot.canExecute() && File(workingDir, "ubuntu-fs/bin/bash").exists()
    }

    fun install(workingDir: File, cacheDir: File, log: (String) -> Unit) {
        val (prootUrl, rootfsUrl) = resolveUrls()
        workingDir.mkdirs()

        log("Downloading proot ($PROOT_VERSION)...")
        val prootFile = File(workingDir, "proot")
        download(prootUrl, prootFile)
        if (!prootFile.setExecutable(true, false)) {
            throw IllegalStateException("Failed to make proot executable")
        }
        log("proot installed: ${prootFile.length() / 1024} KB")

        val rootfsDir = File(workingDir, "ubuntu-fs")
        if (rootfsDir.exists()) {
            log("Cleaning up previous incomplete installation...")
            rootfsDir.deleteRecursively()
        }
        rootfsDir.mkdirs()

        log("Downloading Ubuntu $ROOTFS_VERSION base rootfs (~26 MB)...")
        val archive = File(cacheDir, "ubuntu-rootfs.tar.gz")
        try {
            download(rootfsUrl, archive)
            log("Download finished (${archive.length() / (1024 * 1024)} MB), extracting...")
            extractRootfs(archive, rootfsDir)
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

        if (!File(rootfsDir, "bin/bash").exists()) {
            throw IllegalStateException("Rootfs extraction failed: /bin/bash not found")
        }

        log("Ubuntu rootfs installed successfully")
    }

    private fun extractRootfs(archive: File, rootfsDir: File) {
        GZIPInputStream(archive.inputStream().buffered(64 * 1024)).use { input ->
            TarExtractor.extract(input, rootfsDir)
        }
    }

    private fun resolveUrls(): Pair<String, String> {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        return when (abi) {
            "arm64-v8a" -> Pair(
                "https://github.com/proot-me/proot/releases/download/$PROOT_VERSION/proot-$PROOT_VERSION-aarch64-static",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-$ROOTFS_VERSION-base-arm64.tar.gz"
            )
            "armeabi-v7a", "armeabi" -> Pair(
                "https://github.com/proot-me/proot/releases/download/$PROOT_VERSION/proot-$PROOT_VERSION-arm-static",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-$ROOTFS_VERSION-base-armhf.tar.gz"
            )
            "x86_64" -> Pair(
                "https://github.com/proot-me/proot/releases/download/$PROOT_VERSION/proot-$PROOT_VERSION-x86_64-static",
                "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-$ROOTFS_VERSION-base-amd64.tar.gz"
            )
            else -> throw UnsupportedOperationException("Unsupported CPU ABI: $abi")
        }
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
