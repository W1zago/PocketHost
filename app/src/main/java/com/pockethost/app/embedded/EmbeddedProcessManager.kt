package com.pockethost.app.embedded

import android.content.Context
import android.util.Log
import com.pockethost.app.jvm.InProcessServer
import java.io.File

/**
 * Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»: Р·Р°РїСѓСЃРє РїСЂРѕС†РµСЃС–РІ Р‘Р•Р— Р·РѕРІРЅС–С€РЅСЊРѕРіРѕ Termux
 * С– Р‘Р•Р— РґРѕР·РІРѕР»Сѓ com.termux.permission.RUN_COMMAND.
 *
 * Р’РёРєРѕСЂРёСЃС‚РѕРІСѓС” Р·РІРёС‡Р°Р№РЅРёР№ ProcessBuilder РІСЃРµСЂРµРґРёРЅС– UID РґРѕРґР°С‚РєСѓ:
 *   - СЂРѕР±РѕС‡Р° РїР°РїРєР°: filesDir/servers/<id>
 *   - stdin  -> sendInput() (РєРѕРЅСЃРѕР»СЊ СЃРµСЂРІРµСЂР° / shell)
 *   - stdout+stderr -> server.log (tail С‡РёС‚Р°С” ConsoleActivity)
 *
 * РџРѕС‚РѕРєРѕР±РµР·РїРµС‡РЅРёР№ singleton. РњР°РєСЃРёРјСѓРј 2 РѕРґРЅРѕС‡Р°СЃРЅРёС… СЃРµСЂРІРµСЂРё (СЏРє Р±СѓР»Рѕ).
 */
object EmbeddedProcessManager {

    private const val TAG = "EmbeddedTerminal"
    // MAX 2 РґР»СЏ shell-РїСЂРѕС†РµСЃС–РІ, Р°Р»Рµ in-process JVM вЂ” Р»РёС€Рµ РћР”РќРђ РЅР° РїСЂРѕС†РµСЃ
    // (-Djava.class.path С„С–РєСЃСѓС”С‚СЊСЃСЏ РїСЂРё СЃС‚РІРѕСЂРµРЅРЅС– JVM, С–РЅС€РёР№ classpath = РїРѕС‚СЂС–Р±РµРЅ
    // СЂРµСЃС‚Р°СЂС‚ Р°РїРєРё). РўРѕРјСѓ РґР»СЏ Minecraft РґС–С” РґРѕРґР°С‚РєРѕРІРёР№ canStartMinecraftCheck()
    // РЅРёР¶С‡Рµ, Р° РЅРµ Р»РёС€Рµ С†РµР№ Р»С–РјС–С‚.
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
        // In-process JVM (Mojo-РїС–РґС…С–Рґ) Р¶РёРІРµ РѕРєСЂРµРјРѕ РІС–Рґ ProcessBuilder-РјР°РїРё
        if (InProcessServer.isRunning(serverId)) return true
        val r = running[serverId] ?: return false
        try { r.process.isAlive } catch (_: Exception) { false }
    }

    fun runningCount(): Int = synchronized(lock) {
        // С‡РёСЃС‚РёРјРѕ РјРµСЂС‚РІС–
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
     * C4: С‡Рё РјРѕР¶РЅР° СЃС‚Р°СЂС‚СѓРІР°С‚Рё Minecraft-СЃРµСЂРІРµСЂ in-process.
     * JVM РѕРґРЅР° РЅР° РїСЂРѕС†РµСЃ: СЏРєС‰Рѕ РІР¶Рµ Р±С–Р¶РёС‚СЊ Р†РќРЁРР™ СЃРµСЂРІРµСЂ вЂ” РґСЂСѓРіРёР№ Р· С–РЅС€РёРј classpath
     * РІРјСЂРµ Р· FAILED_DEFINITIVE. Р§РµСЃРЅРѕ РІС–РґРјРѕРІР»СЏС”РјРѕ РѕРґСЂР°Р·Сѓ Р· user-friendly РїСЂРёС‡РёРЅРѕСЋ.
     * @return Pair(canStart, reasonIfNot) вЂ” reason РІР¶Рµ СѓРєСЂР°С—РЅСЃСЊРєРѕСЋ РґР»СЏ Р»РѕРіСѓ/Toast.
     */
    fun canStartMinecraft(serverId: String): Pair<Boolean, String> {
        try {
            val liveId = InProcessServer.serverId
            if (InProcessServer.isRunning() && liveId != null && liveId != serverId) {
                return false to "Р—СѓРїРёРЅРё С–РЅС€РёР№ СЃРµСЂРІРµСЂ (С–РЅС€РёР№ classpath, РїРѕС‚СЂС–Р±РµРЅ СЂРµСЃС‚Р°СЂС‚ Р°РїРєРё)"
            }
        } catch (_: Exception) {}
        val count = try { runningCount() } catch (_: Exception) { 0 }
        if (count >= MAX_SERVERS) {
            return false to "Р—СѓРїРёРЅРё РїРµСЂС€РёР№ СЃРµСЂРІРµСЂ (РјР°РєСЃРёРјСѓРј $MAX_SERVERS)"
        }
        return true to ""
    }

    /**
     * Р—Р°РїСѓСЃРє РєРѕРјР°РЅРґРё. command - РіРѕС‚РѕРІРёР№ argv (Р±РµР· shell-РѕР±РіРѕСЂС‚РєРё).
     * РџРѕРІРµСЂС‚Р°С” true СЏРєС‰Рѕ РїСЂРѕС†РµСЃ СЃС‚Р°СЂС‚СѓРІР°РІ.
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

            // Р‘Р°Р·РѕРІРµ РѕС‚РѕС‡РµРЅРЅСЏ РІР±СѓРґРѕРІР°РЅРѕРіРѕ С‚РµСЂРјС–РЅР°Р»Сѓ (Р°РЅР°Р»РѕРі $PREFIX)
            val env = pb.environment()
            try {
                env["TERM"] = "xterm-256color"
                env["HOME"] = workDir.absolutePath
                env["TMPDIR"] = context.cacheDir.absolutePath
                // tools (jre/proot/busybox) РїРµСЂС€РёРјРё РІ PATH
                val toolsBin = File(EmbeddedPaths.toolsDir(context), "bin").absolutePath
                val oldPath = env["PATH"] ?: "/system/bin:/system/xbin:/vendor/bin"
                env["PATH"] = "$toolsBin:$oldPath"
                env["ANDROID_DATA"] = "/data"
                env["ANDROID_ROOT"] = "/system"
                for ((k, v) in envExtra) env[k] = v
                // P0 FORK-fix: СЏРєС‰Рѕ РєРѕРјР°РЅРґР° Р№РґРµ С‡РµСЂРµР· СЃРёСЃС‚РµРјРЅРёР№ Р»С–РЅРєРµСЂ
                // ([linker, <abs java>, ...]), LD_LIBRARY_PATH+JAVA_HOME РѕР±РѕРІ'СЏР·РєРѕРІС–,
                // С–РЅР°РєС€Рµ `library "libjli.so" not found`. РЇРєС‰Рѕ РІРёРєР»РёРєР°С‡ РЅРµ РїРµСЂРµРґР°РІ вЂ”
                // РґРѕР±СѓРґРѕРІСѓС”РјРѕ Р· linkerEnv Р·Р° java-С€Р»СЏС…РѕРј (command[1]).
                if (command.isNotEmpty() && command[0].contains("linker") &&
                    (command.getOrNull(1)?.contains("java") == true)
                ) {
                    val javaPath = command.getOrNull(1) ?: ""
                    if (javaPath.isEmpty() || !File(javaPath).isAbsolute) {
                        val msg = "Р’С–РґРјРѕРІР°: argv[1] РјР°С” Р±СѓС‚Рё Р°Р±СЃРѕР»СЋС‚РЅРёРј С€Р»СЏС…РѕРј РґРѕ java (РѕС‚СЂРёРјР°РЅРѕ '$javaPath'), " +
                            "С–РЅР°РєС€Рµ linker РїР°РґР°С” `expected absolute path: \"-Xmx64M\"`"
                        Log.e(TAG, msg)
                        appendLog(logFile, "\n[РџРћРњРР›РљРђ Р—РђРџРЈРЎРљРЈ: $msg]\n")
                        return false
                    }
                    if (!EmbeddedJava.hasLibJli(javaPath)) {
                        val jli = try { EmbeddedJava.findLibJli(javaPath)?.absolutePath ?: "РЅРµ Р·РЅР°Р№РґРµРЅРѕ" } catch (_: Exception) { "?" }
                        Log.e(TAG, "libjli.so missing for $javaPath (found: $jli)")
                        appendLog(logFile, "\n[РџРћРџР•Р Р•Р”Р–Р•РќРќРЇ: libjli.so РЅРµ Р·РЅР°Р№РґРµРЅРѕ ($jli) вЂ” РјРѕР¶Р»РёРІРёР№ CANNOT LINK EXECUTABLE]\n")
                    }
                    if (!env.containsKey("LD_LIBRARY_PATH") || !env.containsKey("JAVA_HOME")) {
                        try {
                            val fill = EmbeddedJava.linkerEnv(javaPath)
                            for ((k, v) in fill) if (!env.containsKey(k)) env[k] = v
                            Log.i(TAG, "linker env injected LD_LIBRARY_PATH=${env["LD_LIBRARY_PATH"]} JAVA_HOME=${env["JAVA_HOME"]}")
                        } catch (_: Exception) {}
                    }
                    // -Xmx РЅС–РєРѕР»Рё РЅРµ РјР°С” Р±СѓС‚Рё РЅР° РјС–СЃС†С– executable (command[1]).
                    val exe = command.getOrNull(1) ?: ""
                    if (exe.startsWith("-")) {
                        val msg = "Р’С–РґРјРѕРІР°: linker executable РЅРµ РјРѕР¶Рµ Р±СѓС‚Рё РїСЂР°РїРѕСЂС†РµРј ($exe)"
                        Log.e(TAG, msg)
                        appendLog(logFile, "\n[РџРћРњРР›РљРђ Р—РђРџРЈРЎРљРЈ: $msg]\n")
                        return false
                    }
                }
            } catch (_: Exception) {}

            appendLog(logFile, "\n\$ ${command.joinToString(" ")}\n")

            val process: Process = try {
                pb.start()
            } catch (e: Exception) {
                Log.e(TAG, "ProcessBuilder failed for $command", e)
                // Fallback С‡РµСЂРµР· sh -c (РЅР° РІРёРїР°РґРѕРє СЃРєР»Р°РґРЅРёС… РєРѕРјР°РЅРґ)
                if (shellFallback != null) {
                    appendLog(logFile, "РџСЂСЏРјРёР№ Р·Р°РїСѓСЃРє РЅРµ РІРґР°РІСЃСЏ (${e.message}), РїСЂРѕР±СѓСЋ С‡РµСЂРµР· sh...\n")
                    ProcessBuilder("sh", "-c", shellFallback)
                        .directory(workDir)
                        .redirectErrorStream(true)
                        .start()
                } else throw e
            }

            // process.pid() РґРѕСЃС‚СѓРїРЅРёР№ Р»РёС€Рµ Р· API 26+ СЏРє hidden РЅР° С‡Р°СЃС‚РёРЅРё РїСЂРёСЃС‚СЂРѕС—РІ,
            // С‚РѕРјСѓ С‡РµСЂРµР· СЂРµС„Р»РµРєСЃС–СЋ С‰РѕР± РЅРµ Р»Р°РјР°С‚Рё compileDebugKotlin РЅР° СЃС‚Р°СЂРёС… bootclasspath.
            val pid: Long? = try {
                process.javaClass.getMethod("pid").invoke(process) as? Long
            } catch (_: Exception) { null }
            val session = EmbeddedSession(serverId, pid)
            val holder = RunningProcess(process, session, logFile)
            // РђС‚РѕРјР°СЂРЅР° РІСЃС‚Р°РІРєР°: РїРѕРІС‚РѕСЂРЅР° РїРµСЂРµРІС–СЂРєР° РїС–Рґ Р»РѕРєРѕРј С‰РѕР± Р·Р°РєСЂРёС‚Рё race
            // РґРІРѕС… РѕРґРЅРѕС‡Р°СЃРЅРёС… start() (РїРµСЂРµРІРёС‰РµРЅРЅСЏ MAX Р°Р±Рѕ РїРµСЂРµР·Р°РїРёСЃ РєР»СЋС‡Р°).
            val inserted: Boolean = synchronized(lock) {
                // РїСЂРёР±РёСЂР°С”РјРѕ РјРµСЂС‚РІС– С‰РѕР± Р»С–РјС–С‚ Р±СѓРІ С‚РѕС‡РЅРёРј
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
                // Р—Р°Р№РІРёР№ РїСЂРѕС†РµСЃ РіР»СѓС€РёРјРѕ РѕРґСЂР°Р·Сѓ С‰РѕР± РЅРµ РІРёС‚С–РєР°РІ
                try { process.destroy() } catch (_: Exception) {}
                try {
                    Thread {
                        try { Thread.sleep(1000) } catch (_: Exception) {}
                        try { if (process.isAlive) process.destroyForcibly() } catch (_: Exception) {}
                    }.apply { isDaemon = true; start() }
                } catch (_: Exception) {}
                appendLog(logFile, "\n[СЃРєР°С‚Р°СЂС‚ РІС–РґС…РёР»РµРЅРѕ: СЃРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓСЃРєР°С”С‚СЊСЃСЏ Р°Р±Рѕ Р»С–РјС–С‚ $MAX_SERVERS]\n")
                Log.w(TAG, "start race lost for $serverId")
                return false
            }

            // РџР°РјРї stdout -> server.log РІ С„РѕРЅС–
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
                        appendLog(logFile, "\n[РїСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ, РєРѕРґ $code]\n")
                    } catch (_: Exception) {}
                    // РЅРµ РІРёРґР°Р»СЏС”РјРѕ РѕРґСЂР°Р·Сѓ Р· running С‰РѕР± UI РІСЃС‚РёРі РїРѕР±Р°С‡РёС‚Рё STOPPED С‡РµСЂРµР· liveness;
                    // ServerService СЃР°Рј РІРёРєР»РёС‡Рµ stop() / РїСЂРёР±РµСЂРµ Р·Р°РїРёСЃ
                }
            }, "embedded-log-$serverId").apply { isDaemon = true; start() }

            Log.d(TAG, "started $serverId pid=$pid cmd=${command.take(5)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed $serverId", e)
            try {
                val lf = File(workDir, "server.log")
                appendLog(lf, "\n[РџРћРњРР›РљРђ Р—РђРџРЈРЎРљРЈ: ${e.message}]\n")
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * Р†РЅС‚РµСЂР°РєС‚РёРІРЅРёР№ shell РґР»СЏ Ubuntu-СЃРµСЂРІРµСЂР° / РєРѕРЅСЃРѕР»С–:
     * Р·Р°РїСѓСЃРєР°С” `sh -i` Р· stdin-С‚СЂСѓР±РѕСЋ. Р’РІС–Рґ РїРѕРґР°С”С‚СЊСЃСЏ С‡РµСЂРµР· sendInput().
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
        return start(context, serverId, launch.argv, workDir, launch.env)
    }

    fun sendInput(serverId: String, data: String): Boolean {
        // In-process СЃРµСЂРІРµСЂ С‡РёС‚Р°С” System.in JVM, Р° РЅРµ С‚СЂСѓР±Сѓ РїСЂРѕС†РµСЃСѓ
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

    /** РќР°РґС–СЃР»Р°С‚Рё СЃРёСЂС– Р±Р°Р№С‚Рё Р±РµР· РґРѕРґР°РІР°РЅРЅСЏ \n (РґР»СЏ Ctrl/C, Esc-РїРѕСЃР»С–РґРѕРІРЅРѕСЃС‚РµР№) */
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
        // In-process СЃРµСЂРІРµСЂ Р·СѓРїРёРЅСЏС”С‚СЊСЃСЏ С‡РµСЂРµР· "stop" РІ System.in (System.exit РїР°СЃС‚РєР°),
        // РІР±РёС‚Рё JVM-РЅРёС‚РєСѓ РїСЂРёРјСѓСЃРѕРІРѕ Р±РµР·РїРµС‡РЅРѕ РЅРµРјРѕР¶Р»РёРІРѕ вЂ” СЏРє С– РІ Mojo.
        if (InProcessServer.isRunning(serverId)) {
            return InProcessServer.stop(serverId)
        }
        val holder = synchronized(lock) { running.remove(serverId) } ?: return false
        return try {
            // РІРІС–С‡Р»РёРІРµ Р·Р°РІРµСЂС€РµРЅРЅСЏ: СЃРїСЂРѕР±Р° С‡РµСЂРµР· stdin
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
            appendLog(holder.logFile, "\n[Р·СѓРїРёРЅРµРЅРѕ РєРѕСЂРёСЃС‚СѓРІР°С‡РµРј]\n")
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
            // СЂРѕС‚Р°С†С–СЏ 5MB -> Р»РёС€Р°С”РјРѕ РѕСЃС‚Р°РЅРЅС–Р№ 1MB (СЏРє Р±СѓР»Рѕ РІ TermuxBridge)
            if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
                try {
                    val bytes = logFile.readBytes()
                    val keep = bytes.takeLast(1024 * 1024).toByteArray()
                    logFile.writeBytes(keep)
                    logFile.appendText("\n[Р»РѕРі РѕР±СЂС–Р·Р°РЅРѕ РґРѕ 1MB]\n")
                } catch (_: Exception) {}
            }
            logFile.appendText(text)
        } catch (_: Exception) {}
    }

    /**
     * Р”РѕРїРёСЃ Сѓ server.log РєРѕРЅРєСЂРµС‚РЅРѕС— РїР°РїРєРё. РџР°РїРєСѓ РїРµСЂРµРґР°С” РІРёРєР»РёРєР°С‡
     * (Сѓ СЃРµСЂРІС–СЃР° вЂ” Р·Р°РєРµС€РѕРІР°РЅР° workDir), С‰РѕР± Р»РѕРіРё РєР°СЃС‚РѕРјРЅРёС… РїР°РїРѕРє РЅРµ РіСѓР±РёР»РёСЃСЊ.
     */
    fun appendLogToDir(workDir: File, text: String) {
        try {
            workDir.mkdirs()
            appendLog(File(workDir, "server.log"), text)
        } catch (_: Exception) {}
    }

    /**
     * Р‘СѓРґСѓС” РєРѕРјР°РЅРґСѓ Р·Р°РїСѓСЃРєСѓ Minecraft: [java, -Xmx..., -jar, jar, nogui].
     * @param linkerPrefix РЅРµРїРѕСЂРѕР¶РЅС–Р№ РЅР° РїСЂРёСЃС‚СЂРѕСЏС… Р· W^X exec-Р·Р°Р±РѕСЂРѕРЅРѕСЋ
     * (targetSdk 29+): ["/system/bin/linker64"] СЃС‚Р°РІРёС‚СЊСЃСЏ РїРѕРїРµСЂРµРґСѓ java.
     *
     * P0 FORK-fix РєРѕРЅС‚СЂР°РєС‚:
     *  - argv[0] РїС–СЃР»СЏ РїСЂРµС„С–РєСЃР° Р—РђР’Р–Р”Р Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС… РґРѕ java (РЅС–РєРѕР»Рё "-Xmx...").
     *    Р’С–РґРЅРѕСЃРЅРёР№ "java" Р· PATH РґР°С” `F/linker: expected absolute path: "-Xmx64M"`.
     *  - LD_LIBRARY_PATH (jre/lib/server:jre/lib:jre/lib/jli) + JAVA_HOME РјР°СЋС‚СЊ РїСЂРёР№С‚Рё
     *    С‡РµСЂРµР· envExtra (РґРёРІ. EmbeddedJava.linkerEnv) Р°Р±Рѕ Р±СѓРґСѓС‚СЊ РґРѕС–РЅР¶РµРєС‡РµРЅС– РІ start(),
     *    С–РЅР°РєС€Рµ `CANNOT LINK EXECUTABLE ... library "libjli.so" not found`.
     *  - libjli.so РјР°С” С–СЃРЅСѓРІР°С‚Рё РїРѕСЂСѓС‡ Р· java вЂ” РїРµСЂРµРІС–СЂСЏС”С‚СЊСЃСЏ РІ start()/probeJava.
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
            Log.e(TAG, "buildMinecraftCommand: javaPath not absolute ($javaPath) вЂ” linker РІРїР°РґРµ expected absolute path")
        }
        if (linkerPrefix.any { it.startsWith("-X") }) {
            Log.e(TAG, "buildMinecraftCommand: linkerPrefix must not contain -X flags: $linkerPrefix")
        }
        // linkerPrefix[0] вЂ” Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС… РґРѕ Р»С–РЅРєРµСЂР°, [1] вЂ” Р°Р±СЃРѕР»СЋС‚РЅРёР№ java. -Xmx Р»РёС€Рµ РїС–СЃР»СЏ java.
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
        // Android 14+ MTE С„С–РєСЃ (СЏРє Р±СѓР»Рѕ)
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
        // Р±СѓРґСЊ-СЏРєРёР№ *.jar
        return try {
            workDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }
                ?.maxByOrNull { it.length() }?.name
        } catch (_: Exception) { null }
    }
}
