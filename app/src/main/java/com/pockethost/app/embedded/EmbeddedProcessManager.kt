package com.pockethost.app.embedded

import android.content.Context
import android.util.Log
import com.pockethost.app.jvm.InProcessServer
import java.io.File

/**
 * Built-in terminal: process launch WITHOUT external Termux
 * and WITHOUT com.termux.permission.RUN_COMMAND.
 *
 * Uses plain ProcessBuilder inside the app UID:
 *   - work dir: filesDir/servers/<id>
 *   - stdin  -> sendInput() (server console / shell)
 *   - stdout+stderr -> server.log (tailed by ConsoleActivity)
 *
 * Thread-safe singleton. Max 2 concurrent servers (as before).
 */
object EmbeddedProcessManager {

    private const val TAG = "EmbeddedTerminal"
    // MAX 2 for shell processes, but in-process JVM is only ONE per process
    // (-Djava.class.path is fixed at JVM creation, another classpath needs
    // an apk restart). So Minecraft gets an extra canStartMinecraftCheck()
    // below, not just this limit.
    const val MAX_SERVERS = 2

    data class EmbeddedSession(
        val serverId: String,
        val pid: Long?,
        val startedAt: Long = System.currentTimeMillis()
    )

    private data class RunningProcess(
        val process: Process,
        val session: EmbeddedSession,
        val logFile: File
    )

    private val lock = Any()
    private val running = mutableMapOf<String, RunningProcess>()

    fun isRunning(serverId: String): Boolean = synchronized(lock) {
        // In-process JVM (Mojo approach) lives separately from the ProcessBuilder map
        if (InProcessServer.isRunning(serverId)) return true
        val r = running[serverId] ?: return false
        try { r.process.isAlive } catch (_: Exception) { false }
    }

    fun runningCount(): Int = synchronized(lock) {
        // sweep the dead
        val dead = running.filterValues { try { !it.process.isAlive } catch (_: Exception) { true } }.keys.toList()
        dead.forEach { running.remove(it) }
        running.size + (if (InProcessServer.isRunning()) 1 else 0)
    }

    fun getSession(serverId: String): EmbeddedSession? = synchronized(lock) {
        if (InProcessServer.isRunning(serverId)) {
            return EmbeddedSession(serverId, null)
        }
        running[serverId]?.session
    }

    /**
     * C4: can a Minecraft server start in-process?
     * JVM is one per process: if ANOTHER server already runs, a second one with a different classpath
     * fails with FAILED_DEFINITIVE. Refuse honestly upfront with a user-friendly reason.
     * @return Pair(canStart, reasonIfNot) - reason already in English for log/Toast.
     */
    fun canStartMinecraft(serverId: String): Pair<Boolean, String> {
        try {
            val liveId = InProcessServer.serverId
            if (InProcessServer.isRunning() && liveId != null && liveId != serverId) {
                return false to "Stop the other server first (different classpath, apk restart needed)"
            }
        } catch (_: Exception) {}
        val count = try { runningCount() } catch (_: Exception) { 0 }
        if (count >= MAX_SERVERS) {
            return false to "Stop the first server (max $MAX_SERVERS)"
        }
        return true to ""
    }

    /**
     * Launches a command. command is a ready argv (no shell wrapper).
     * Returns true when the process started.
     */
    fun start(
        context: Context,
        serverId: String,
        command: List<String>,
        workDir: File,
        envExtra: Map<String, String> = emptyMap(),
        shellFallback: String? = null
    ): Boolean {
        synchronized(lock) {
            if (running.containsKey(serverId)) {
                Log.w(TAG, "already running $serverId")
                return false
            }
            if (running.size >= MAX_SERVERS) {
                Log.w(TAG, "max servers reached")
                return false
            }
        }
        return try {
            workDir.mkdirs()
            val logFile = File(workDir, "server.log")
            if (!logFile.exists()) {
                try { logFile.createNewFile() } catch (_: Exception) {}
            }

            val pb = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)

            // Base env of the built-in terminal ($PREFIX analog)
            val env = pb.environment()
            try {
                env["TERM"] = "xterm-256color"
                env["HOME"] = workDir.absolutePath
                env["TMPDIR"] = context.cacheDir.absolutePath
                // tools (jre/proot/busybox) first in PATH
                val toolsBin = File(EmbeddedPaths.toolsDir(context), "bin").absolutePath
                val oldPath = env["PATH"] ?: "/system/bin:/system/xbin:/vendor/bin"
                env["PATH"] = "$toolsBin:$oldPath"
                env["ANDROID_DATA"] = "/data"
                env["ANDROID_ROOT"] = "/system"
                for ((k, v) in envExtra) env[k] = v
                // P0 FORK-fix: when the command goes through the system linker
                // ([linker, <abs java>, ...]), LD_LIBRARY_PATH+JAVA_HOME are mandatory,
                // otherwise `library "libjli.so" not found`. When the caller did not pass them -
                // append from linkerEnv by java path (command[1]).
                if (command.isNotEmpty() && command[0].contains("linker") &&
                    (command.getOrNull(1)?.contains("java") == true)
                ) {
                    val javaPath = command.getOrNull(1) ?: ""
                    if (javaPath.isEmpty() || !File(javaPath).isAbsolute) {
                        val msg = "Refusing: argv[1] must be the absolute java path (got '$javaPath'), " +
                            "otherwise the linker fails `expected absolute path: \"-Xmx64M\"`"
                        Log.e(TAG, msg)
                        appendLog(logFile, "\n[LAUNCH ERROR: $msg]\n")
                        return false
                    }
                    if (!EmbeddedJava.hasLibJli(javaPath)) {
                        val jli = try { EmbeddedJava.findLibJli(javaPath)?.absolutePath ?: "not found" } catch (_: Exception) { "?" }
                        Log.e(TAG, "libjli.so missing for $javaPath (found: $jli)")
                        appendLog(logFile, "\n[WARNING: libjli.so not found ($jli) - possible CANNOT LINK EXECUTABLE]\n")
                    }
                    if (!env.containsKey("LD_LIBRARY_PATH") || !env.containsKey("JAVA_HOME")) {
                        try {
                            val fill = EmbeddedJava.linkerEnv(javaPath)
                            for ((k, v) in fill) if (!env.containsKey(k)) env[k] = v
                            Log.i(TAG, "linker env injected LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]} JAVA_HOME=${env["JAVA_HOME"]}")
                        } catch (_: Exception) {}
                    }
                    // -Xmx must never sit where the executable goes (command[1]).
                    val exe = command.getOrNull(1) ?: ""
                    if (exe.startsWith("-")) {
                        val msg = "Refusing: linker executable cannot be a flag ($exe)"
                        Log.e(TAG, msg)
                        appendLog(logFile, "\n[LAUNCH ERROR: $msg]\n")
                        return false
                    }
                }
            } catch (_: Exception) {}

            appendLog(logFile, "\n\$ ${command.joinToString(" ")}\n")

            val process: Process = try {
                pb.start()
            } catch (e: Exception) {
                Log.e(TAG, "ProcessBuilder failed for $command", e)
                // Fallback via sh -c (for complex commands)
                if (shellFallback != null) {
                    appendLog(logFile, "Direct launch failed (${e.message}), trying via sh...\n")
                    ProcessBuilder("sh", "-c", shellFallback)
                        .directory(workDir)
                        .redirectErrorStream(true)
                        .start()
                } else throw e
            }

            // PID without hidden-API reflection and without java.lang.Process.pid()
            // (missing from the Android bootclasspath used here, and reflective
            // getMethod("pid") is blocked on Android 16). Parse ART's
            // Process.toString() ("Process[pid=1234, ...]") - best effort only,
            // the pid is display-only (EmbeddedSession.pid), null is fine.
            val pid: Long? = try {
                Regex("pid=(\\d+)").find(process.toString())
                    ?.groupValues?.getOrNull(1)?.toLongOrNull()
            } catch (_: Exception) {
                null
            }
            val session = EmbeddedSession(serverId, pid)
            val holder = RunningProcess(process, session, logFile)
            // Atomic insert: re-check under lock to close the race
            // of two concurrent start() calls (MAX overflow or key overwrite).
            val inserted: Boolean = synchronized(lock) {
                // sweep the dead so the limit is exact
                val dead = running.filterValues { try { !it.process.isAlive } catch (_: Exception) { true } }.keys.toList()
                dead.forEach { running.remove(it) }
                if (running.containsKey(serverId) || running.size >= MAX_SERVERS) {
                    false
                } else {
                    running[serverId] = holder
                    true
                }
            }
            if (!inserted) {
                // Mute the extra process immediately to avoid leaks
                try { process.destroy() } catch (_: Exception) {}
                try {
                    Thread {
                        try { Thread.sleep(1000) } catch (_: Exception) {}
                        try { if (process.isAlive) process.destroyForcibly() } catch (_: Exception) {}
                    }.apply { isDaemon = true; start() }
                } catch (_: Exception) {}
                appendLog(logFile, "\n[start rejected: server already launching or limit $MAX_SERVERS]\n")
                Log.w(TAG, "start race lost for $serverId")
                return false
            }

            // Pump stdout -> server.log in background
            Thread({
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(8192)
                        while (true) {
                            val n = try { reader.read(buf) } catch (_: Exception) { -1 }
                            if (n <= 0) break
                            val chunk = String(buf, 0, n)
                            appendLog(logFile, chunk)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        val code = process.waitFor()
                        appendLog(logFile, "\n[process exited, code $code]\n")
                    } catch (_: Exception) {}
                    // do not remove from running yet so UI sees STOPPED via liveness;
                    // ServerService calls stop() / sweeps the record itself
                }
            }, "embedded-log-$serverId").apply { isDaemon = true; start() }

            Log.d(TAG, "started $serverId pid=$pid cmd=${command.take(5)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed $serverId", e)
            try {
                val lf = File(workDir, "server.log")
                appendLog(lf, "\n[LAUNCH ERROR: ${e.message}]\n")
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * Interactive shell for Ubuntu servers / console:
     * launches `sh -i` with a stdin pipe. Input via sendInput().
     */
    fun startInteractiveShell(context: Context, serverId: String, workDir: File): Boolean {
        return start(context, serverId, listOf("sh", "-i"), workDir)
    }

    /**
     * Full Ubuntu 22.04 via Termux proot (linker exec, no root).
     * The server workDir is bind-mounted at /root/server (see
     * EmbeddedUbuntu.buildLaunch). Returns false when proot/rootfs is
     * missing so the caller can fall back to the built-in shell.
     */
    fun startUbuntuViaProot(context: Context, serverId: String, workDir: File): Boolean {
        val launch = EmbeddedUbuntu.buildLaunch(context, workDir) { msg ->
            appendLogToDir(workDir, msg + "\n")
        } ?: return false
        // Proof that PROOT_TMPDIR/PROOT_LOADER really reach the proot
        // process: EmbeddedProcessManager.start() applies launch.env 1:1 to
        // the ProcessBuilder environment (see the loop over envExtra there).
        // Logged here so server.log shows the effective values next to the
        // pre-flight, instead of trusting the code path blindly.
        appendLogToDir(
            workDir,
            "proot env applied: " +
                launch.env.entries.joinToString(" ") { "${it.key}=${it.value}" } + "\n"
        )
        return start(context, serverId, launch.argv, workDir, launch.env)
    }

    /**
     * Honest liveness: true only when the process handle is alive AND the
     * log file grew past [logStartLen] (first shell/proot output arrived).
     * Proot dies fast on EACCES/ENOENT while ProcessBuilder.start() still
     * succeeded - RUNNING on start() alone lies. Polls up to [timeoutMs].
     * Returns false when the process died or stayed silent.
     */
    fun waitForLiveOutput(
        serverId: String,
        workDir: File,
        logStartLen: Long,
        timeoutMs: Long = 15_000L,
        log: (String) -> Unit = {}
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastAlive = false
        while (System.currentTimeMillis() < deadline) {
            val holder = synchronized(lock) { running[serverId] }
            if (holder == null) {
                log("liveness: session gone (rejected or stopped)")
                return false
            }
            val alive = try { holder.process.isAlive } catch (_: Exception) { false }
            if (!alive) {
                // Capture the exit code fast so EACCES vs ENOENT is visible.
                val code = try { holder.process.exitValue() } catch (_: Exception) { null }
                log("liveness: process died fast (exit=${code ?: "?"}) - see proot pre-flight above")
                return false
            }
            lastAlive = alive
            val len = try {
                val f = File(workDir, "server.log")
                if (f.isFile) f.length() else logStartLen
            } catch (_: Exception) { logStartLen }
            if (len > logStartLen) return true
            try { Thread.sleep(250) } catch (_: Exception) { break }
        }
        // Timeout but still alive (slow device): treat as live so Start does
        // not flip-flop; the service liveness poll keeps watching.
        if (lastAlive) {
            log("liveness: still silent after ${timeoutMs}ms but process alive - continuing")
            return true
        }
        return false
    }

    /** Current log length for [waitForLiveOutput] baselines. */
    fun logLength(workDir: File): Long {
        return try {
            val f = File(workDir, "server.log")
            if (f.isFile) f.length() else 0L
        } catch (_: Exception) { 0L }
    }

    /**
     * Honest shell liveness: true only when [marker] (a unique echo token
     * sent via [sendInput] AFTER launch) comes back through the live
     * stdin->shell->server.log round-trip while the process stays alive.
     *
     * This is what gates RUNNING. [waitForLiveOutput] alone is not enough:
     * our own pre-flight lines also grow server.log, and a dying proot
     * stays "alive" for ~1s while printing its fatal error - both used to
     * produce a false Running that died right after. A unique token cannot
     * match stale output from a previous boot.
     *
     * Returns false when the process dies, the session vanishes, or the
     * token never arrives within [timeoutMs].
     */
    fun waitForMarker(
        serverId: String,
        workDir: File,
        marker: String,
        timeoutMs: Long = 15_000L,
        log: (String) -> Unit = {}
    ): Boolean {
        val logFile = File(workDir, "server.log")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val holder = synchronized(lock) { running[serverId] }
            if (holder == null) {
                log("liveness marker: session gone (rejected or stopped)")
                return false
            }
            val alive = try { holder.process.isAlive } catch (_: Exception) { false }
            // The token may arrive in the same flush as the exit: check the
            // log first so a live answer is never missed, then the handle.
            val found = tailContains(logFile, marker)
            if (found && alive) return true
            if (found) {
                log("liveness marker: '$marker' arrived but the process is already dead")
                return false
            }
            if (!alive) {
                val code = try { holder.process.exitValue() } catch (_: Exception) { null }
                log("liveness marker: process died before answering (exit=${code ?: "?"})")
                return false
            }
            try { Thread.sleep(250) } catch (_: Exception) { break }
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
            val maxBytes = 256 * 1024L
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
        } catch (_: Exception) { false }
    }

    fun sendInput(serverId: String, data: String): Boolean {
        // In-process server reads System.in of the JVM, not the process pipe
        if (InProcessServer.isRunning(serverId)) {
            return InProcessServer.sendInput(serverId, data)
        }
        val holder = synchronized(lock) { running[serverId] } ?: return false
        return try {
            val raw = if (data.endsWith("\n")) data else "$data\n"
            val out = holder.process.outputStream
            out.write(raw.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendInput failed $serverId", e)
            false
        }
    }

    /** Send raw bytes without appending \n (for Ctrl/C, Esc sequences) */
    fun sendRaw(serverId: String, bytes: ByteArray): Boolean {
        if (InProcessServer.isRunning(serverId)) {
            return InProcessServer.sendRaw(serverId, bytes)
        }
        val holder = synchronized(lock) { running[serverId] } ?: return false
        return try {
            val out = holder.process.outputStream
            out.write(bytes)
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendRaw failed", e)
            false
        }
    }

    fun stop(serverId: String): Boolean {
        // In-process server stops via "stop" into System.in (System.exit trap),
        // killing the JVM thread forcibly is unsafe - same as Mojo.
        if (InProcessServer.isRunning(serverId)) {
            return InProcessServer.stop(serverId)
        }
        val holder = synchronized(lock) { running.remove(serverId) } ?: return false
        return try {
            // polite shutdown: try via stdin first
            try {
                holder.process.outputStream.write("exit\n".toByteArray())
                holder.process.outputStream.flush()
            } catch (_: Exception) {}
            try { holder.process.destroy() } catch (_: Exception) {}
            Thread {
                try { Thread.sleep(1500) } catch (_: Exception) {}
                try {
                    if (holder.process.isAlive) holder.process.destroyForcibly()
                } catch (_: Exception) {}
                try { holder.process.outputStream.close() } catch (_: Exception) {}
                try { holder.process.inputStream.close() } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            appendLog(holder.logFile, "\n[stopped by user]\n")
            true
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
            false
        }
    }

    fun stopAll() {
        val ids = synchronized(lock) { running.keys.toList() }
        ids.forEach { stop(it) }
    }

    // ---------- helpers ----------

    @Synchronized
    private fun appendLog(logFile: File, text: String) {
        try {
            // rotation 5MB -> keep last 1MB (as in TermuxBridge)
            if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
                try {
                    val bytes = logFile.readBytes()
                    val keep = bytes.takeLast(1024 * 1024).toByteArray()
                    logFile.writeBytes(keep)
                    logFile.appendText("\n[log trimmed to 1MB]\n")
                } catch (_: Exception) {}
            }
            logFile.appendText(text)
        } catch (_: Exception) {}
    }

    /**
     * Append to server.log of a specific folder. The caller passes the folder
     * (in the service it is the cached workDir) so custom-folder logs are not lost.
     */
    fun appendLogToDir(workDir: File, text: String) {
        try {
            workDir.mkdirs()
            appendLog(File(workDir, "server.log"), text)
        } catch (_: Exception) {}
    }

    /**
     * Builds the Minecraft launch command: [java, -Xmx..., -jar, jar, nogui].
     * @param linkerPrefix non-empty on devices with the W^X exec ban
     * (targetSdk 29+): ["/system/bin/linker64"] goes before java.
     *
     * P0 FORK-fix contract:
     *  - argv[0] after the prefix is ALWAYS the absolute java path (never "-Xmx...").
     *    Relative "java" from PATH yields `F/linker: expected absolute path: "-Xmx64M"`.
     *  - LD_LIBRARY_PATH (jre/lib/server:jre/lib:jre/lib/jli) + JAVA_HOME must arrive
     *    via envExtra (see EmbeddedJava.linkerEnv) or they are injected in start(),
     *    otherwise `CANNOT LINK EXECUTABLE ... library "libjli.so" not found`.
     *  - libjli.so must sit next to java - verified in start()/probeJava.
     */
    fun buildMinecraftCommand(
        javaPath: String,
        jarName: String,
        maxMemoryMB: Int,
        minMemoryMB: Int,
        extraArgs: List<String> = emptyList(),
        linkerPrefix: List<String> = emptyList()
    ): List<String> {
        if (!File(javaPath).isAbsolute) {
            Log.e(TAG, "buildMinecraftCommand: javaPath not absolute ($javaPath) - linker will fail expected absolute path")
        }
        if (linkerPrefix.any { it.startsWith("-X") }) {
            Log.e(TAG, "buildMinecraftCommand: linkerPrefix must not contain -X flags: $linkerPrefix")
        }
        // linkerPrefix[0] is the absolute linker path, [1] the absolute java. -Xmx only after java.
        val cmd = mutableListOf<String>()
        cmd.addAll(linkerPrefix)
        cmd.addAll(
            listOf(
                javaPath,
                "-Xmx${maxMemoryMB}M",
                "-Xms${minMemoryMB}M",
                "-XX:+UseSerialGC",
                "-Djava.awt.headless=true",
                "-Xss256k"
            )
        )
        // Android 14+ MTE fix (as before)
        cmd.add("-XX:-UseCompressedOops")
        cmd.addAll(extraArgs)
        // Headless console like in-process (PocketJvm.prepare appArgs):
        // Paperclip forwards unknown args, JLine without it reads /dev/tty
        // and graceful "stop" via stdin hangs.
        cmd.addAll(listOf("-jar", jarName, "nogui", "--nojline"))
        return cmd
    }

    fun guessJarName(loaderName: String?): String = when (loaderName) {
        "VANILLA" -> "server.jar"
        "FORGE" -> "forge.jar"
        "NEOFORGE" -> "neoforge.jar"
        "FABRIC" -> "fabric-server-launch.jar"
        "PAPER" -> "paper.jar"
        "SPIGOT" -> "spigot.jar"
        "BUKKIT" -> "craftbukkit.jar"
        "PURPUR" -> "purpur.jar"
        else -> "paper.jar"
    }

    fun findExistingJar(workDir: File, preferred: String): String? {
        val candidates = listOf(preferred, "server.jar", "paper.jar", "purpur.jar", "fabric-server-launch.jar", "neoforge.jar", "forge.jar", "spigot.jar", "craftbukkit.jar")
        for (c in candidates) {
            if (File(workDir, c).exists()) return c
        }
        // any *.jar
        return try {
            workDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                ?.maxByOrNull { it.length() }?.name
        } catch (_: Exception) { null }
    }
}
