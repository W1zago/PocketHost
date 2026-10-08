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

    /** Bump to re-run the first-start provision on existing installs. */
    const val ESSENTIAL_PACKAGES_VERSION = "1"

    /**
     * "Maximum" first-start set (user choice): base tools + shells +
     * runtimes. Heavy part is nodejs/npm + python3 (~300-400MB with deps,
     * several minutes on a phone) - installed in background AFTER the
     * console is already live, never blocking Start.
     */
    val ESSENTIAL_PACKAGES = listOf(
        "ca-certificates",
        "curl",
        "wget",
        "git",
        "nano",
        "vim",
        "iputils-ping",
        "htop",
        "python3",
        "python3-pip",
        "openssh-client",
        "nodejs",
        "npm"
    )

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

    /** proot trampoline for guest ELF exec.
     *
     * The Termux .deb also ships a copy under tools/proot/libexec, but that
     * copy lives in filesDir where execve() is denied (W^X, EACCES on
     * Android 10+ with targetSdk 29+). The packaged jniLibs copy is mapped
     * from nativeLibraryDir (exec allowed, same as dlopen) - use it first,
     * keep the filesDir copy only as legacy fallback.
     * Packaged names: libproot_loader.so / libproot_loader32.so
     * (see app/src/main/jniLibs/<abi>/). */
    fun prootLoader(context: Context): File {
        try {
            val native = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so")
            if (native.isFile) return native
        } catch (_: Exception) {}
        return File(prootPrefix(context), "libexec/proot/loader")
    }

    fun prootLoader32(context: Context): File {
        try {
            val native32 = File(context.applicationInfo.nativeLibraryDir, "libproot_loader32.so")
            if (native32.isFile) return native32
            // 32-bit ABIs ship a single loader (armeabi-v7a has only
            // libproot_loader.so): it IS the 32-bit trampoline there.
            val abi = EmbeddedJava.abi()
            if (abi == "armeabi-v7a" || abi == "armeabi" || abi == "x86") {
                val native = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so")
                if (native.isFile) return native
            }
        } catch (_: Exception) {}
        return File(prootPrefix(context), "libexec/proot/loader32")
    }

    /** True when at least one loader resolves to a real file (native or legacy). */
    fun loaderDiag(context: Context): String {
        return try {
            val l = prootLoader(context)
            val l32 = prootLoader32(context)
            val nativeDir = try { context.applicationInfo.nativeLibraryDir } catch (_: Exception) { "?" }
            "nativeDir=$nativeDir loader=${l.absolutePath}(${l.isFile},exec=${l.canExecute()}) " +
                "loader32=${l32.absolutePath}(${l32.isFile},exec=${l32.canExecute()})"
        } catch (e: Exception) {
            "loaderDiag failed: ${e.message}"
        }
    }

    fun rootfsDir(context: Context): File = EmbeddedPaths.ubuntuRootfsDir(context)

    fun installedMarker(context: Context): File = File(rootfsDir(context), ".installed")

    fun versionFile(context: Context): File = File(rootfsDir(context), ".version")

    /** First-start provision marker: content is [ESSENTIAL_PACKAGES_VERSION]. */
    fun packagesMarker(context: Context): File = File(rootfsDir(context), ".packages-installed")

    /** True when the guest is installed but the essential set was not provisioned yet. */
    fun needsEssentialPackages(context: Context): Boolean {
        return try {
            if (!isInstalled(context)) return false
            packagesMarker(context).takeIf { it.isFile }?.readText()?.trim() != ESSENTIAL_PACKAGES_VERSION
        } catch (_: Exception) {
            true
        }
    }

    fun markEssentialPackagesInstalled(context: Context) {
        try {
            packagesMarker(context).writeText(ESSENTIAL_PACKAGES_VERSION)
        } catch (_: Exception) {
        }
    }

    /** Non-interactive guest shell command for the first-start provision. */
    fun essentialAptCommand(): String {
        val pkgs = ESSENTIAL_PACKAGES.joinToString(" ")
        return "export DEBIAN_FRONTEND=noninteractive && " +
            "apt-get update && apt-get install -y $pkgs && apt-get clean"
    }

    fun isInstalled(context: Context): Boolean {
        return try {
            if (!installedMarker(context).exists()) return false
            if (versionFile(context).takeIf { it.isFile }?.readText()?.trim() != UBUNTU_VERSION) return false
            if (!prootBin(context).isFile) return false
            if (!prootLoader(context).isFile) return false
            // usr/bin/bash directly (bin/ is a symlink to usr/bin in ubuntu-base).
            if (!File(rootfsDir(context), "usr/bin/bash").isFile) return false
            // env is the actual exec target (via `env -u ... bash`); without it
            // proot dies with a bare ENOENT, so treat as not installed.
            if (!File(rootfsDir(context), "usr/bin/env").isFile) return false
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
        // Pre-flight: the exact files proot exec needs. On failure log WHICH
        // one is missing - "execve: No such file" gives no details otherwise.
        val envBin = File(rootfs, "usr/bin/env")
        val bash = File(rootfs, "bin/bash")
        val bashUsr = File(rootfs, "usr/bin/bash")
        val loader = File(rootfs, "usr/lib/ld-linux-aarch64.so.1")
        val libLink = File(rootfs, "lib")
        val trampoline = prootLoader(context)
        val trampoline32 = prootLoader32(context)
        // Re-assert +x on every launch for the filesDir copies only: missing
        // exec bit and the SELinux exec ban look identical (EACCES), this
        // tells them apart. Native .so copies in nativeLibraryDir are
        // already mapped executable - chmod there is a no-op, never required.
        try {
            proot.setExecutable(true)
            val prefixPath = prootPrefix(context).absolutePath
            if (trampoline.absolutePath.startsWith(prefixPath)) {
                trampoline.setExecutable(true)
            }
            if (trampoline32.absolutePath.startsWith(prefixPath)) {
                trampoline32.setExecutable(true)
            }
        } catch (_: Exception) {
        }
        // Guest exec/read bits: proot mmap's the guest ELFs (env, bash, the
        // glibc loader) through the trampoline, so an unreadable guest file
        // surfaces as a bare `execve("/usr/bin/env"): Permission denied`.
        // Best-effort chmod - tar modes are usually already right.
        try {
            envBin.setExecutable(true)
            bashUsr.setExecutable(true)
        } catch (_: Exception) {
        }
        log(
            "proot pre-flight: env(file=${envBin.isFile},size=${envBin.length()}," +
                "read=${envBin.canRead()},exec=${envBin.canExecute()}) " +
                "bash=${bash.isFile}(exec=${bash.canExecute()}) " +
                "loader=${loader.isFile} liblink=${java.nio.file.Files.isSymbolicLink(libLink.toPath())} " +
                "trampoline=${trampoline.isFile}(${trampoline.canExecute()}) " +
                "trampoline32=${trampoline32.isFile} prootExec=${proot.canExecute()}"
        )
        log("proot loader resolve: ${loaderDiag(context)}")
        if (!envBin.isFile) {
            log("ERROR: usr/bin/env missing in rootfs (${envBin.absolutePath}) - rootfs truncated, reinstall Ubuntu from server settings.")
            return null
        }
        if (!envBin.canRead()) {
            log("ERROR: usr/bin/env not readable (${envBin.absolutePath}) - reinstall Ubuntu from server settings.")
            return null
        }
        // The filesDir trampoline copy (tools/proot/libexec/...) can NEVER
        // exec on Android 10+ with targetSdk 29+: File.canExecute() reads
        // only mode bits, the kernel/SELinux W^X ban still denies it, and
        // proot then dies with `execve("/usr/bin/env"): Permission denied`.
        // Only the packaged jniLibs copy mapped from nativeLibraryDir
        // (libproot_loader.so) is executable there. Launching with the
        // filesDir copy is a guaranteed 1-second death - refuse honestly
        // instead, with the native dir listing so the cause is visible.
        val prefixPath = prootPrefix(context).absolutePath
        if (trampoline.absolutePath.startsWith(prefixPath)) {
            val nativeDir = try {
                context.applicationInfo.nativeLibraryDir
            } catch (_: Exception) {
                "?"
            }
            val nativeLs = try {
                File(nativeDir).listFiles()?.map { it.name }?.sorted()?.joinToString(",") ?: "<empty>"
            } catch (e: Exception) {
                "<unlistable: ${e.message}>"
            }
            log("ERROR: no packaged loader in nativeLibraryDir ($nativeDir has: $nativeLs) - " +
                "filesDir copy ${trampoline.absolutePath} cannot exec on Android 10+ (W^X). " +
                "Update/reinstall the app so jniLibs/libproot_loader.so is packaged, then retry.")
            return null
        }
        if (!bash.isFile || !loader.isFile) {
            log("rootfs incomplete (bash or loader missing) - reinstall required")
            return null
        }
        // Termux-built proot has /data/data/com.termux/files/usr/tmp and the
        // loader prefix compiled in. Neither appears in our argv/env below:
        // both defaults are overridden by PROOT_TMPDIR / PROOT_LOADER(_32),
        // which the proot process verifiably receives (see the "proot env:"
        // line after launch - EmbeddedProcessManager applies launch.env to
        // the ProcessBuilder environment 1:1).
        // The dir MUST exist before exec: proot aborts when it cannot
        // create its socket there. Never fall back to the com.termux path.
        val prootTmp = File(context.cacheDir, "proot-tmp")
        try {
            prootTmp.mkdirs()
            // Best-effort writability probe so a read-only cache surfaces here,
            // not as a bare proot execve failure.
            if (!prootTmp.isDirectory) {
                log("ERROR: cannot create PROOT_TMPDIR ${prootTmp.absolutePath}")
                return null
            }
            val probe = File(prootTmp, ".write_test")
            try {
                probe.createNewFile()
                probe.delete()
            } catch (e: Exception) {
                log("ERROR: PROOT_TMPDIR not writable ${prootTmp.absolutePath}: ${e.message}")
                return null
            }
        } catch (e: Exception) {
            log("ERROR: PROOT_TMPDIR setup failed: ${e.message}")
            return null
        }
        log("PROOT_TMPDIR=${prootTmp.absolutePath} (exists=${prootTmp.isDirectory})")
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
            "-w", "/root/server"
        )
        if (envBin.isFile) {
            argv.addAll(listOf("/usr/bin/env", "-u", "LD_LIBRARY_PATH", "/bin/bash", "--login"))
        } else {
            // Partial rootfs without coreutils env: run bash directly so the
            // shell still starts (LD_LIBRARY_PATH leak is harmless for bash).
            log("WARNING: usr/bin/env missing, launching bash directly")
            argv.addAll(listOf("/bin/bash", "--login"))
        }
        val env = mutableMapOf(
            // Outer linker needs this to resolve libtalloc/libandroid-shmem
            // for the proot binary itself. Unset again inside (see env -u).
            "LD_LIBRARY_PATH" to prootLibDir(context).absolutePath,
            // Seccomp acceleration breaks on many new kernels; ptrace-only
            // is slower but correct (same as proot-distro default there).
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_TMPDIR" to prootTmp.absolutePath,
            // Termux proot has /data/data/com.termux/... compiled in and looks
            // for its loader trampoline there; override to our own prefix,
            // otherwise every guest exec dies with a bare ENOENT.
            "PROOT_LOADER" to prootLoader(context).absolutePath,
            "PROOT_LOADER_32" to prootLoader32(context).absolutePath,
            "TERM" to "xterm-256color",
            "HOME" to "/root",
            "TMPDIR" to "/tmp",
            // Guest PATH: ProcessBuilder inherits the Android PATH
            // (/product/bin:/apex/...:/system/bin) when launch.env lacks it,
            // so bare `uname`/`ls`/`cat` die with "command not found".
            // Ubuntu login default (same as /etc/login.defs ENV_PATH).
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        )
        log(
            "proot env: PROOT_TMPDIR=${env["PROOT_TMPDIR"]} " +
                "PROOT_LOADER=${env["PROOT_LOADER"]} " +
                "PROOT_LOADER_32=${env["PROOT_LOADER_32"]} " +
                "PROOT_NO_SECCOMP=1 LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]}"
        )
        return ProotLaunch(argv, env)
    }

    /**
     * One-shot variant of [buildLaunch]: runs a single guest command
     * (`bash --login -c <commandLine>`) instead of an interactive shell.
     * Used by the background first-start provision so apt runs in its own
     * proot process while the interactive console stays usable.
     * The caller passes a separate [tmpDir] (not the live shell PROOT_TMPDIR)
     * so two concurrent proot instances never share one socket dir.
     */
    fun buildOneShot(
        context: Context,
        workDir: File,
        commandLine: String,
        tmpDir: File,
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
        try {
            tmpDir.mkdirs()
        } catch (e: Exception) {
            log("ERROR: PROOT_TMPDIR setup failed: ${e.message}")
            return null
        }
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
            "/bin/bash", "--login", "-c", commandLine
        )
        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to prootLibDir(context).absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_TMPDIR" to tmpDir.absolutePath,
            "PROOT_LOADER" to prootLoader(context).absolutePath,
            "PROOT_LOADER_32" to prootLoader32(context).absolutePath,
            "TERM" to "xterm-256color",
            "HOME" to "/root",
            "TMPDIR" to "/tmp",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
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
            // Exec/read bits for the exact files proot execs on every boot.
            // Tar modes are usually right already; without +r here the first
            // launch dies with `execve("/usr/bin/env"): Permission denied`.
            try {
                for (p in listOf("usr/bin/env", "usr/bin/bash", "bin/bash")) {
                    try {
                        val f = File(rootfs, p)
                        if (f.isFile) {
                            f.setReadable(true, false)
                            f.setExecutable(true, false)
                        } else {
                            log("guest fixup: MISSING $p (rootfs truncated?)")
                        }
                    } catch (e: Exception) {
                        log("guest fixup chmod $p: ${e.message}")
                    }
                }
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
