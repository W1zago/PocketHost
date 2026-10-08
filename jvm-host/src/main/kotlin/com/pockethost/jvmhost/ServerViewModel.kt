package com.pockethost.jvmhost

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Observable wrapper over [UbuntuServer] + [BridgeServer] for the PC UI.
 *
 * This is the `ServerViewModel` the jvm-host README sketches, kept
 * dependency-free on purpose (plain listener callbacks, no coroutines,
 * no Compose): a future Compose Desktop shell mirrors [state] into
 * `MutableStateFlow` and binds start/stop/install buttons to the methods
 * below. The CLI (`serve`) uses it too, so UI and CLI share one path.
 *
 * All methods block - never call from the UI thread.
 */
class ServerViewModel(
    baseDir: File = HostInfo.defaultBaseDir(),
    serverId: String = "ubuntu",
    private val externalLog: (String) -> Unit = {}
) {

    /** Point-in-time UI state. */
    data class VmState(
        val installed: Boolean,
        val shellRunning: Boolean,
        val backend: Backend?,
        val pid: Long?,
        val bridgeRunning: Boolean,
        val bridgePort: Int?,
        val lanIps: List<String>,
        val workDir: File,
        val host: Host,
        val recentLog: List<String>,
        val degradedMode: Boolean = false,
        val fallbackReason: String? = null
    )

    private val listeners = CopyOnWriteArrayList<(VmState) -> Unit>()
    private val logBuffer = ArrayDeque<String>()
    private val logLock = Any()

    val server: UbuntuServer = UbuntuServer(baseDir, serverId) { line ->
        appendLog(line)
        try {
            externalLog(line)
        } catch (_: Exception) {
        }
    }
    val bridge: BridgeServer = BridgeServer(server) { line ->
        appendLog(line)
        try {
            externalLog(line)
        } catch (_: Exception) {
        }
    }

    fun addListener(listener: (VmState) -> Unit) {
        listeners.add(listener)
        try {
            listener(state())
        } catch (_: Exception) {
        }
    }

    fun removeListener(listener: (VmState) -> Unit) {
        listeners.remove(listener)
    }

    fun state(): VmState {
        val st = server.status()
        val recent = synchronized(logLock) { logBuffer.toList() }
        // No-log selection here: state() is polled frequently.
        val sel = server.selectBackend(st.host)
        return VmState(
            installed = st.installed,
            shellRunning = st.running,
            backend = if (st.running) st.backend else sel.backend,
            pid = st.pid,
            bridgeRunning = bridge.isRunning(),
            bridgePort = bridge.actualPort,
            lanIps = LanInfo.lanIPv4(),
            workDir = st.workDir,
            host = st.host,
            recentLog = recent,
            degradedMode = sel.degraded,
            fallbackReason = sel.fallbackReason
        )
    }

    /** Human `IP:PORT` lines for the observer, empty when bridge is down. */
    fun lanUrls(): List<String> {
        val port = bridge.actualPort ?: return emptyList()
        val ips = LanInfo.lanIPv4()
        if (ips.isEmpty()) return listOf("http://127.0.0.1:$port/api/status")
        return ips.map { "http://$it:$port/api/status" }
    }

    fun install(onProgress: (Int) -> Unit = {}): Boolean {
        val ok = server.install(onProgress)
        refresh()
        return ok
    }

    fun start(): Boolean {
        val ok = server.start()
        refresh()
        return ok
    }

    fun stop(): Boolean {
        val ok = server.stop()
        refresh()
        return ok
    }

    fun startBridge(port: Int = BridgeServer.DEFAULT_PORT): Boolean {
        val ok = bridge.start(port)
        refresh()
        return ok
    }

    fun stopBridge(): Boolean {
        val ok = bridge.stop()
        refresh()
        return ok
    }

    /**
     * Full "Ubuntu Server online" path: shell first (guest required),
     * then the LAN bridge. Returns true only when the bridge
     * is listening - that is what the phone connects to.
     *
     * Strict by default: on a host without an isolated Ubuntu guest
     * (no WSL / no proot+rootfs / low disk) this FAILS with an explicit
     * ERROR instead of silently serving PowerShell as "Ubuntu".
     * Pass [allowDegraded] = true to keep the old HOST_SHELL fallback
     * (a WARNING is logged and surfaced via /api/status fallbackReason).
     *
     * Stale `server.pid` from a dead JVM is cleaned first ("stale pid
     * cleaned") so a previous crash can never fake a live shell.
     */
    fun serve(port: Int = BridgeServer.DEFAULT_PORT, allowDegraded: Boolean = false): Boolean {
        cleanStaleShellPid()
        val host = HostInfo.current()
        val readinessError = server.guestReadinessError(host)
        if (readinessError != null && !allowDegraded) {
            val msg = "ERROR: cannot serve Ubuntu Server ($readinessError). " +
                "Fix the backend above and retry; or re-run with degraded host-shell mode if you really want it."
            try {
                externalLog(msg)
            } catch (_: Exception) {
            }
            appendLog(msg)
            refresh()
            return false
        }
        if (readinessError != null) {
            val warn = "WARNING: serving in degraded HOST_SHELL mode ($readinessError)"
            try {
                externalLog(warn)
            } catch (_: Exception) {
            }
            appendLog(warn)
        }
        if (!server.status().running) {
            if (!server.start()) {
                refresh()
                return false
            }
        }
        val ok = if (bridge.isRunning()) true else bridge.start(port)
        refresh()
        return ok
    }

    /**
     * Drops a dead `server.pid` left by a crashed JVM so it can never
     * fake a live shell. Logs "stale pid cleaned (was N)".
     */
    fun cleanStaleShellPid(): Boolean {
        return try {
            val dir = server.workDir()
            val pidFile = File(dir, LocalProcessManager.PID_NAME)
            if (!pidFile.isFile) return false
            val pid = pidFile.readText().trim().toLongOrNull() ?: run {
                try {
                    pidFile.delete()
                } catch (_: Exception) {
                }
                return true
            }
            if (server.processes.isRunning(server.serverId, dir)) return false
            // Double-check via OS handle: isRunning already does this, but be explicit.
            val alive = try {
                ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            } catch (_: Exception) {
                false
            }
            if (!alive) {
                try {
                    pidFile.delete()
                } catch (_: Exception) {
                }
                val msg = "stale pid cleaned (was $pid)"
                try {
                    externalLog(msg)
                } catch (_: Exception) {
                }
                appendLog(msg)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Stops the bridge and the shell, in that order. */
    fun serveStop(): Boolean {
        var okBridge = true
        var okShell = true
        if (bridge.isRunning()) okBridge = bridge.stop()
        if (server.status().running) okShell = server.stop()
        refresh()
        return okBridge && okShell
    }

    fun exec(commandLine: String): Boolean {
        val ok = server.exec(commandLine)
        refresh()
        return ok
    }

    fun runOneShot(commandLine: String, timeoutMs: Long = 120_000L): Int {
        val code = server.runOneShot(commandLine, timeoutMs)
        refresh()
        return code
    }

    fun tailLog(lines: Int = 200): String = server.tailLog(lines)

    fun refresh() {
        val s = try {
            state()
        } catch (_: Exception) {
            return
        }
        for (l in listeners) {
            try {
                l(s)
            } catch (_: Exception) {
            }
        }
    }

    private fun appendLog(line: String) {
        // Keep per-line entries so the UI can render a lazy list.
        val parts = line.split('\n')
        synchronized(logLock) {
            for (p in parts) {
                if (p.isEmpty() && logBuffer.isNotEmpty()) continue
                logBuffer.addLast(p)
                while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            }
        }
        refresh()
    }

    companion object {
        private const val MAX_LOG_LINES = 500
    }
}
