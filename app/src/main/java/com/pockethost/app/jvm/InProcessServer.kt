package com.pockethost.app.jvm

import android.content.Context
import android.system.Os
import android.util.Log
import com.pockethost.app.embedded.EmbeddedJava
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process С…РѕСЃС‚РёРЅРі Minecraft-СЃРµСЂРІРµСЂР° вЂ” Р°РЅР°Р»РѕРі Mojo GameRunner.launchGame()
 * + JavaRunner.startJvm(), Р°Р»Рµ РґР»СЏ headless-СЃРµСЂРІРµСЂР°.
 *
 * РЇРє Сѓ Mojo:
 *  - РѕРґРёРЅ JVM РЅР° РїСЂРѕС†РµСЃ (one-game model), РїРѕС‚С–Рє Р±Р»РѕРєСѓС”С‚СЊСЃСЏ РІ nativeLoadJVM
 *    РїРѕРєРё main() РЅРµ РїРѕРІРµСЂРЅРµС‚СЊСЃСЏ Р°Р±Рѕ СЃРµСЂРІРµСЂ РЅРµ РІРёРєР»РёС‡Рµ System.exit();
 *  - System.exit() РќР• РІР±РёРІР°С” РґРѕРґР°С‚РѕРє: cpp-РїР°СЃС‚РєР° (setjmp/longjmp) РїРѕРІРµСЂС‚Р°С”
 *    РєРѕРґ РІРёС…РѕРґСѓ РІ С†РµР№ РїРѕС‚С–Рє;
 *  - chdir(workDir) + LD_LIBRARY_PATH РїРµСЂРµРґ СЃС‚Р°СЂС‚РѕРј.
 *
 * Р§РёРј РІС–РґСЂС–Р·РЅСЏС”С‚СЊСЃСЏ РІС–Рґ СЃС‚Р°СЂРѕРіРѕ EmbeddedProcessManager (ProcessBuilder):
 *  - Р¶РѕРґРЅРѕРіРѕ execve() -> С–РјСѓРЅС–С‚РµС‚ РґРѕ W^X error=13 РЅР° Android 10+,
 *    РЅРµ РїРѕС‚СЂС–Р±РµРЅ /system/bin/linker РєРѕСЃС‚РёР»СЊ;
 *  - stdin/stdout С†Рµ System.in/out JVM, Р° РЅРµ С‚СЂСѓР±Рё С„РѕСЂРєРЅСѓС‚РѕРіРѕ РїСЂРѕС†РµСЃСѓ,
 *    С‚РѕРјСѓ ConsoleActivity РїСЂР°С†СЋС” С‡РµСЂРµР· С‚С– Р¶ sendInput/sendRaw.
 */
object InProcessServer {

    private const val TAG = "InProcessServer"

    enum class State { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile var state: State = State.STOPPED
        private set

    @Volatile var serverId: String? = null
        private set

    /** Classpath Р· СЏРєРёРј СЃС‚РІРѕСЂРµРЅР° Р¶РёРІР° JVM (-Djava.class.path С„С–РєСЃСѓС”С‚СЊСЃСЏ РїСЂРё СЃС‚РІРѕСЂРµРЅРЅС–). */
    @Volatile private var createdClasspath: String? = null

    // ---- Paperclip-liveness (main РїРѕРІРµСЂС‚Р°С”С‚СЊСЃСЏ РѕРґСЂР°Р·Сѓ, СЃРµСЂРІРµСЂ Р¶РёРІРµ РґР°Р»С–) ----
    // ART Thread.enumerate РќР• Р±Р°С‡РёС‚СЊ HotSpot-РїРѕС‚РѕРєРё, С‚РѕРјСѓ Р¶РёРІС–СЃС‚СЊ РІРёР·РЅР°С‡Р°С”РјРѕ
    // Р·Р° С„Р°РєС‚Р°РјРё: РїСЂРёРІ'СЏР·Р°РЅРёР№ РїРѕСЂС‚ + РјР°СЂРєРµСЂ "Done (...s)!" РІ server.log.
    // Native-С€Р°СЂ (jvm_launcher.c) С‚РµР¶ РїР°СЂРєСѓС”С‚СЊСЃСЏ РІ РѕС‡С–РєСѓРІР°РЅРЅС– СЃРµСЂРІРµСЂРЅРёС…
    // РїРѕС‚РѕРєС–РІ, С†Рµ вЂ” Kotlin safety-net РґР»СЏ СЃС‚Р°СЂРѕРіРѕ .so / РіРѕРЅРєРё СЃС‚Р°СЂС‚Сѓ.
    @Volatile private var stopRequested = false
    @Volatile private var workDirRef: File? = null
    @Volatile private var serverPort: Int = -1
    @Volatile private var startTimeMs: Long = 0L

    // RESTART-FIX 2026-09-06: SingleThreadExecutor Р·Р°Р±РёРІР°РІСЃСЏ вЂ” РґСЂСѓРіРёР№ submit
    // С‡РµРєР°РІ РїРѕРєРё РїРµСЂС€РёР№ РІРѕСЂРєРµСЂ РІРёСЃРёС‚СЊ РІ native parking (futureDone=false),
    // С‚РѕРјСѓ СЂРµСЃС‚Р°СЂС‚ РЅС–РєРѕР»Рё РЅРµ СЃС‚Р°СЂС‚СѓРІР°РІ (РЅРµРјР° $ в„–2 / Reusing / Calling).
    // var С‰РѕР± РјРѕР¶РЅР° Р±СѓР»Рѕ РїРµСЂРµСЃС‚РІРѕСЂРёС‚Рё РїС–СЃР»СЏ hung + dead-by-log; mcThread РґР»СЏ
    // interrupt; startSeq С‰РѕР± stale-РІРѕСЂРєРµСЂ РЅРµ Р·Р°С‚РёСЂР°РІ РЅРѕРІРёР№ СЃС‚Р°СЂС‚ РІ STOPPED.
    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mc-server").apply { isDaemon = true }
    }
    /** Р–РёРІРёР№ С‡Рё РїРѕС‚С–Рє mc-server (С„РµР№Рє-done РґРµС‚РµРєС‚: cancel СЃС‚Р°РІРёС‚СЊ isDone, РїРѕС‚С–Рє Р»РёС€Р°С”С‚СЊСЃСЏ). */
    private fun mcThreadAlive(): Boolean = try { mcThread?.isAlive == true } catch (_: Exception) { false }
    private var future: Future<*>? = null
    @Volatile private var mcThread: Thread? = null
    @Volatile private var startSeq: Long = 0L

    // ---- System.in/out redirection (СЃС‚Р°РІРёС‚СЊСЃСЏ РѕРґРёРЅ СЂР°Р· РЅР° РїСЂРѕС†РµСЃ) ----
    private var stdinInstalled = false
    private var stdinWriter: PipedOutputStream? = null
    private val stdinLock = Any()

    // OS-СЂС–РІРµРЅСЊ stdin РґР»СЏ HotSpot (РґРёРІ. ensureFdStdin): ART-С‚СЂСѓР±Р° РІРёС‰Рµ
    // СЃРµСЂРІРµСЂСѓ РЅРµРІРёРґРёРјР°, Р° С†Рµ вЂ” fd 0 РїСЂРѕС†РµСЃСѓ, СЏРєРёР№ HotSpot РѕР±РіРѕСЂС‚Р°С” Сѓ СЃРІС–Р№ System.in.
    private var fdStdinWriter: FileOutputStream? = null
    private val fdStdinLock = Any()

    private var stdoutInstalled = false
    private val currentLog = AtomicReference<FileOutputStream?>(null)

    private class TeeOutputStream(
        private val original: OutputStream,
        private val current: AtomicReference<FileOutputStream?>
    ) : OutputStream() {
        override fun write(b: Int) {
            try { original.write(b) } catch (_: Exception) {}
            try { current.get()?.write(b) } catch (_: Exception) {}
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            try { original.write(b, off, len) } catch (_: Exception) {}
            try { current.get()?.write(b, off, len) } catch (_: Exception) {}
        }

        override fun flush() {
            try { original.flush() } catch (_: Exception) {}
            try { current.get()?.flush() } catch (_: Exception) {}
        }
    }

    private fun ensureStdin(): PipedOutputStream? = synchronized(stdinLock) {
        try {
            if (!stdinInstalled) {
                val pipedIn = PipedInputStream(64 * 1024)
                val pipedOut = PipedOutputStream(pipedIn)
                System.setIn(pipedIn)
                stdinWriter = pipedOut
                stdinInstalled = true
            }
            stdinWriter
        } catch (e: Exception) {
            Log.e(TAG, "stdin redirect failed", e)
            null
        }
    }

    private fun ensureStdout() {
        if (stdoutInstalled) return
        try {
            val origOut = System.out
            val tee = TeeOutputStream(origOut, currentLog)
            // autoFlush=true С‰РѕР± Р»РѕРіРё Р·'СЏРІР»СЏР»РёСЃСЊ РІ ConsoleActivity Р±РµР· Р·Р°С‚СЂРёРјРѕРє
            val ps = PrintStream(tee, true, Charsets.UTF_8.name())
            System.setOut(ps)
            System.setErr(ps)
            stdoutInstalled = true
        } catch (e: Exception) {
            Log.e(TAG, "stdout redirect failed", e)
        }
    }

    /**
     * HotSpot вЂ” РѕРєСЂРµРјР° VM Р·С– СЃРІРѕС—Рј java.lang.System: ART System.setIn()
     * (ensureStdin) С—Р№ РЅРµРІРёРґРёРјРёР№, С‚РѕРјСѓ РєРѕРЅСЃРѕР»СЊРЅРёР№ РІРІС–Рґ ("stop", РєРѕРјР°РЅРґРё Р·
     * ConsoleActivity) РґРѕ СЃРµСЂРІРµСЂР° РЅРµ РґРѕС…РѕРґРёРІ С– РІРІС–С‡Р»РёРІР° Р·СѓРїРёРЅРєР° РІРёСЃС–Р»Р°.
     *
     * Р›С–РєСѓС”С‚СЊСЃСЏ СЏРє Сѓ Docker: OS-С‚СЂСѓР±Р°, С‡РёР№ read-РєС–РЅРµС†СЊ РїС–РґРјС–РЅСЋС” fd 0 РїСЂРѕС†РµСЃСѓ
     * Р”Рћ СЃС‚РІРѕСЂРµРЅРЅСЏ JVM. HotSpot РѕР±РіРѕСЂС‚Р°С” fd 0 Сѓ СЃРІС–Р№ System.in, С‚РѕР¶ sendInput/
     * sendRaw РїРёС€СѓС‚СЊ СЃСЋРґРё (С– РґСѓР±Р»СЋСЋС‚СЊ Сѓ ART-С‚СЂСѓР±Сѓ РґР»СЏ СЃСѓРјС–СЃРЅРѕСЃС‚С–).
     * Р’РёРєР»РёРєР°С‚Рё РґРѕ РїРµСЂС€РѕРіРѕ nativeLoadJVM; С–РґРµРјРїРѕС‚РµРЅС‚РЅРѕ, РїРѕС‚С–РєРѕР±РµР·РїРµС‡РЅРѕ.
     */
    private fun ensureFdStdin(): Boolean = synchronized(fdStdinLock) {
        try {
            if (fdStdinWriter != null) return true
            val fds = try {
                Os.pipe() // [0]=read, [1]=write
            } catch (e: Exception) {
                Log.e(TAG, "Os.pipe failed", e)
                return false
            }
            if (fds.size < 2) {
                Log.e(TAG, "Os.pipe returned bad fds")
                return false
            }
            try {
                Os.dup2(fds[0], 0)
            } catch (e: Exception) {
                Log.e(TAG, "dup2 stdin failed", e)
                try { Os.close(fds[0]) } catch (_: Exception) {}
                try { Os.close(fds[1]) } catch (_: Exception) {}
                return false
            }
            fdStdinWriter = try {
                FileOutputStream(fds[1])
            } catch (e: Exception) {
                Log.e(TAG, "fd writer failed", e)
                null
            }
            val ok = fdStdinWriter != null
            Log.i(TAG, "fd-0 stdin pipe installed: $ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "ensureFdStdin failed", e)
            false
        }
    }

    private fun writeFdStdin(bytes: ByteArray): Boolean = synchronized(fdStdinLock) {
        val w = fdStdinWriter ?: return false
        return try {
            w.write(bytes)
            w.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "fd stdin write failed", e)
            false
        }
    }

    /**
     * FAIL 17:44: Р·Р°РїР°СЂРєРѕРІР°РЅРёР№ РІРѕСЂРєРµСЂ (futureDone=false) Р· РїРѕСЂС‚РѕРј РІС–Р»СЊРЅРёРј С–
     * Closing РїС–СЃР»СЏ Done вЂ” РјРµСЂС‚РІРёР№, РЅР°РІС–С‚СЊ СЏРєС‰Рѕ native С‰Рµ РЅРµ РїРѕРІРµСЂРЅСѓРІСЃСЏ
     * (chunk-РІРѕСЂРєРµСЂРё РІРёСЃСЏС‚СЊ). true = С‚РѕС‡РЅРѕ РјРµСЂС‚РІРёР№ Р·Р° Р»РѕРіРѕРј+РїРѕСЂС‚РѕРј.
     * РќРѕРІР° С–РЅРєР°СЂРЅР°С†С–СЏ (СЃРІС–Р¶РёР№ СЃС‚Р°СЂС‚-РјР°СЂРєРµСЂ РїС–СЃР»СЏ Closing) РјРµСЂС‚РІРѕСЋ РЅРµ РІРІР°Р¶Р°С”С‚СЊСЃСЏ.
     */
    private fun isShutdownCompleteByLog(): Boolean {
        return try {
            val wd = workDirRef ?: return false
            val tail = readTail(File(wd, "server.log"), 64 * 1024)
            if (tail.isEmpty()) return false
            val donePos = tail.lastIndexOf("Done (")
            if (donePos < 0) return false
            val stopPos = lastPosOf(tail, STOP_MARKERS)
            if (stopPos <= donePos) return false
            // РЎРІС–Р¶РёР№ СЃС‚Р°СЂС‚-РјР°СЂРєРµСЂ РїС–СЃР»СЏ Closing = РЅРѕРІРёР№ Р±СѓС‚, Р° РЅРµ СЃРјРµСЂС‚СЊ.
            val startPos = lastPosOf(tail, START_MARKERS)
            if (startPos > stopPos) return false
            true
        } catch (_: Exception) { false }
    }

    /**
     * РЁРІРёРґРєР° РїРµСЂРµРІС–СЂРєР° СЃРјРµСЂС‚С– РџР•Р Р•Р” fast-path future (FAIL 17:44):
     * РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ + (stopRequested | Closing РїС–СЃР»СЏ Done) = РјРµСЂС‚РІРёР№,
     * futureDone=false С–РіРЅРѕСЂСѓС”С‚СЊСЃСЏ. РџС–Рґ С‡Р°СЃ СЃРІС–Р¶РѕРіРѕ STARTING-РІС–РєРЅР° (<30СЃ)
     * СЃС‚Р°СЂРёР№ Closing РІ Р»РѕРіСѓ Р·Р°СЃС‚Р°СЂС–Р»РёР№ вЂ” СЃРјРµСЂС‚СЋ РЅРµ РІРІР°Р¶Р°С”РјРѕ, СЃС‚Р°СЂС‚ СЂР°С…СѓС”С‚СЊСЃСЏ Р¶РёРІРёРј.
     */
    private fun isDeadByFactsFast(): Boolean {
        return try {
            if (state == State.STARTING &&
                System.currentTimeMillis() - startTimeMs < 30_000
            ) return false
            val p = serverPort
            if (p <= 0) return false
            val free = try {
                com.pockethost.core.util.NetworkUtils.isPortAvailable(p)
            } catch (_: Exception) { return false }
            if (!free) return false
            // РџРѕСЂС‚ РІС–Р»СЊРЅРёР№ Р·РІС–РґСЃРё РґР°Р»С–.
            // Р’РІС–С‡Р»РёРІР° Р·СѓРїРёРЅРєР° РїРѕРїСЂРѕС€РµРЅР° С– СЃРѕРєРµС‚ РІР¶Рµ РІС–РґРїСѓС‰РµРЅРѕ вЂ” СЃРІС–С‚ РґРѕСЃРёРїР°С”С‚СЊСЃСЏ.
            if (stopRequested) return true
            // Closing РїС–СЃР»СЏ Done Р±РµР· СЃРІС–Р¶РѕРіРѕ СЃС‚Р°СЂС‚Сѓ вЂ” С†РµР№ Р±СѓС‚ РјРµСЂС‚РІРёР№.
            isShutdownCompleteByLog()
        } catch (_: Exception) { false }
    }

    fun isRunning(id: String? = null): Boolean {
        if (id != null && serverId != null && serverId != id) return false
        // РЎРџРћР§РђРўРљРЈ С„Р°РєС‚Рё СЃРјРµСЂС‚С–: Р·Р°РїР°СЂРєРѕРІР°РЅРёР№ РІРѕСЂРєРµСЂ Р· РІС–Р»СЊРЅРёРј РїРѕСЂС‚РѕРј С–
        // Closing РїС–СЃР»СЏ Done вЂ” false, РЅРµ РґРѕС…РѕРґСЏС‡Рё РґРѕ future fast-path.
        try {
            if (isDeadByFactsFast()) return false
        } catch (_: Exception) {}
        // РЁРІРёРґРєРёР№ С€Р»СЏС…: РІРѕСЂРєРµСЂ-РЅРёС‚РєР° С‰Рµ РІ nativeLoadJVM (РЅРѕСЂРјР° РґР»СЏ Vanilla
        // С– РґР»СЏ РїСЂРѕРїР°С‚С‡РµРЅРѕРіРѕ native С‰Рѕ РїР°СЂРєСѓС”С‚СЊСЃСЏ РЅР° Paperclip-РїРѕС‚РѕРєР°С…).
        try {
            val f = future
            if (f != null && !f.isDone) {
                if (id == null || serverId == null || serverId == id) return true
            }
        } catch (_: Exception) {}
        // РџРѕРІС–Р»СЊРЅРёР№ С€Р»СЏС…: Paperclip.main() РїРѕРІРµСЂРЅСѓРІСЃСЏ Р· 0 (СЃС‚Р°СЂРёР№ .so Р°Р±Рѕ
        // РіРѕРЅРєР°), Р°Р»Рµ СЃРµСЂРІРµСЂ Р¶РёРІРёР№ вЂ” С„Р°РєС‚Рё: РїРѕСЂС‚ + Done-РјР°СЂРєРµСЂ.
        if (id != null && serverId != null && serverId != id) return false
        if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING) {
            if (isServerAliveByFacts()) return true
        }
        return false
    }

    /** Р§Рё РїСЂРёРІ'СЏР·Р°РЅРёР№ РїРѕСЂС‚ (Р·Р°Р№РЅСЏС‚РёР№ = СЃРµСЂРІРµСЂ СЃР»СѓС…Р°С” = РґРѕР±СЂРµ). */
    private fun isPortBound(port: Int): Boolean {
        if (port <= 0) return false
        return try {
            !com.pockethost.core.util.NetworkUtils.isPortAvailable(port)
        } catch (_: Exception) { false }
    }

    private fun readTail(f: File, maxBytes: Int): String {
        return try {
            if (!f.isFile) return ""
            val len = f.length()
            if (len <= 0) return ""
            val off = if (len > maxBytes) len - maxBytes else 0L
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(off)
                val n = (len - off).toInt().coerceAtMost(maxBytes)
                val buf = ByteArray(n)
                var total = 0
                while (total < n) {
                    val r = raf.read(buf, total, n - total)
                    if (r <= 0) break
                    total += r
                }
                String(buf, 0, total)
            }
        } catch (_: Exception) { "" }
    }

    private fun hasDeathMarker(tail: String): Boolean =
        tail.contains("[РїСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ")

    /**
     * РњР°СЂРєРµСЂРё РІРІС–С‡Р»РёРІРѕРіРѕ С€Р°С‚РґР°СѓРЅСѓ MC: СЏРєС‰Рѕ Р±СѓРґСЊ-СЏРєРёР№ Р· РЅРёС… РџР†РЎР›РЇ РѕСЃС‚Р°РЅРЅСЊРѕРіРѕ
     * Done вЂ” С†РµР№ Р±СѓС‚ РјРµСЂС‚РІРёР№ (Р‘Р°Рі A: stale Done РїС–СЃР»СЏ Closing Server).
     * РњР°СЂРєРµСЂ Р·СѓРїРёРЅРµРЅРѕ-РєРѕСЂРёСЃС‚СѓРІР°С‡РµРј РїРёС€Рµ stop() РґРѕ С€Р°С‚РґР°СѓРЅСѓ, РјР°СЂРєРµСЂ
     * РѕС‡С–РєСѓРІР°РЅРЅСЏ-Р·Р°РІРµСЂС€РµРЅРѕ РїРёС€Рµ РІРѕСЂРєРµСЂ РїС–СЃР»СЏ РїР°СЂРєСѓРІР°РЅРЅСЏ вЂ” РѕР±РёРґРІР° С‚РµР¶
     * РѕР·РЅР°С‡Р°СЋС‚СЊ С‰Рѕ Done РІР¶Рµ РЅРµ СЃРІС–Р¶РёР№.
     */
    private val STOP_MARKERS = listOf(
        "Stopping the server",
        "Stopping server",
        "Saving players",
        "Saving worlds",
        "Saving chunks",
        "All dimensions are saved",
        "Closing Server",
        "Server stopped",
        "Server Stop",
        "Server has stopped",
        "[Р·СѓРїРёРЅРµРЅРѕ РєРѕСЂРёСЃС‚СѓРІР°С‡РµРј]",
        "[РѕС‡С–РєСѓРІР°РЅРЅСЏ СЃРµСЂРІРµСЂР° Р·Р°РІРµСЂС€РµРЅРѕ"
    )
    private val DEATH_MARKERS = listOf("[РїСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ")
    private val START_MARKERS = listOf(
        "Starting org.bukkit",
        "Starting minecraft server",
        "Loading libraries",
        "This server is running",
        "Preparing level",
        "Preparing start region"
    )

    private fun lastPosOf(tail: String, markers: List<String>): Int {
        var best = -1
        for (m in markers) {
            try {
                val p = tail.lastIndexOf(m)
                if (p > best) best = p
            } catch (_: Exception) {}
        }
        return best
    }

    /**
     * РЎРІС–Р¶РёР№ Done: РѕСЃС‚Р°РЅРЅС–Р№ "Done (" РїС–СЃР»СЏ РѕСЃС‚Р°РЅРЅСЊРѕРіРѕ Closing/stop-РјР°СЂРєРµСЂР°
     * С– РїС–СЃР»СЏ РѕСЃС‚Р°РЅРЅСЊРѕС— СЃРјРµСЂС‚С–, Р· "For help, type" СЃР»С–РґРѕРј (С‚РµР¶ РїС–СЃР»СЏ РЅРёС…).
     * РџРѕСЂС–РІРЅСЋС”РјРѕ РџРћР—РР¦Р†Р‡ РІ С…РІРѕСЃС‚С– Р»РѕРіСѓ: РїРµСЂРµР·Р°РїСѓСЃРє РґРѕРїРёСЃСѓС” РЅРѕРІРёР№ Done РїС–СЃР»СЏ
     * СЃС‚Р°СЂРѕРіРѕ Closing, С‚РѕР¶ СЃС‚Р°СЂРёР№ Done (РґРѕ Closing) РЅРµ РІРІР°Р¶Р°С”С‚СЊСЃСЏ Р¶РёРІРёРј.
     */
    private fun hasFreshDone(tail: String): Boolean {
        try {
            val donePos = tail.lastIndexOf("Done (")
            if (donePos < 0) return false
            val stopPos = lastPosOf(tail, STOP_MARKERS)
            val deathPos = lastPosOf(tail, DEATH_MARKERS)
            if (donePos < stopPos || donePos < deathPos) return false
            // "For help, type" Р№РґРµ РЅР°СЃС‚СѓРїРЅРёРј СЂСЏРґРєРѕРј РїС–СЃР»СЏ Done вЂ” РјР°С” Р±СѓС‚Рё С‚РµР¶ РїС–СЃР»СЏ Closing/СЃРјРµСЂС‚С–.
            val helpPos = tail.indexOf("For help, type", donePos)
            if (helpPos < 0) return false
            if (helpPos < stopPos || helpPos < deathPos) return false
            return true
        } catch (_: Exception) { return false }
    }

    /** Р”С–Р°РіРЅРѕСЃС‚РёРєР° РґР»СЏ logcat/РєРѕРЅСЃРѕР»С–: С‡РѕРјСѓ С„Р°РєС‚Рё РІРІР°Р¶Р°СЋС‚СЊ Р¶РёРІРёРј/РјРµСЂС‚РІРёРј (Р‘Р°Рі B).
     * РџСѓР±Р»С–С‡РЅР°: ServerService phase1 Р»РѕРіСѓС” С—С— РєРѕР¶РЅС– 5СЃ С‰РѕР± Observer Р±Р°С‡РёРІ
     * donePos/stopPos/portFree/stopReq. */
    fun describeFacts(): String {
        return try {
            val fDone = try {
                val ff = future; ff == null || ff.isDone
            } catch (_: Exception) { true }
            val port = serverPort
            val portFree = try {
                port <= 0 || com.pockethost.core.util.NetworkUtils.isPortAvailable(port)
            } catch (_: Exception) { true }
            var donePos = -2; var stopPos = -2; var deathPos = -2; var tailLen = -1
            try {
                val wd = workDirRef
                if (wd != null) {
                    val tail = readTail(File(wd, "server.log"), 64 * 1024)
                    tailLen = tail.length
                    if (tail.isNotEmpty()) {
                        donePos = tail.lastIndexOf("Done (")
                        stopPos = lastPosOf(tail, STOP_MARKERS)
                        deathPos = lastPosOf(tail, DEATH_MARKERS)
                    }
                }
            } catch (_: Exception) {}
            "futureDone=$fDone port=$port portFree=$portFree stopReq=$stopRequested state=$state seq=$startSeq threadAlive=${mcThreadAlive()} donePos=$donePos stopPos=$stopPos deathPos=$deathPos tailLen=$tailLen"
        } catch (_: Exception) { "facts?err" }
    }

    /**
     * РЎРµСЂРІРµСЂ Р¶РёРІРёР№ Р·Р° С„Р°РєС‚Р°РјРё (РІРёРґРёРјРёРјРё С– Р· ART). РџРѕСЂСЏРґРѕРє (С„С–РєСЃ Р‘Р°РіС–РІ A/B):
     *  1. РїРѕСЂС‚ РїСЂРёРІ'СЏР·Р°РЅРёР№ -> РѕРґРЅРѕР·РЅР°С‡РЅРѕ Р¶РёРІРёР№ (РјРё Р№РѕРіРѕ Р·Р°Р№РЅСЏР»Рё РїСЂРё СЃС‚Р°СЂС‚С–);
     *  2. РІРѕСЂРєРµСЂ done + РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ -> РѕРґРЅРѕР·РЅР°С‡РЅРѕ РјРµСЂС‚РІРёР№ (РЅР°РІС–С‚СЊ Р·С– stale Done);
     *  3. stopRequested + РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ -> РјРµСЂС‚РІРёР№ (С€Р°С‚РґР°СѓРЅ, СЃРѕРєРµС‚ РІР¶Рµ РІС–РґРїСѓС‰РµРЅРѕ);
     *  4. СЃРІС–Р¶РёР№ Done (РїС–СЃР»СЏ РѕСЃС‚Р°РЅРЅСЊРѕРіРѕ Closing/СЃРјРµСЂС‚С–) -> Р¶РёРІРёР№;
     *  5. СЃРІС–Р¶РёР№ СЃС‚Р°СЂС‚-РјР°СЂРєРµСЂ Сѓ РІС–РєРЅС– СЃС‚Р°СЂС‚Сѓ -> Р¶РёРІРёР№;
     *  6. С–РЅР°РєС€Рµ РјРµСЂС‚РІРёР№.
     */
    private fun isServerAliveByFacts(): Boolean {
        try {
            if (!PocketJvm.nativeIsJvmCreated()) return false
        } catch (_: Exception) { return false }
        val port = serverPort
        val portBound = port > 0 && isPortBound(port)
        if (portBound) return true
        // РџРѕСЂС‚ РІС–Р»СЊРЅРёР№ + РІРѕСЂРєРµСЂ РІРёР№С€РѕРІ = STOPPED РЅР°РІС–С‚СЊ Р·С– stale Done РІ Р»РѕРіСѓ.
        // (Paperclip-РїР°СЂРєСѓРІР°РЅРЅСЏ С‚СЂРёРјР°С” future РќР• done, СЃСЋРґРё РЅРµ РїРѕС‚СЂР°РїР»СЏС”.)
        try {
            val f = future
            if (f != null && f.isDone) return false
            if (f == null && state == State.STOPPED) return false
        } catch (_: Exception) {}
        // Р’РІС–С‡Р»РёРІР° Р·СѓРїРёРЅРєР° РїРѕРїСЂРѕС€РµРЅР° С– РїРѕСЂС‚ РІР¶Рµ РІС–Р»СЊРЅРёР№ вЂ” MC РІС–РґРїСѓСЃС‚РёРІ СЃРѕРєРµС‚,
        // СЃРІС–С‚ РґРѕСЃРёРїР°С”С‚СЊСЃСЏ; С‡РµРєР°С‚Рё stale Done РЅРµ С‚СЂРµР±Р° (С–РЅР°РєС€Рµ await РІРёСЃРёС‚СЊ 60СЃ).
        if (stopRequested) return false
        try {
            val wd = workDirRef ?: return false
            val tail = readTail(File(wd, "server.log"), 64 * 1024)
            if (tail.isEmpty()) {
                // РќРµРјР° Р»РѕРіСѓ Р°Р»Рµ РІРѕСЂРєРµСЂ РјРµСЂС‚РІРёР№ С– РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ -> РјРµСЂС‚РІРёР№.
                // РЇРєС‰Рѕ state==STARTING С– СЃС‚Р°СЂС‚СѓРІР°Р»Рё <30СЃ С‚РѕРјСѓ вЂ” С‰Рµ РІР°РЅС‚Р°Р¶РёС‚СЊСЃСЏ.
                return state == State.STARTING &&
                    System.currentTimeMillis() - startTimeMs < 30_000
            }
            if (hasDeathMarker(tail)) {
                // РЎРјРµСЂС‚СЊ Р·Р°С„С–РєСЃРѕРІР°РЅР° РІРѕСЂРєРµСЂРѕРј; РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ (РїРµСЂРµРІС–СЂРµРЅРѕ РІРёС‰Рµ)
                // -> С‚РѕС‡РЅРѕ РјРµСЂС‚РІРёР№. РџРѕСЂС‚ Р·Р°Р№РЅСЏС‚РёР№ СЃСЋРґРё РЅРµ РґРѕС…РѕРґРёС‚СЊ (СЂР°РЅРЅС–Р№ return).
                return false
            }
            if (hasFreshDone(tail)) return true
            val startPos = lastPosOf(tail, START_MARKERS)
            if (startPos >= 0) {
                val stopPos = lastPosOf(tail, STOP_MARKERS)
                val deathPos = lastPosOf(tail, DEATH_MARKERS)
                // РЎС‚Р°СЂС‚-РјР°СЂРєРµСЂ СЃРІС–Р¶РёР№ (РїС–СЃР»СЏ РѕСЃС‚Р°РЅРЅСЊРѕРіРѕ Closing/СЃРјРµСЂС‚С–) С– РІС–РєРЅРѕ СЃС‚Р°СЂС‚Сѓ.
                if (startPos > stopPos && startPos > deathPos &&
                    System.currentTimeMillis() - startTimeMs < 180_000
                ) return true
            }
            // РЎС‚Р°СЂС‚СѓС”РјРѕ Р°Р»Рµ РјР°СЂРєРµСЂС–РІ С‰Рµ РЅРµРјР° (РїРµСЂС€С– СЃРµРєСѓРЅРґРё): РІРІР°Р¶Р°С”РјРѕ Р¶РёРІРёРј
            // РїРѕРєРё РЅРµРјР° СЃРјРµСЂС‚С–/С€Р°С‚РґР°СѓРЅСѓ РІ С…РІРѕСЃС‚С–.
            if (state == State.STARTING &&
                System.currentTimeMillis() - startTimeMs < 60_000
            ) {
                // Р„ Closing РІС–Рґ РїРѕРїРµСЂРµРґРЅСЊРѕРіРѕ Р±СѓС‚Р° РІ С‚РѕРјСѓ Р¶ Р»РѕРіСѓ С– СЃРІС–Р¶РѕРіРѕ
                // СЃС‚Р°СЂС‚-РјР°СЂРєРµСЂР° РїС–СЃР»СЏ РЅСЊРѕРіРѕ РЅРµРјР° (РїРµСЂРµРІС–СЂРµРЅРѕ РІРёС‰Рµ) вЂ” С†Рµ stale.
                if (lastPosOf(tail, STOP_MARKERS) >= 0) return false
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /** Р РµР·СѓР»СЊС‚Р°С‚ СЃРїСЂРѕР±Рё СЃС‚Р°СЂС‚Сѓ: С‡Рё РјР°С” СЃРµРЅСЃ fallback РЅР° legacy exec. */
    enum class StartResult {
        STARTED,            // РїРѕС‚С–Рє Р·Р°РїСѓС‰РµРЅРѕ (СЃР°РјР° РіСЂР° С‰Рµ РІР°РЅС‚Р°Р¶РёС‚СЊСЃСЏ)
        ALREADY_RUNNING,    // no-op: Р¶РёРІРёР№ СЃРµСЂРІРµСЂ РІР¶Рµ С”, РґСЂСѓРіРёР№ Paper РЅРµ СЃС‚Р°СЂС‚СѓС”РјРѕ
        FAILED_RETRYABLE,   // РјРѕР¶РЅР° РїСЂРѕР±СѓРІР°С‚Рё legacy exec (РЅРµРјР° РЅР°С‚РёРІРЅРѕС— lib С‚РѕС‰Рѕ)
        FAILED_DEFINITIVE   // legacy С‚РµР¶ РЅРµ РґРѕРїРѕРјРѕР¶Рµ (Р±РёС‚РёР№ jar, СЃС‚Р°СЂР° Java) вЂ” РѕРґСЂР°Р·Сѓ ERROR
    }

    /** Р›СЋРґСЃСЊРєРµ РїРѕСЏСЃРЅРµРЅРЅСЏ РЅРµРіР°С‚РёРІРЅРёС… РєРѕРґС–РІ nativeLoadJVM (РґРёРІ. cpp/jvm_launcher.c). */
    private fun describeCode(code: Int): String = when (code) {
        999 -> "main РїРѕРІРµСЂРЅСѓРІСЃСЏ, Р°Р»Рµ РїРѕС‚РѕРєРё СЃРµСЂРІРµСЂР° Р¶РёРІС– (Paperclip-СЂРµР¶РёРј) вЂ” С†Рµ РЅРµ СЃРјРµСЂС‚СЊ, РІРѕСЂРєРµСЂ С‡РµРєР°С” РґР°Р»С–."
        -8 -> "РіРѕР»РѕРІРЅРёР№ РєР»Р°СЃ РЅРµ Р·Р°РІР°РЅС‚Р°Р¶РёРІСЃСЏ. РќР°Р№С‡Р°СЃС‚С–С€Рµ: СЃРµСЂРІРµСЂСѓ РїРѕС‚СЂС–Р±РЅР° РЅРѕРІС–С€Р° Java (РґРёРІ. СЂСЏРґРѕРє Р· exception РІРёС‰Рµ вЂ” С€СѓРєР°Р№ UnsupportedClassVersionError), Р°Р±Рѕ jar РЅРµСЃСѓРјС–СЃРЅРёР№ Р· С†РёРј classpath."
        -9 -> "РІ РєР»Р°СЃС– РЅРµРјР° РјРµС‚РѕРґСѓ main(String[]). Jar РїРѕС€РєРѕРґР¶РµРЅРёР№ Р°Р±Рѕ С†Рµ РЅРµ СЃРµСЂРІРµСЂРЅРёР№ jar."
        -10 -> "РІРёРЅСЏС‚РѕРє Сѓ main() СЃРµСЂРІРµСЂР°. РџСЂРёС‡РёРЅР° вЂ” РІ СЂСЏРґРєСѓ Р· exception РІРёС‰Рµ."
        // STOP->START FIX 2026-09-07: С‡РµСЃРЅР° РІС–РґРјРѕРІР° reuse-РіС–Р»РєРё (jvm_launcher.c):
        // СЃС‚Р°СЂРёР№ mc-РїРѕС‚С–Рє С‰Рµ РІ main/parking, РЅРѕРІРёР№ Paper РќР• СЃС‚Р°СЂС‚СѓРІР°РІ (Р¶РѕРґРЅРѕРіРѕ
        // СЂСЏРґРєР° РІС–Рґ РЅСЊРѕРіРѕ РІ server.log С– РЅРµ Р±СѓРґРµ). Р¦Рµ РќР• РєСЂР°С…: Р·Р°С‡РµРєР°Р№ РІРёС…РѕРґСѓ
        // СЃС‚Р°СЂРѕРіРѕ (grace ~10СЃ) С– С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·.
        -11 -> "РїРѕРїРµСЂРµРґРЅС–Р№ СЃРµСЂРІРµСЂРЅРёР№ РїРѕС‚С–Рє С‰Рµ Р·Р°РІРµСЂС€СѓС”С‚СЊСЃСЏ (stop/grace РІ РїСЂРѕС†РµСЃС–), РЅРѕРІРёР№ Paper РЅРµ СЃС‚Р°СЂС‚СѓРІР°РІ. Р—Р°С‡РµРєР°Р№ РєС–Р»СЊРєР° СЃРµРєСѓРЅРґ С– С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·."
        -4 -> "JVM РЅРµ СЃС‚РІРѕСЂРёР»Р°СЃСЊ (РЅРµСЃСѓРјС–СЃРЅС– РїСЂР°РїРѕСЂС†С– Р°Р±Рѕ Р±СЂР°Рє РїР°Рј'СЏС‚С– РґР»СЏ РєСѓРїРё)."
        -1, -2 -> "РЅРµ РІРґР°Р»РѕСЃСЏ РІС–РґРєСЂРёС‚Рё libjvm.so. JRE Р±РёС‚РёР№ вЂ” РїРµСЂРµРІСЃС‚Р°РЅРѕРІРё tools/jre."
        else -> "РґРёРІ. СЂСЏРґРѕРє Р· exception/JVM РџРћРњРР›РљРђ РІРёС‰Рµ."
    }

    /** РџРѕС‚РѕС‡РЅРёР№ СЃС‚Р°СЂС‚-Р»С–С‡РёР»СЊРЅРёРє (РґР»СЏ stale-РїРѕР»Р»С–РІ: С‡СѓР¶РёР№ seq = РЅРѕРІС–С€РёР№ Р±СѓС‚). */
    fun currentSeq(): Long = try { startSeq } catch (_: Exception) { -1L }

    /**
     * РЎС‚Р°СЂС‚ СЃРµСЂРІРµСЂР° РІ JVM-РїРѕС‚РѕС†С–. Р‘Р»РѕРєСѓС” Р»РёС€Рµ С„РѕРЅРѕРІРёР№ РїРѕС‚С–Рє.
     */
    fun start(
        context: Context,
        id: String,
        serverJar: File,
        workDir: File,
        maxMemoryMB: Int,
        minMemoryMB: Int,
        userArgs: List<String>,
        minecraftVersion: String? = null,
        log: (String) -> Unit,
        serverPort: Int = 25565,
        /** РЁР»СЏС… РґРѕ java Р· ensureJava: РґС–Рј JRE Р±РµСЂРµРјРѕ Р· РЅСЊРѕРіРѕ (jre21 РґР»СЏ 1.20.5+). */
        javaPath: String? = null
    ): StartResult {
        synchronized(this) {
            if (isRunning()) {
                log("РЎРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓС‰РµРЅРёР№ (in-process, РѕРґРёРЅ Р·Р° СЂР°Р·) вЂ” РїРѕРІС‚РѕСЂРЅРёР№ СЃС‚Р°СЂС‚ РїСЂРѕРїСѓС‰РµРЅРѕ")
                return StartResult.ALREADY_RUNNING
            }
            // РџРѕСЂС‚ Р·Р°Р№РЅСЏС‚РёР№ Р¶РёРІРёРј СЃРµСЂРІРµСЂРѕРј (РЅР°РїСЂ. РїС–СЃР»СЏ РїРµСЂРµР·Р°РїСѓСЃРєСѓ UI):
            // РЅРµ СЃС‚Р°СЂС‚СѓС”РјРѕ РґСЂСѓРіРёР№ Paper РїРѕРІРµСЂС… Р·Р°Р»РѕС‡РµРЅРѕРіРѕ СЃРІС–С‚Сѓ.
            if (serverPort > 0 && isPortBound(serverPort)) {
                log("РЎРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓС‰РµРЅРёР№ (РїРѕСЂС‚ $serverPort Р·Р°Р№РЅСЏС‚РёР№) вЂ” РїРѕРІС‚РѕСЂРЅРёР№ СЃС‚Р°СЂС‚ РїСЂРѕРїСѓС‰РµРЅРѕ")
                return StartResult.ALREADY_RUNNING
            }
        }
        // RESTART-FIX: СЂРѕР·Р±Р»РѕРєСѓРІР°РЅРЅСЏ Р·Р°Р±РёС‚РѕРіРѕ SingleThreadExecutor.
        // РџРѕРїРµСЂРµРґРЅС–Р№ РІРѕСЂРєРµСЂ РјС–Рі Р·Р°РІРёСЃРЅСѓС‚Рё РІ native parking (futureDone=false),
        // РЅРѕРІРёР№ submit С‚РѕРґС– С‡РµСЂРіСѓС”С‚СЊСЃСЏ РІС–С‡РЅРѕ: РЅРµРјР° $ в„–2 / Reusing / Calling.
        // РЇРєС‰Рѕ С„Р°РєС‚Рё РєР°Р¶СѓС‚СЊ "РјРµСЂС‚РІРёР№" (РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ + Closing РїС–СЃР»СЏ Done, Р°Р±Рѕ
        // РІР·Р°РіР°Р»С– РЅРµ alive Р·Р° С„Р°РєС‚Р°РјРё) вЂ” Р±СѓРґРёРјРѕ native, РїРµСЂРµСЂРёРІР°С”РјРѕ mcThread
        // С– С‡РµРєР°С”РјРѕ РІРёС…РѕРґСѓ (native grace ~10СЃ), С‰РѕР± РЅРµ РіРЅР°С‚Рё РґРІР° JVM-main
        // РїР°СЂР°Р»РµР»СЊРЅРѕ (РїРѕСЂС‚/world-lock). РЎРјРµСЂС‚СЊ РїРµСЂРµРІС–СЂСЏС”РјРѕ Р·Р° mcThread.isAlive,
        // РќР• Р·Р° isDone (cancel Р±СЂРµС€Рµ). РЇРєС‰Рѕ РїРѕС‚С–Рє Р¶РёРІРёР№ РїС–СЃР»СЏ 12СЃ вЂ” РІС–РґРјРѕРІР° Р·
        // FAILED_DEFINITIVE ("РїРµСЂРµР·Р°РїСѓСЃС‚Рё РґРѕРґР°С‚РѕРє"), executor РїРµСЂРµСЃС‚РІРѕСЂСЋС”РјРѕ
        // Р»РёС€Рµ РєРѕР»Рё РїРѕС‚РѕРєСѓ РІР¶Рµ РЅРµРјР°; stale-РІРѕСЂРєРµСЂ С–РіРЅРѕСЂСѓС”С‚СЊСЃСЏ Р·Р° seq.
        val prevFut: Future<*>? = synchronized(this) { future }
        // FAIL 18:09 FAKE-done: Future.cancel(true) СЃС‚Р°РІРёС‚СЊ isDone=true, Р°Р»Рµ
        // Java-РїРѕС‚С–Рє Р»РёС€Р°С”С‚СЊСЃСЏ Р¶РёРІРёРј (mc-server РІРёСЃРёС‚СЊ РІ native parking: ps
        // РїРѕРєР°Р·СѓС” Р¶РёРІРёР№ mc-server + Reference Handler/Finalizer/Signal/Service,
        // Р° "prev worker finished while waiting futureDone=true" вЂ” Р±СЂРµС…РЅСЏ).
        // РЎС‚Р°СЂС‚ РЅРѕРІРѕРіРѕ Paper РІ С‚С–Р№ Р¶Рµ JVM РїРѕРІРµСЂС… Р¶РёРІРѕРіРѕ РґР°С” РґРІР° Paper: РґСЂСѓРіРёР№
        // РЅРµ Р±РёРЅРґРёС‚СЊСЃСЏ + РІРёС‚С–Рє С‚СЂРµРґС–РІ (Server thread, Craft Scheduler, Tuinity
        // Chunk, Netty). РўРѕРјСѓ СЃРјРµСЂС‚СЊ РїРµСЂРµРІС–СЂСЏС”РјРѕ Р·Р° mcThread.isAlive, Р° РќР• Р·Р°
        // isDone: СЃРїСЂР°РІР¶РЅС” Р·Р°РІРµСЂС€РµРЅРЅСЏ Р·Р°РІР¶РґРё С‡РёСЃС‚РёС‚СЊ mcThread=null Сѓ finally
        // Р”Рћ С‚РѕРіРѕ СЏРє future СЃС‚Р°РЅРµ done; isDone + Р¶РёРІРёР№ mcThread = С„РµР№Рє.
        // Р–РёРІРёР№ РїРѕС‚С–Рє РїС–СЃР»СЏ cancel+12СЃ вЂ” РІС–РґРјРѕРІР° Р· ERROR "РїРµСЂРµР·Р°РїСѓСЃС‚Рё РґРѕРґР°С‚РѕРє".
        // STUCK-STOPPING FIX 2026-09-07: РїС–СЃР»СЏ graceful stop СЃС‚Р°РЅ Р·Р°РІРёСЃР°РІ Сѓ
        // STOPPING + stopRequested=true, Р° СЂРµСЃС‚Р°СЂС‚Рё РІС–РґРјРѕРІР»СЏР»Рё РЅР°Р·Р°РІР¶РґРё:
        //  Р°) cancel(true) СЃС‚Р°РІРёС‚СЊ isDone=true РњРРўРўР„Р’Рћ, Р° СЃС‚Р°СЂРёР№ РєРѕРґ С‡РµРєР°РІ
        //     `while (!prevFut.isDone)` вЂ” С‚РѕР±С‚Рѕ 0СЃ вЂ” Р±Р°С‡РёРІ Р¶РёРІРёР№ РїРѕС‚С–Рє С–
        //     РІС–РґРјРѕРІР»СЏРІ FAILED_DEFINITIVE; РєРѕР¶РµРЅ РЅР°СЃС‚СѓРїРЅРёР№ Start Р±Р°С‡РёРІ С‚Рµ СЃР°РјРµ
        //     (futureDone=true + threadAlive) С– С‚РµР¶ РІС–РґРјРѕРІР»СЏРІ вЂ” deadlock;
        //  Р±) native parking РјС–Рі РІС–С€Р°С‚РёСЃСЊ РІСЃРµСЂРµРґРёРЅС– getAllStackTraces
        //     (Threads_lock С‚СЂРёРјР°С” РІРјРёСЂР°СЋС‡РёР№ Server-РїРѕС‚С–Рє) вЂ” gate Р”Рћ enum РЅРµ
        //     СЂСЏС‚СѓРІР°РІ. Native-С„С–РєСЃ: РїС–СЃР»СЏ stop latch enum СЃРєРёРїР°С”С‚СЊСЃСЏ РІР·Р°РіР°Р»С–.
        // РўРµРїРµСЂ С‡РµРєР°С”РјРѕ РЎРњР•Р РўР† РџРћРўРћРљРЈ (mcThread.isAlive), Р° РЅРµ future, РґРѕ 12СЃ
        // (native grace 10СЃ РІСЃС‚РёРіР°С” РІРёР№С‚Рё), С– РІС–РґРјРѕРІР»СЏС”РјРѕ Р»РёС€Рµ СЏРєС‰Рѕ С„Р°РєС‚Рё Р–РР’Р†.
        // РњРµСЂС‚РІРёР№ Р·Р° С„Р°РєС‚Р°РјРё + Р¶РёРІРёР№ РїРѕС‚С–Рє РїС–СЃР»СЏ 12СЃ вЂ” abandon (РЅРѕРІРёР№ executor
        // + seq bump С‰РѕР± hung-РІРѕСЂРєРµСЂ СЃС‚Р°РІ stale), Р° РЅРµ РІС–С‡РЅРёР№ "СЂРµСЃС‚Р°СЂС‚ Р°РїРєРё".
        fun abandonHungWorker(reason: String) {
            val hungId = try { "${mcThread?.name}/${mcThread?.id} alive=${mcThreadAlive()}" } catch (_: Exception) { "?" }
            try { Log.w(TAG, "abandonHungWorker: $reason hungThread=$hungId facts=${describeFacts()}") } catch (_: Exception) {}
            try { executor.shutdownNow() } catch (_: Exception) {}
            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "mc-server").apply { isDaemon = true }
            }
            // Hung-РІРѕСЂРєРµСЂ РєРѕР»Рё РІРёР№РґРµ Р· native вЂ” РїРѕР±Р°С‡РёС‚СЊ С‡СѓР¶РёР№ seq С– РЅРµ Р·Р°С‚СЂРµ РЅРѕРІРёР№ СЃС‚Р°СЂС‚.
            synchronized(this) {
                startSeq++
                future = null
            }
        }
        // Р¤Р°РєС‚Рё РјРµСЂС‚РІС–? (РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ + РЅРµ alive) вЂ” С‚РѕРґС– СЂРµСЃС‚Р°СЂС‚ Р±РµР·РїРµС‡РЅРёР№.
        fun deadByFactsNow(): Boolean {
            return try {
                val p = this.serverPort
                val free = p <= 0 || com.pockethost.core.util.NetworkUtils.isPortAvailable(p)
                free && !isServerAliveByFacts()
            } catch (_: Exception) { false }
        }
        if (prevFut != null && prevFut.isDone && mcThreadAlive()) {
            // Р¤РµР№Рє-done РЅР° РІС…РѕРґС– (РїРѕРїРµСЂРµРґРЅС–Р№ cancel), Р°Р»Рµ РїРѕС‚С–Рє С‰Рµ Р¶РёРІРёР№.
            if (deadByFactsNow()) {
                log("WARN: РїРѕРїРµСЂРµРґРЅС–Р№ РїРѕС‚С–Рє С‰Рµ РІРёС…РѕРґРёС‚СЊ (С„РµР№Рє-done), С‡РµРєР°СЋ РґРѕ 12СЃ...")
                val t0 = System.currentTimeMillis()
                while (mcThreadAlive() && System.currentTimeMillis() - t0 < 12_000) {
                    try { Thread.sleep(500) } catch (_: Exception) { break }
                }
                if (mcThreadAlive()) {
                    if (deadByFactsNow()) {
                        log("WARN: СЃС‚Р°СЂРёР№ РїРѕС‚С–Рє РЅРµ РІРёР№С€РѕРІ Р·Р° 12СЃ, Р°Р»Рµ СЃРµСЂРІРµСЂ РјРµСЂС‚РІРёР№ Р·Р° С„Р°РєС‚Р°РјРё вЂ” СЃС‚Р°СЂС‚СѓСЋ РЅР° РЅРѕРІРѕРјСѓ С‚СЂРµРґС– (СЃС‚Р°СЂРёР№ Р±СѓРґРµ С–РіРЅРѕСЂРѕРІР°РЅРѕ)")
                        abandonHungWorker("fake-done РЅР° РІС…РѕРґС–, РїРѕС‚С–Рє Р¶РёРІРёР№ РїС–СЃР»СЏ 12СЃ")
                    } else {
                        log("РЎРµСЂРІРµСЂ С‰Рµ Р·Р°РІРµСЂС€СѓС”С‚СЊСЃСЏ (РІРѕСЂРєРµСЂ Р¶РёРІРёР№) вЂ” Р·Р°С‡РµРєР°Р№ РєС–Р»СЊРєР° СЃРµРєСѓРЅРґ С– С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·")
                        return StartResult.ALREADY_RUNNING
                    }
                } else {
                    log("РџРѕРїРµСЂРµРґРЅС–Р№ РїРѕС‚С–Рє Р·Р°РІРµСЂС€РµРЅРѕ, СЃС‚Р°СЂС‚СѓСЋ РЅРѕРІРёР№...")
                    // РџС–СЃР»СЏ cancel С‚СЂРµРґ РјС–Рі Р»РёС€РёС‚Рё interrupt-С„Р»Р°Рі вЂ” С‡РёСЃС‚РёР№ executor Р±РµР·РїРµС‡РЅС–С€Рµ.
                    try { executor.shutdownNow() } catch (_: Exception) {}
                    executor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "mc-server").apply { isDaemon = true }
                    }
                    synchronized(this) { future = null }
                }
            } else {
                // Р’Р¶Рµ "done" РЅР° РІС…РѕРґС– (РїРѕРїРµСЂРµРґРЅС–Р№ cancel), Р°Р»Рµ РїРѕС‚С–Рє Р¶РёРІРёР№ С– С„Р°РєС‚Рё Р¶РёРІС–.
                log("ERROR: РїРѕРїРµСЂРµРґРЅС–Р№ СЃРµСЂРІРµСЂРЅРёР№ РїРѕС‚С–Рє РґРѕСЃС– Р¶РёРІРёР№. РџРµСЂРµР·Р°РїСѓСЃС‚Рё РґРѕРґР°С‚РѕРє: РЅРѕРІРёР№ Paper РїРѕРІРµСЂС… Р¶РёРІРѕРіРѕ РІ РѕРґРЅС–Р№ JVM Р·Р°Р±РѕСЂРѕРЅРµРЅРѕ.")
                try { Log.e(TAG, "refusing start: fake-done prev worker, mcThread alive + facts alive. facts=${describeFacts()}") } catch (_: Exception) {}
                return StartResult.FAILED_DEFINITIVE
            }
        }
        if (prevFut != null && !prevFut.isDone) {
            val portFreePrev = try {
                val p = this.serverPort
                p <= 0 || com.pockethost.core.util.NetworkUtils.isPortAvailable(p)
            } catch (_: Exception) { true }
            val deadByLogPrev = try { isShutdownCompleteByLog() } catch (_: Exception) { false }
            val alivePrev = try { isServerAliveByFacts() } catch (_: Exception) { true }
            Log.i(TAG, "start pre-check hung prev: portFree=$portFreePrev deadByLog=$deadByLogPrev aliveByFacts=$alivePrev facts=${describeFacts()}")
            if (portFreePrev && !alivePrev) {
                log("WARN: РїРѕРїРµСЂРµРґРЅС–Р№ РІРѕСЂРєРµСЂ Р·Р°РІРёСЃ (futureDone=false) Р°Р»Рµ СЃРµСЂРІРµСЂ РјРµСЂС‚РІРёР№ Р·Р° С„Р°РєС‚Р°РјРё вЂ” СЂРѕР·Р±Р»РѕРєРѕРІСѓСЋ...")
                try { PocketJvm.requestStop() } catch (_: Exception) {}
                try { prevFut.cancel(true) } catch (_: Exception) {}
                try { mcThread?.interrupt() } catch (_: Exception) {}
                // Р§РµРєР°С”РјРѕ РЎРњР•Р РўР† РџРћРўРћРљРЈ, РЅРµ future: cancel СЂРѕР±РёС‚СЊ isDone=true
                // РѕРґСЂР°Р·Сѓ, СЃС‚Р°СЂРёР№ `while (!isDone)` С‡РµРєР°РІ 0СЃ С– РІС–РґРјРѕРІР»СЏРІ.
                val t0 = System.currentTimeMillis()
                while (mcThreadAlive() && System.currentTimeMillis() - t0 < 12_000) {
                    try { Thread.sleep(500) } catch (_: Exception) { break }
                    val waited = System.currentTimeMillis() - t0
                    if (waited % 2000 < 500) {
                        try { Log.i(TAG, "start waiting prev thread death... ${describeFacts()}") } catch (_: Exception) {}
                    }
                }
                if (mcThreadAlive()) {
                    if (deadByFactsNow()) {
                        log("WARN: СЃС‚Р°СЂРёР№ РїРѕС‚С–Рє РЅРµ РІРёР№С€РѕРІ Р·Р° 12СЃ, Р°Р»Рµ СЃРµСЂРІРµСЂ РјРµСЂС‚РІРёР№ Р·Р° С„Р°РєС‚Р°РјРё вЂ” СЃС‚Р°СЂС‚СѓСЋ РЅР° РЅРѕРІРѕРјСѓ С‚СЂРµРґС– (СЃС‚Р°СЂРёР№ Р±СѓРґРµ С–РіРЅРѕСЂРѕРІР°РЅРѕ)")
                        abandonHungWorker("hung prev, РїРѕС‚С–Рє Р¶РёРІРёР№ РїС–СЃР»СЏ 12СЃ")
                    } else {
                        // РџРѕС‚С–Рє Р¶РёРІРёР№ С– С„Р°РєС‚Рё РѕР¶РёР»Рё (РїРѕСЂС‚ Р·Р°Р№РЅСЏС‚Рѕ) вЂ” РґСЂСѓРіРёР№
                        // Paper РїРѕРІРµСЂС… Р¶РёРІРѕРіРѕ СЃРІС–С‚Сѓ = OverlappingFileLock.
                        log("РЎРµСЂРІРµСЂ С‰Рµ Р·Р°РІРµСЂС€СѓС”С‚СЊСЃСЏ (РІРѕСЂРєРµСЂ Р¶РёРІРёР№) вЂ” Р·Р°С‡РµРєР°Р№ РєС–Р»СЊРєР° СЃРµРєСѓРЅРґ С– С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·")
                        return StartResult.ALREADY_RUNNING
                    }
                } else {
                    log("РџРѕРїРµСЂРµРґРЅС–Р№ РІРѕСЂРєРµСЂ Р·Р°РІРµСЂС€РµРЅРѕ, СЃС‚Р°СЂС‚СѓСЋ РЅРѕРІРёР№...")
                    try { Log.i(TAG, "prev worker finished while waiting, facts=${describeFacts()}") } catch (_: Exception) {}
                    // РџС–СЃР»СЏ cancel С‚СЂРµРґ РјС–Рі Р»РёС€РёС‚Рё interrupt-С„Р»Р°Рі вЂ” С‡РёСЃС‚РёР№ executor Р±РµР·РїРµС‡РЅС–С€Рµ.
                    try { executor.shutdownNow() } catch (_: Exception) {}
                    executor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "mc-server").apply { isDaemon = true }
                    }
                    synchronized(this) { future = null }
                }
            } else if (alivePrev || !portFreePrev) {
                // Р’РѕСЂРєРµСЂ РІРёСЃРёС‚СЊ Р°Р»Рµ С„Р°РєС‚Рё Р¶РёРІС– (РїРѕСЂС‚ Р·Р°Р№РЅСЏС‚РёР№) вЂ” РґСЂСѓРіРёР№ Paper
                // РїРѕРІРµСЂС… Р¶РёРІРѕРіРѕ СЃРІС–С‚Сѓ = OverlappingFileLockException. РќРµ СЃС‚Р°СЂС‚СѓС”РјРѕ.
                log("РЎРµСЂРІРµСЂ С‰Рµ Р·Р°РІРµСЂС€СѓС”С‚СЊСЃСЏ (РІРѕСЂРєРµСЂ Р¶РёРІРёР№) вЂ” Р·Р°С‡РµРєР°Р№ РєС–Р»СЊРєР° СЃРµРєСѓРЅРґ С– С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·")
                return StartResult.ALREADY_RUNNING
            }
        }
        val mySeq: Long = synchronized(this) {
            state = State.STARTING
            serverId = id
            stopRequested = false
            workDirRef = workDir
            this.serverPort = serverPort
            startTimeMs = System.currentTimeMillis()
            startSeq++
            startSeq
        }
        val myId = id

        if (!PocketJvm.isNativeAvailable()) {
            log("ERROR: РЅР°С‚РёРІРЅР° Р±С–Р±Р»С–РѕС‚РµРєР° pockethost_jvm РЅРµ Р·С–Р±СЂР°РЅР°. Р—Р±РµСЂРё APK Р· NDK (CMake) Р°Р±Рѕ Р·Р°РїСѓСЃРєР°Р№ С‡РµСЂРµР· legacy-СЂРµР¶РёРј.")
            synchronized(this) { state = State.STOPPED; serverId = null }
            return StartResult.FAILED_RETRYABLE
        }

        val runtimeHome = try {
            EmbeddedJava.runtimeHomeFor(javaPath)?.takeIf { it.isDirectory }
                ?: EmbeddedJava.bundledJreDir(context)
        } catch (_: Exception) { EmbeddedJava.bundledJreDir(context) }
        // Р‘СѓР»Рѕ `&&`: С‡Р°СЃС‚РєРѕРІРёР№ JRE (РїР°РїРєР° С”, bin/java РЅРµРјР°) РїСЂРѕР»Р°Р·РёРІ Сѓ prepare
        // С– РІРјРёСЂР°РІ С‚Р°Рј Р· Definitive "no libjvm" Р·Р°РјС–СЃС‚СЊ С‡РµСЃРЅРѕРіРѕ retryable.
        if (!File(runtimeHome, "bin/java").exists() || !runtimeHome.isDirectory) {
            log("ERROR: JRE РЅРµ Р·РЅР°Р№РґРµРЅРѕ РІ ${runtimeHome.absolutePath}")
            synchronized(this) { state = State.STOPPED; serverId = null }
            return StartResult.FAILED_RETRYABLE
        }

        val params: PocketJvm.StartParams
        try {
            params = PocketJvm.prepare(
                context, runtimeHome, serverJar, workDir,
                maxMemoryMB, minMemoryMB, userArgs, minecraftVersion, log
            ) ?: run {
                synchronized(this) { state = State.STOPPED; serverId = null }
                return StartResult.FAILED_RETRYABLE
            }
        } catch (e: PocketJvm.DefinitiveFailureException) {
            synchronized(this) { state = State.STOPPED; serverId = null }
            return StartResult.FAILED_DEFINITIVE
        }
        // -Djava.class.path С„С–РєСЃСѓС”С‚СЊСЃСЏ РїСЂРё СЃС‚РІРѕСЂРµРЅРЅС– JVM: С–РЅС€РёР№ classpath = РїРѕС‚СЂС–Р±РµРЅ СЂРµСЃС‚Р°СЂС‚ РїСЂРѕС†РµСЃСѓ.
        val wantCp = params.classpath
        val liveCp = createdClasspath
        if (liveCp != null && liveCp != wantCp && PocketJvm.nativeIsJvmCreated()) {
            // Р—Р°С…РёСЃРЅРѕ: СЏРєС‰Рѕ РїРѕРїРµСЂРµРґРЅС–Р№ СЃРµСЂРІРµСЂ РґРёРІРѕРј Р¶РёРІРёР№ вЂ” РЅРµ С‡С–РїР°С”РјРѕ СЃС‚Р°РЅ,
            // РїРѕРІС‚РѕСЂРЅРёР№ Paper РїРѕРІРµСЂС… Р¶РёРІРѕРіРѕ СЃРІС–С‚Сѓ = OverlappingFileLockException.
            if (isRunning() || isServerAliveByFacts()) {
                log("РЎРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓС‰РµРЅРёР№ вЂ” РїРѕРІС‚РѕСЂРЅРёР№ СЃС‚Р°СЂС‚ РїСЂРѕРїСѓС‰РµРЅРѕ")
                return StartResult.ALREADY_RUNNING
            }
            log("ERROR: Р—СѓРїРёРЅРё С–РЅС€РёР№ СЃРµСЂРІРµСЂ (С–РЅС€РёР№ classpath, РїРѕС‚СЂС–Р±РµРЅ СЂРµСЃС‚Р°СЂС‚ Р°РїРєРё).")
            log("JVM РІР¶Рµ СЃС‚РІРѕСЂРµРЅР° Р· С–РЅС€РёРј classpath вЂ” РґСЂСѓРіРёР№ Minecraft in-process РЅРµ СЃС‚Р°СЂС‚СѓС”.")
            log("Р±СѓР»Рѕ: $liveCp")
            log("С‚СЂРµР±Р°: $wantCp")
            synchronized(this) { state = State.STOPPED; serverId = null }
            return StartResult.FAILED_DEFINITIVE
        }
        // user.dir РјР°С” РІРєР°Р·СѓРІР°С‚Рё РЅР° РїР°РїРєСѓ СЃРµСЂРІРµСЂР° (РІС–РґРЅРѕСЃРЅС– С€Р»СЏС…Рё РїР»Р°РіС–РЅС–РІ/СЃРІС–С‚Сѓ)
        val jvmArgs = params.jvmArgs.toMutableList().also { list ->
            if (list.none { it.startsWith("-Duser.dir=") }) {
                list.add("-Duser.dir=" + workDir.absolutePath)
            }
        }

        ensureStdin()
        ensureStdout()
        // fd 0 РјР°С” СЃС‚Р°С‚Рё С‚СЂСѓР±РѕСЋ Р”Рћ СЃС‚РІРѕСЂРµРЅРЅСЏ JVM (РґРёРІ. ensureFdStdin),
        // С–РЅР°РєС€Рµ HotSpot СѓСЃРїР°РґРєСѓС” /dev/null С– РєРѕРјР°РЅРґРё РєРѕРЅСЃРѕР»С– РЅРµ РґС–Р№РґСѓС‚СЊ.
        val fdStdinOk = ensureFdStdin()
        if (!fdStdinOk) {
            log("WARN: fd-stdin С‚СЂСѓР±Р° РЅРµ РІСЃС‚Р°РЅРѕРІРёР»Р°СЃСЊ вЂ” РєРѕРјР°РЅРґРё РєРѕРЅСЃРѕР»С– РјРѕР¶СѓС‚СЊ РЅРµ РґРѕС…РѕРґРёС‚Рё РґРѕ СЃРµСЂРІРµСЂР°")
        }

        val logFile = File(workDir, "server.log")
        try {
            workDir.mkdirs()
            if (!logFile.exists()) logFile.createNewFile()
        } catch (_: Exception) {}

        // Executor РјС–Рі Р»РёС€РёС‚РёСЃСЊ shutdown РїС–СЃР»СЏ abandon/cancel вЂ” submit С‚РѕРґС– РєРёРЅРµ
        // RejectedExecution С– СЃС‚Р°РЅ Р·Р°РІРёСЃРЅРµ Сѓ STARTING. РџРµСЂРµСЃС‚РІРѕСЂСЋС”РјРѕ + СЂРµС‚СЂР°Р№.
        // (STUCK-STOPPING FIX: РїСЂРѕРІР°Р» submit С‚РµРїРµСЂ СЃРєРёРґР°С” СЃС‚Р°РЅ Сѓ STOPPED.)
        try {
            if (executor.isShutdown || executor.isTerminated) {
                executor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "mc-server").apply { isDaemon = true }
                }
            }
        } catch (_: Exception) {}
        val submittedFut: Future<*>
        try {
            submittedFut = executor.submit {
            val myThread = Thread.currentThread()
            mcThread = myThread
            var exitCode = -999
            // Stale-РІРѕСЂРєРµСЂ (РїРѕРїРµСЂРµРґРЅС–Р№ Р±СѓС‚) РїС–СЃР»СЏ СЂРµСЃС‚Р°СЂС‚Сѓ: РЅРѕРІРёР№ СЃС‚Р°СЂС‚ РІР¶Рµ
            // С–РЅРєСЂРµРјРµРЅС‚СѓРІР°РІ startSeq вЂ” РІРёС…РѕРґРёРјРѕ РѕРґСЂР°Р·Сѓ, СЃС‚Р°РЅ РЅРµ С‡С–РїР°С”РјРѕ.
            fun isStale(): Boolean = try { startSeq != mySeq } catch (_: Exception) { false }
            try {
                appendLog(logFile, "\n\$ [in-process] ${serverJar.name} (main=${params.mainClass} seq=$mySeq thread=${myThread.name}/${myThread.id})\n")
                try { Log.i(TAG, "worker seq=$mySeq enter thread=${myThread.name} id=${myThread.id}") } catch (_: Exception) {}
                currentLog.set(try { FileOutputStream(logFile, true) } catch (_: Exception) { null })

                // РћС‚РѕС‡РµРЅРЅСЏ СЏРє Mojo setImmutableEnvVars + chdir(gameDir)
                try { Os.setenv("HOME", workDir.absolutePath, true) } catch (_: Exception) {}
                try { Os.setenv("TMPDIR", context.cacheDir.absolutePath, true) } catch (_: Exception) {}
                try { Os.setenv("JAVA_HOME", runtimeHome.absolutePath, true) } catch (_: Exception) {}
                // РљР РРўРР§РќРћ РґР»СЏ Bionic JRE: libnio.so С‚СЏРіРЅРµ DT_NEEDED libnet.so, Р° HotSpot
                // РІР°РЅС‚Р°Р¶РёС‚СЊ С—С… Сѓ СЃРІС–Р¶РёР№ linker-namespace (clns-N), СЏРєРёР№ С–РЅС–С†С–Р°Р»С–Р·СѓС”С‚СЊСЃСЏ
                // Р· ENVIRON, Р° РЅРµ Р· РІРЅСѓС‚СЂС–С€РЅСЊРѕРіРѕ РєРµС€Сѓ android_update_LD_LIBRARY_PATH.
                // Р‘РµР· С†СЊРѕРіРѕ вЂ” UnsatisfiedLinkError libnet.so С– РєР°СЃРєР°РґРЅРёР№ -8
                // (NoClassDefFoundError FileSystems$DefaultFileSystemHolder).
                // РЇРє Mojo setImmutableEnvVars: LD_LIBRARY_PATH РјР°С” Р±СѓС‚Рё РІ environ
                // Р”Рћ dlopen(libjvm)/JNI_CreateJavaVM.
                val jreLdPath = PocketJvm.ldLibraryPathFor(params.vmPath)
                try { Os.setenv("LD_LIBRARY_PATH", jreLdPath, true) } catch (_: Exception) {}
                try {
                    PocketJvm.nativeSetLdLibraryPath(jreLdPath)
                } catch (e: Exception) {
                    Log.w(TAG, "setLdLibraryPath failed", e)
                }
                try {
                    val r = PocketJvm.nativeChdir(workDir.absolutePath)
                    if (r != 0) appendLog(logFile, "WARN: chdir failed ($r)\n")
                } catch (e: Exception) {
                    appendLog(logFile, "WARN: chdir: ${e.message}\n")
                }

                // РЇРєС‰Рѕ РїРѕРєРё СЃС‚РѕСЏР»Рё РІ С‡РµСЂР·С– executor РІР¶Рµ РїСЂРёР№С€РѕРІ РЅРѕРІС–С€РёР№ СЃС‚Р°СЂС‚ вЂ”
                // С†РµР№ РІРѕСЂРєРµСЂ stale, РЅРµ Р±СѓРґРµРјРѕ РіРЅР°С‚Рё РґСЂСѓРіРёР№ JVM-main РїР°СЂР°Р»РµР»СЊРЅРѕ.
                if (isStale()) {
                    appendLog(logFile, "[stale worker: РЅРѕРІС–С€РёР№ СЃС‚Р°СЂС‚ Р°РєС‚РёРІРЅРёР№, РїСЂРѕРїСѓСЃРєР°СЋ Р·Р°РїСѓСЃРє]\n")
                    try { Log.w(TAG, "stale worker seq=$mySeq skipping launch, current=${startSeq}") } catch (_: Exception) {}
                    return@submit
                }
                synchronized(this) {
                    // РќРµ Р·Р°С‚РёСЂР°С”РјРѕ РЅРѕРІС–С€РёР№ СЃС‚Р°СЂС‚ С‰Рѕ РјС–Рі РїСЂРёР№С‚Рё РїРѕРєРё С‡РµРєР°Р»Рё prepare.
                    if (startSeq != mySeq) return@submit
                    state = State.RUNNING
                }
                log("РЎРµСЂРІРµСЂ Р·Р°РїСѓС‰РµРЅРѕ (in-process JVM) jar=${serverJar.name} seq=$mySeq")
                // --- РґС–Р°РіРЅРѕСЃС‚РёРєР° РїРµСЂРµРґ native: С‰Рѕ СЂРµР°Р»СЊРЅРѕ Р№РґРµ РІ JVM ---
                Log.i(TAG, "start: jar=${serverJar.absolutePath} exists=${serverJar.isFile} size=${serverJar.length()}")
                Log.i(TAG, "start: wantCp=$wantCp")
                Log.i(TAG, "start: jvmArgs(${jvmArgs.size})=${jvmArgs.joinToString(" | ")}")
                Log.i(TAG, "start: jvmCreated=${PocketJvm.nativeIsJvmCreated()} createdCp=$createdClasspath main=${params.mainClass}")
                appendLog(logFile, "DBG start: jar size=${serverJar.length()} cp=$wantCp\n")
                appendLog(logFile, "DBG start: jvmCreated=${try { PocketJvm.nativeIsJvmCreated() } catch (_: Exception) { "?" }} createdCp=$createdClasspath\n")
                // STOP->START FIX 2026-09-07: РјР°СЂРєРµСЂ "РІРёРєР»РёРєР°СЋ native" вЂ” СЏРєС‰Рѕ РїС–СЃР»СЏ
                // РЅСЊРѕРіРѕ РІ logcat РЅРµРјР° reuse attach/Reusing/calling main, СЃРјРµСЂС‚СЊ
                // СЃС‚Р°Р»Р°СЃСЏ РІ AttachCurrentThread (Threads_lock СЃС‚Р°СЂРѕРіРѕ РїРѕС‚РѕРєСѓ).
                appendLog(logFile, "DBG calling nativeLoadJVM seq=$mySeq (РїС–СЃР»СЏ С†СЊРѕРіРѕ С‡РµРєР°Р№ reuse attach/Reusing/calling main РІ logcat)...\n")
                try { Log.i(TAG, "worker seq=$mySeq calling nativeLoadJVM jvmCreated=${try { PocketJvm.nativeIsJvmCreated() } catch (_: Exception) { "?" }} main=${params.mainClass}") } catch (_: Exception) {}

                exitCode = PocketJvm.nativeLoadJVM(
                    params.vmPath.absolutePath,
                    jvmArgs.toTypedArray(),
                    params.mainClass,
                    params.appArgs.toTypedArray(),
                    wantCp
                )
                try { Log.i(TAG, "worker seq=$mySeq nativeLoadJVM returned $exitCode") } catch (_: Exception) {}
                appendLog(logFile, "DBG nativeLoadJVM returned $exitCode seq=$mySeq\n")
                if (createdClasspath == null) createdClasspath = wantCp
                // Paperclip safety-net: РЅРѕРІРёР№ native РІР¶Рµ РїР°СЂРєСѓС”С‚СЊСЃСЏ СЃР°Рј, Р°Р»Рµ
                // СЃС‚Р°СЂРёР№ .so (Р°Р±Рѕ РіРѕРЅРєР° РїРѕСЏРІРё РїРѕС‚РѕРєС–РІ) РїРѕРІРµСЂРЅРµ 0 РѕРґСЂР°Р·Сѓ.
                // РџРѕРІРµСЂРЅРµРЅРЅСЏ main() РќР• РІРІР°Р¶Р°С”РјРѕ СЃРјРµСЂС‚СЋ РїРѕРєРё Р¶РёРІС– С„Р°РєС‚Рё
                // (РїРѕСЂС‚ + Done-РјР°СЂРєРµСЂ) С– РЅРµРјР° stopRequested.
                if ((exitCode == 0 || exitCode == 999) && !stopRequested && !isStale()) {
                    try {
                        var sawAlive = exitCode == 999
                        var waited = 0
                        while (!sawAlive && waited < 20000 && !stopRequested && !isStale()) {
                            if (isServerAliveByFacts()) { sawAlive = true; break }
                            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                            waited += 500
                            try {
                                val t = readTail(logFile, 64 * 1024)
                                if (hasDeathMarker(t)) break
                                // РЁР°С‚РґР°СѓРЅ (Closing/Stopping РїС–СЃР»СЏ Done) вЂ” С†Рµ СЃРјРµСЂС‚СЊ, Р° РЅРµ
                                // СЂР°РЅРЅС” Paperclip-РїРѕРІРµСЂРЅРµРЅРЅСЏ main: РЅРµ С‡РµРєР°С‚Рё 20СЃ, РІРёР№С‚Рё РѕРґСЂР°Р·Сѓ
                                // (С–РЅР°РєС€Рµ phase1 stop Р±РµР· stopRequested РІРёСЃРёС‚СЊ +20СЃ).
                                if (t.contains("Closing Server") || t.contains("All dimensions are saved") ||
                                    t.contains("Stopping the server") || t.contains("Stopping server")
                                ) {
                                    if (!hasFreshDone(t)) break
                                }
                            } catch (_: Exception) {}
                        }
                        if (isStale()) {
                            appendLog(logFile, "[stale worker: РЅРѕРІС–С€РёР№ СЃС‚Р°СЂС‚, РїР°СЂРєСѓРІР°РЅРЅСЏ СЃРєР°СЃРѕРІР°РЅРѕ]\n")
                            return@submit
                        }
                        if (sawAlive) {
                            appendLog(logFile, "[main РїРѕРІРµСЂРЅСѓРІСЃСЏ, Р°Р»Рµ СЃРµСЂРІРµСЂ Р¶РёРІРёР№ вЂ” С‡РµРєР°СЋ Р·Р°РІРµСЂС€РµРЅРЅСЏ РїРѕС‚РѕРєС–РІ...]\n")
                            Log.i(TAG, "main returned $exitCode but server alive, parking worker")
                            while (!stopRequested && !isStale() && isServerAliveByFacts()) {
                                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                            }
                            if (isStale()) {
                                appendLog(logFile, "[stale worker: РЅРѕРІС–С€РёР№ СЃС‚Р°СЂС‚, РІРёС…РѕРґР¶Сѓ Р±РµР· Р·РјС–РЅРё СЃС‚Р°РЅСѓ]\n")
                                return@submit
                            }
                            if (stopRequested) {
                                // "stop" Р·Р±РµСЂС–РіР°С” СЃРІС–С‚ вЂ” РґР°С”РјРѕ С„Р°РєС‚Р°Рј Р·РіР°СЃРЅСѓС‚Рё, Р°Р»Рµ
                                // FAIL 17:44: РЅРµ С‡РµРєР°С”РјРѕ СЃРјРµСЂС‚С– РїРѕС‚РѕРєС–РІ 30СЃ РІСЃР»С–РїСѓ:
                                // РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ + Closing РїС–СЃР»СЏ Done = СЃРІС–С‚ Р·Р±РµСЂРµР¶РµРЅРѕ,
                                // СЂРµС€С‚Р°-РІРѕСЂРєРµСЂРё (chunk) РґРѕСЃРёРїР°СЋС‚СЊ Сѓ С„РѕРЅС–, РІРёС…РѕРґРёРјРѕ РѕРґСЂР°Р·Сѓ.
                                var extra = 0
                                while (extra < 30000 && !isStale() && isServerAliveByFacts()) {
                                    try {
                                        if (isShutdownCompleteByLog()) break
                                    } catch (_: Exception) {}
                                    try { Thread.sleep(1000) } catch (_: Exception) { break }
                                    extra += 1000
                                }
                            }
                            appendLog(logFile, "[РѕС‡С–РєСѓРІР°РЅРЅСЏ СЃРµСЂРІРµСЂР° Р·Р°РІРµСЂС€РµРЅРѕ, stopRequested=$stopRequested]\n")
                            exitCode = 0
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "post-main wait failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "JVM crashed", e)
                val nativeErr = PocketJvm.lastError().takeIf { it.isNotBlank() }
                appendLog(logFile, "\n[JVM РџРћРњРР›РљРђ: ${e.message}${if (nativeErr != null) " | $nativeErr" else ""}]\n")
                exitCode = -999
            } finally {
                try { currentLog.getAndSet(null)?.close() } catch (_: Exception) {}
                try { if (mcThread === Thread.currentThread()) mcThread = null } catch (_: Exception) {}
                // Stale-РІРѕСЂРєРµСЂ (СЃС‚Р°СЂРёР№ Р±СѓС‚ РїС–СЃР»СЏ СЂРµСЃС‚Р°СЂС‚Сѓ): СЃС‚Р°РЅ РЅР°Р»РµР¶РёС‚СЊ РЅРѕРІРѕРјСѓ
                // СЃС‚Р°СЂС‚Сѓ вЂ” РЅС–С‡РѕРіРѕ РЅРµ С‡С–РїР°С”РјРѕ, С‚С–Р»СЊРєРё Р»РѕРі.
                if (isStale()) {
                    try { appendLog(logFile, "[stale worker seq=$mySeq finished, ignoring (current=${startSeq})]\n") } catch (_: Exception) {}
                    try { Log.i(TAG, "stale worker seq=$mySeq finished code=$exitCode, ignoring (current seq=${startSeq})") } catch (_: Exception) {}
                    return@submit
                }
                // РЇРєС‰Рѕ С„Р°РєС‚Рё РєР°Р¶СѓС‚СЊ "Р¶РёРІРёР№" Р° stop РЅРµ РїСЂРѕСЃРёР»Рё вЂ” С†Рµ С…РёР±РЅРµ
                // РїРѕРІРµСЂРЅРµРЅРЅСЏ Paperclip: РќР• СЃС‚Р°РІРёРјРѕ STOPPED, Р»РёС€Р°С”РјРѕ RUNNING
                // С– РјРѕРЅС–С‚РѕСЂРёРјРѕ Сѓ С„РѕРЅС– РґРѕ СЂРµР°Р»СЊРЅРѕС— СЃРјРµСЂС‚С–.
                val stillAlive = !stopRequested && !isStale() &&
                    (exitCode == 0 || exitCode == 999) && isServerAliveByFacts()
                if (stillAlive) {
                    appendLog(logFile, "[worker Р·Р°РІРµСЂС€РёРІСЃСЏ Р·Р°СЂР°РЅРѕ, РјРѕРЅС–С‚РѕСЂРёРЅРі РїСЂРѕРґРѕРІР¶РµРЅРѕ]\n")
                    Log.i(TAG, "worker finished early but server alive, continuing monitor")
                    synchronized(this) {
                        if (startSeq != mySeq) return@submit
                        state = State.RUNNING
                    }
                    // Р—Р°С…РѕРїР»СЋС”РјРѕ id/СЃС‚Р°СЂС‚/seq С‰РѕР± РјРѕРЅС–С‚РѕСЂ РЅРµ Р·Р°С‚РёСЂР°РІ С‡СѓР¶РёР№ РЅРѕРІРёР№ СЃС‚Р°СЂС‚.
                    val watchedId = synchronized(this) { serverId }
                    val watchedStart = startTimeMs
                    val watchedSeq = mySeq
                    try {
                        Thread({
                            try {
                                while (!stopRequested && startSeq == watchedSeq && isServerAliveByFacts()) {
                                    Thread.sleep(2000)
                                }
                            } catch (_: Exception) {}
                            if (startSeq != watchedSeq) {
                                try { Log.i(TAG, "monitor stale seq=$watchedSeq, current=${startSeq}, ignoring") } catch (_: Exception) {}
                                return@Thread
                            }
                            try {
                                appendLog(logFile, "\n[РїСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ, РєРѕРґ 0]\n")
                            } catch (_: Exception) {}
                            synchronized(this) {
                                // РќРµ Р·Р°С‚РёСЂР°С”РјРѕ С‡СѓР¶РёР№ СЃС‚Р°СЂС‚ (РЅРѕРІРёР№ serverId/startTime/seq).
                                if (startSeq != watchedSeq) return@synchronized
                                if (serverId != null && serverId != watchedId) return@synchronized
                                if (startTimeMs != watchedStart) return@synchronized
                                state = State.STOPPED
                                serverId = null
                                // РЎРєРёРґР°С”РјРѕ С„Р»Р°Рі С‰РѕР± РЅР°СЃС‚СѓРїРЅРёР№ СЃС‚Р°СЂС‚ РЅРµ Р±Р°С‡РёРІ stale stopRequested (Р‘Р°Рі A).
                                stopRequested = false
                            }
                            try { Log.i(TAG, "monitor finished, facts: ${describeFacts()}") } catch (_: Exception) {}
                        }, "mc-monitor").apply { isDaemon = true; start() }
                    } catch (_: Exception) {}
                    return@submit
                }
                val nativeErr = PocketJvm.lastError().takeIf { it.isNotBlank() }
                if (exitCode < 0 || exitCode > 128) {
                    // РќРµРіР°С‚РёРІРЅС– РєРѕРґРё вЂ” РЅР°С€С– РІРЅСѓС‚СЂС–С€РЅС– (-8 main-РєР»Р°СЃ, -10 РІРёРЅСЏС‚РѕРє...),
                    // >128 вЂ” Р°РІР°СЂС–Р№РЅРёР№ System.exit РІС–Рґ СЃРµСЂРІРµСЂР°. РџРѕСЏСЃРЅСЋС”РјРѕ РѕР±РёРґРІР°.
                    appendLog(logFile, "[РїСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ, РєРѕРґ $exitCode: ${describeCode(exitCode)}]\n")
                    if (nativeErr != null) appendLog(logFile, "[РґРµС‚Р°Р»С–: $nativeErr]\n")
                } else {
                    appendLog(logFile, "\n[РїСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ, РєРѕРґ $exitCode]\n")
                }
                synchronized(this) {
                    // RESTART-FIX: stale-РІРѕСЂРєРµСЂ РЅРµ Р·Р°С‚РёСЂР°С” РЅРѕРІС–С€РёР№ СЃС‚Р°СЂС‚.
                    if (startSeq != mySeq) {
                        try { Log.i(TAG, "stale worker seq=$mySeq finished, keeping current seq=${startSeq} state") } catch (_: Exception) {}
                        return@synchronized
                    }
                    if (serverId != null && serverId != myId) return@synchronized
                    state = State.STOPPED
                    serverId = null
                    // РЎРєРёРґР°С”РјРѕ С„Р»Р°Рі РїСЂРё РїРµСЂРµС…РѕРґС– РІ STOPPED (Р‘Р°Рі A: С–РЅР°РєС€Рµ РІРёСЃРёС‚СЊ РґРѕ СЂРµСЃС‚Р°СЂС‚Сѓ).
                    stopRequested = false
                }
                try { Log.i(TAG, "worker finished code=$exitCode seq=$mySeq, facts: ${describeFacts()}") } catch (_: Exception) {}
            }
        }
        } catch (e: Exception) {
            // Submit РЅРµ РІРґР°РІСЃСЏ (shutdown executor С‚РѕС‰Рѕ) вЂ” РЅРµ РІРёСЃС–С‚Рё Сѓ STARTING:
            // СЃРєРёРґР°С”РјРѕ СЃС‚Р°РЅ С‰РѕР± РЅР°СЃС‚СѓРїРЅРёР№ Start РїСЂРѕР№С€РѕРІ С‡РёСЃС‚Рѕ.
            try { Log.e(TAG, "executor submit failed", e) } catch (_: Exception) {}
            log("ERROR: РЅРµ РІРґР°Р»РѕСЃСЏ Р·Р°РїСѓСЃС‚РёС‚Рё СЃРµСЂРІРµСЂРЅРёР№ РїРѕС‚С–Рє (${e.message}) вЂ” С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·")
            synchronized(this) {
                if (startSeq == mySeq) {
                    state = State.STOPPED
                    serverId = null
                    stopRequested = false
                }
            }
            return StartResult.FAILED_RETRYABLE
        }
        synchronized(this) { future = submittedFut }
        return StartResult.STARTED
    }

    /**
     * Р›РµРіРєРёР№ latch Р·СѓРїРёРЅРєРё РґР»СЏ phase1: СЃС‚Р°РІРёС‚СЊ stopRequested + Р±СѓРґРёС‚СЊ
     * native-РїР°СЂРєСѓРІР°РЅРЅСЏ, Р‘Р•Р— РІС–РґРїСЂР°РІРєРё "stop" (С—С— С€Р»Рµ РІРёРєР»РёРєР°С‡ РѕРєСЂРµРјРѕ).
     * FAIL 17:44: phase1 СЃР»Р°Р»Р° С‚С–Р»СЊРєРё sendInput("stop"), С„Р»Р°Рі Р»РёС€Р°РІСЃСЏ false
     * 60СЃ С– РіС–Р»РєР° stopRequested+portFree Р±СѓР»Р° РјРµСЂС‚РІР°.
     */
    fun requestStop(id: String): Boolean {
        if (id != null && serverId != null && serverId != id) return false
        stopRequested = true
        synchronized(this) {
            if (state == State.RUNNING || state == State.STARTING) state = State.STOPPING
        }
        // Р‘СѓРґРёРјРѕ native parking loop (jvm_launcher.c): Р±РµР· С†СЊРѕРіРѕ futureDone=false
        // РІРёСЃРёС‚СЊ РЅР° chunk-РІРѕСЂРєРµСЂР°С… РЅР°РІС–С‚СЊ РїС–СЃР»СЏ Closing+РІС–Р»СЊРЅРѕРіРѕ РїРѕСЂС‚Сѓ.
        try { PocketJvm.requestStop() } catch (_: Exception) {}
        try { Log.i(TAG, "requestStop($id) latched, facts: ${describeFacts()}") } catch (_: Exception) {}
        return true
    }

    /** Р§Рё С” "stop" РєРѕРјР°РЅРґРѕСЋ Р·СѓРїРёРЅРєРё (latch stopRequested С– РґР»СЏ РєРѕРЅСЃРѕР»СЊРЅРѕРіРѕ РІРІРѕРґСѓ). */
    private fun isStopCommand(data: String): Boolean {
        return try {
            val t = data.trim().lowercase()
            t == "stop" || t == "stop\n" || t.startsWith("stop\n") || t.startsWith("stop ")
        } catch (_: Exception) { false }
    }

    fun sendInput(id: String, data: String): Boolean {
        if (!isRunning(id)) return false
        // РљРѕРЅСЃРѕР»СЊРЅРёР№ "stop" (РєРЅРѕРїРєР° ConsoleActivity, РЅРµ С‚С–Р»СЊРєРё Stop-СЃРµСЂРІС–СЃ) вЂ”
        // С‚РµР¶ Р·СѓРїРёРЅРєР°: С–РЅР°РєС€Рµ С„Р»Р°Рі false С– РїР°СЂРєСѓРІР°РЅРЅСЏ С‡РµРєР°С” СЃРјРµСЂС‚С– РїРѕС‚РѕРєС–РІ.
        try {
            if (isStopCommand(data)) {
                stopRequested = true
                synchronized(this) {
                    if (state == State.RUNNING || state == State.STARTING) state = State.STOPPING
                }
                try { PocketJvm.requestStop() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val raw = if (data.endsWith("\n")) data else "$data\n"
        val bytes = raw.toByteArray(Charsets.UTF_8)
        // fd-С‚СЂСѓР±Р° вЂ” РѕСЃРЅРѕРІРЅРёР№ РєР°РЅР°Р» (С‡РёС‚Р°С” HotSpot); ART-С‚СЂСѓР±Р° вЂ” legacy-РґСѓР±Р»СЊ.
        val fdOk = writeFdStdin(bytes)
        val artOk = try {
            val w = synchronized(stdinLock) { stdinWriter } ?: return fdOk
            w.write(bytes)
            w.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendInput failed", e)
            false
        }
        return artOk || fdOk
    }

    fun sendRaw(id: String, bytes: ByteArray): Boolean {
        if (!isRunning(id)) return false
        try {
            val asText = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { "" }
            if (isStopCommand(asText)) {
                stopRequested = true
                synchronized(this) {
                    if (state == State.RUNNING || state == State.STARTING) state = State.STOPPING
                }
                try { PocketJvm.requestStop() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val fdOk = writeFdStdin(bytes)
        val artOk = try {
            val w = synchronized(stdinLock) { stdinWriter } ?: return fdOk
            w.write(bytes)
            w.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendRaw failed", e)
            false
        }
        return artOk || fdOk
    }

    /**
     * Р’РІС–С‡Р»РёРІР° Р·СѓРїРёРЅРєР°: С€Р»РµРјРѕ "stop" РІ stdin СЃРµСЂРІРµСЂР° С– С‡РµРєР°С”РјРѕ Р Р•РђР›Р¬РќРћР“Рћ
     * Р·Р°РІРµСЂС€РµРЅРЅСЏ РїРѕС‚РѕРєС–РІ (System.exit-РїР°СЃС‚РєР° Р°Р±Рѕ СЃРјРµСЂС‚СЊ Р·Р° С„Р°РєС‚Р°РјРё).
     * "stop" Р№РґРµ Р·Р° С„Р°РєС‚РѕРј Р¶РёРІРѕРіРѕ JVM, Р° РЅРµ РїСЂР°РїРѕСЂС†СЏ future.isDone, С‰РѕР±
     * РєРѕРЅСЃРѕР»СЊ РїСЂР°С†СЋРІР°Р»Р° С– РІ Paperclip-РІС–РєРЅС– (main РІР¶Рµ РїРѕРІРµСЂРЅСѓРІСЃСЏ).
     * РџСЂРёРјСѓСЃРѕРІРѕ РІР±РёС‚Рё JVM-РЅРёС‚РєСѓ Р±РµР·РїРµС‡РЅРѕ РЅРµРјРѕР¶Р»РёРІРѕ, С‚РѕРјСѓ Р±РµР· С‚Р°Р№РјР°СѓС‚Р°-РєС–Р»РµСЂР°.
     */
    fun stop(id: String, workDir: File? = null): Boolean {
        try { Log.i(TAG, "stop($id) requested, facts before: ${describeFacts()}") } catch (_: Exception) {}
        if (!isRunning(id)) {
            try { Log.i(TAG, "stop($id) ignored (not running), facts: ${describeFacts()}") } catch (_: Exception) {}
            return false
        }
        stopRequested = true
        synchronized(this) {
            if (state == State.RUNNING || state == State.STARTING) state = State.STOPPING
        }
        // Р‘СѓРґРёРјРѕ native parking loop вЂ” С–РЅР°РєС€Рµ futureDone=false РІРёСЃРёС‚СЊ РЅР°
        // chunk-РІРѕСЂРєРµСЂР°С… РїС–СЃР»СЏ Closing (FAIL 17:44).
        try { PocketJvm.requestStop() } catch (_: Exception) {}
        // sendInput РїРµСЂРµРІС–СЂСЏС” isRunning (С„Р°РєС‚Рё) вЂ” РїС–Рґ С‡Р°СЃ STOPPING С„Р°РєС‚Рё С‰Рµ
        // Р¶РёРІС–, С‚РѕР¶ РєРѕРјР°РЅРґР° РґС–Р№РґРµ. РЁР»РµРјРѕ РЅР°РїСЂСЏРјСѓ С‰РѕР± РЅРµ Р·Р°Р»РµР¶Р°С‚Рё РІС–Рґ РїРѕСЂСЏРґРєСѓ.
        try {
            val raw = "stop\n".toByteArray(Charsets.UTF_8)
            val fdOk = writeFdStdin(raw)
            val artOk = try {
                val w = synchronized(stdinLock) { stdinWriter }
                if (w != null) { w.write(raw); w.flush(); true } else false
            } catch (_: Exception) { false }
            Log.i(TAG, "stop sent to $id fdOk=$fdOk artOk=$artOk")
        } catch (e: Exception) {
            Log.e(TAG, "stop send failed", e)
        }
        val wd = workDir ?: workDirRef
        if (wd != null) {
            appendLog(File(wd, "server.log"), "\n[Р·СѓРїРёРЅРµРЅРѕ РєРѕСЂРёСЃС‚СѓРІР°С‡РµРј]\n")
        }
        return true
    }

    /**
     * Р§РµРєР°С” Р Р•РђР›Р¬РќРћР“Рћ Р·Р°РІРµСЂС€РµРЅРЅСЏ: С– РІРѕСЂРєРµСЂ-РЅРёС‚РєР° done, С– С„Р°РєС‚Рё РјРµСЂС‚РІС–
     * (РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ + РЅРµРјР° РЎР’Р†Р–РћР“Рћ Done Р±РµР· Closing). Р”Рѕ 60СЃ РґР»СЏ Р·Р±РµСЂРµР¶РµРЅРЅСЏ СЃРІС–С‚Сѓ.
     * true СЏРєС‰Рѕ Р·СѓРїРёРЅРёРІСЃСЏ Р·Р° timeoutMs.
     *
     * Р‘Р°Рі B С„С–РєСЃ: СЏРєС‰Рѕ future done Р† РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ вЂ” РїРѕРІРµСЂС‚Р°С”РјРѕ true РѕРґСЂР°Р·Сѓ,
     * РЅРµ С‡РµРєР°СЋС‡Рё stale Done РІ Р»РѕРіСѓ (С–РЅР°РєС€Рµ РІРїРµСЂС‚Рѕ РІРёСЃРёС‚СЊ РґРѕ С‚Р°Р№РјР°СѓС‚Сѓ).
     * FAIL 17:44: shortcut (portFree && closingAfterDone) -> true РќР•Р—РђР›Р•Р–РќРћ
     * РІС–Рґ futureDone вЂ” Р·Р°РїР°СЂРєРѕРІР°РЅРёР№ РІРѕСЂРєРµСЂ Р±С–Р»СЊС€Рµ РЅРµ С‚СЂРёРјР°С” await 60СЃ.
     */
    fun awaitStop(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var iter = 0
        while (System.currentTimeMillis() < deadline) {
            val done = try {
                val f = future
                f == null || f.isDone
            } catch (_: Exception) { true }
            val portFree = try {
                val p = serverPort
                p <= 0 || com.pockethost.core.util.NetworkUtils.isPortAvailable(p)
            } catch (_: Exception) { true }
            // Р“РѕР»РѕРІРЅРёР№ shortcut: РІРѕСЂРєРµСЂ РІРёР№С€РѕРІ + РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ = СЃС‚РѕРї, С„Р°РєС‚Рё Done С–РіРЅРѕСЂСѓС”РјРѕ.
            if (done && portFree) return true
            if (done && !isServerAliveByFacts()) return true
            // РЁР°С‚РґР°СѓРЅ Р·Р° Р»РѕРіРѕРј (РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ + Closing РїС–СЃР»СЏ Done, Р±РµР· СЃРІС–Р¶РѕРіРѕ
            // СЃС‚Р°СЂС‚Сѓ): СЃРІС–С‚ Р·Р±РµСЂРµР¶РµРЅРѕ, chunk-С…РІС–СЃС‚ РЅРµ СЂР°С…СѓС”С‚СЊСЃСЏ вЂ” СЃС‚РѕРї С”,
            // РЅР°РІС–С‚СЊ СЏРєС‰Рѕ future С‰Рµ РїР°СЂРєСѓС”С‚СЊСЃСЏ РІ native.
            try {
                if (portFree && isShutdownCompleteByLog()) return true
            } catch (_: Exception) {}
            // Р”С–Р°РіРЅРѕСЃС‚РёРєР° РєРѕР¶РЅС– ~5СЃ С‰РѕР± РЅР° С‚РµР»РµС„РѕРЅС– Р±СѓР»Рѕ РІРёРґРЅРѕ С‡РѕРјСѓ С‡РµРєР°С”.
            if (iter % 10 == 0) {
                try { Log.i(TAG, "awaitStop wait: ${describeFacts()}") } catch (_: Exception) {}
            }
            iter++
            try { Thread.sleep(500) } catch (_: Exception) { break }
        }
        return try {
            val f = future
            val done = f == null || f.isDone
            val portFree = try {
                val p = serverPort
                p <= 0 || com.pockethost.core.util.NetworkUtils.isPortAvailable(p)
            } catch (_: Exception) { true }
            try { Log.i(TAG, "awaitStop timeout/end: ${describeFacts()}") } catch (_: Exception) {}
            if (portFree) {
                try {
                    if (isShutdownCompleteByLog()) return true
                } catch (_: Exception) {}
            }
            (done && portFree) || (done && !isServerAliveByFacts())
        } catch (_: Exception) { true }
    }

    @Synchronized
    private fun appendLog(logFile: File, text: String) {
        try {
            if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
                try {
                    val bytes = logFile.readBytes()
                    logFile.writeBytes(bytes.takeLast(1024 * 1024).toByteArray())
                    logFile.appendText("\n[Р»РѕРі РѕР±СЂС–Р·Р°РЅРѕ РґРѕ 1MB]\n")
                } catch (_: Exception) {}
            }
            logFile.appendText(text)
        } catch (_: Exception) {}
    }
}
