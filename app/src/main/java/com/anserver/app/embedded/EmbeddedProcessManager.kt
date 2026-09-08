package com.anserver.app.embedded

import android.content.Context
import android.util.Log
import com.anserver.app.jvm.InProcessServer
import java.io.File

/**
 * Вбудований термінал: запуск процесів БЕЗ зовнішнього Termux
 * і БЕЗ дозволу com.termux.permission.RUN_COMMAND.
 *
 * Використовує звичайний ProcessBuilder всередині UID додатку:
 *   - робоча папка: filesDir/servers/<id>
 *   - stdin  -> sendInput() (консоль сервера / shell)
 *   - stdout+stderr -> server.log (tail читає ConsoleActivity)
 *
 * Потокобезпечний singleton. Максимум 2 одночасних сервери (як було).
 */
object EmbeddedProcessManager {

    private const val TAG = "EmbeddedTerminal"
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
        // In-process JVM (Mojo-підхід) живе окремо від ProcessBuilder-мапи
        if (InProcessServer.isRunning(serverId)) return true
        val r = running[serverId] ?: return false
        try { r.process.isAlive } catch (_: Exception) { false }
    }

    fun runningCount(): Int = synchronized(lock) {
        // чистимо мертві
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
     * Запуск команди. command - готовий argv (без shell-обгортки).
     * Повертає true якщо процес стартував.
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

            // Базове оточення вбудованого терміналу (аналог $PREFIX)
            val env = pb.environment()
            try {
                env["TERM"] = "xterm-256color"
                env["HOME"] = workDir.absolutePath
                env["TMPDIR"] = context.cacheDir.absolutePath
                // tools (jre/proot/busybox) першими в PATH
                val toolsBin = File(EmbeddedPaths.toolsDir(context), "bin").absolutePath
                val oldPath = env["PATH"] ?: "/system/bin:/system/xbin:/vendor/bin"
                env["PATH"] = "$toolsBin:$oldPath"
                env["ANDROID_DATA"] = "/data"
                env["ANDROID_ROOT"] = "/system"
                for ((k, v) in envExtra) env[k] = v
            } catch (_: Exception) {}

            appendLog(logFile, "\n\$ ${command.joinToString(" ")}\n")

            val process: Process = try {
                pb.start()
            } catch (e: Exception) {
                Log.e(TAG, "ProcessBuilder failed for $command", e)
                // Fallback через sh -c (на випадок складних команд)
                if (shellFallback != null) {
                    appendLog(logFile, "Прямий запуск не вдався (${e.message}), пробую через sh...\n")
                    ProcessBuilder("sh", "-c", shellFallback)
                        .directory(workDir)
                        .redirectErrorStream(true)
                        .start()
                } else throw e
            }

            // process.pid() доступний лише з API 26+ як hidden на частини пристроїв,
            // тому через рефлексію щоб не ламати compileDebugKotlin на старих bootclasspath.
            val pid: Long? = try {
                process.javaClass.getMethod("pid").invoke(process) as? Long
            } catch (_: Exception) { null }
            val session = EmbeddedSession(serverId, pid)
            val holder = RunningProcess(process, session, logFile)
            // Атомарна вставка: повторна перевірка під локом щоб закрити race
            // двох одночасних start() (перевищення MAX або перезапис ключа).
            val inserted: Boolean = synchronized(lock) {
                // прибираємо мертві щоб ліміт був точним
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
                // Зайвий процес глушимо одразу щоб не витікав
                try { process.destroy() } catch (_: Exception) {}
                try {
                    Thread {
                        try { Thread.sleep(1000) } catch (_: Exception) {}
                        try { if (process.isAlive) process.destroyForcibly() } catch (_: Exception) {}
                    }.apply { isDaemon = true; start() }
                } catch (_: Exception) {}
                appendLog(logFile, "\n[скатарт відхилено: сервер вже запускається або ліміт $MAX_SERVERS]\n")
                Log.w(TAG, "start race lost for $serverId")
                return false
            }

            // Памп stdout -> server.log в фоні
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
                        appendLog(logFile, "\n[процес завершено, код $code]\n")
                    } catch (_: Exception) {}
                    // не видаляємо одразу з running щоб UI встиг побачити STOPPED через liveness;
                    // ServerService сам викличе stop() / прибере запис
                }
            }, "embedded-log-$serverId").apply { isDaemon = true; start() }

            Log.d(TAG, "started $serverId pid=$pid cmd=${command.take(5)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed $serverId", e)
            try {
                val lf = File(workDir, "server.log")
                appendLog(lf, "\n[ПОМИЛКА ЗАПУСКУ: ${e.message}]\n")
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * Інтерактивний shell для Ubuntu-сервера / консолі:
     * запускає `sh -i` з stdin-трубою. Ввід подається через sendInput().
     */
    fun startInteractiveShell(context: Context, serverId: String, workDir: File): Boolean {
        return start(context, serverId, listOf("sh", "-i"), workDir)
    }

    fun sendInput(serverId: String, data: String): Boolean {
        // In-process сервер читає System.in JVM, а не трубу процесу
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

    /** Надіслати сирі байти без додавання \n (для Ctrl/C, Esc-послідовностей) */
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
        // In-process сервер зупиняється через "stop" в System.in (System.exit пастка),
        // вбити JVM-нитку примусово безпечно неможливо — як і в Mojo.
        if (InProcessServer.isRunning(serverId)) {
            return InProcessServer.stop(serverId)
        }
        val holder = synchronized(lock) { running.remove(serverId) } ?: return false
        return try {
            // ввічливе завершення: спроба через stdin
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
            appendLog(holder.logFile, "\n[зупинено користувачем]\n")
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
            // ротація 5MB -> лишаємо останній 1MB (як було в TermuxBridge)
            if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
                try {
                    val bytes = logFile.readBytes()
                    val keep = bytes.takeLast(1024 * 1024).toByteArray()
                    logFile.writeBytes(keep)
                    logFile.appendText("\n[лог обрізано до 1MB]\n")
                } catch (_: Exception) {}
            }
            logFile.appendText(text)
        } catch (_: Exception) {}
    }

    /**
     * Допис у server.log конкретної папки. Папку передає викликач
     * (у сервіса — закешована workDir), щоб логи кастомних папок не губились.
     */
    fun appendLogToDir(workDir: File, text: String) {
        try {
            workDir.mkdirs()
            appendLog(File(workDir, "server.log"), text)
        } catch (_: Exception) {}
    }

    /**
     * Будує команду запуску Minecraft: [java, -Xmx..., -jar, jar, nogui].
     * @param linkerPrefix непорожній на пристроях з W^X exec-забороною
     * (targetSdk 29+): ["/system/bin/linker64"] ставиться попереду java.
     */
    fun buildMinecraftCommand(
        javaPath: String,
        jarName: String,
        maxMemoryMB: Int,
        minMemoryMB: Int,
        extraArgs: List<String> = emptyList(),
        linkerPrefix: List<String> = emptyList()
    ): List<String> {
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
        // Android 14+ MTE фікс (як було)
        cmd.add("-XX:-UseCompressedOops")
        cmd.addAll(extraArgs)
        cmd.addAll(listOf("-jar", jarName, "nogui"))
        return cmd
    }

    fun guessJarName(loaderName: String?): String = when (loaderName) {
        "VANILLA" -> "server.jar"
        "FORGE", "NEOFORGE" -> "forge.jar"
        "FABRIC" -> "fabric-server-launch.jar"
        "PAPER" -> "paper.jar"
        "SPIGOT" -> "spigot.jar"
        "BUKKIT" -> "craftbukkit.jar"
        "PURPUR" -> "purpur.jar"
        else -> "paper.jar"
    }

    fun findExistingJar(workDir: File, preferred: String): String? {
        val candidates = listOf(preferred, "server.jar", "paper.jar", "purpur.jar", "fabric-server-launch.jar", "forge.jar", "spigot.jar", "craftbukkit.jar")
        for (c in candidates) {
            if (File(workDir, c).exists()) return c
        }
        // будь-який *.jar
        return try {
            workDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                ?.maxByOrNull { it.length() }?.name
        } catch (_: Exception) { null }
    }
}
