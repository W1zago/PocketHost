package com.pockethost.app.embedded

import android.content.Context
import java.io.File

/**
 * Full Ubuntu 22.04 userland via Termux-built proot (no root, no Termux app).
 *
 * Layout inside the private app dir:
 *   filesDir/ubuntu/          - Ubuntu base rootfs + `.installed` + `.version`
 *   filesDir/tools/proot/     - prefix from 3 Termux .debs (strip=6):
 *       bin/proot, lib/libtalloc.so*, lib/libandroid-shmem.so
 *
 * Launch goes through the system linker (same W^X workaround as the JRE in
 * [EmbeddedJava]): `[linker64, <proot>, -r <rootfs> ... /usr/bin/env -u
 * LD_LIBRARY_PATH /bin/bash --login]`. The `env -u` is load-bearing: the
 * outer LD_LIBRARY_PATH (bionic .so for proot itself) must not leak into the
 * Ubuntu guest, whose glibc would otherwise pick bionic libs.
 *
 * Sources (verified 2026-10):
 *   rootfs: cdimage.ubuntu.com/ubuntu-base 22.04.5 (~26MB tar.gz per arch)
 *   proot:  packages.termux.dev proot 5.1.107.96 + libtalloc 2.5.0 +
 *           libandroid-shmem 0.7 (per-arch .deb, data.tar.xz inside)
 */
object EmbeddedUbuntu {

    const val UBUNTU_VERSION = "22.04.5"
    const val UBUNTU_CODENAME = "jammy"

    private const val TERMUX_APT = "https://packages.termux.dev/apt/termux-main/"
    private const val UBUNTU_BASE_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/jammy/release/"

    private const val PROOT_VERSION = "5.1.107.96"
    private const val LIBTALLOC_VERSION = "2.5.0"
    private const val SHMEM_VERSION = "0.7"

    /** Termux arch + Ubuntu-base arch for this device, or null when unsupported. */
    data class ArchSpec(val termuxArch: String, val ubuntuArch: String)

    fun archSpec(): ArchSpec? = when (EmbeddedJava.abi()) {
        "arm64-v8a" -> ArchSpec("aarch64", "arm64")
        "armeabi-v7a", "armeabi" -> ArchSpec("arm", "armhf")
        "x86_64" -> ArchSpec("x86_64", "amd64")
        // x86 (32-bit Intel): no ubuntu-base 22.04 i386 exists upstream.
        else -> null
    }

    fun isSupported(): Boolean = archSpec() != null

    fun supportHint(): String =
        "Architecture ${EmbeddedJava.abi()}: no Ubuntu 22.04 rootfs upstream " +
            "(only arm64 / armhf / amd64). Shell servers still work without Ubuntu."

    fun rootfsUrl(): String? {
        val spec = archSpec() ?: return null
        return "${UBUNTU_BASE_URL}ubuntu-base-${UBUNTU_VERSION}-base-${spec.ubuntuArch}.tar.gz"
    }

    /** (localFileName, url) for the 3 Termux .debs, or null when unsupported. */
    fun prootDebs(): List<Pair<String, String>>? {
        val spec = archSpec() ?: return null
        val a = spec.termuxArch
        return listOf(
            "proot_$a.deb" to
                "${TERMUX_APT}pool/main/p/proot/proot_${PROOT_VERSION}_$a.deb",
            "libtalloc_$a.deb" to
                "${TERMUX_APT}pool/main/libt/libtalloc/libtalloc_${LIBTALLOC_VERSION}_$a.deb",
            "libandroid-shmem_$a.deb" to
                "${TERMUX_APT}pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VERSION}_$a.deb"
        )
    }

    // ---------- paths ----------

    /** Prefix with bin/proot plus lib dir with .so files (all 3 debs extract here, strip=6). */
    fun prootPrefix(context: Context): File =
        File(EmbeddedPaths.toolsDir(context), "proot").apply { mkdirs() }

    fun prootBin(context: Context): File = File(prootPrefix(context), "bin/proot")

    fun prootLibDir(context: Context): File = File(prootPrefix(context), "lib")

    fun rootfsDir(context: Context): File = EmbeddedPaths.ubuntuRootfsDir(context)

    fun installedMarker(context: Context): File = File(rootfsDir(context), ".installed")

    fun versionFile(context: Context): File = File(rootfsDir(context), ".version")

    fun isInstalled(context: Context): Boolean {
        return try {
            if (!installedMarker(context).exists()) return false
            if (versionFile(context).takeIf { it.isFile }?.readText()?.trim() != UBUNTU_VERSION) return false
            if (!prootBin(context).isFile) return false
            // usr/bin/bash directly (bin/ is a symlink to usr/bin in ubuntu-base).
            if (!File(rootfsDir(context), "usr/bin/bash").isFile) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    // ---------- launch ----------

    data class ProotLaunch(
        val argv: List<String>,
        val env: Map<String, String>
    )

    /**
     * Builds `[linker, proot, ...]`. Returns null with the reason in [log]
     * when proot/rootfs/linker is missing.
     *
     * The server workDir is bind-mounted at /root/server and used as the
     * guest cwd, so relative files land in the real server dir (visible in
     * Files) while the shell sees a normal Ubuntu fs.
     */
    fun buildLaunch(
        context: Context,
        workDir: File,
        log: (String) -> Unit = {}
    ): ProotLaunch? {
        val proot = prootBin(context)
        if (!proot.isFile) {
            log("proot missing (${proot.absolutePath})")
            return null
        }
        try {
            proot.setExecutable(true)
        } catch (_: Exception) {
        }
        val rootfs = rootfsDir(context)
        if (!File(rootfs, "usr/bin/bash").isFile) {
            log("Ubuntu rootfs missing (${rootfs.absolutePath})")
            return null
        }
        val linker = EmbeddedJava.systemLinker()
        if (linker == null) {
            log("system linker not found, cannot exec from app dir")
            return null
        }
        if (!File(linker).canExecute()) {
            log("system linker not executable: $linker")
            return null
        }
        workDir.mkdirs()
        val argv = mutableListOf(
            linker,
            proot.absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev",
            "-b", "${workDir.absolutePath}:/root/server",
            "-w", "/root/server",
            "/usr/bin/env", "-u", "LD_LIBRARY_PATH",
            "/bin/bash", "--login"
        )
        val env = mutableMapOf(
            // Outer linker needs this to resolve libtalloc/libandroid-shmem
            // for the proot binary itself. Unset again inside (see env -u).
            "LD_LIBRARY_PATH" to prootLibDir(context).absolutePath,
            // Seccomp acceleration breaks on many new kernels; ptrace-only
            // is slower but correct (same as proot-distro default there).
            "PROOT_NO_SECCOMP" to "1",
            "TERM" to "xterm-256color",
            "HOME" to "/root",
            "TMPDIR" to "/tmp"
        )
        return ProotLaunch(argv, env)
    }

    /** Minimal guest fixups after rootfs extraction. */
    fun applyGuestFixups(context: Context, log: (String) -> Unit = {}) {
        try {
            val rootfs = rootfsDir(context)
            // DNS: ubuntu-base ships an empty stub; without it apt fails.
            try {
                val resolv = File(rootfs, "etc/resolv.conf")
                // resolv.conf may be a dangling symlink in some bases; replace.
                try {
                    if (java.nio.file.Files.isSymbolicLink(resolv.toPath())) resolv.delete()
                } catch (_: Exception) {
                }
                resolv.parentFile?.mkdirs()
                resolv.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
            } catch (e: Exception) {
                log("resolv.conf fixup: ${e.message}")
            }
            // Hostname so prompts/tools don't choke on empty.
            try {
                val hostname = File(rootfs, "etc/hostname")
                if (!hostname.isFile || hostname.readText().isBlank()) {
                    hostname.writeText("pockethost\n")
                }
            } catch (_: Exception) {
            }
            // Guest-visible server dir + marker.
            try {
                File(rootfs, "root/server").mkdirs()
            } catch (_: Exception) {
            }
            try {
                versionFile(context).writeText(UBUNTU_VERSION)
                installedMarker(context).writeText(System.currentTimeMillis().toString())
            } catch (e: Exception) {
                log("marker write: ${e.message}")
            }
        } catch (e: Exception) {
            log("guest fixups: ${e.message}")
        }
    }
}
