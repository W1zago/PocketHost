package com.pockethost.jvmhost

import java.io.File

/**
 * Host-side process launcher for PC server sessions.
 *
 * Mirrors the Android port's EmbeddedProcessManager without any Android APIs:
 * - work dir per server, stdin via [sendInput], stdout+stderr to `server.log`
 * - log rotation 5 MB -> keep last 1 MB, background pump thread per process
 * - max [MAX_SERVERS] concurrent servers, race-safe insert under lock
 *
 * Backend argv builders ([prootArgv], [wslArgv], [hostShellArgv]) cover the
 * three ways an Ubuntu/host shell can run on a PC; backend selection lives
 * in [UbuntuServer].
 */
class LocalProcessManager {

    data class Session(
        val serverId: String,
        val pid: Long?,
        val startedAt: Long = System.currentTimeMillis()
    )

    private data class Running(
        val process: Process,
        val session: Session,
        val logFile: File
    )

    private val lock = Any()
    private val running = mutableMapOf<String, Running>()

    fun isRunning(serverId: String, workDir: File? = null): Boolean = synchronized(lock) {
        val r = running[serverId]
        if (r != null) {
            try {
                if (r.process.isAlive) return true
            } catch (_: Exception) {
            }
        }
        // Cross-process: a previous CLI/UI run may have left a live server
        // behind (pid file). The stdin pipe is gone with the old JVM, but
        // liveness, stop() and logs still work.
        if (workDir != null) {
            try {
                val pid = readPid(workDir)
                if (pid != null && isPidAlive(pid)) return true
            } catch (_: Exception) {
            }
        }
        false
    }

    fun runningCount(): Int = synchronized(lock) {
        val dead = running.filterValues {
            try {
                !it.process.isAlive
            } catch (_: Exception) {
                true
            }
        }.keys.toList()
        dead.forEach { running.remove(it) }
        running.size
    }

    fun getSession(serverId: String): Session? = synchronized(lock) {
        running[serverId]?.session
    }

    /**
     * Launches [command] as a ready argv (no shell wrapper).
     * Returns true when the process started.
     */
    fun start(
        serverId: String,
        command: List<String>,
        workDir: File,
        envExtra: Map<String, String> = emptyMap()
    ): Boolean {
        synchronized(lock) {
            if (running.containsKey(serverId)) return false
            if (running.size >= MAX_SERVERS) return false
        }
        return try {
            workDir.mkdirs()
            val logFile = logFile(workDir)
            if (!logFile.exists()) {
                try {
                    logFile.createNewFile()
                } catch (_: Exception) {
                }
            }

            val pb = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
            val env = pb.environment()
            try {
                if (!env.containsKey("TERM")) env["TERM"] = "xterm-256color"
                for ((k, v) in envExtra) env[k] = v
            } catch (_: Exception) {
            }

            appendLog(logFile, "\n\$ ${command.joinToString(" ")}\n")

            val process = pb.start()
            val pid: Long? = try {
                process.pid()
            } catch (_: Exception) {
                null
            }
            val session = Session(serverId, pid)
            val holder = Running(process, session, logFile)
            val inserted: Boolean = synchronized(lock) {
                val dead = running.filterValues {
                    try {
                        !it.process.isAlive
                    } catch (_: Exception) {
                        true
                    }
                }.keys.toList()
                dead.forEach { running.remove(it) }
                if (running.containsKey(serverId) || running.size >= MAX_SERVERS) {
                    false
                } else {
                    running[serverId] = holder
                    true
                }
            }
            if (!inserted) {
                try {
                    process.destroy()
                } catch (_: Exception) {
                }
                try {
                    Thread {
                        try {
                            Thread.sleep(1000)
                        } catch (_: Exception) {
                        }
                        try {
                            if (process.isAlive) process.destroyForcibly()
                        } catch (_: Exception) {
                        }
                    }.apply { isDaemon = true; start() }
                } catch (_: Exception) {
                }
                appendLog(logFile, "\n[start rejected: server already launching or limit $MAX_SERVERS]\n")
                return false
            }

            Thread({
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
                            appendLog(logFile, String(buf, 0, n))
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        val code = process.waitFor()
                        appendLog(logFile, "\n[process exited, code $code]\n")
                    } catch (_: Exception) {
                    }
                    // Our session ended: drop the pid file when it still points at us.
                    try {
                        if (pid != null && readPid(workDir) == pid) {
                            File(workDir, PID_NAME).delete()
                        }
                    } catch (_: Exception) {
                    }
                }
            }, "jvmhost-log-$serverId").apply { isDaemon = true; start() }

            // Pid file so a later CLI/UI invocation can see and stop this server.
            writePid(workDir, pid)

            true
        } catch (e: Exception) {
            try {
                appendLog(File(workDir, LOG_NAME), "\n[LAUNCH ERROR: ${e.message}]\n")
            } catch (_: Exception) {
            }
            false
        }
    }

    fun sendInput(serverId: String, data: String): Boolean {
        val holder = synchronized(lock) { running[serverId] } ?: return false
        return try {
            val raw = if (data.endsWith("\n")) data else "$data\n"
            val out = holder.process.outputStream
            out.write(raw.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends raw bytes without appending a newline (Ctrl/C, escape sequences). */
    fun sendRaw(serverId: String, bytes: ByteArray): Boolean {
        val holder = synchronized(lock) { running[serverId] } ?: return false
        return try {
            val out = holder.process.outputStream
            out.write(bytes)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Stops the server. Works in-session (process handle) and across
     * processes (pid file left by whoever started it). Returns true when a
     * live server was actually terminated.
     */
    fun stop(serverId: String, workDir: File? = null): Boolean {
        val holder = synchronized(lock) { running.remove(serverId) }
        if (holder != null) {
            return try {
                try {
                    holder.process.outputStream.write("exit\n".toByteArray())
                    holder.process.outputStream.flush()
                } catch (_: Exception) {
                }
                try {
                    holder.process.destroy()
                } catch (_: Exception) {
                }
                Thread {
                    try {
                        Thread.sleep(1500)
                    } catch (_: Exception) {
                    }
                    try {
                        if (holder.process.isAlive) holder.process.destroyForcibly()
                    } catch (_: Exception) {
                    }
                    try {
                        holder.process.outputStream.close()
                    } catch (_: Exception) {
                    }
                    try {
                        holder.process.inputStream.close()
                    } catch (_: Exception) {
                    }
                }.apply { isDaemon = true; start() }
                appendLog(holder.logFile, "\n[stopped by user]\n")
                try {
                    File(holder.logFile.parentFile, PID_NAME).delete()
                } catch (_: Exception) {
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        // Cross-process path: kill by pid file (no stdin pipe in this JVM).
        if (workDir != null) {
            val pid = try {
                readPid(workDir)
            } catch (_: Exception) {
                null
            }
            if (pid != null) {
                if (!isPidAlive(pid)) {
                    try {
                        File(workDir, PID_NAME).delete()
                    } catch (_: Exception) {
                    }
                    return false
                }
                return try {
                    val handle = ProcessHandle.of(pid).orElse(null)
                    if (handle == null || !handle.isAlive) {
                        try {
                            File(workDir, PID_NAME).delete()
                        } catch (_: Exception) {
                        }
                        return false
                    }
                    handle.destroy()
                    val gone = try {
                        handle.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (!gone) {
                        try {
                            handle.destroyForcibly()
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        File(workDir, PID_NAME).delete()
                    } catch (_: Exception) {
                    }
                    appendLogToDir(workDir, "\n[stopped by user (pid $pid)]\n")
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
        return false
    }

    fun stopAll() {
        val ids = synchronized(lock) { running.keys.toList() }
        ids.forEach { stop(it) }
    }

    // ---------- pid file (cross-process stop/status) ----------

    fun pidFile(workDir: File): File = File(workDir, PID_NAME)

    fun readPid(workDir: File): Long? {
        return try {
            val text = File(workDir, PID_NAME).takeIf { it.isFile }?.readText()?.trim()
            text?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun isPidAlive(pid: Long): Boolean {
        return try {
            val handle = ProcessHandle.of(pid).orElse(null) ?: return false
            handle.isAlive
        } catch (_: Exception) {
            false
        }
    }

    private fun writePid(workDir: File, pid: Long?) {
        try {
            val f = File(workDir, PID_NAME)
            if (pid == null) {
                f.delete()
            } else {
                f.writeText(pid.toString())
            }
        } catch (_: Exception) {
        }
    }

    // ---------- log tailing ----------

    fun logFile(workDir: File): File = File(workDir, LOG_NAME)

    fun appendLogToDir(workDir: File, text: String) {
        try {
            workDir.mkdirs()
            appendLog(File(workDir, LOG_NAME), text)
        } catch (_: Exception) {
        }
    }

    /**
     * Honest shell liveness: true only when [marker] (a unique echo token
     * sent via [sendInput] AFTER launch) comes back through the live
     * stdin -> shell -> server.log round-trip while the process stays
     * alive. A unique token cannot match stale output from a previous
     * boot; `start()` succeeding alone proves nothing (a dying proot or
     * a missing WSL distro still launches the wrapper process).
     *
     * Returns false when the process dies, the session vanishes, or the
     * token never arrives within [timeoutMs]. Never throws.
     */
    fun waitForMarker(
        serverId: String,
        workDir: File,
        marker: String,
        timeoutMs: Long = 20_000L,
        log: (String) -> Unit = {}
    ): Boolean {
        val logFile = logFile(workDir)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val holder = synchronized(lock) { running[serverId] }
            if (holder == null) {
                log("liveness marker: session gone (rejected or stopped)")
                return false
            }
            val alive = try {
                holder.process.isAlive
            } catch (_: Exception) {
                false
            }
            // The token may arrive in the same flush as the exit: check the
            // log first so a live answer is never missed, then the handle.
            val found = tailContains(logFile, marker)
            if (found && alive) return true
            if (found) {
                log("liveness marker: '$marker' arrived but the process is already dead")
                return false
            }
            if (!alive) {
                val code = try {
                    holder.process.exitValue()
                } catch (_: Exception) {
                    null
                }
                log("liveness marker: process died before answering (exit=${code ?: "?"})")
                return false
            }
            try {
                Thread.sleep(250)
            } catch (_: Exception) {
                break
            }
        }
        log("liveness marker: '$marker' not seen in ${timeoutMs}ms - shell is not answering")
        return false
    }

    /** True when the tail (last 256 KB) of [logFile] contains [marker]. */
    private fun tailContains(logFile: File, marker: String): Boolean {
        return try {
            if (!logFile.isFile) return false
            val len = logFile.length()
            if (len <= 0) return false
            val maxBytes = 256L * 1024
            val start = maxOf(0L, len - maxBytes)
            val n = (len - start).toInt()
            val buf = ByteArray(n)
            java.io.RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(start)
                var tot = 0
                while (tot < n) {
                    val r = raf.read(buf, tot, n - tot)
                    if (r <= 0) break
                    tot += r
                }
                String(buf, 0, tot, Charsets.UTF_8).contains(marker)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Last [lines] lines of `server.log` (empty string when no log yet).
     * Reads at most the final 512 KB so huge logs stay cheap.
     */
    fun tailLog(workDir: File, lines: Int = 200): String {
        return try {
            val f = File(workDir, LOG_NAME)
            if (!f.isFile) return ""
            val maxBytes = 512L * 1024
            val text = if (f.length() > maxBytes) {
                // Read the tail window; drop a possible partial first line.
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(f.length() - maxBytes)
                    val buf = ByteArray(maxBytes.toInt())
                    val n = raf.read(buf)
                    String(buf, 0, if (n < 0) 0 else n, Charsets.UTF_8).substringAfter('\n')
                }
            } else {
                f.readText(Charsets.UTF_8)
            }
            val all = text.lines()
            if (all.size <= lines) text.trimEnd('\n') else all.takeLast(lines).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    private fun appendLog(logFile: File, text: String) {
        try {
            if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
                try {
                    val bytes = logFile.readBytes()
                    logFile.writeBytes(bytes.takeLast(1024 * 1024).toByteArray())
                    logFile.appendText("\n[log trimmed to 1MB]\n")
                } catch (_: Exception) {
                }
            }
            logFile.appendText(text)
        } catch (_: Exception) {
        }
    }

    // ---------- backend argv builders ----------

    /**
     * Desktop-Linux proot launch. Unlike Android there is no linker/W^X ban,
     * so proot executes directly. The server workDir is bind-mounted at
     * /root/server and used as the guest cwd.
     */
    fun prootArgv(prootBin: File, rootfs: File, workDir: File): List<String> {
        workDir.mkdirs()
        return listOf(
            prootBin.absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev",
            "-b", "${workDir.absolutePath}:/root/server",
            "-w", "/root/server",
            "/usr/bin/env", "-u", "LD_LIBRARY_PATH", "/bin/bash", "--login"
        )
    }

    /** Environment for the outer proot process (never leaks into the guest). */
    fun prootEnv(prootTmp: File): Map<String, String> {
        prootTmp.mkdirs()
        return mapOf(
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_TMPDIR" to prootTmp.absolutePath,
            "TERM" to "xterm-256color",
            "HOME" to "/root",
            "TMPDIR" to "/tmp",
            // Guest PATH: must override host PATH, otherwise bare
            // `uname`/`ls`/`cat` die with "command not found" inside proot.
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        )
    }

    /**
     * Windows WSL launch. wsl.exe inherits the ProcessBuilder cwd and maps it
     * via drvfs, so the guest starts in the server folder - no manual
     * `E:\...` to `/mnt/e/...` translation needed.
     */
    fun wslArgv(distro: String = UbuntuSpec.WSL_DISTRO): List<String> =
        listOf("wsl", "-d", distro, "bash", "--login")

    /**
     * Last-resort interactive host shell, per OS.
     * Both read stdin line-by-line, so [sendInput] works like a console.
     */
    fun hostShellArgv(host: Host = HostInfo.current()): List<String> = when (host.os) {
        // NOTE: no `-Command -` here: with it PowerShell buffers all of stdin
        // as one script instead of running each line interactively.
        OsKind.WINDOWS -> listOf("powershell.exe", "-NoLogo", "-NoExit")
        else -> {
            val bash = HostInfo.findOnPath("bash")
            if (bash != null) listOf(bash.absolutePath, "--login") else listOf("sh", "-i")
        }
    }

    /**
     * One-shot variant of [prootArgv]: runs a single command in the guest
     * instead of an interactive login shell.
     */
    fun prootOneShotArgv(prootBin: File, rootfs: File, workDir: File, commandLine: String): List<String> {
        workDir.mkdirs()
        return listOf(
            prootBin.absolutePath,
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
    }

    /** One-shot variant of [wslArgv]: runs a single command in the WSL distro. */
    fun wslOneShotArgv(commandLine: String, distro: String = UbuntuSpec.WSL_DISTRO): List<String> =
        listOf("wsl", "-d", distro, "bash", "--login", "-c", commandLine)

    /** One-shot variant of [hostShellArgv]: runs a single host command. */
    fun hostShellOneShotArgv(commandLine: String, host: Host = HostInfo.current()): List<String> =
        when (host.os) {
            OsKind.WINDOWS -> listOf("powershell.exe", "-NoLogo", "-Command", commandLine)
            else -> {
                val bash = HostInfo.findOnPath("bash")
                if (bash != null) {
                    listOf(bash.absolutePath, "--login", "-c", commandLine)
                } else {
                    listOf("sh", "-c", commandLine)
                }
            }
        }

    companion object {
        const val MAX_SERVERS = 2
        const val LOG_NAME = "server.log"
        const val PID_NAME = "server.pid"
    }
}
