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
 * In-process Minecraft server hosting - Mojo GameRunner.launchGame() analog
 * + JavaRunner.startJvm(), but for a headless server.
 *
 * Like in Mojo:
 *  - one JVM per process (one-game model), the thread blocks in nativeLoadJVM
 *    until main() returns or the server calls System.exit();
 *  - System.exit() does NOT kill the app: the cpp trap (setjmp/longjmp) returns
 *    the exit code to this thread;
 *  - chdir(workDir) + LD_LIBRARY_PATH before start.
 *
 * How it differs from old EmbeddedProcessManager (ProcessBuilder):
 *  - no execve() -> immune to the W^X error=13 on Android 10+,
 *    no /system/bin/linker crutch needed;
 *  - stdin/stdout are the JVM System.in/out, not forked-process pipes,
 *    so ConsoleActivity works through the same sendInput/sendRaw.
 */
object InProcessServer {

    private const val TAG = "InProcessServer"

    enum class State { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile var state: State = State.STOPPED
        private set

    @Volatile var serverId: String? = null
        private set

    /** Classpath the live JVM was created with (-Djava.class.path is fixed at creation). */
    @Volatile private var createdClasspath: String? = null

    // ---- Paperclip-liveness (main returns at once, server lives on) ----
    // ART Thread.enumerate does NOT see HotSpot threads, so liveness is judged
    // by facts: bound port + "Done (...s)!" marker in server.log.
    // The native layer (jvm_launcher.c) also parks in server-thread wait,
    // this is the Kotlin safety-net for an old .so / start race.
    @Volatile private var stopRequested = false
    @Volatile private var workDirRef: File? = null
    @Volatile private var serverPort: Int = -1
    @Volatile private var startTimeMs: Long = 0L

    // RESTART-FIX 2026-09-06: SingleThreadExecutor starved - second submit
    // waited while the first worker hung in native parking (futureDone=false),
    // so restart never started (no $ #2 / Reusing / Calling).
    // var so it can be recreated after hung + dead-by-log; mcThread for
    // interrupt; startSeq so a stale worker never wipes a new start into STOPPED.
    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mc-server").apply { isDaemon = true }
    }
    /** Is the mc-server thread alive (fake-done detect: cancel sets isDone, thread stays). */
    private fun mcThreadAlive(): Boolean = try { mcThread?.isAlive == true } catch (_: Exception) { false }
    private var future: Future<*>? = null
    @Volatile private var mcThread: Thread? = null
    @Volatile private var startSeq: Long = 0L

    // ---- System.in/out redirection (installed once per process) ----
    private var stdinInstalled = false
    private var stdinWriter: PipedOutputStream? = null
    private val stdinLock = Any()

    // OS-level stdin for HotSpot (see ensureFdStdin): the ART pipe above is
    // invisible to the server, while this is fd 0 of the process, which HotSpot wraps into its System.in.
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
            // autoFlush=true so logs appear in ConsoleActivity without delay
            val ps = PrintStream(tee, true, Charsets.UTF_8.name())
            System.setOut(ps)
            System.setErr(ps)
            stdoutInstalled = true
        } catch (e: Exception) {
            Log.e(TAG, "stdout redirect failed", e)
        }
    }

    /**
     * HotSpot is a separate VM with its own java.lang.System: ART System.setIn()
     * (ensureStdin) is invisible to it, so console input ("stop", commands from
     * ConsoleActivity) never reached the server and graceful stop hung.
     *
     * Fixed like in Docker: an OS pipe whose read end replaces fd 0 of the process
     * BEFORE JVM creation. HotSpot wraps fd 0 into its System.in, so sendInput/
     * sendRaw write there (and duplicate into the ART pipe for compatibility).
     * Call before the first nativeLoadJVM; idempotent, thread-safe.
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
     * FAIL 17:44: parked worker (futureDone=false) with free port and
     * Closing after Done is dead, even if native has not returned yet
     * (chunk workers hanging). true = exactly dead by log+port.
     * A new incarnation (fresh start marker after Closing) is not counted dead.
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
            // Fresh start-marker after Closing = new boot, not death.
            val startPos = lastPosOf(tail, START_MARKERS)
            if (startPos > stopPos) return false
            true
        } catch (_: Exception) { false }
    }

    /**
     * Quick death check BEFORE the fast-path future (FAIL 17:44):
     * free port + (stopRequested | Closing after Done) = dead,
     * futureDone=false is ignored. During a fresh STARTING window (<30s)
     * an old Closing in the log is stale - not counted dead, start is considered alive.
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
            // Port free so far.
            // A polite stop was asked and the socket is already released - the world is saving.
            if (stopRequested) return true
            // Closing after Done with no fresh start - this boot is dead.
            isShutdownCompleteByLog()
        } catch (_: Exception) { false }
    }

    fun isRunning(id: String? = null): Boolean {
        if (id != null && serverId != null && serverId != id) return false
        // FIRST the death facts: a parked worker with free port and
        // Closing after Done is false, before reaching the future fast-path.
        try {
            if (isDeadByFactsFast()) return false
        } catch (_: Exception) {}
        // Fast path: worker thread still in nativeLoadJVM (normal for Vanilla
        // and for patched native that parks on Paperclip threads).
        try {
            val f = future
            if (f != null && !f.isDone) {
                if (id == null || serverId == null || serverId == id) return true
            }
        } catch (_: Exception) {}
        // Slow path: Paperclip.main() returned 0 (old .so or
        // race), but the server is alive - facts: port + Done marker.
        if (id != null && serverId != null && serverId != id) return false
        if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING) {
            if (isServerAliveByFacts()) return true
        }
        return false
    }

    /** Is the port bound (busy = server listening = good). */
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
        tail.contains("[process exited")

    /**
     * Markers of a graceful MC shutdown: if any of them is AFTER the last
     * Done - this boot is dead (Bug A: stale Done after Closing Server).
     * The user-stopped marker is written by stop() before shutdown, the wait-
     * finished marker by the worker after parking - both also mean
     * that Done is no longer fresh.
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
        "[stopped by user]",
        "[server wait finished"
    )
    private val DEATH_MARKERS = listOf("[process exited")
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
     * Fresh Done: the last "Done (" after the last Closing/stop marker
     * and after the last death, with "For help, type" following (also after them).
     * We compare POSITIONS in the log tail: a restart appends a new Done after
     * the old Closing, so an old Done (before Closing) is not counted alive.
     */
    private fun hasFreshDone(tail: String): Boolean {
        try {
            val donePos = tail.lastIndexOf("Done (")
            if (donePos < 0) return false
            val stopPos = lastPosOf(tail, STOP_MARKERS)
            val deathPos = lastPosOf(tail, DEATH_MARKERS)
            if (donePos < stopPos || donePos < deathPos) return false
            // "For help, type" comes on the next line after Done - must also be after Closing/death.
            val helpPos = tail.indexOf("For help, type", donePos)
            if (helpPos < 0) return false
            if (helpPos < stopPos || helpPos < deathPos) return false
            return true
        } catch (_: Exception) { return false }
    }

    /** Diagnostics for logcat/console: why facts judge alive/dead (Bug B).
     * Public: ServerService phase1 logs it every 5s so the Observer sees
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
     * A server alive by facts (visible also from ART). Order (fixes for Bugs A/B):
     *  1. bound port -> definitely alive (we bound it at start);
     *  2. worker done + port free -> definitely dead (even with stale Done);
     *  3. stopRequested + port free -> dead (shutdown, socket already released);
     *  4. fresh Done (after last Closing/death) -> alive;
     *  5. fresh start marker inside the start window -> alive;
     *  6. otherwise dead.
     */
    private fun isServerAliveByFacts(): Boolean {
        try {
            if (!PocketJvm.nativeIsJvmCreated()) return false
        } catch (_: Exception) { return false }
        val port = serverPort
        val portBound = port > 0 && isPortBound(port)
        if (portBound) return true
        // Port free + worker exited = STOPPED even with stale Done in the log.
        // (Paperclip parking holds future NOT done, never reaches here.)
        try {
            val f = future
            if (f != null && f.isDone) return false
            if (f == null && state == State.STOPPED) return false
        } catch (_: Exception) {}
        // A polite stop was asked and the port is already free - MC released the socket,
        // the world is saving; no need to wait for stale Done (otherwise await hangs 60s).
        if (stopRequested) return false
        try {
            val wd = workDirRef ?: return false
            val tail = readTail(File(wd, "server.log"), 64 * 1024)
            if (tail.isEmpty()) {
                // No log but worker dead and port free -> dead.
                // When state==STARTING and start was <30s ago - still launching.
                return state == State.STARTING &&
                    System.currentTimeMillis() - startTimeMs < 30_000
            }
            if (hasDeathMarker(tail)) {
                // Death recorded by the worker; port free (checked above)
                // -> exactly dead. A busy port never reaches here (early return).
                return false
            }
            if (hasFreshDone(tail)) return true
            val startPos = lastPosOf(tail, START_MARKERS)
            if (startPos >= 0) {
                val stopPos = lastPosOf(tail, STOP_MARKERS)
                val deathPos = lastPosOf(tail, DEATH_MARKERS)
                // Start marker fresh (after last Closing/death) and start window.
                if (startPos > stopPos && startPos > deathPos &&
                    System.currentTimeMillis() - startTimeMs < 180_000
                ) return true
            }
            // Starting but no markers yet (first seconds): count alive
            // until death/shutdown appears in the tail.
            if (state == State.STARTING &&
                System.currentTimeMillis() - startTimeMs < 60_000
            ) {
                // A Closing from the previous boot in the same log with no fresh
                // start marker after it (checked above) - stale.
                if (lastPosOf(tail, STOP_MARKERS) >= 0) return false
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /** Start attempt result: is legacy exec fallback sensible. */
    enum class StartResult {
        STARTED,            // thread launched (the game itself still loading)
        ALREADY_RUNNING,    // no-op: live server already there, second Paper not started
        FAILED_RETRYABLE,   // legacy exec may be tried (no native lib etc)
        FAILED_DEFINITIVE   // legacy cannot help either (broken jar, old Java) - ERROR at once
    }

    /** Human explanation of negative nativeLoadJVM codes (see cpp/jvm_launcher.c). */
    private fun describeCode(code: Int): String = when (code) {
        999 -> "main returned, but server threads are alive (Paperclip mode) - not death, worker keeps waiting."
        -8 -> "main class failed to load. Most often: the server needs a newer Java (see the exception line above - look for UnsupportedClassVersionError), or the jar is incompatible with this classpath."
        -9 -> "no main(String[]) method in the class. Jar is broken or not a server jar."
        -10 -> "exception in server main. Cause - in the exception line above."
        // STOP->START FIX 2026-09-07: honest refusal of the reuse trap (jvm_launcher.c):
        // old mc thread still in main/parking, new Paper did NOT start (no
        // line from it in server.log ever will be). Not a crash: wait for the old
        // one to exit (grace ~10s) and press Start again.
        -11 -> "previous server thread still finishing (stop/grace in progress), new Paper did not start (code -11). Wait a few seconds and press Start again."
        -4 -> "JVM failed to create (bad flags or no heap memory)."
        -1, -2 -> "failed to open libjvm.so. JRE is broken - reinstall tools/jre."
        else -> "see the exception/JVM ERROR line above."
    }

    /** Is the mc-server worker thread alive (regardless of Future state).
     * Public for ServerService stop diagnostics: STOPPED must not be reported
     * while this is true (next Start would refuse with "thread still alive"). */
    fun isWorkerAlive(): Boolean = mcThreadAlive()

    /** Per-start counter (for stale polls: foreign seq = newer boot). */
    fun currentSeq(): Long = try { startSeq } catch (_: Exception) { -1L }

    /**
     * Server start in a JVM thread. Blocks only the background thread.
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
        /** java path from ensureJava: take the JRE home from it (jre21 for 1.20.5+). */
        javaPath: String? = null
    ): StartResult {
        synchronized(this) {
            if (isRunning()) {
                log("Server already running (in-process, one at a time) - duplicate start skipped")
                return StartResult.ALREADY_RUNNING
            }
            // Port busy with a live server (e.g. after UI restart):
            // do not start a second Paper over a locked world.
            if (serverPort > 0 && isPortBound(serverPort)) {
                log("Server already running (port $serverPort busy) - duplicate start skipped")
                return StartResult.ALREADY_RUNNING
            }
        }
        // RESTART-FIX: unblock a stuck SingleThreadExecutor.
        // The previous worker could hang in native parking (futureDone=false),
        // then a new submit queued forever: no $ #2 / Reusing / Calling.
        // When facts say "dead" (port free + Closing after Done, or
        // generally not alive by facts) - wake native, interrupt mcThread
        // and wait for exit (native grace ~10s), so two JVM-mains never run
        // in parallel (port/world-lock). Death is checked via mcThread.isAlive,
        // NOT via isDone (cancel lies). When the thread is alive after 12s - refuse with
        // FAILED_DEFINITIVE ("restart the app"), recreate the executor
        // only once the thread is gone; a stale worker is ignored by seq.
        val prevFut: Future<*>? = synchronized(this) { future }
        // FAIL 18:09 FAKE-done: Future.cancel(true) sets isDone=true, but
        // the Java thread stays alive (mc-server hangs in native parking: ps
        // shows live mc-server + Reference Handler/Finalizer/Signal/Service,
        // while "prev worker finished while waiting futureDone=true" is a lie).
        // Starting a new Paper in the same JVM over a live one yields two Papers: the second
        // never binds + thread leak (Server thread, Craft Scheduler, Tuinity
        // Chunk, Netty). So death is checked via mcThread.isAlive, NOT via
        // isDone: a truly finished thread always clears mcThread=null in finally
        // BEFORE future becomes done; isDone + live mcThread = fake.
        // A live thread after cancel+12s means refuse with ERROR "restart the app".
        // STUCK-STOPPING FIX 2026-09-07: after graceful stop the state hung in
        // STOPPING + stopRequested=true, while restarts were refused forever:
        //  a) cancel(true) sets isDone=true INSTANTLY, but the old code waited
        //     `while (!prevFut.isDone)` - i.e. 0s - saw a live thread and
        //     refused with FAILED_DEFINITIVE; every next Start saw the same
        //     (futureDone=true + threadAlive) and also refused - deadlock;
        //  b) native parking could hang inside getAllStackTraces
        //     (Threads_lock held by the dying Server thread) - gate BEFORE enum did
        //     not help. Native fix: after the stop latch the enum is skipped entirely.
        // Now wait for THREAD DEATH (mcThread.isAlive), not future, up to 12s
        // (native grace 10s is enough to exit), and refuse when the thread
        // is still alive afterwards.
        // RESTART-CRASH FIX: never start a second Paper over a live worker
        // in the same JVM (the old abandonHungWorker path did exactly this:
        // new executor + seq bump while T1 still sat in native main with
        // g_in_server_main=1). The second main then died instantly with no
        // Paper output - native -11 (reuse refused) or -10 (OverlappingFileLock
        // on session.lock / dirty HotSpot static state: registries, Log4j,
        // Netty), and the 15s poll reported the generic "died right after
        // start". A live mcThread after 12s now refuses honestly
        // (FAILED_DEFINITIVE = restart the app), it never launches concurrently.
        fun refuseHungWorker(reason: String): StartResult {
            val hungId = try { "${mcThread?.name}/${mcThread?.id} alive=${mcThreadAlive()}" } catch (_: Exception) { "?" }
            try { Log.e(TAG, "refusing start: $reason hungThread=$hungId facts=${describeFacts()} lastErr=${PocketJvm.lastError()}") } catch (_: Exception) {}
            log("ERROR: previous server thread still alive after 12s ($reason).")
            log("A second Paper in the same JVM over a live one dies instantly (world lock / native -11).")
            log("Wait ~30s and press Start again; if it repeats - restart the app (one JVM per process).")
            return StartResult.FAILED_DEFINITIVE
        }
        // Facts dead? (port free + not alive) - then restart is safe.
        fun deadByFactsNow(): Boolean {
            return try {
                val p = this.serverPort
                val free = p <= 0 || com.pockethost.core.util.NetworkUtils.isPortAvailable(p)
                free && !isServerAliveByFacts()
            } catch (_: Exception) { false }
        }
        if (prevFut != null && prevFut.isDone && mcThreadAlive()) {
            // Fake-done on entry (previous cancel), but thread still alive.
            if (deadByFactsNow()) {
                log("WARN: previous thread still exiting (fake-done), waiting up to 12s...")
                val t0 = System.currentTimeMillis()
                while (mcThreadAlive() && System.currentTimeMillis() - t0 < 12_000) {
                    try { Thread.sleep(500) } catch (_: Exception) { break }
                }
                if (mcThreadAlive()) {
                    if (deadByFactsNow()) {
                        return refuseHungWorker("fake-done on entry, thread alive after 12s")
                    } else {
                log("Server still finishing (worker alive) - wait a few seconds and press Start again")
                        return StartResult.ALREADY_RUNNING
                    }
                } else {
                    log("Previous worker finished, starting new one...")
                    // After cancel the thread may keep the interrupt flag - a clean executor is safer.
                    try { executor.shutdownNow() } catch (_: Exception) {}
                    executor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "mc-server").apply { isDaemon = true }
                    }
                    synchronized(this) { future = null }
                }
            } else {
                // Already "done" on entry (previous cancel), but thread alive and facts alive.
                log("ERROR: previous server thread still alive. Restart the app: a new Paper over a live one in one JVM is forbidden.")
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
                // Thread parked but facts dead: latch native stop grace and wait
                // for THREAD DEATH. NOTE: no Future.cancel/interrupt here - a
                // worker blocked inside nativeLoadJVM ignores both, cancel only
                // poisons the future into fake-done (isDone=true + live thread)
                // and a stale interrupt flag breaks the worker's later sleeps.
                // If the thread outlives the 12s wait we refuse (no concurrent
                // second main in one JVM) - see refuseHungWorker.
                val hookOk = try { PocketJvm.requestStop() } catch (_: Exception) { false }
                if (!hookOk) log("WARN: old native lib without stop hook - reinstall the APK, otherwise stop/restart hangs.")
                // Wait for THREAD DEATH, not future: cancel makes isDone=true
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
                        return refuseHungWorker("hung prev, thread alive after 12s")
                    } else {
                        // Thread alive and facts came back (port busy) - a second
                        // Paper over a live world = OverlappingFileLock.
                log("Server still finishing (worker alive) - wait a few seconds and press Start again")
                        return StartResult.ALREADY_RUNNING
                    }
                } else {
                    log("Previous worker finished, starting new one...")
                    try { Log.i(TAG, "prev worker finished while waiting, facts=${describeFacts()}") } catch (_: Exception) {}
                    // After cancel the thread may keep the interrupt flag - a clean executor is safer.
                    try { executor.shutdownNow() } catch (_: Exception) {}
                    executor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "mc-server").apply { isDaemon = true }
                    }
                    synchronized(this) { future = null }
                }
            } else if (alivePrev || !portFreePrev) {
                // Worker hangs but facts alive (port busy) - second Paper
                // over a live world = OverlappingFileLockException. Do not start.
                log("Server still finishing (worker alive) - wait a few seconds and press Start again")
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
            log("ERROR: pockethost_jvm native library not built. Build the APK with NDK (CMake) or run via legacy mode.")
            synchronized(this) { state = State.STOPPED; serverId = null }
            return StartResult.FAILED_RETRYABLE
        }

        val runtimeHome = try {
            EmbeddedJava.runtimeHomeFor(javaPath)?.takeIf { it.isDirectory }
                ?: EmbeddedJava.bundledJreDir(context)
        } catch (_: Exception) { EmbeddedJava.bundledJreDir(context) }
        // Was `&&`: a partial JRE (folder exists, no bin/java) slipped into prepare
        // and died there with Definitive "no libjvm" instead of honest retryable.
        if (!File(runtimeHome, "bin/java").exists() || !runtimeHome.isDirectory) {
            log("ERROR: JRE not found in ${runtimeHome.absolutePath}")
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
        // -Djava.class.path is fixed at JVM creation: another classpath needs a process restart.
        val wantCp = params.classpath
        val liveCp = createdClasspath
        if (liveCp != null && liveCp != wantCp && PocketJvm.nativeIsJvmCreated()) {
            // Defensive: if the previous server looks alive - do not touch state,
            // a second Paper over a live world = OverlappingFileLockException.
            if (isRunning() || isServerAliveByFacts()) {
                log("Server already running - duplicate start skipped")
                return StartResult.ALREADY_RUNNING
            }
            log("ERROR: Stop the other server (different classpath, apk restart needed).")
            log("JVM already created with another classpath - second Minecraft in-process will not start.")
            log("was: $liveCp")
            log("want: $wantCp")
            synchronized(this) { state = State.STOPPED; serverId = null }
            return StartResult.FAILED_DEFINITIVE
        }
        // user.dir must point at the server folder (relative plugin/world paths)
        val jvmArgs = params.jvmArgs.toMutableList().also { list ->
            if (list.none { it.startsWith("-Duser.dir=") }) {
                list.add("-Duser.dir=" + workDir.absolutePath)
            }
        }

        ensureStdin()
        ensureStdout()
        // fd 0 must become the pipe BEFORE JVM creation (see ensureFdStdin),
        // otherwise HotSpot inherits /dev/null and console commands do nothing.
        val fdStdinOk = ensureFdStdin()
        if (!fdStdinOk) {
            log("WARN: fd-stdin pipe not installed - console commands may not reach the server")
        }

        val logFile = File(workDir, "server.log")
        try {
            workDir.mkdirs()
            if (!logFile.exists()) logFile.createNewFile()
        } catch (_: Exception) {}

        // Executor may be left shutdown after abandon/cancel - submit then throws
        // RejectedExecution and state hangs in STARTING. Recreate + retry.
        // (STUCK-STOPPING FIX: a failed submit now drops state to STOPPED.)
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
            // Stale worker (old boot after restart): the new start already
            // incremented startSeq - exit at once, do not touch state.
            fun isStale(): Boolean = try { startSeq != mySeq } catch (_: Exception) { false }
            try {
                appendLog(logFile, "\n\$ [in-process] ${serverJar.name} (main=${params.mainClass} seq=$mySeq thread=${myThread.name}/${myThread.id})\n")
                try { Log.i(TAG, "worker seq=$mySeq enter thread=${myThread.name} id=${myThread.id}") } catch (_: Exception) {}
                currentLog.set(try { FileOutputStream(logFile, true) } catch (_: Exception) { null })

                // Env like Mojo setImmutableEnvVars + chdir(gameDir)
                try { Os.setenv("HOME", workDir.absolutePath, true) } catch (_: Exception) {}
                try { Os.setenv("TMPDIR", context.cacheDir.absolutePath, true) } catch (_: Exception) {}
                try { Os.setenv("JAVA_HOME", runtimeHome.absolutePath, true) } catch (_: Exception) {}
                // CRITICAL for Bionic JRE: libnio.so pulls DT_NEEDED libnet.so, while HotSpot
                // loads them into a fresh linker-namespace (clns-N) initialized
                // from ENVIRON, not from the internal android_update_LD_LIBRARY_PATH cache.
                // Without this - UnsatisfiedLinkError libnet.so and cascading -8
                // (NoClassDefFoundError FileSystems$DefaultFileSystemHolder).
                // Like Mojo setImmutableEnvVars: LD_LIBRARY_PATH must be in environ
                // BEFORE dlopen(libjvm)/JNI_CreateJavaVM.
                val jreLdPath = PocketJvm.ldLibraryPathFor(params.vmPath)
                try { Os.setenv("LD_LIBRARY_PATH", jreLdPath, true) } catch (_: Exception) {}
                try {
                    PocketJvm.nativeSetLdLibraryPath(jreLdPath)
                } catch (e: Exception) {
                    Log.w(TAG, "setLdLibraryPath failed", e)
                }
                // Per-server tmp for JNA/JLine extraction (-Djna.tmpdir points here).
                // Shared cacheDir is cleaned by the OS mid-run and shared between
                // servers; a stale/locked dispatch there is one more reason for
                // "Could not initialize class com.sun.jna.Native" (OSHI warnings).
                try { File(workDir, "tmp").mkdirs() } catch (_: Exception) {}
                try {
                    val r = PocketJvm.nativeChdir(workDir.absolutePath)
                    if (r != 0) appendLog(logFile, "WARN: chdir failed ($r)\n")
                } catch (e: Exception) {
                    appendLog(logFile, "WARN: chdir: ${e.message}\n")
                }

                // If a newer start queued in the executor while standing here -
                // this worker is stale, never run a second JVM-main in parallel.
                if (isStale()) {
                appendLog(logFile, "[stale worker: newer start active, skipping launch]\n")
                    try { Log.w(TAG, "stale worker seq=$mySeq skipping launch, current=${startSeq}") } catch (_: Exception) {}
                    return@submit
                }
                synchronized(this) {
                    // Do not wipe a newer start that may have arrived while waiting for prepare.
                    if (startSeq != mySeq) return@submit
                    state = State.RUNNING
                }
                log("Server started (in-process JVM) jar=${serverJar.name} seq=$mySeq")
                // --- diagnostics before native: what really goes into the JVM ---
                Log.i(TAG, "start: jar=${serverJar.absolutePath} exists=${serverJar.isFile} size=${serverJar.length()}")
                Log.i(TAG, "start: wantCp=$wantCp")
                Log.i(TAG, "start: jvmArgs(${jvmArgs.size})=${jvmArgs.joinToString(" | ")}")
                Log.i(TAG, "start: jvmCreated=${PocketJvm.nativeIsJvmCreated()} createdCp=$createdClasspath main=${params.mainClass}")
                appendLog(logFile, "DBG start: jar size=${serverJar.length()} cp=$wantCp\n")
                appendLog(logFile, "DBG start: jvmCreated=${try { PocketJvm.nativeIsJvmCreated() } catch (_: Exception) { "?" }} createdCp=$createdClasspath\n")
                // STOP->START FIX 2026-09-07: marker "calling native" - if after
                // it logcat lacks reuse attach/Reusing/calling main, death
                // happened in AttachCurrentThread (old thread Threads_lock).
                appendLog(logFile, "DBG calling nativeLoadJVM seq=$mySeq (after this expect reuse attach/Reusing/calling main in logcat)...\n")
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
                // Paperclip safety-net: new native already parks by itself, but
                // old .so (or a thread-spawn race) returns 0 at once.
                // A returned main() is NOT counted death while facts are alive
                // (port + Done marker) and no stopRequested.
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
                                // Shutdown (Closing/Stopping after Done) means death, not
                                // an early Paperclip main return: do not wait 20s, exit at once
                                // (otherwise phase1 stop without stopRequested hangs +20s).
                                if (t.contains("Closing Server") || t.contains("All dimensions are saved") ||
                                    t.contains("Stopping the server") || t.contains("Stopping server")
                                ) {
                                    if (!hasFreshDone(t)) break
                                }
                            } catch (_: Exception) {}
                        }
                        if (isStale()) {
                            appendLog(logFile, "[stale worker: newer start, parking cancelled]\n")
                            return@submit
                        }
                        if (sawAlive) {
                            appendLog(logFile, "[main returned but server alive - waiting for threads...]\n")
                            Log.i(TAG, "main returned $exitCode but server alive, parking worker")
                            while (!stopRequested && !isStale() && isServerAliveByFacts()) {
                                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                            }
                            if (isStale()) {
                            appendLog(logFile, "[stale worker: newer start, exiting without state change]\n")
                                return@submit
                            }
                            if (stopRequested) {
                                // "stop" saves the world - let facts fade, but
                                // FAIL 17:44: do not blindly wait 30s for thread death:
                                // free port + Closing after Done = world saved,
                                // leftover workers (chunk) doze in background, exit at once.
                                var extra = 0
                                while (extra < 30000 && !isStale() && isServerAliveByFacts()) {
                                    try {
                                        if (isShutdownCompleteByLog()) break
                                    } catch (_: Exception) {}
                                    try { Thread.sleep(1000) } catch (_: Exception) { break }
                                    extra += 1000
                                }
                            }
                            appendLog(logFile, "[server wait finished, stopRequested=$stopRequested]\n")
                            exitCode = 0
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "post-main wait failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "JVM crashed", e)
                val nativeErr = PocketJvm.lastError().takeIf { it.isNotBlank() }
                appendLog(logFile, "\n[JVM ERROR: ${e.message}${if (nativeErr != null) " | $nativeErr" else ""}]\n")
                exitCode = -999
            } finally {
                try { currentLog.getAndSet(null)?.close() } catch (_: Exception) {}
                try { if (mcThread === Thread.currentThread()) mcThread = null } catch (_: Exception) {}
                // Stale worker (old boot after restart): state belongs to the new
                // start - touch nothing, only log.
                if (isStale()) {
                    try { appendLog(logFile, "[stale worker seq=$mySeq finished, ignoring (current=${startSeq})]\n") } catch (_: Exception) {}
                    try { Log.i(TAG, "stale worker seq=$mySeq finished code=$exitCode, ignoring (current seq=${startSeq})") } catch (_: Exception) {}
                    return@submit
                }
                // When facts say "alive" but no stop was asked - this is a false
                // Paperclip return: do NOT set STOPPED, keep RUNNING
                // and monitor in background until real death.
                val stillAlive = !stopRequested && !isStale() &&
                    (exitCode == 0 || exitCode == 999) && isServerAliveByFacts()
                if (stillAlive) {
                    appendLog(logFile, "[worker finished early, monitoring continues]\n")
                    Log.i(TAG, "worker finished early but server alive, continuing monitor")
                    synchronized(this) {
                        if (startSeq != mySeq) return@submit
                        state = State.RUNNING
                    }
                    // Capture id/start/seq so the monitor never wipes a foreign new start.
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
                                appendLog(logFile, "\n[process exited, code 0]\n")
                            } catch (_: Exception) {}
                            synchronized(this) {
                                // Do not wipe a foreign start (new serverId/startTime/seq).
                                if (startSeq != watchedSeq) return@synchronized
                                if (serverId != null && serverId != watchedId) return@synchronized
                                if (startTimeMs != watchedStart) return@synchronized
                                state = State.STOPPED
                                serverId = null
                                // Drop the flag so the next start never sees stale stopRequested (Bug A).
                                stopRequested = false
                            }
                            try { Log.i(TAG, "monitor finished, facts: ${describeFacts()}") } catch (_: Exception) {}
                        }, "mc-monitor").apply { isDaemon = true; start() }
                    } catch (_: Exception) {}
                    return@submit
                }
                val nativeErr = PocketJvm.lastError().takeIf { it.isNotBlank() }
                if (exitCode < 0 || exitCode > 128) {
                    // Negative codes are our internal ones (-8 main-class, -10 exception...),
                    // >128 is an emergency System.exit from the server. Explain both.
                    // RESTART-CRASH FIX: the same text goes to the app console
                    // via log() (timestamped [19:...] lines), not only to
                    // server.log - otherwise the 15s poll's "see the cause
                    // above" points at nothing the user can see.
                    appendLog(logFile, "[process exited, code $exitCode: ${describeCode(exitCode)}]\n")
                    if (nativeErr != null) appendLog(logFile, "[details: $nativeErr]\n")
                    try { log("[process exited, code $exitCode: ${describeCode(exitCode)}]") } catch (_: Exception) {}
                    if (nativeErr != null) try { log("[details: $nativeErr]") } catch (_: Exception) {}
                } else {
                    appendLog(logFile, "\n[process exited, code $exitCode]\n")
                }
                synchronized(this) {
                    // RESTART-FIX: stale worker never overwrites a newer start.
                    if (startSeq != mySeq) {
                        try { Log.i(TAG, "stale worker seq=$mySeq finished, keeping current seq=${startSeq} state") } catch (_: Exception) {}
                        return@synchronized
                    }
                    if (serverId != null && serverId != myId) return@synchronized
                    state = State.STOPPED
                    serverId = null
                    // Drop the flag on transition to STOPPED (Bug A: otherwise hangs until restart).
                    stopRequested = false
                }
                try { Log.i(TAG, "worker finished code=$exitCode seq=$mySeq, facts: ${describeFacts()}") } catch (_: Exception) {}
            }
        }
        } catch (e: Exception) {
            // Submit failed (shutdown executor etc) - do not hang in STARTING:
            // reset state so the next Start goes clean.
            try { Log.e(TAG, "executor submit failed", e) } catch (_: Exception) {}
            log("ERROR: failed to start server thread (${e.message}) - press Start again")
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
     * Light stop latch for phase1: sets stopRequested + wakes
     * native parking, WITHOUT sending "stop" (the caller sends it separately).
     * FAIL 17:44: phase1 only sent sendInput("stop"), the flag stayed false
     * for 60s and the stopRequested+portFree branch was dead.
     */
    fun requestStop(id: String): Boolean {
        if (id != null && serverId != null && serverId != id) return false
        stopRequested = true
        synchronized(this) {
            if (state == State.RUNNING || state == State.STARTING) state = State.STOPPING
        }
        // Wake the native parking loop (jvm_launcher.c): without this futureDone=false
        // hangs on chunk workers even after Closing + free port.
        try {
            val hookOk = PocketJvm.requestStop()
            if (!hookOk) Log.w(TAG, "requestStop($id): old native .so without stop hook - reinstall APK")
        } catch (_: Exception) {}
        try { Log.i(TAG, "requestStop($id) latched, facts: ${describeFacts()}") } catch (_: Exception) {}
        return true
    }

    /** Is "stop" a stop command (latches stopRequested, also for console input). */
    private fun isStopCommand(data: String): Boolean {
        return try {
            val t = data.trim().lowercase()
            t == "stop" || t == "stop\n" || t.startsWith("stop\n") || t.startsWith("stop ")
        } catch (_: Exception) { false }
    }

    fun sendInput(id: String, data: String): Boolean {
        if (!isRunning(id)) return false
        // Console "stop" (ConsoleActivity button, not just the Stop service) -
        // also a stop: otherwise the flag is false and parking waits for thread death.
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
        // fd pipe is the primary channel (read by HotSpot); ART pipe is the legacy duplicate.
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
     * Polite stop: send "stop" to server stdin and wait for REAL
     * thread completion (System.exit trap or death by facts).
     * "stop" goes on the fact of a live JVM, not the future.isDone flag, so
     * the console keeps working even in the Paperclip window (main already returned).
     * Forcibly killing the JVM thread is unsafe, hence no timeout killer.
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
        // Wake the native parking loop - otherwise futureDone=false hangs on
        // chunk workers after Closing (FAIL 17:44).
        try { PocketJvm.requestStop() } catch (_: Exception) {}
        // sendInput checks isRunning (facts) - during STOPPING facts are still
        // alive, so the command gets through. Send directly, order-independent.
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
            appendLog(File(wd, "server.log"), "\n[stopped by user]\n")
        }
        return true
    }

    /**
     * Waits for REAL completion: both worker-thread done and facts dead
     * (port free + no FRESH Done without Closing). Up to 60s to save the world.
     * true when stopped within timeoutMs.
     *
     * Bug B fix: when future is done AND port is free - return true at once,
     * without waiting on stale Done in the log (otherwise stubbornly hangs to timeout).
     * FAIL 17:44: shortcut (portFree && closingAfterDone) -> true REGARDLESS
     * of futureDone - a parked worker no longer holds await for 60s.
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
            // Main shortcut: worker exited + port free = stop, Done facts ignored.
            if (done && portFree) return true
            if (done && !isServerAliveByFacts()) return true
            // Shutdown by log (port free + Closing after Done, no fresh
            // start): world saved, chunk tail does not count - stopped,
            // even if future still parks in native.
            try {
                if (portFree && isShutdownCompleteByLog()) return true
            } catch (_: Exception) {}
            // Diagnostics every ~5s so the phone shows why it waits.
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
                    logFile.appendText("\n[log trimmed to 1MB]\n")
                } catch (_: Exception) {}
            }
            logFile.appendText(text)
        } catch (_: Exception) {}
    }
}
