package com.pockethost.jvmhost

import java.io.File

/** How the Ubuntu shell actually runs on this PC. */
enum class Backend {
    /** Linux: ubuntu-base rootfs entered via system proot (isolated guest). */
    PROOT_GUEST,

    /** Windows: Microsoft WSL2 Ubuntu distro (isolated guest). */
    WSL,

    /** Fallback everywhere: interactive host shell in the server folder. */
    HOST_SHELL
}

/** Point-in-time server state for the (future) PC UI to render. */
data class ServerStatus(
    val installed: Boolean,
    val running: Boolean,
    val backend: Backend?,
    val pid: Long?,
    val workDir: File,
    val host: Host
)

/**
 * Result of backend selection with an explicit degraded flag.
 *
 * When [degraded] is true, [backend] fell back to [Backend.HOST_SHELL]
 * NOT by user choice - [fallbackReason] names the exact cause
 * (WSL not installed / no distro / low disk / rootfs or proot missing).
 * Callers of `serve()` use this to fail fast instead of silently
 * running PowerShell while the user expects Ubuntu.
 */
data class BackendSelection(
    val backend: Backend,
    val degraded: Boolean,
    val fallbackReason: String?
)

/**
 * Single entry point for the "local Ubuntu Server" feature on PC.
 *
 * This is the JVM counterpart of the Android flow
 * (EmbeddedBootstrap + EmbeddedProcessManager + ServerService.startUbuntu):
 * - [install] fetches and prepares the Ubuntu image for this host OS
 * - [start] launches an interactive shell (guest when available,
 *   host shell otherwise) managed like any other PocketHost server:
 *   stdin via [exec], output tailed from `server.log` via [tailLog]
 * - [stop] terminates the session
 *
 * Dependency-free on purpose (plain callbacks, no coroutines/Compose):
 * a future Compose Desktop `ServerViewModel` wraps this class and mirrors
 * [status] into `MutableStateFlow`. See the jvm-host README for a snippet.
 * All blocking calls - never invoke from the UI thread.
 */
class UbuntuServer(
    baseDir: File = HostInfo.defaultBaseDir(),
    val serverId: String = "ubuntu",
    val log: (String) -> Unit = {}
) {

    val installer = UbuntuInstaller(baseDir, log)
    val processes = LocalProcessManager()
    private var lastBackend: Backend? = null
    private var lastFallbackReason: String? = null

    /** Last backend selection (null before the first start/select). */
    fun lastSelection(): BackendSelection? {
        val b = lastBackend ?: return null
        return BackendSelection(b, b == Backend.HOST_SHELL, lastFallbackReason)
    }

    /** Last fallback reason, or null when the last selection was a clean guest. */
    fun lastFallbackReason(): String? = lastFallbackReason

    /** True when the last selection degraded to HOST_SHELL. */
    fun isDegraded(): Boolean = lastBackend == Backend.HOST_SHELL && lastFallbackReason != null

    fun workDir(): File = File(installer.serversRoot(), serverId).apply { mkdirs() }

    fun status(): ServerStatus {
        val host = HostInfo.current()
        val dir = workDir()
        val running = processes.isRunning(serverId, dir)
        return ServerStatus(
            installed = isBackendReady(host),
            running = running,
            // No logging here: status is polled on every /api/status call.
            backend = if (running) lastBackend ?: selectBackend(host).backend else null,
            pid = processes.getSession(serverId)?.pid ?: processes.readPid(dir),
            workDir = dir,
            host = host
        )
    }

    /**
     * Picks the backend for this host:
     * Linux + rootfs + proot -> PROOT_GUEST;
     * Windows + WSL Ubuntu   -> WSL;
     * otherwise              -> HOST_SHELL (always available).
     *
     * Never silent: when the result degrades to HOST_SHELL the exact
     * [fallbackReason] is logged (WSL not installed / no distro /
     * low disk / rootfs or proot missing). Prefer [selectBackend] when
     * the caller needs the reason programmatically.
     */
    fun detectBackend(host: Host = HostInfo.current()): Backend {
        val sel = selectBackend(host)
        if (sel.degraded && sel.fallbackReason != null) {
            log("WARNING: degraded to HOST_SHELL (${sel.fallbackReason})")
        }
        return sel.backend
    }

    /**
     * Backend selection with an explicit degraded flag and reason.
     * Pure except for the WSL/distro probe (short-lived `wsl -l -q`).
     * Does NOT log by itself - see [detectBackend] for the logging wrapper.
     */
    fun selectBackend(host: Host = HostInfo.current()): BackendSelection {
        if (host.os == OsKind.LINUX) {
            val proot = HostInfo.systemProot()
            val installed = installer.isInstalled()
            if (installed && proot != null) {
                return BackendSelection(Backend.PROOT_GUEST, false, null)
            }
            val parts = mutableListOf<String>()
            if (!installed) parts.add("rootfs not installed - run install first")
            if (proot == null) parts.add("proot missing (${HostInfo.prootHint()})")
            val diskErr = checkLinuxDiskSpace()
            if (diskErr != null) parts.add(diskErr)
            return BackendSelection(
                Backend.HOST_SHELL, true,
                parts.ifEmpty { listOf(reason(host)) }.joinToString("; ")
            )
        }
        if (host.os == OsKind.WINDOWS) {
            // Disk health-check first: WSL needs gigabytes, not megabytes.
            val diskErr = checkWslDiskSpace()
            if (diskErr != null) {
                return BackendSelection(Backend.HOST_SHELL, true, diskErr)
            }
            if (!HostInfo.wslAvailable()) {
                return BackendSelection(
                    Backend.HOST_SHELL, true,
                    "WSL not installed - run in an elevated PowerShell: " +
                        "wsl --install -d ${UbuntuSpec.WSL_DISTRO}, reboot, then Start again"
                )
            }
            val distros = try {
                HostInfo.wslDistros()
            } catch (e: Exception) {
                emptyList()
            }
            if (distros.any { it.equals(UbuntuSpec.WSL_DISTRO, ignoreCase = true) || it.startsWith("Ubuntu", ignoreCase = true) }) {
                return BackendSelection(Backend.WSL, false, null)
            }
            val detail = if (distros.isEmpty()) {
                "WSL installed but has no distributions - run: wsl --install -d ${UbuntuSpec.WSL_DISTRO}"
            } else {
                "no Ubuntu distro in WSL (found: ${distros.joinToString(", ")}) - run: wsl --install -d ${UbuntuSpec.WSL_DISTRO}"
            }
            return BackendSelection(Backend.HOST_SHELL, true, detail)
        }
        return BackendSelection(Backend.HOST_SHELL, true, reason(host))
    }

    /** Human-readable fallback reason, or null when a clean guest is selected. */
    fun fallbackReason(host: Host = HostInfo.current()): String? = selectBackend(host).fallbackReason

    /**
     * Strict guest readiness for `serve()`: null when an isolated Ubuntu
     * guest can start, otherwise a readable error telling the user exactly
     * what to fix (no silent fallback - the caller must fail fast).
     */
    fun guestReadinessError(host: Host = HostInfo.current()): String? {
        val sel = selectBackend(host)
        if (!sel.degraded) return null
        return sel.fallbackReason ?: reason(host)
    }

    /** Disk health-check for the WSL path (Windows). Null when OK. */
    fun checkWslDiskSpace(): String? {
        return try {
            val free = HostInfo.freeBytes(installer.baseDir)
            if (free < 0) return null // unknown - do not block
            if (free < UbuntuSpec.WSL_MIN_FREE_BYTES) {
                val freeGb = free / 1024 / 1024 / 1024
                val needGb = UbuntuSpec.WSL_MIN_FREE_BYTES / 1024 / 1024 / 1024
                "insufficient disk space for WSL backend " +
                    "(${freeGb} GB free, need >= ${needGb} GB) - free disk space and retry"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Disk health-check for the Linux rootfs path. Null when OK. */
    private fun checkLinuxDiskSpace(): String? {
        return try {
            val free = HostInfo.freeBytes(installer.baseDir)
            if (free in 0 until UbuntuSpec.MIN_FREE_BYTES) {
                "low space (${free / 1024 / 1024} MB free, ~300 MB needed for Ubuntu)"
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** True when an isolated Ubuntu guest can start (not just the fallback shell). */
    fun isBackendReady(host: Host = HostInfo.current()): Boolean = when (host.os) {
        OsKind.LINUX -> installer.isInstalled()
        OsKind.WINDOWS -> isWslReady()
        OsKind.MACOS, OsKind.UNKNOWN -> false
    }

    fun isWslReady(distro: String = UbuntuSpec.WSL_DISTRO): Boolean {
        if (HostInfo.current().os != OsKind.WINDOWS) return false
        // Canonical answer: a clean (non-degraded) WSL selection.
        // The distro parameter narrows the match when a caller asks for
        // a specific distribution name.
        if (distro != UbuntuSpec.WSL_DISTRO) {
            if (checkWslDiskSpace() != null) return false
            if (!HostInfo.wslAvailable()) return false
            return try {
                HostInfo.wslDistros().any { it.equals(distro, ignoreCase = true) }
            } catch (_: Exception) {
                false
            }
        }
        return !selectBackend().degraded
    }

    /**
     * Downloads/prepares the Ubuntu image for this host.
     * On Windows this only verifies WSL (provisioning is `wsl --install`).
     */
    fun install(onProgress: (Int) -> Unit = {}): Boolean =
        installer.ensureInstalled(HostInfo.current(), onProgress)

    /**
     * Starts the Ubuntu shell. Falls back to a host shell when the isolated
     * guest is unavailable, logging exactly which backend was chosen and why.
     *
     * Honest success: `ProcessBuilder.start()` succeeding only means the
     * launcher ran - the shell may die instantly (broken proot/rootfs,
     * missing WSL distro). A unique echo token must round-trip through
     * stdin -> shell -> server.log while the process stays alive, otherwise
     * the session is stopped again and false is returned - RUNNING is only
     * ever set on a proven-live shell.
     */
    fun start(): Boolean {
        val host = HostInfo.current()
        val dir = workDir()
        if (processes.isRunning(serverId)) {
            log("Server '$serverId' is already running.")
            return false
        }
        val sel = selectBackend(host)
        lastBackend = sel.backend
        lastFallbackReason = sel.fallbackReason
        if (sel.degraded) {
            log("WARNING: degraded to HOST_SHELL (${sel.fallbackReason})")
        }
        val backend = sel.backend
        val launched = when (backend) {
            Backend.PROOT_GUEST -> {
                val proot = HostInfo.systemProot()
                if (proot == null) {
                    log(HostInfo.prootHint())
                    return startHostShell(dir, host, "proot went missing")
                }
                log("Starting Ubuntu ${UbuntuSpec.UBUNTU_VERSION} via proot (guest)...")
                val rootfs = installer.rootfsDir()
                if (!preflightProotGuest(proot, rootfs, dir)) return false
                val argv = processes.prootArgv(proot, rootfs, dir)
                val env = processes.prootEnv(File(installer.baseDir, "cache/proot-tmp"))
                log("proot env: PROOT_TMPDIR=${env["PROOT_TMPDIR"]} PROOT_NO_SECCOMP=1")
                processes.start(serverId, argv, dir, env)
            }
            Backend.WSL -> {
                log("Starting Ubuntu via WSL (${UbuntuSpec.WSL_DISTRO})...")
                processes.start(serverId, processes.wslArgv(), dir)
            }
            Backend.HOST_SHELL -> return startHostShellVerified(dir, host, reason(host))
        }
        if (!launched) {
            log("ERROR: failed to launch the shell process.")
            return false
        }
        val live = verifyLiveShell(dir, host)
        // First-start provision (mirrors Android: maximum set, background,
        // console stays usable). Marker-gated so anything but success retries
        // on the next start. Only the proot guest needs it: the WSL Store
        // image is already fuller and `apt` there is a manual user step.
        if (live && lastBackend == Backend.PROOT_GUEST) {
            try {
                if (installer.needsEssentialPackages()) {
                    log("First start: installing essential packages in background (${UbuntuSpec.ESSENTIAL_PACKAGES.joinToString(" ")}) - do not run apt manually meanwhile...")
                    Thread({
                        try {
                            ensureEssentialPackages()
                        } catch (e: Exception) {
                            try { log("Essential packages error: ${e.message}") } catch (_: Exception) {}
                        }
                    }, "ubuntu-essential").apply { isDaemon = true; start() }
                }
            } catch (_: Exception) {
            }
        }
        return live
    }

    /**
     * Background first-start provision of [UbuntuSpec.ESSENTIAL_PACKAGES]
     * on the Linux/proot backend. Runs apt as a one-shot guest command
     * (output goes to `server.log`); returns true only when the marker was
     * written. False (low space, apt error, timeout) retries next start.
     */
    fun ensureEssentialPackages(timeoutMs: Long = ESSENTIAL_PACKAGES_TIMEOUT_MS): Boolean {
        if (lastBackend != null && lastBackend != Backend.PROOT_GUEST) return false
        if (!installer.isInstalled()) return false
        if (!installer.needsEssentialPackages()) return true
        return try {
            val free = HostInfo.freeBytes(installer.baseDir)
            if (free >= 0 && free < UbuntuSpec.ESSENTIAL_PACKAGES_MIN_FREE_BYTES) {
                log("Essential packages skipped: low space (${free / 1024 / 1024}MB free, ~800MB needed). Free space and restart to retry.")
                return false
            }
            val code = runOneShot(UbuntuSpec.essentialAptCommand(), timeoutMs)
            if (code == 0) {
                installer.markEssentialPackagesInstalled()
                log("Essential packages installed (${UbuntuSpec.ESSENTIAL_PACKAGES.size} pkgs).")
                log("SSH ready: run `passwd` once, then `/usr/sbin/sshd -p 2222` (no systemd under proot, port 22 is unreachable as fake-root).")
                true
            } else {
                log("Essential packages: apt exited with code $code - will retry on next start. See output above.")
                false
            }
        } catch (e: Exception) {
            log("Essential packages error: ${e.message} - will retry on next start.")
            false
        }
    }

    /**
     * Guest pre-flight (Linux/proot): the exact files the guest exec needs.
     * A missing/unreadable `/usr/bin/env` is the classic bare
     * `execve("/usr/bin/env"): Permission denied` one second later -
     * refuse here with the file named instead.
     */
    private fun preflightProotGuest(proot: File, rootfs: File, dir: File): Boolean {
        if (!proot.isFile || !proot.canExecute()) {
            log("ERROR: proot binary not executable: ${proot.absolutePath}")
            return false
        }
        val envBin = File(rootfs, "usr/bin/env")
        val bash = File(rootfs, "usr/bin/bash")
        log(
            "proot pre-flight: env(file=${envBin.isFile},read=${envBin.canRead()},exec=${envBin.canExecute()}) " +
                "bash(file=${bash.isFile},exec=${bash.canExecute()}) workDir=${dir.absolutePath}"
        )
        if (!envBin.isFile) {
            log("ERROR: usr/bin/env missing in rootfs (${envBin.absolutePath}) - rootfs truncated, run install again.")
            return false
        }
        if (!envBin.canRead()) {
            log("ERROR: usr/bin/env not readable (${envBin.absolutePath}) - check permissions, run install again.")
            return false
        }
        if (!bash.isFile) {
            log("ERROR: usr/bin/bash missing in rootfs - rootfs truncated, run install again.")
            return false
        }
        return true
    }

    /**
     * Sends a unique echo token and requires it back in server.log while
     * the process stays alive. Stops the session and returns false when
     * the shell never answers (dead pipe, instant crash).
     */
    private fun verifyLiveShell(dir: File, host: Host): Boolean {
        val token = "POCKETHOST_ALIVE_" + System.currentTimeMillis()
        val probe = if (host.os == OsKind.WINDOWS && lastBackend == Backend.HOST_SHELL) {
            // PowerShell: echo is Write-Output, $? expands to True/False -
            // either way the token lands in the log.
            "echo $token"
        } else {
            "echo $token"
        }
        try {
            processes.sendInput(serverId, probe)
        } catch (_: Exception) {
        }
        val answered = processes.waitForMarker(serverId, dir, token, MARKER_TIMEOUT_MS) { log(it) }
        if (!answered || !processes.isRunning(serverId, dir)) {
            log("ERROR: shell started but did not answer (no $token in server.log) - see [process exited] above.")
            try {
                processes.stop(serverId, dir)
            } catch (_: Exception) {
            }
            return false
        }
        log("Shell answered ($token) - server '$serverId' is live.")
        return true
    }

    private fun reason(host: Host): String = when (host.os) {
        OsKind.LINUX -> "rootfs or proot missing - run install first"
        OsKind.WINDOWS -> "no Ubuntu distro in WSL - run install for setup steps"
        OsKind.MACOS, OsKind.UNKNOWN -> "no isolated backend on this OS yet"
    }

    private fun startHostShell(dir: File, host: Host, why: String): Boolean {
        log("Starting host shell instead ($why).")
        if (host.os == OsKind.WINDOWS && !HostInfo.findOnPath("powershell.exe").let { it != null }) {
            log("powershell.exe not found on PATH, trying cmd.")
        }
        lastBackend = Backend.HOST_SHELL
        return processes.start(serverId, processes.hostShellArgv(host), dir)
    }

    /**
     * Host-shell start with the same liveness gate as the guest path:
     * the shell must echo the token back, otherwise the session is
     * stopped again and false is returned.
     */
    private fun startHostShellVerified(dir: File, host: Host, why: String): Boolean {
        if (!startHostShell(dir, host, why)) return false
        return verifyLiveShell(dir, host)
    }

    fun stop(): Boolean = processes.stop(serverId, workDir())

    /** Sends a command line to the running shell. False when not running. */
    fun exec(commandLine: String): Boolean = processes.sendInput(serverId, commandLine)

    /**
     * Runs a single command on the Ubuntu backend and waits for it.
     * Output goes to `server.log`; returns the exit code
     * (-1 on launch failure, -2 on timeout).
     */
    fun runOneShot(commandLine: String, timeoutMs: Long = 120_000L): Int {
        val host = HostInfo.current()
        val dir = workDir()
        val backend = selectBackend(host).backend
        val argv: List<String>
        val envExtra: Map<String, String>
        when (backend) {
            Backend.PROOT_GUEST -> {
                val proot = HostInfo.systemProot()
                if (proot == null) {
                    log(HostInfo.prootHint())
                    return -1
                }
                argv = processes.prootOneShotArgv(proot, installer.rootfsDir(), dir, commandLine)
                envExtra = processes.prootEnv(File(installer.baseDir, "cache/proot-tmp"))
            }
            Backend.WSL -> {
                argv = processes.wslOneShotArgv(commandLine)
                envExtra = emptyMap()
            }
            Backend.HOST_SHELL -> {
                argv = processes.hostShellOneShotArgv(commandLine, host)
                envExtra = emptyMap()
            }
        }
        log("Running ($backend): $commandLine")
        return try {
            processes.appendLogToDir(dir, "\n\$ $commandLine\n")
            val pb = ProcessBuilder(argv).directory(dir).redirectErrorStream(true)
            val env = pb.environment()
            try {
                if (!env.containsKey("TERM")) env["TERM"] = "xterm-256color"
                for ((k, v) in envExtra) env[k] = v
            } catch (_: Exception) {
            }
            val process = pb.start()
            val pump = Thread({
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = try {
                                reader.read(buf)
                            } catch (_: Exception) {
                                -1
                            }
                            if (n <= 0) break
                            processes.appendLogToDir(dir, String(buf, 0, n))
                        }
                    }
                } catch (_: Exception) {
                }
            }, "jvmhost-oneshot").apply { isDaemon = true; start() }
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                try {
                    process.destroyForcibly()
                } catch (_: Exception) {
                }
                processes.appendLogToDir(dir, "\n[command timed out after ${timeoutMs / 1000}s]\n")
                return -2
            }
            try {
                pump.join(5000)
            } catch (_: Exception) {
            }
            val code = process.exitValue()
            processes.appendLogToDir(dir, "\n[command exited, code $code]\n")
            code
        } catch (e: Exception) {
            processes.appendLogToDir(dir, "\n[LAUNCH ERROR: ${e.message}]\n")
            -1
        }
    }

    /** Sends raw bytes (Ctrl/C, escape sequences). False when not running. */
    fun sendRaw(bytes: ByteArray): Boolean = processes.sendRaw(serverId, bytes)

    /** Last [lines] lines of `server.log`. */
    fun tailLog(lines: Int = 200): String = processes.tailLog(workDir(), lines)

    companion object {
        /**
         * How long [start] waits for the echo token. WSL cold-boots a VM,
         * so this is generous; a dead shell fails fast on process death
         * anyway (waitForMarker returns the moment the handle dies).
         */
        private const val MARKER_TIMEOUT_MS = 20_000L

        /** apt update + install of the max set can take a while. */
        const val ESSENTIAL_PACKAGES_TIMEOUT_MS = 30L * 60 * 1000
    }
}
