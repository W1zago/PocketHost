package com.anserver.app.jvm

import android.content.Context
import android.system.Os
import android.util.Log
import com.anserver.app.embedded.EmbeddedJava
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
 * In-process хостинг Minecraft-сервера — аналог Mojo GameRunner.launchGame()
 * + JavaRunner.startJvm(), але для headless-сервера.
 *
 * Як у Mojo:
 *  - один JVM на процес (one-game model), потік блокується в nativeLoadJVM
 *    поки main() не повернеться або сервер не викличе System.exit();
 *  - System.exit() НЕ вбиває додаток: cpp-пастка (setjmp/longjmp) повертає
 *    код виходу в цей потік;
 *  - chdir(workDir) + LD_LIBRARY_PATH перед стартом.
 *
 * Чим відрізняється від старого EmbeddedProcessManager (ProcessBuilder):
 *  - жодного execve() -> імунітет до W^X error=13 на Android 10+,
 *    не потрібен /system/bin/linker костиль;
 *  - stdin/stdout це System.in/out JVM, а не труби форкнутого процесу,
 *    тому ConsoleActivity працює через ті ж sendInput/sendRaw.
 */
object InProcessServer {

    private const val TAG = "InProcessServer"

    enum class State { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile var state: State = State.STOPPED
        private set

    @Volatile var serverId: String? = null
        private set

    /** Classpath з яким створена жива JVM (-Djava.class.path фіксується при створенні). */
    @Volatile private var createdClasspath: String? = null

    // ---- Paperclip-liveness (main повертається одразу, сервер живе далі) ----
    // ART Thread.enumerate НЕ бачить HotSpot-потоки, тому живість визначаємо
    // за фактами: прив'язаний порт + маркер "Done (...s)!" в server.log.
    // Native-шар (jvm_launcher.c) теж паркується в очікуванні серверних
    // потоків, це — Kotlin safety-net для старого .so / гонки старту.
    @Volatile private var stopRequested = false
    @Volatile private var workDirRef: File? = null
    @Volatile private var serverPort: Int = -1
    @Volatile private var startTimeMs: Long = 0L

    // RESTART-FIX 2026-09-06: SingleThreadExecutor забивався — другий submit
    // чекав поки перший воркер висить в native parking (futureDone=false),
    // тому рестарт ніколи не стартував (нема $ №2 / Reusing / Calling).
    // var щоб можна було перестворити після hung + dead-by-log; mcThread для
    // interrupt; startSeq щоб stale-воркер не затирав новий старт в STOPPED.
    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mc-server").apply { isDaemon = true }
    }
    /** Живий чи потік mc-server (фейк-done детект: cancel ставить isDone, потік лишається). */
    private fun mcThreadAlive(): Boolean = try { mcThread?.isAlive == true } catch (_: Exception) { false }
    private var future: Future<*>? = null
    @Volatile private var mcThread: Thread? = null
    @Volatile private var startSeq: Long = 0L

    // ---- System.in/out redirection (ставиться один раз на процес) ----
    private var stdinInstalled = false
    private var stdinWriter: PipedOutputStream? = null
    private val stdinLock = Any()

    // OS-рівень stdin для HotSpot (див. ensureFdStdin): ART-труба вище
    // серверу невидима, а це — fd 0 процесу, який HotSpot обгортає у свій System.in.
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
            // autoFlush=true щоб логи з'являлись в ConsoleActivity без затримок
            val ps = PrintStream(tee, true, Charsets.UTF_8.name())
            System.setOut(ps)
            System.setErr(ps)
            stdoutInstalled = true
        } catch (e: Exception) {
            Log.e(TAG, "stdout redirect failed", e)
        }
    }

    /**
     * HotSpot — окрема VM зі своїм java.lang.System: ART System.setIn()
     * (ensureStdin) їй невидимий, тому консольний ввід ("stop", команди з
     * ConsoleActivity) до сервера не доходив і ввічлива зупинка висіла.
     *
     * Лікується як у Docker: OS-труба, чий read-кінець підмінює fd 0 процесу
     * ДО створення JVM. HotSpot обгортає fd 0 у свій System.in, тож sendInput/
     * sendRaw пишуть сюди (і дублюють у ART-трубу для сумісності).
     * Викликати до першого nativeLoadJVM; ідемпотентно, потікобезпечно.
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
     * FAIL 17:44: запаркований воркер (futureDone=false) з портом вільним і
     * Closing після Done — мертвий, навіть якщо native ще не повернувся
     * (chunk-воркери висять). true = точно мертвий за логом+портом.
     * Нова інкарнація (свіжий старт-маркер після Closing) мертвою не вважається.
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
            // Свіжий старт-маркер після Closing = новий бут, а не смерть.
            val startPos = lastPosOf(tail, START_MARKERS)
            if (startPos > stopPos) return false
            true
        } catch (_: Exception) { false }
    }

    /**
     * Швидка перевірка смерті ПЕРЕД fast-path future (FAIL 17:44):
     * порт вільний + (stopRequested | Closing після Done) = мертвий,
     * futureDone=false ігнорується. Під час свіжого STARTING-вікна (<30с)
     * старий Closing в логу застарілий — смертю не вважаємо, старт рахується живим.
     */
    private fun isDeadByFactsFast(): Boolean {
        return try {
            if (state == State.STARTING &&
                System.currentTimeMillis() - startTimeMs < 30_000
            ) return false
            val p = serverPort
            if (p <= 0) return false
            val free = try {
                com.anserver.core.util.NetworkUtils.isPortAvailable(p)
            } catch (_: Exception) { return false }
            if (!free) return false
            // Порт вільний звідси далі.
            // Ввічлива зупинка попрошена і сокет вже відпущено — світ досипається.
            if (stopRequested) return true
            // Closing після Done без свіжого старту — цей бут мертвий.
            isShutdownCompleteByLog()
        } catch (_: Exception) { false }
    }

    fun isRunning(id: String? = null): Boolean {
        if (id != null && serverId != null && serverId != id) return false
        // СПОЧАТКУ факти смерті: запаркований воркер з вільним портом і
        // Closing після Done — false, не доходячи до future fast-path.
        try {
            if (isDeadByFactsFast()) return false
        } catch (_: Exception) {}
        // Швидкий шлях: воркер-нитка ще в nativeLoadJVM (норма для Vanilla
        // і для пропатченого native що паркується на Paperclip-потоках).
        try {
            val f = future
            if (f != null && !f.isDone) {
                if (id == null || serverId == null || serverId == id) return true
            }
        } catch (_: Exception) {}
        // Повільний шлях: Paperclip.main() повернувся з 0 (старий .so або
        // гонка), але сервер живий — факти: порт + Done-маркер.
        if (id != null && serverId != null && serverId != id) return false
        if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING) {
            if (isServerAliveByFacts()) return true
        }
        return false
    }

    /** Чи прив'язаний порт (зайнятий = сервер слухає = добре). */
    private fun isPortBound(port: Int): Boolean {
        if (port <= 0) return false
        return try {
            !com.anserver.core.util.NetworkUtils.isPortAvailable(port)
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
        tail.contains("[процес завершено")

    /**
     * Маркери ввічливого шатдауну MC: якщо будь-який з них ПІСЛЯ останнього
     * Done — цей бут мертвий (Баг A: stale Done після Closing Server).
     * Маркер зупинено-користувачем пише stop() до шатдауну, маркер
     * очікування-завершено пише воркер після паркування — обидва теж
     * означають що Done вже не свіжий.
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
        "[зупинено користувачем]",
        "[очікування сервера завершено"
    )
    private val DEATH_MARKERS = listOf("[процес завершено")
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
     * Свіжий Done: останній "Done (" після останнього Closing/stop-маркера
     * і після останньої смерті, з "For help, type" слідом (теж після них).
     * Порівнюємо ПОЗИЦІЇ в хвості логу: перезапуск дописує новий Done після
     * старого Closing, тож старий Done (до Closing) не вважається живим.
     */
    private fun hasFreshDone(tail: String): Boolean {
        try {
            val donePos = tail.lastIndexOf("Done (")
            if (donePos < 0) return false
            val stopPos = lastPosOf(tail, STOP_MARKERS)
            val deathPos = lastPosOf(tail, DEATH_MARKERS)
            if (donePos < stopPos || donePos < deathPos) return false
            // "For help, type" йде наступним рядком після Done — має бути теж після Closing/смерті.
            val helpPos = tail.indexOf("For help, type", donePos)
            if (helpPos < 0) return false
            if (helpPos < stopPos || helpPos < deathPos) return false
            return true
        } catch (_: Exception) { return false }
    }

    /** Діагностика для logcat/консолі: чому факти вважають живим/мертвим (Баг B).
     * Публічна: ServerService phase1 логує її кожні 5с щоб Observer бачив
     * donePos/stopPos/portFree/stopReq. */
    fun describeFacts(): String {
        return try {
            val fDone = try {
                val ff = future; ff == null || ff.isDone
            } catch (_: Exception) { true }
            val port = serverPort
            val portFree = try {
                port <= 0 || com.anserver.core.util.NetworkUtils.isPortAvailable(port)
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
     * Сервер живий за фактами (видимими і з ART). Порядок (фікс Багів A/B):
     *  1. порт прив'язаний -> однозначно живий (ми його зайняли при старті);
     *  2. воркер done + порт вільний -> однозначно мертвий (навіть зі stale Done);
     *  3. stopRequested + порт вільний -> мертвий (шатдаун, сокет вже відпущено);
     *  4. свіжий Done (після останнього Closing/смерті) -> живий;
     *  5. свіжий старт-маркер у вікні старту -> живий;
     *  6. інакше мертвий.
     */
    private fun isServerAliveByFacts(): Boolean {
        try {
            if (!PocketJvm.nativeIsJvmCreated()) return false
        } catch (_: Exception) { return false }
        val port = serverPort
        val portBound = port > 0 && isPortBound(port)
        if (portBound) return true
        // Порт вільний + воркер вийшов = STOPPED навіть зі stale Done в логу.
        // (Paperclip-паркування тримає future НЕ done, сюди не потрапляє.)
        try {
            val f = future
            if (f != null && f.isDone) return false
            if (f == null && state == State.STOPPED) return false
        } catch (_: Exception) {}
        // Ввічлива зупинка попрошена і порт вже вільний — MC відпустив сокет,
        // світ досипається; чекати stale Done не треба (інакше await висить 60с).
        if (stopRequested) return false
        try {
            val wd = workDirRef ?: return false
            val tail = readTail(File(wd, "server.log"), 64 * 1024)
            if (tail.isEmpty()) {
                // Нема логу але воркер мертвий і порт вільний -> мертвий.
                // Якщо state==STARTING і стартували <30с тому — ще вантажиться.
                return state == State.STARTING &&
                    System.currentTimeMillis() - startTimeMs < 30_000
            }
            if (hasDeathMarker(tail)) {
                // Смерть зафіксована воркером; порт вільний (перевірено вище)
                // -> точно мертвий. Порт зайнятий сюди не доходить (ранній return).
                return false
            }
            if (hasFreshDone(tail)) return true
            val startPos = lastPosOf(tail, START_MARKERS)
            if (startPos >= 0) {
                val stopPos = lastPosOf(tail, STOP_MARKERS)
                val deathPos = lastPosOf(tail, DEATH_MARKERS)
                // Старт-маркер свіжий (після останнього Closing/смерті) і вікно старту.
                if (startPos > stopPos && startPos > deathPos &&
                    System.currentTimeMillis() - startTimeMs < 180_000
                ) return true
            }
            // Стартуємо але маркерів ще нема (перші секунди): вважаємо живим
            // поки нема смерті/шатдауну в хвості.
            if (state == State.STARTING &&
                System.currentTimeMillis() - startTimeMs < 60_000
            ) {
                // Є Closing від попереднього бута в тому ж логу і свіжого
                // старт-маркера після нього нема (перевірено вище) — це stale.
                if (lastPosOf(tail, STOP_MARKERS) >= 0) return false
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /** Результат спроби старту: чи має сенс fallback на legacy exec. */
    enum class StartResult {
        STARTED,            // потік запущено (сама гра ще вантажиться)
        ALREADY_RUNNING,    // no-op: живий сервер вже є, другий Paper не стартуємо
        FAILED_RETRYABLE,   // можна пробувати legacy exec (нема нативної lib тощо)
        FAILED_DEFINITIVE   // legacy теж не допоможе (битий jar, стара Java) — одразу ERROR
    }

    /** Людське пояснення негативних кодів nativeLoadJVM (див. cpp/jvm_launcher.c). */
    private fun describeCode(code: Int): String = when (code) {
        999 -> "main повернувся, але потоки сервера живі (Paperclip-режим) — це не смерть, воркер чекає далі."
        -8 -> "головний клас не завантажився. Найчастіше: серверу потрібна новіша Java (див. рядок з exception вище — шукай UnsupportedClassVersionError), або jar несумісний з цим classpath."
        -9 -> "в класі нема методу main(String[]). Jar пошкоджений або це не серверний jar."
        -10 -> "виняток у main() сервера. Причина — в рядку з exception вище."
        // STOP->START FIX 2026-09-07: чесна відмова reuse-гілки (jvm_launcher.c):
        // старий mc-потік ще в main/parking, новий Paper НЕ стартував (жодного
        // рядка від нього в server.log і не буде). Це НЕ крах: зачекай виходу
        // старого (grace ~10с) і тисни Start ще раз.
        -11 -> "попередній серверний потік ще завершується (stop/grace в процесі), новий Paper не стартував. Зачекай кілька секунд і тисни Start ще раз."
        -4 -> "JVM не створилась (несумісні прапорці або брак пам'яті для купи)."
        -1, -2 -> "не вдалося відкрити libjvm.so. JRE битий — перевстанови tools/jre."
        else -> "див. рядок з exception/JVM ПОМИЛКА вище."
    }

    /** Поточний старт-лічильник (для stale-поллів: чужий seq = новіший бут). */
    fun currentSeq(): Long = try { startSeq } catch (_: Exception) { -1L }

    /**
     * Старт сервера в JVM-потоці. Блокує лише фоновий потік.
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
        /** Шлях до java з ensureJava: дім JRE беремо з нього (jre21 для 1.20.5+). */
        javaPath: String? = null
    ): StartResult {
        synchronized(this) {
            if (isRunning()) {
                log("Сервер вже запущений (in-process, один за раз) — повторний старт пропущено")
                return StartResult.ALREADY_RUNNING
            }
            // Порт зайнятий живим сервером (напр. після перезапуску UI):
            // не стартуємо другий Paper поверх залоченого світу.
            if (serverPort > 0 && isPortBound(serverPort)) {
                log("Сервер вже запущений (порт $serverPort зайнятий) — повторний старт пропущено")
                return StartResult.ALREADY_RUNNING
            }
        }
        // RESTART-FIX: розблокування забитого SingleThreadExecutor.
        // Попередній воркер міг зависнути в native parking (futureDone=false),
        // новий submit тоді чергується вічно: нема $ №2 / Reusing / Calling.
        // Якщо факти кажуть "мертвий" (порт вільний + Closing після Done, або
        // взагалі не alive за фактами) — будимо native, перериваємо mcThread
        // і чекаємо виходу (native grace ~10с), щоб не гнати два JVM-main
        // паралельно (порт/world-lock). Смерть перевіряємо за mcThread.isAlive,
        // НЕ за isDone (cancel бреше). Якщо потік живий після 12с — відмова з
        // FAILED_DEFINITIVE ("перезапусти додаток"), executor перестворюємо
        // лише коли потоку вже нема; stale-воркер ігнорується за seq.
        val prevFut: Future<*>? = synchronized(this) { future }
        // FAIL 18:09 FAKE-done: Future.cancel(true) ставить isDone=true, але
        // Java-потік лишається живим (mc-server висить в native parking: ps
        // показує живий mc-server + Reference Handler/Finalizer/Signal/Service,
        // а "prev worker finished while waiting futureDone=true" — брехня).
        // Старт нового Paper в тій же JVM поверх живого дає два Paper: другий
        // не биндиться + витік тредів (Server thread, Craft Scheduler, Tuinity
        // Chunk, Netty). Тому смерть перевіряємо за mcThread.isAlive, а НЕ за
        // isDone: справжнє завершення завжди чистить mcThread=null у finally
        // ДО того як future стане done; isDone + живий mcThread = фейк.
        // Живий потік після cancel+12с — відмова з ERROR "перезапусти додаток".
        // STUCK-STOPPING FIX 2026-09-07: після graceful stop стан зависав у
        // STOPPING + stopRequested=true, а рестарти відмовляли назавжди:
        //  а) cancel(true) ставить isDone=true МИТТЄВО, а старий код чекав
        //     `while (!prevFut.isDone)` — тобто 0с — бачив живий потік і
        //     відмовляв FAILED_DEFINITIVE; кожен наступний Start бачив те саме
        //     (futureDone=true + threadAlive) і теж відмовляв — deadlock;
        //  б) native parking міг вішатись всередині getAllStackTraces
        //     (Threads_lock тримає вмираючий Server-потік) — gate ДО enum не
        //     рятував. Native-фікс: після stop latch enum скипається взагалі.
        // Тепер чекаємо СМЕРТІ ПОТОКУ (mcThread.isAlive), а не future, до 12с
        // (native grace 10с встигає вийти), і відмовляємо лише якщо факти ЖИВІ.
        // Мертвий за фактами + живий потік після 12с — abandon (новий executor
        // + seq bump щоб hung-воркер став stale), а не вічний "рестарт апки".
        fun abandonHungWorker(reason: String) {
            val hungId = try { "${mcThread?.name}/${mcThread?.id} alive=${mcThreadAlive()}" } catch (_: Exception) { "?" }
            try { Log.w(TAG, "abandonHungWorker: $reason hungThread=$hungId facts=${describeFacts()}") } catch (_: Exception) {}
            try { executor.shutdownNow() } catch (_: Exception) {}
            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "mc-server").apply { isDaemon = true }
            }
            // Hung-воркер коли вийде з native — побачить чужий seq і не затре новий старт.
            synchronized(this) {
                startSeq++
                future = null
            }
        }
        // Факти мертві? (порт вільний + не alive) — тоді рестарт безпечний.
        fun deadByFactsNow(): Boolean {
            return try {
                val p = this.serverPort
                val free = p <= 0 || com.anserver.core.util.NetworkUtils.isPortAvailable(p)
                free && !isServerAliveByFacts()
            } catch (_: Exception) { false }
        }
        if (prevFut != null && prevFut.isDone && mcThreadAlive()) {
            // Фейк-done на вході (попередній cancel), але потік ще живий.
            if (deadByFactsNow()) {
                log("WARN: попередній потік ще виходить (фейк-done), чекаю до 12с...")
                val t0 = System.currentTimeMillis()
                while (mcThreadAlive() && System.currentTimeMillis() - t0 < 12_000) {
                    try { Thread.sleep(500) } catch (_: Exception) { break }
                }
                if (mcThreadAlive()) {
                    if (deadByFactsNow()) {
                        log("WARN: старий потік не вийшов за 12с, але сервер мертвий за фактами — стартую на новому треді (старий буде ігноровано)")
                        abandonHungWorker("fake-done на вході, потік живий після 12с")
                    } else {
                        log("Сервер ще завершується (воркер живий) — зачекай кілька секунд і тисни Start ще раз")
                        return StartResult.ALREADY_RUNNING
                    }
                } else {
                    log("Попередній потік завершено, стартую новий...")
                    // Після cancel тред міг лишити interrupt-флаг — чистий executor безпечніше.
                    try { executor.shutdownNow() } catch (_: Exception) {}
                    executor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "mc-server").apply { isDaemon = true }
                    }
                    synchronized(this) { future = null }
                }
            } else {
                // Вже "done" на вході (попередній cancel), але потік живий і факти живі.
                log("ERROR: попередній серверний потік досі живий. Перезапусти додаток: новий Paper поверх живого в одній JVM заборонено.")
                try { Log.e(TAG, "refusing start: fake-done prev worker, mcThread alive + facts alive. facts=${describeFacts()}") } catch (_: Exception) {}
                return StartResult.FAILED_DEFINITIVE
            }
        }
        if (prevFut != null && !prevFut.isDone) {
            val portFreePrev = try {
                val p = this.serverPort
                p <= 0 || com.anserver.core.util.NetworkUtils.isPortAvailable(p)
            } catch (_: Exception) { true }
            val deadByLogPrev = try { isShutdownCompleteByLog() } catch (_: Exception) { false }
            val alivePrev = try { isServerAliveByFacts() } catch (_: Exception) { true }
            Log.i(TAG, "start pre-check hung prev: portFree=$portFreePrev deadByLog=$deadByLogPrev aliveByFacts=$alivePrev facts=${describeFacts()}")
            if (portFreePrev && !alivePrev) {
                log("WARN: попередній воркер завис (futureDone=false) але сервер мертвий за фактами — розблоковую...")
                try { PocketJvm.requestStop() } catch (_: Exception) {}
                try { prevFut.cancel(true) } catch (_: Exception) {}
                try { mcThread?.interrupt() } catch (_: Exception) {}
                // Чекаємо СМЕРТІ ПОТОКУ, не future: cancel робить isDone=true
                // одразу, старий `while (!isDone)` чекав 0с і відмовляв.
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
                        log("WARN: старий потік не вийшов за 12с, але сервер мертвий за фактами — стартую на новому треді (старий буде ігноровано)")
                        abandonHungWorker("hung prev, потік живий після 12с")
                    } else {
                        // Потік живий і факти ожили (порт зайнято) — другий
                        // Paper поверх живого світу = OverlappingFileLock.
                        log("Сервер ще завершується (воркер живий) — зачекай кілька секунд і тисни Start ще раз")
                        return StartResult.ALREADY_RUNNING
                    }
                } else {
                    log("Попередній воркер завершено, стартую новий...")
                    try { Log.i(TAG, "prev worker finished while waiting, facts=${describeFacts()}") } catch (_: Exception) {}
                    // Після cancel тред міг лишити interrupt-флаг — чистий executor безпечніше.
                    try { executor.shutdownNow() } catch (_: Exception) {}
                    executor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "mc-server").apply { isDaemon = true }
                    }
                    synchronized(this) { future = null }
                }
            } else if (alivePrev || !portFreePrev) {
                // Воркер висить але факти живі (порт зайнятий) — другий Paper
                // поверх живого світу = OverlappingFileLockException. Не стартуємо.
                log("Сервер ще завершується (воркер живий) — зачекай кілька секунд і тисни Start ще раз")
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
            log("ERROR: нативна бібліотека pockethost_jvm не зібрана. Збери APK з NDK (CMake) або запускай через legacy-режим.")
            synchronized(this) { state = State.STOPPED; serverId = null }
            return StartResult.FAILED_RETRYABLE
        }

        val runtimeHome = try {
            EmbeddedJava.runtimeHomeFor(javaPath)?.takeIf { it.isDirectory }
                ?: EmbeddedJava.bundledJreDir(context)
        } catch (_: Exception) { EmbeddedJava.bundledJreDir(context) }
        // Було `&&`: частковий JRE (папка є, bin/java нема) пролазив у prepare
        // і вмирав там з Definitive "no libjvm" замість чесного retryable.
        if (!File(runtimeHome, "bin/java").exists() || !runtimeHome.isDirectory) {
            log("ERROR: JRE не знайдено в ${runtimeHome.absolutePath}")
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
        // -Djava.class.path фіксується при створенні JVM: інший classpath = потрібен рестарт процесу.
        val wantCp = params.classpath
        val liveCp = createdClasspath
        if (liveCp != null && liveCp != wantCp && PocketJvm.nativeIsJvmCreated()) {
            // Захисно: якщо попередній сервер дивом живий — не чіпаємо стан,
            // повторний Paper поверх живого світу = OverlappingFileLockException.
            if (isRunning() || isServerAliveByFacts()) {
                log("Сервер вже запущений — повторний старт пропущено")
                return StartResult.ALREADY_RUNNING
            }
            log("ERROR: JVM вже створена з іншим classpath. Перезапусти додаток щоб запустити інший сервер in-process.")
            log("було: $liveCp")
            log("треба: $wantCp")
            synchronized(this) { state = State.STOPPED; serverId = null }
            return StartResult.FAILED_DEFINITIVE
        }
        // user.dir має вказувати на папку сервера (відносні шляхи плагінів/світу)
        val jvmArgs = params.jvmArgs.toMutableList().also { list ->
            if (list.none { it.startsWith("-Duser.dir=") }) {
                list.add("-Duser.dir=" + workDir.absolutePath)
            }
        }

        ensureStdin()
        ensureStdout()
        // fd 0 має стати трубою ДО створення JVM (див. ensureFdStdin),
        // інакше HotSpot успадкує /dev/null і команди консолі не дійдуть.
        val fdStdinOk = ensureFdStdin()
        if (!fdStdinOk) {
            log("WARN: fd-stdin труба не встановилась — команди консолі можуть не доходити до сервера")
        }

        val logFile = File(workDir, "server.log")
        try {
            workDir.mkdirs()
            if (!logFile.exists()) logFile.createNewFile()
        } catch (_: Exception) {}

        // Executor міг лишитись shutdown після abandon/cancel — submit тоді кине
        // RejectedExecution і стан зависне у STARTING. Перестворюємо + ретрай.
        // (STUCK-STOPPING FIX: провал submit тепер скидає стан у STOPPED.)
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
            // Stale-воркер (попередній бут) після рестарту: новий старт вже
            // інкрементував startSeq — виходимо одразу, стан не чіпаємо.
            fun isStale(): Boolean = try { startSeq != mySeq } catch (_: Exception) { false }
            try {
                appendLog(logFile, "\n\$ [in-process] ${serverJar.name} (main=${params.mainClass} seq=$mySeq thread=${myThread.name}/${myThread.id})\n")
                try { Log.i(TAG, "worker seq=$mySeq enter thread=${myThread.name} id=${myThread.id}") } catch (_: Exception) {}
                currentLog.set(try { FileOutputStream(logFile, true) } catch (_: Exception) { null })

                // Оточення як Mojo setImmutableEnvVars + chdir(gameDir)
                try { Os.setenv("HOME", workDir.absolutePath, true) } catch (_: Exception) {}
                try { Os.setenv("TMPDIR", context.cacheDir.absolutePath, true) } catch (_: Exception) {}
                try { Os.setenv("JAVA_HOME", runtimeHome.absolutePath, true) } catch (_: Exception) {}
                // КРИТИЧНО для Bionic JRE: libnio.so тягне DT_NEEDED libnet.so, а HotSpot
                // вантажить їх у свіжий linker-namespace (clns-N), який ініціалізується
                // з ENVIRON, а не з внутрішнього кешу android_update_LD_LIBRARY_PATH.
                // Без цього — UnsatisfiedLinkError libnet.so і каскадний -8
                // (NoClassDefFoundError FileSystems$DefaultFileSystemHolder).
                // Як Mojo setImmutableEnvVars: LD_LIBRARY_PATH має бути в environ
                // ДО dlopen(libjvm)/JNI_CreateJavaVM.
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

                // Якщо поки стояли в черзі executor вже прийшов новіший старт —
                // цей воркер stale, не будемо гнати другий JVM-main паралельно.
                if (isStale()) {
                    appendLog(logFile, "[stale worker: новіший старт активний, пропускаю запуск]\n")
                    try { Log.w(TAG, "stale worker seq=$mySeq skipping launch, current=${startSeq}") } catch (_: Exception) {}
                    return@submit
                }
                synchronized(this) {
                    // Не затираємо новіший старт що міг прийти поки чекали prepare.
                    if (startSeq != mySeq) return@submit
                    state = State.RUNNING
                }
                log("Сервер запущено (in-process JVM) jar=${serverJar.name} seq=$mySeq")
                // --- діагностика перед native: що реально йде в JVM ---
                Log.i(TAG, "start: jar=${serverJar.absolutePath} exists=${serverJar.isFile} size=${serverJar.length()}")
                Log.i(TAG, "start: wantCp=$wantCp")
                Log.i(TAG, "start: jvmArgs(${jvmArgs.size})=${jvmArgs.joinToString(" | ")}")
                Log.i(TAG, "start: jvmCreated=${PocketJvm.nativeIsJvmCreated()} createdCp=$createdClasspath main=${params.mainClass}")
                appendLog(logFile, "DBG start: jar size=${serverJar.length()} cp=$wantCp\n")
                appendLog(logFile, "DBG start: jvmCreated=${try { PocketJvm.nativeIsJvmCreated() } catch (_: Exception) { "?" }} createdCp=$createdClasspath\n")
                // STOP->START FIX 2026-09-07: маркер "викликаю native" — якщо після
                // нього в logcat нема reuse attach/Reusing/calling main, смерть
                // сталася в AttachCurrentThread (Threads_lock старого потоку).
                appendLog(logFile, "DBG calling nativeLoadJVM seq=$mySeq (після цього чекай reuse attach/Reusing/calling main в logcat)...\n")
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
                // Paperclip safety-net: новий native вже паркується сам, але
                // старий .so (або гонка появи потоків) поверне 0 одразу.
                // Повернення main() НЕ вважаємо смертю поки живі факти
                // (порт + Done-маркер) і нема stopRequested.
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
                                // Шатдаун (Closing/Stopping після Done) — це смерть, а не
                                // раннє Paperclip-повернення main: не чекати 20с, вийти одразу
                                // (інакше phase1 stop без stopRequested висить +20с).
                                if (t.contains("Closing Server") || t.contains("All dimensions are saved") ||
                                    t.contains("Stopping the server") || t.contains("Stopping server")
                                ) {
                                    if (!hasFreshDone(t)) break
                                }
                            } catch (_: Exception) {}
                        }
                        if (isStale()) {
                            appendLog(logFile, "[stale worker: новіший старт, паркування скасовано]\n")
                            return@submit
                        }
                        if (sawAlive) {
                            appendLog(logFile, "[main повернувся, але сервер живий — чекаю завершення потоків...]\n")
                            Log.i(TAG, "main returned $exitCode but server alive, parking worker")
                            while (!stopRequested && !isStale() && isServerAliveByFacts()) {
                                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                            }
                            if (isStale()) {
                                appendLog(logFile, "[stale worker: новіший старт, виходжу без зміни стану]\n")
                                return@submit
                            }
                            if (stopRequested) {
                                // "stop" зберігає світ — даємо фактам згаснути, але
                                // FAIL 17:44: не чекаємо смерті потоків 30с всліпу:
                                // порт вільний + Closing після Done = світ збережено,
                                // решта-воркери (chunk) досипають у фоні, виходимо одразу.
                                var extra = 0
                                while (extra < 30000 && !isStale() && isServerAliveByFacts()) {
                                    try {
                                        if (isShutdownCompleteByLog()) break
                                    } catch (_: Exception) {}
                                    try { Thread.sleep(1000) } catch (_: Exception) { break }
                                    extra += 1000
                                }
                            }
                            appendLog(logFile, "[очікування сервера завершено, stopRequested=$stopRequested]\n")
                            exitCode = 0
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "post-main wait failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "JVM crashed", e)
                val nativeErr = PocketJvm.lastError().takeIf { it.isNotBlank() }
                appendLog(logFile, "\n[JVM ПОМИЛКА: ${e.message}${if (nativeErr != null) " | $nativeErr" else ""}]\n")
                exitCode = -999
            } finally {
                try { currentLog.getAndSet(null)?.close() } catch (_: Exception) {}
                try { if (mcThread === Thread.currentThread()) mcThread = null } catch (_: Exception) {}
                // Stale-воркер (старий бут після рестарту): стан належить новому
                // старту — нічого не чіпаємо, тільки лог.
                if (isStale()) {
                    try { appendLog(logFile, "[stale worker seq=$mySeq finished, ignoring (current=${startSeq})]\n") } catch (_: Exception) {}
                    try { Log.i(TAG, "stale worker seq=$mySeq finished code=$exitCode, ignoring (current seq=${startSeq})") } catch (_: Exception) {}
                    return@submit
                }
                // Якщо факти кажуть "живий" а stop не просили — це хибне
                // повернення Paperclip: НЕ ставимо STOPPED, лишаємо RUNNING
                // і моніторимо у фоні до реальної смерті.
                val stillAlive = !stopRequested && !isStale() &&
                    (exitCode == 0 || exitCode == 999) && isServerAliveByFacts()
                if (stillAlive) {
                    appendLog(logFile, "[worker завершився зарано, моніторинг продовжено]\n")
                    Log.i(TAG, "worker finished early but server alive, continuing monitor")
                    synchronized(this) {
                        if (startSeq != mySeq) return@submit
                        state = State.RUNNING
                    }
                    // Захоплюємо id/старт/seq щоб монітор не затирав чужий новий старт.
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
                                appendLog(logFile, "\n[процес завершено, код 0]\n")
                            } catch (_: Exception) {}
                            synchronized(this) {
                                // Не затираємо чужий старт (новий serverId/startTime/seq).
                                if (startSeq != watchedSeq) return@synchronized
                                if (serverId != null && serverId != watchedId) return@synchronized
                                if (startTimeMs != watchedStart) return@synchronized
                                state = State.STOPPED
                                serverId = null
                                // Скидаємо флаг щоб наступний старт не бачив stale stopRequested (Баг A).
                                stopRequested = false
                            }
                            try { Log.i(TAG, "monitor finished, facts: ${describeFacts()}") } catch (_: Exception) {}
                        }, "mc-monitor").apply { isDaemon = true; start() }
                    } catch (_: Exception) {}
                    return@submit
                }
                val nativeErr = PocketJvm.lastError().takeIf { it.isNotBlank() }
                if (exitCode < 0 || exitCode > 128) {
                    // Негативні коди — наші внутрішні (-8 main-клас, -10 виняток...),
                    // >128 — аварійний System.exit від сервера. Пояснюємо обидва.
                    appendLog(logFile, "[процес завершено, код $exitCode: ${describeCode(exitCode)}]\n")
                    if (nativeErr != null) appendLog(logFile, "[деталі: $nativeErr]\n")
                } else {
                    appendLog(logFile, "\n[процес завершено, код $exitCode]\n")
                }
                synchronized(this) {
                    // RESTART-FIX: stale-воркер не затирає новіший старт.
                    if (startSeq != mySeq) {
                        try { Log.i(TAG, "stale worker seq=$mySeq finished, keeping current seq=${startSeq} state") } catch (_: Exception) {}
                        return@synchronized
                    }
                    if (serverId != null && serverId != myId) return@synchronized
                    state = State.STOPPED
                    serverId = null
                    // Скидаємо флаг при переході в STOPPED (Баг A: інакше висить до рестарту).
                    stopRequested = false
                }
                try { Log.i(TAG, "worker finished code=$exitCode seq=$mySeq, facts: ${describeFacts()}") } catch (_: Exception) {}
            }
        }
        } catch (e: Exception) {
            // Submit не вдався (shutdown executor тощо) — не висіти у STARTING:
            // скидаємо стан щоб наступний Start пройшов чисто.
            try { Log.e(TAG, "executor submit failed", e) } catch (_: Exception) {}
            log("ERROR: не вдалося запустити серверний потік (${e.message}) — тисни Start ще раз")
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
     * Легкий latch зупинки для phase1: ставить stopRequested + будить
     * native-паркування, БЕЗ відправки "stop" (її шле викликач окремо).
     * FAIL 17:44: phase1 слала тільки sendInput("stop"), флаг лишався false
     * 60с і гілка stopRequested+portFree була мертва.
     */
    fun requestStop(id: String): Boolean {
        if (id != null && serverId != null && serverId != id) return false
        stopRequested = true
        synchronized(this) {
            if (state == State.RUNNING || state == State.STARTING) state = State.STOPPING
        }
        // Будимо native parking loop (jvm_launcher.c): без цього futureDone=false
        // висить на chunk-воркерах навіть після Closing+вільного порту.
        try { PocketJvm.requestStop() } catch (_: Exception) {}
        try { Log.i(TAG, "requestStop($id) latched, facts: ${describeFacts()}") } catch (_: Exception) {}
        return true
    }

    /** Чи є "stop" командою зупинки (latch stopRequested і для консольного вводу). */
    private fun isStopCommand(data: String): Boolean {
        return try {
            val t = data.trim().lowercase()
            t == "stop" || t == "stop\n" || t.startsWith("stop\n") || t.startsWith("stop ")
        } catch (_: Exception) { false }
    }

    fun sendInput(id: String, data: String): Boolean {
        if (!isRunning(id)) return false
        // Консольний "stop" (кнопка ConsoleActivity, не тільки Stop-сервіс) —
        // теж зупинка: інакше флаг false і паркування чекає смерті потоків.
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
        // fd-труба — основний канал (читає HotSpot); ART-труба — legacy-дубль.
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
     * Ввічлива зупинка: шлемо "stop" в stdin сервера і чекаємо РЕАЛЬНОГО
     * завершення потоків (System.exit-пастка або смерть за фактами).
     * "stop" йде за фактом живого JVM, а не прапорця future.isDone, щоб
     * консоль працювала і в Paperclip-вікні (main вже повернувся).
     * Примусово вбити JVM-нитку безпечно неможливо, тому без таймаута-кілера.
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
        // Будимо native parking loop — інакше futureDone=false висить на
        // chunk-воркерах після Closing (FAIL 17:44).
        try { PocketJvm.requestStop() } catch (_: Exception) {}
        // sendInput перевіряє isRunning (факти) — під час STOPPING факти ще
        // живі, тож команда дійде. Шлемо напряму щоб не залежати від порядку.
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
            appendLog(File(wd, "server.log"), "\n[зупинено користувачем]\n")
        }
        return true
    }

    /**
     * Чекає РЕАЛЬНОГО завершення: і воркер-нитка done, і факти мертві
     * (порт вільний + нема СВІЖОГО Done без Closing). До 60с для збереження світу.
     * true якщо зупинився за timeoutMs.
     *
     * Баг B фікс: якщо future done І порт вільний — повертаємо true одразу,
     * не чекаючи stale Done в логу (інакше вперто висить до таймауту).
     * FAIL 17:44: shortcut (portFree && closingAfterDone) -> true НЕЗАЛЕЖНО
     * від futureDone — запаркований воркер більше не тримає await 60с.
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
                p <= 0 || com.anserver.core.util.NetworkUtils.isPortAvailable(p)
            } catch (_: Exception) { true }
            // Головний shortcut: воркер вийшов + порт вільний = стоп, факти Done ігноруємо.
            if (done && portFree) return true
            if (done && !isServerAliveByFacts()) return true
            // Шатдаун за логом (порт вільний + Closing після Done, без свіжого
            // старту): світ збережено, chunk-хвіст не рахується — стоп є,
            // навіть якщо future ще паркується в native.
            try {
                if (portFree && isShutdownCompleteByLog()) return true
            } catch (_: Exception) {}
            // Діагностика кожні ~5с щоб на телефоні було видно чому чекає.
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
                p <= 0 || com.anserver.core.util.NetworkUtils.isPortAvailable(p)
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
                    logFile.appendText("\n[лог обрізано до 1MB]\n")
                } catch (_: Exception) {}
            }
            logFile.appendText(text)
        } catch (_: Exception) {}
    }
}
