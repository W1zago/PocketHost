package com.pockethost.jvmhost

import java.io.File

/**
 * Downloads and prepares the local Ubuntu Server files.
 *
 * Backend strategy per host OS (see jvm-host README, "Backends"):
 * - Linux: official ubuntu-base rootfs tarball + system proot.
 *   The rootfs is extracted to `<baseDir>/ubuntu`, markers `.installed`
 *   and `.version` use the same format as the Android port.
 * - Windows: native proot cannot run there, so Ubuntu comes from WSL2.
 *   Nothing is downloaded here: [ensureInstalled] only verifies that WSL
 *   with an Ubuntu distro is present and otherwise logs setup steps.
 *   (The actual image pull is done by `wsl --install` itself.)
 * - macOS / unknown: same as Windows - verify only, host shell otherwise.
 *
 * Layout under [baseDir] (default `~/.pockethost`):
 *   ubuntu/   - Ubuntu rootfs + `.installed` + `.version` (Linux)
 *   cache/    - downloaded archives (deleted after successful extraction)
 *   servers/  - per-server work dirs, each with `server.log`
 *
 * All blocking calls. Run them off the UI thread; progress and human
 * readable status go to [log].
 */
class UbuntuInstaller(
    val baseDir: File,
    val log: (String) -> Unit = {}
) {

    fun rootfsDir(): File = File(baseDir, "ubuntu").apply { mkdirs() }
    fun cacheDir(): File = File(baseDir, "cache").apply { mkdirs() }
    fun serversRoot(): File = File(baseDir, "servers").apply { mkdirs() }

    fun installedMarker(): File = File(rootfsDir(), ".installed")
    fun versionFile(): File = File(rootfsDir(), ".version")

    /** First-start provision marker: content is [UbuntuSpec.ESSENTIAL_PACKAGES_VERSION]. */
    fun packagesMarker(): File = File(rootfsDir(), ".packages-installed")

    /** True when the guest is installed but the essential set was not provisioned yet. */
    fun needsEssentialPackages(): Boolean {
        return try {
            if (!isInstalled()) return false
            packagesMarker().takeIf { it.isFile }?.readText()?.trim() != UbuntuSpec.ESSENTIAL_PACKAGES_VERSION
        } catch (_: Exception) {
            true
        }
    }

    fun markEssentialPackagesInstalled() {
        try {
            packagesMarker().writeText(UbuntuSpec.ESSENTIAL_PACKAGES_VERSION)
        } catch (_: Exception) {
        }
    }

    /** Full guest rootfs present and verified (Linux path). */
    fun isInstalled(): Boolean {
        return try {
            if (!installedMarker().exists()) return false
            if (versionFile().takeIf { it.isFile }?.readText()?.trim() != UbuntuSpec.UBUNTU_VERSION) return false
            if (!File(rootfsDir(), "usr/bin/bash").isFile) return false
            // `env` is the actual exec target (via `env -u ... bash`).
            if (!File(rootfsDir(), "usr/bin/env").isFile) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ensures the local Ubuntu backend for [host] is ready.
     * Returns true when a guest shell can be started.
     */
    fun ensureInstalled(
        host: Host = HostInfo.current(),
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        return when (host.os) {
            OsKind.LINUX -> installLinuxRootfs(host, onProgress)
            OsKind.WINDOWS -> ensureWsl()
            OsKind.MACOS, OsKind.UNKNOWN -> {
                log("No isolated Ubuntu backend on this OS yet. The server runs as a host shell.")
                false
            }
        }
    }

    // ---------- Windows / WSL ----------

    /**
     * Verifies WSL2 with an Ubuntu distro. Never downloads anything:
     * image provisioning belongs to `wsl --install`.
     */
    fun ensureWsl(distro: String = UbuntuSpec.WSL_DISTRO): Boolean {
        if (HostInfo.current().os != OsKind.WINDOWS) {
            log("WSL check only applies on Windows.")
            return false
        }
        if (!HostInfo.wslAvailable()) {
            log("WSL is not installed. Steps:")
            log("1. Run in an elevated PowerShell: wsl --install -d $distro")
            log("2. Reboot, finish the Ubuntu first-run setup, then press Start again.")
            return false
        }
        val distros = HostInfo.wslDistros()
        if (distros.any { it.equals(distro, ignoreCase = true) || it.startsWith("Ubuntu", ignoreCase = true) }) {
            log("WSL distro ready: ${distros.joinToString(", ")}")
            return true
        }
        if (distros.isEmpty()) {
            log("WSL is installed but has no distributions. Run: wsl --install -d $distro")
        } else {
            log("No Ubuntu distro in WSL (found: ${distros.joinToString(", ")}). Run: wsl --install -d $distro")
        }
        return false
    }

    // ---------- Linux / proot + rootfs ----------

    private fun installLinuxRootfs(host: Host, onProgress: (Int) -> Unit): Boolean {
        if (isInstalled()) {
            log("Ubuntu ${UbuntuSpec.UBUNTU_VERSION} is already installed.")
            return true
        }
        if (!UbuntuSpec.isSupported(host)) {
            log(UbuntuSpec.supportHint(host))
            return false
        }
        if (HostInfo.systemProot() == null) {
            // Not fatal here: the caller falls back to a host shell.
            // Still continue the download so a later `apt install proot` just works.
            log(HostInfo.prootHint())
        }
        try {
            val free = HostInfo.freeBytes(baseDir)
            if (free >= 0 && free < UbuntuSpec.MIN_FREE_BYTES) {
                log("ERROR: low space (${free / 1024 / 1024} MB free, ~300 MB needed for Ubuntu).")
                return false
            }
        } catch (_: Exception) {
        }

        val url = UbuntuSpec.rootfsUrl(host.cpu)
        if (url == null) {
            log(UbuntuSpec.supportHint(host))
            return false
        }
        val archive = File(cacheDir(), "ubuntu-rootfs.tar.gz")
        if (archive.exists() && !Downloader.looksLikeGz(archive)) {
            log("Cached rootfs archive is damaged, re-downloading...")
            try {
                archive.delete()
            } catch (_: Exception) {
            }
        }
        if (!(archive.exists() && archive.length() > 10L * 1024 * 1024)) {
            log("Downloading Ubuntu ${UbuntuSpec.UBUNTU_VERSION} rootfs (~26 MB)...")
            log("URL: $url")
            var attempt = 0
            var err: String? = null
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                err = Downloader.download(url, archive, onProgress = { pct ->
                    if (pct % 10 == 0) {
                        try {
                            onProgress(pct)
                        } catch (_: Exception) {
                        }
                        log("Downloading Ubuntu: $pct%")
                    }
                }, log = log)
                if (err == null) break
                if (!Downloader.isRetryable(err)) break
                if (attempt < MAX_ATTEMPTS) {
                    val keptMb = try {
                        archive.length() / 1024 / 1024
                    } catch (_: Exception) {
                        0L
                    }
                    log("Attempt $attempt/$MAX_ATTEMPTS interrupted ($err). Kept $keptMb MB, retrying...")
                    try {
                        Thread.sleep(3000)
                    } catch (_: Exception) {
                    }
                }
            }
            if (err != null) {
                log("Failed to download rootfs ($err). Retry to resume the download.")
                return false
            }
        } else {
            log("Rootfs archive already downloaded, extracting...")
        }

        log("Extracting Ubuntu rootfs (~100 MB, may take a few minutes)...")
        val root = rootfsDir()
        try {
            if (root.exists() && !isInstalled()) root.deleteRecursively()
            root.mkdirs()
        } catch (_: Exception) {
        }
        if (!TarGzExtractor.extract(archive, root, log)) {
            log("Failed to extract rootfs. Archive kept, retry will skip downloading.")
            return false
        }
        try {
            archive.delete()
        } catch (_: Exception) {
        }
        applyGuestFixups()
        try {
            File(root, "usr/bin/bash").setExecutable(true)
            // The actual proot exec target (`env -u ... bash`): without
            // +r/+x here the first boot dies with a bare
            // `execve("/usr/bin/env"): Permission denied`.
            File(root, "usr/bin/env").setReadable(true, false)
            File(root, "usr/bin/env").setExecutable(true, false)
        } catch (_: Exception) {
        }
        return if (isInstalled()) {
            log("Ubuntu ${UbuntuSpec.UBUNTU_VERSION} installed.")
            true
        } else {
            log("Install finished but verification failed. See details above.")
            false
        }
    }

    /** Minimal guest fixups after rootfs extraction (same as Android port). */
    fun applyGuestFixups() {
        try {
            val rootfs = rootfsDir()
            try {
                val resolv = File(rootfs, "etc/resolv.conf")
                try {
                    if (Files_isSymbolicLink(resolv)) resolv.delete()
                } catch (_: Exception) {
                }
                resolv.parentFile?.mkdirs()
                resolv.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
            } catch (e: Exception) {
                log("resolv.conf fixup: ${e.message}")
            }
            try {
                val hostname = File(rootfs, "etc/hostname")
                if (!hostname.isFile || hostname.readText().isBlank()) {
                    hostname.writeText("pockethost\n")
                }
            } catch (_: Exception) {
            }
            try {
                File(rootfs, "root/server").mkdirs()
            } catch (_: Exception) {
            }
            try {
                versionFile().writeText(UbuntuSpec.UBUNTU_VERSION)
                installedMarker().writeText(System.currentTimeMillis().toString())
            } catch (e: Exception) {
                log("marker write: ${e.message}")
            }
        } catch (e: Exception) {
            log("guest fixups: ${e.message}")
        }
    }

    private fun Files_isSymbolicLink(f: File): Boolean {
        return try {
            java.nio.file.Files.isSymbolicLink(f.toPath())
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
