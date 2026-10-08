package com.pockethost.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pockethost.app.PocketHostApplication
import com.pockethost.app.R
import com.pockethost.app.embedded.EmbeddedBootstrap
import com.pockethost.app.embedded.EmbeddedJava
import com.pockethost.app.embedded.EmbeddedPaths
import com.pockethost.app.embedded.EmbeddedProcessManager
import com.pockethost.app.jvm.InProcessServer
import com.pockethost.app.ui.MainActivity
import com.pockethost.app.util.ServerProperties
import com.pockethost.core.model.ServerStatus
import com.pockethost.core.model.ServerType
import com.pockethost.core.util.NetworkUtils
import com.pockethost.plugins.minecraft.MinecraftServerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in server launch service.
 * WITHOUT external Termux, WITHOUT RUN_COMMAND, WITHOUT SAF.
 * Everything runs in the app private dir via EmbeddedProcessManager.
 */
class ServerService : Service() {

    private val binder = ServerBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    // Cache of work dirs: logs must go to the real server folder
    // (including custom ones), never guessed via probe.
    private val workDirCache = ConcurrentHashMap<String, File>()
    // Servers currently going through long downloads (jar/JRE) outside the lock.
    // Guarded by startMutex.
    private val startingIds = mutableSetOf<String>()
    // Unified with MainActivity/CreateServerActivity: safe lazy access.
    // Service cannot finish(), so fallback is a local repository.
    private val repository by lazy {
        runCatching { PocketHostApplication.serverRepository }.getOrElse { e ->
            Log.e("ServerService", "repo not ready, using fallback", e)
            com.pockethost.core.repository.ServerRepository(this@ServerService)
        }
    }

    inner class ServerBinder : Binder() {
        fun getService(): ServerService = this@ServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                serverId?.let { startServer(it) }
            }
            ACTION_STOP_SERVER -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                serverId?.let { stopServer(it) }
            }
        }
        return START_STICKY
    }

    private fun startServer(serverId: String) {
        serviceScope.launch {
            // 1. Fast phase under lock: checks + slot reserve, no network I/O.
            val reservedWorkDir: File? = startMutex.withLock {
                try {
                    Log.d("ServerService", "startServer embedded $serverId")
                    if (startingIds.contains(serverId)) {
                        log(serverId, "Server is already launching")
                        return@withLock null
                    }
                    val server = repository.getServer(serverId).first()
                    if (server == null) {
                        Log.e("ServerService", "Server not found $serverId")
                        return@withLock null
                    }
                    if (server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING) {
                        log(serverId, "Server already running")
                        return@withLock null
                    }
                    if (EmbeddedProcessManager.runningCount() >= EmbeddedProcessManager.MAX_SERVERS) {
                        log(serverId, "Stop the first server (max ${EmbeddedProcessManager.MAX_SERVERS})")
                        // never block the mutex on persist
                        serviceScope.launch { repository.updateServerStatus(serverId, ServerStatus.ERROR) }
                        return@withLock null
                    }
                    // C4: 1 JVM per process - second Minecraft with another classpath will not start.
                    // Honest refusal instead of a silent FAILED_DEFINITIVE deep in InProcessServer.
                    if (server.type == ServerType.MINECRAFT) {
                        val (canStart, reason) = try {
                            EmbeddedProcessManager.canStartMinecraft(serverId)
                        } catch (_: Exception) { true to "" }
                        if (!canStart) {
                            log(serverId, reason)
                            Log.w("ServerService", "canStartMinecraft denied $serverId: $reason")
                            try {
                                android.widget.Toast.makeText(
                                    this@ServerService, reason, android.widget.Toast.LENGTH_LONG
                                ).show()
                            } catch (_: Exception) {}
                            serviceScope.launch { repository.updateServerStatus(serverId, ServerStatus.ERROR) }
                            return@withLock null
                        }
                    }
                    if (EmbeddedProcessManager.isRunning(serverId)) {
                        log(serverId, "Server already running")
                        // FAIL 17:44: silent reserve blocked restart ("Server already
                        // running", port idle). Facts in logcat show why.
                        try {
                            Log.i(
                                "ServerService",
                                "start reserve blocked $serverId facts=${InProcessServer.describeFacts()}"
                            )
                        } catch (_: Exception) {}
                        return@withLock null
                    }
                    startingIds.add(serverId)
                    // STARTING status is set at once so UI reacts
                    serviceScope.launch { repository.updateServerStatus(serverId, ServerStatus.STARTING) }

                    val workDir = EmbeddedPaths.resolveWorkDir(
                        this@ServerService, serverId,
                        server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                    )
                    workDir
                } catch (e: Exception) {
                    Log.e("ServerService", "startServer reserve error", e)
                    log(serverId, "EXCEPTION: ${e.message}")
                    null
                }
            }
            if (reservedWorkDir == null) return@launch

            // 2. Slow phase OUTSIDE the lock: files, jar/JRE downloads, process launch.
            try {
                val server = repository.getServer(serverId).first()
                if (server == null) {
                    startMutex.withLock { startingIds.remove(serverId) }
                    return@launch
                }
                // Bug A: stale isRunning after a previous stop (Done without Closing)
                // blocked restart in the same process. Facts are already fixed in
                // InProcessServer (fresh Done + port-first), here only diagnostics.
                try {
                    val alive = EmbeddedProcessManager.isRunning(serverId) ||
                        InProcessServer.isRunning(serverId)
                    val portFree = try {
                        NetworkUtils.isPortAvailable(server.port)
                    } catch (_: Exception) { true }
                    Log.i(
                        "ServerService",
                        "startMinecraft pre-check $serverId alive=$alive portFree=$portFree status=${server.status} facts=${InProcessServer.describeFacts()}"
                    )
                } catch (_: Exception) {}
                reservedWorkDir.mkdirs()
                workDirCache[serverId] = reservedWorkDir
                EmbeddedBootstrap.ensureServerDirReady(reservedWorkDir)

                // Storage may vanish after server creation (ejected SD,
                // revoked all-files access). Never start in a foreign/empty
                // folder - refuse honestly.
                val storedUri = server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                when (EmbeddedPaths.kindOfUri(storedUri)) {
                    EmbeddedPaths.StorageKind.EXTERNAL -> {
                        if (!EmbeddedPaths.isExternalStorageAvailable(this@ServerService)) {
                            log(serverId, "ERROR: external storage unavailable (ejected SD card?). Insert it or move the server to internal storage.")
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            return@launch
                        }
                    }
                    EmbeddedPaths.StorageKind.CUSTOM -> {
                        if (!EmbeddedPaths.hasAllFilesAccess(this@ServerService)) {
                            log(serverId, "ERROR: no access to the server folder. Grant 'all files access' in system settings and retry.")
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            return@launch
                        }
                    }
                    else -> {}
                }
                if (!EmbeddedPaths.isDirWritable(reservedWorkDir)) {
                    log(serverId, "ERROR: folder not writable: ${reservedWorkDir.absolutePath}")
                    repository.updateServerStatus(serverId, ServerStatus.ERROR)
                    return@launch
                }

                log(serverId, "Starting server (built-in terminal)...")
                log(serverId, "Server: ${server.name} type=${server.type} port=${server.port}")
                log(serverId, "Folder: ${reservedWorkDir.absolutePath}")

                try {
                    val stat = StatFs(reservedWorkDir.absolutePath)
                    if (stat.availableBytes < 512L * 1024 * 1024) {
                        log(serverId, "Warning: low space <512MB")
                    }
                } catch (_: Exception) {}

                when (server.type) {
                    ServerType.MINECRAFT -> startMinecraft(serverId, reservedWorkDir)
                    ServerType.UBUNTU_SERVER -> startUbuntu(serverId, reservedWorkDir)
                }
            } catch (e: Exception) {
                Log.e("ServerService", "startServer error", e)
                log(serverId, "EXCEPTION: ${e.message}")
                try { repository.updateServerStatus(serverId, ServerStatus.ERROR) } catch (_: Exception) {}
            } finally {
                startMutex.withLock { startingIds.remove(serverId) }
            }
        }
    }

    private suspend fun startMinecraft(serverId: String, workDir: File) {
        val server = repository.getServer(serverId).first() ?: return

        // eula + properties in the internal folder.
        // online-mode / view-distance are taken from the saved config ON EVERY
        // start (patch the existing file, never touch other keys) - so sliders
        // work for servers created before they existed.
        try {
            val eula = File(workDir, "eula.txt")
            if (!eula.exists()) eula.writeText("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).\neula=true")
            patchServerProperties(
                workDir,
                server.port,
                server.config.effectiveOnlineMode,
                server.config.effectiveViewDistance,
                server.name
            )
        } catch (_: Exception) {}

        // Provision jar into the internal folder (plain File I/O, no SAF)
        // Default 1.20.4: bundled JRE17 runs only Minecraft <=1.20.4
        // (1.20.5+ needs Java 21 - see advisory below).
        val version = server.config.minecraftVersion ?: "1.20.4"
        val loader = server.config.minecraftLoader
        if (EmbeddedJava.minJavaForMinecraft(version) > 17) {
            log(serverId, "WARNING: $version needs Java 21+, but bundled JRE is Java 17. Start will fail anyway; 1.20.4 recommended (or import JRE 21 manually into tools/jre21/ via Files).")
        }
        // Busy port: when it is OUR live server - no-op, otherwise ERROR.
        // Without this a second Start launched another Paper over a locked
        // world -> OverlappingFileLockException.
        try {
            if (!NetworkUtils.isPortAvailable(server.port)) {
                if (EmbeddedProcessManager.isRunning(serverId) ||
                    InProcessServer.isRunning(serverId)
                ) {
                    log(serverId, "Server already running (port ${server.port} busy with it) - duplicate start skipped")
                    try {
                        val cur = repository.getServer(serverId).first()
                        if (cur?.status != ServerStatus.RUNNING) {
                            repository.updateServerStatus(serverId, ServerStatus.RUNNING)
                        }
                    } catch (_: Exception) {}
                    return
                }
                log(serverId, "ERROR: port ${server.port} is already busy with another process. Change the port in server settings.")
                repository.updateServerStatus(serverId, ServerStatus.ERROR)
                return
            }
        } catch (_: Exception) {}
        val manager = MinecraftServerManager(workDir.parent ?: workDir.absolutePath)
        val foundBefore = EmbeddedProcessManager.findExistingJar(
            workDir, EmbeddedProcessManager.guessJarName(loader?.name)
        )
        // File presence is not integrity: a truncated jar is wiped via re-provision.
        val hasJarBefore = foundBefore != null &&
            MinecraftServerManager.isValidServerJar(File(workDir, foundBefore))
        if (!hasJarBefore) {
            log(serverId, "Downloading ${loader ?: "PAPER"} $version (~50-100 MB)...")
            val ok = try {
                manager.provisionIfNeeded(
                    workDir.absolutePath, version, loader,
                    server.config.maxMemory, server.config.minMemory, server.port
                ) { msg -> log(serverId, msg) }
            } catch (e: Exception) {
                log(serverId, "Provision error: ${e.message}")
                false
            }
            if (!ok) {
                val stillMissing = EmbeddedProcessManager.findExistingJar(
                    workDir, EmbeddedProcessManager.guessJarName(loader?.name)
                ) == null
                if (stillMissing) {
                    log(serverId, "ERROR: failed to download jar. Open Console/Files for the cause (HTTP code) above. With internet on - try VANILLA/PURPUR/FABRIC.")
                    repository.updateServerStatus(serverId, ServerStatus.ERROR)
                    return
                }
            }
        }

        // Java from the bundled runtime (no Termux!)
        log(serverId, "Looking for bundled Java...")
        val javaPath = EmbeddedBootstrap.ensureJava(this@ServerService, version) { msg -> log(serverId, msg) }
        if (javaPath == null) {
            log(serverId, "ERROR: Java unavailable. See the cause above (HTTP code). Details in Console.")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        val preferred = EmbeddedProcessManager.guessJarName(loader?.name)
        val jar = EmbeddedProcessManager.findExistingJar(workDir, preferred)
        if (jar == null) {
            log(serverId, "ERROR: jar not found in ${workDir.absolutePath}")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        // W^X: direct exec from the app dir is banned by Android policy for
        // targetSdk 29+ (error=13). Main path is in-process JVM like in MojoLauncher
        // (dlopen libjvm.so + JNI_CreateJavaVM, no exec at all).
        // Legacy exec via system linker stays only as fallback for retryable failures.
        val jarFile = File(workDir, jar)
        val inProcessResult = try {
            log(serverId, "Starting in-process JVM (Mojo approach, no exec)...")
            InProcessServer.start(
                this@ServerService, serverId, jarFile, workDir,
                server.config.maxMemory, server.config.minMemory,
                server.config.javaArgs, version,
                { msg -> log(serverId, msg) },
                server.port,
                javaPath
            )
        } catch (e: Exception) {
            Log.e("ServerService", "in-process start failed, fallback to exec", e)
            log(serverId, "In-process start failed (${e.message}), trying legacy exec...")
            InProcessServer.StartResult.FAILED_RETRYABLE
        }
        if (inProcessResult == InProcessServer.StartResult.ALREADY_RUNNING) {
            // No-op: live server already there (Paperclip main returned, but facts
            // alive). Status stays RUNNING, second Paper is not started.
            log(serverId, "Server already running - duplicate start skipped")
            try {
                val cur = repository.getServer(serverId).first()
                if (cur?.status != ServerStatus.RUNNING) {
                    repository.updateServerStatus(serverId, ServerStatus.RUNNING)
                }
            } catch (_: Exception) {}
            startLivenessPoll(serverId)
            return
        }
        if (inProcessResult == InProcessServer.StartResult.STARTED) {
            repository.updateTermuxExecutionId(serverId, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
            repository.updateServerStatus(serverId, ServerStatus.RUNNING)
            log(serverId, "Server started (in-process JVM) jar=$jar")
            startLivenessPoll(serverId)
            // Fast crash (bad Java, broken world, repeated port bind):
            // do not hang in RUNNING - cause is already in the log above, pin ERROR.
            // Paperclip window accounted: isRunning() is now facts-based
            // (port + Done marker), so a live server never lands here.
            // Delay raised to 15s - slow phones load the world long.
            serviceScope.launch {
                delay(15000)
                try {
                    val s = repository.getServer(serverId).first()
                    if (s?.status == ServerStatus.RUNNING && !EmbeddedProcessManager.isRunning(serverId)) {
                        // Double-check facts before ERROR: port or Done
                        // mean "alive, just a slow start".
                        var looksAlive = false
                        try {
                            if (!NetworkUtils.isPortAvailable(server.port)) {
                                looksAlive = true
                            }
                        } catch (_: Exception) {}
                        if (!looksAlive) {
                            try {
                                val lf = File(workDir, "server.log")
                                if (lf.isFile) {
                                    val len = lf.length()
                                    val tailLen = if (len > 64 * 1024) 64 * 1024 else len.toInt()
                                    RandomAccessFile(lf, "r").use { raf ->
                                        raf.seek(len - tailLen)
                                        val buf = ByteArray(tailLen)
                                        var tot = 0
                                        while (tot < tailLen) {
                                            val r = raf.read(buf, tot, tailLen - tot)
                                            if (r <= 0) break
                                            tot += r
                                        }
                                        val tail = String(buf, 0, tot)
                                        // Bug A: only a FRESH Done (after the last
                                        // Closing/stop/death), otherwise a stale Done
                                        // of the previous boot masks a crash as "alive".
                                        val donePos = tail.lastIndexOf("Done (")
                                        fun lastOf(vararg ms: String): Int {
                                            var b = -1
                                            for (m in ms) {
                                                val p = tail.lastIndexOf(m)
                                                if (p > b) b = p
                                            }
                                            return b
                                        }
                                        val stopPos = lastOf(
                                            "Stopping the server", "Stopping server",
                                            "Saving worlds", "All dimensions are saved",
                                            "Closing Server", "Server stopped",
                                            "[stopped by user]",
                                            "[server wait finished"
                                        )
                                        val deathPos = tail.lastIndexOf("[process exited")
                                        if (donePos >= 0 && donePos > stopPos && donePos > deathPos &&
                                            tail.indexOf("For help, type", donePos) > stopPos
                                        ) {
                                            looksAlive = true
                                        } else {
                                            val startPos = lastOf(
                                                "Starting org.bukkit",
                                                "Starting minecraft server",
                                                "Loading libraries",
                                                "This server is running"
                                            )
                                            if (startPos >= 0 && startPos > stopPos &&
                                                startPos > deathPos
                                            ) looksAlive = true
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        if (!looksAlive) {
                            // STOP->START FIX 2026-09-07: honest refusal -11 (old
                            // thread still exiting, new Paper never started) is not
                            // a crash: STOPPED + ask for retry, not ERROR.
                            var sawBusyPrev = false
                            try {
                                val lf = File(workDir, "server.log")
                                if (lf.isFile && lf.length() > 0) {
                                    val n = if (lf.length() > 8192) 8192 else lf.length().toInt()
                                    RandomAccessFile(lf, "r").use { raf ->
                                        raf.seek(lf.length() - n)
                                        val b = ByteArray(n)
                                        var t = 0
                                        while (t < n) {
                                            val r = raf.read(b, t, n - t)
                                            if (r <= 0) break
                                            t += r
                                        }
                                        if (String(b, 0, t).contains("code -11")) sawBusyPrev = true
                                    }
                                }
                            } catch (_: Exception) {}
                            if (sawBusyPrev) {
                                log(serverId, "Previous thread still finishing - press Start again.")
                                repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                            } else {
                                log(serverId, "Server died right after start - see the cause above (often: busy port or bad Java).")
                                repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            }
                        } else {
                            log(serverId, "Server still starting (slow device) - waiting...")
                        }
                    }
                } catch (_: Exception) {}
            }
            return
        }
        if (inProcessResult == InProcessServer.StartResult.FAILED_DEFINITIVE) {
        // Broken jar / old Java / busy JVM - legacy exec cannot fix.
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        log(serverId, "Fallback: legacy exec via system linker...")
        val launch = com.pockethost.app.embedded.EmbeddedJava.probeJava(javaPath) { msg -> log(serverId, msg) }
        if (!launch.works) {
            log(serverId, "ERROR: Java launches neither directly nor via system linker (details above).")
            log(serverId, "Cause - exec ban from the app dir (Android 10+, targetSdk 29+). Options: manual JRE with system java, or a targetSdk 28 build.")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        if (launch.useLinker) {
            log(serverId, "Java: ${launch.version} (via system linker - direct launch banned by Android policy)")
        } else {
            log(serverId, "Java: ${launch.version} (direct launch)")
        }

        val cmd = EmbeddedProcessManager.buildMinecraftCommand(
            javaPath, jar, server.config.maxMemory, server.config.minMemory,
            server.config.javaArgs, launch.prefix
        )
        val startEnv = mutableMapOf("MC_PORT" to server.port.toString())
        // Environment the probe worked with (LD_LIBRARY_PATH/JAVA_HOME for linker).
        startEnv.putAll(launch.env)
        log(serverId, "Start: java -Xmx${server.config.maxMemory}M -jar $jar nogui")

        val ok = EmbeddedProcessManager.start(
            this@ServerService, serverId, cmd, workDir,
            startEnv
        )
        if (!ok) {
            log(serverId, "ERROR: failed to start process")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        repository.updateTermuxExecutionId(serverId, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        repository.updateServerStatus(serverId, ServerStatus.RUNNING)
        log(serverId, "Server started (built-in terminal) jar=$jar")
        startLivenessPoll(serverId)
    }

    /**
     * Creates server.properties when missing, otherwise pinpoint-updates keys
     * controlled by UI (online-mode, view-distance, server-port).
     * Leaves the rest of the file (player motd, world seed etc) alone.
     * Logic moved to [com.pockethost.app.util.ServerProperties].
     */
    private fun patchServerProperties(
        workDir: File,
        port: Int,
        onlineMode: Boolean,
        viewDistance: Int,
        serverName: String
    ) {
        ServerProperties.patch(
            workDir, port, onlineMode, viewDistance, serverName
        )
    }

    private suspend fun startUbuntu(serverId: String, workDir: File) {
        val ip = getDeviceIp()
        // Full Ubuntu when available; otherwise auto-install now (same UX
        // as the JRE: download on Start with progress in the log). The
        // built-in shell stays as fallback so Start never dead-ends.
        val ubuntuReady = EmbeddedBootstrap.ensureUbuntu(this@ServerService) { msg -> log(serverId, msg) }
        if (!ubuntuReady) {
            if (!com.pockethost.app.embedded.EmbeddedUbuntu.isSupported()) {
                log(serverId, com.pockethost.app.embedded.EmbeddedUbuntu.supportHint())
            } else {
                log(serverId, "Ubuntu is not installed, downloading now (~30MB)...")
                val installed = EmbeddedBootstrap.installUbuntuFull(this@ServerService) { msg -> log(serverId, msg) }
                if (!installed) {
                    log(serverId, "Ubuntu download did not finish. Starting built-in shell instead; press Start again once install completes.")
                }
            }
        }
        val useProot = com.pockethost.app.embedded.EmbeddedUbuntu.isInstalled(this@ServerService)
        val ok = if (useProot) {
            log(serverId, "Starting Ubuntu 22.04 via proot...")
            EmbeddedProcessManager.startUbuntuViaProot(this@ServerService, serverId, workDir)
        } else {
            EmbeddedProcessManager.startInteractiveShell(this@ServerService, serverId, workDir)
        }
        if (!ok) {
            log(serverId, "ERROR: failed to start shell")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        // Fresh baseline AFTER the launch lines above: our own pre-flight
        // ("proot pre-flight...", "PROOT_TMPDIR=...", "$ <cmd>") also grows
        // server.log, so a baseline taken before launch makes
        // waitForLiveOutput true on our own output - the false Running.
        // Only bytes the live process prints past this point count.
        val freshBaseline = EmbeddedProcessManager.logLength(workDir)
        // Honest RUNNING: ProcessBuilder.start() succeeding means nothing -
        // proot dies in <1s on EACCES/ENOENT. Wait for a live handle plus
        // the first bytes in server.log (shell prompt/banner). Only then
        // continue; a fast death becomes ERROR with the cause above.
        val live = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EmbeddedProcessManager.waitForLiveOutput(
                    serverId, workDir, freshBaseline, 15_000L
                ) { msg -> log(serverId, msg) }
            }
        } catch (_: Exception) { EmbeddedProcessManager.isRunning(serverId) }
        if (!live || !EmbeddedProcessManager.isRunning(serverId)) {
            log(serverId, "ERROR: shell died right after start - see proot pre-flight / [process exited] above.")
            log(serverId, "Typical causes: loader EACCES (fixed via nativeLibraryDir), missing usr/bin/env, broken rootfs - reinstall Ubuntu from server settings.")
            try { EmbeddedProcessManager.stop(serverId) } catch (_: Exception) {}
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        // Proof of a LIVE shell, not just a lingering handle: a unique echo
        // token must round-trip through stdin -> shell -> server.log while
        // the process stays alive. The old fire-and-forget
        // `echo POCKETHOST_ALIVE` never verified the answer, and a constant
        // token could match a stale previous boot - both lied as Running.
        val token = "POCKETHOST_ALIVE_" + System.currentTimeMillis()
        try {
            EmbeddedProcessManager.sendInput(serverId, "echo $token")
        } catch (_: Exception) {}
        val answered = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                EmbeddedProcessManager.waitForMarker(
                    serverId, workDir, token, 15_000L
                ) { msg -> log(serverId, msg) }
            }
        } catch (_: Exception) { false }
        if (!answered || !EmbeddedProcessManager.isRunning(serverId)) {
            log(serverId, "ERROR: shell started but did not answer (no $token in server.log) - stdin is not wired or the shell died. See [process exited] above.")
            try { EmbeddedProcessManager.stop(serverId) } catch (_: Exception) {}
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        repository.updateTermuxExecutionId(serverId, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        repository.updateServerStatus(serverId, ServerStatus.RUNNING)
        if (useProot) {
            log(serverId, "\nUbuntu 22.04 started (proot, user-space, no root). IP: $ip\n")
            log(serverId, "Essential packages (python3, nodejs, git, curl...) install in background on first start. /root/server mirrors the server folder.\n")
        } else {
            log(serverId, "\nBuilt-in Linux shell started. IP: $ip\n")
            log(serverId, "Type commands in the console below (sh -i). Full Ubuntu rootfs is available in server settings.\n")
        }
        startLivenessPoll(serverId)
        // First-start provision (user choice: maximum set, background, console
        // stays usable). Marker-gated: success writes .packages-installed,
        // anything else retries on the next Start. Never blocks RUNNING.
        if (useProot) {
            serviceScope.launch {
                try {
                    if (com.pockethost.app.embedded.EmbeddedUbuntu.needsEssentialPackages(this@ServerService)) {
                        EmbeddedBootstrap.ensureEssentialPackages(this@ServerService, workDir) { msg -> log(serverId, msg) }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopServer(serverId: String) {
        serviceScope.launch {
            // Polite stop via stdin + waiting OUTSIDE the lock to avoid blocking start().
            // In-process server needs more time (world save + System.exit
            // trap + Paperclip parking), so wait up to 60s.
            // "stop" goes on the fact of a live JVM (isRunning is facts-based),
            // otherwise the console refused in the Paperclip window.
            val wasRunning = EmbeddedProcessManager.isRunning(serverId)
            if (wasRunning) {
                try {
                    Log.i(
                        "ServerService",
                        "stopServer: sending stop to $serverId facts=${InProcessServer.describeFacts()}"
                    )
                    // FAIL 17:44: phase1 MUST set stopRequested at once.
                    // sendInput("stop") alone never sets the flag -> 60s with stopReq=false,
                    // the stopRequested+portFree branch dead, fast-path held alive=true.
                    try {
                        InProcessServer.requestStop(serverId)
                    } catch (_: Exception) {}
                    val stopCmd = try {
                        val s = repository.getServer(serverId).first()
                        if (s?.type == ServerType.UBUNTU_SERVER) "exit" else "stop"
                    } catch (_: Exception) {
                        "stop"
                    }
                    EmbeddedProcessManager.sendInput(serverId, stopCmd)
                    var waited = 0
                    while (waited < 60000 && EmbeddedProcessManager.isRunning(serverId)) {
                        delay(1000)
                        waited += 1000
                        // Diagnostics every 5s (Bug B + FAIL 17:44): facts from
                        // donePos/stopPos so Observer sees Closing after Done,
                        // not just "alive=true".
                        if (waited % 5000 == 0) {
                            try {
                                Log.i(
                                    "ServerService",
                                    "stopServer wait ${waited}ms $serverId alive=${EmbeddedProcessManager.isRunning(serverId)} facts=${InProcessServer.describeFacts()}"
                                )
                            } catch (_: Exception) {}
                        }
                    }
                    if (EmbeddedProcessManager.isRunning(serverId)) {
                        log(serverId, "Server did not answer stop in 60s - waiting in background (world may be saving)")
                        try {
                            Log.w("ServerService", "stopServer timeout $serverId still alive after 60s")
                        } catch (_: Exception) {}
                    } else {
                        try {
                            Log.i("ServerService", "stopServer graceful in ${waited}ms $serverId")
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            startMutex.withLock {
                try {
                    Log.d("ServerService", "stopServer embedded $serverId")
                    if (!EmbeddedProcessManager.isRunning(serverId)) {
                        // Here we land after a polite stop in phase1 too
                        // (free port + Closing): success, not "not running".
                        if (wasRunning) {
                            log(serverId, "Server stopped")
                        } else {
                            log(serverId, "Server not running")
                        }
                        repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                        return@withLock
                    }
                    repository.updateServerStatus(serverId, ServerStatus.STOPPING)
                    log(serverId, "Server stopping...")
                    EmbeddedProcessManager.stop(serverId)
                    repository.updateTermuxExecutionId(serverId, null)
                    repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                    log(serverId, "Server stopped")
                } catch (e: Exception) {
                    Log.e("ServerService", "stop error", e)
                    log(serverId, "Error stopping: ${e.message}")
                }
            }
        }
    }

    private fun log(serverId: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $message\n"
        // write straight into the file of the real work folder (no SAF!)
        try {
            val dir = workDirCache[serverId]
                ?: EmbeddedPaths.lookupServerDir(this@ServerService, serverId)
            EmbeddedProcessManager.appendLogToDir(dir, line)
        } catch (_: Exception) {}
    }

    private fun getDeviceIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) return host
                    }
                }
            }
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: continue
                }
            }
            "192.168.0.100"
        } catch (_: Exception) { "192.168.0.100" }
    }

    private fun startLivenessPoll(serverId: String) {
        // STOP->START FIX 2026-09-07: old poll killed a fresh start.
        // Two guards: (a) stale-poll guard by seq - the previous boot poll
        // silently exits when seq changes (new boot monitors its own poll);
        // (b) 30s grace - fresh Paper writes markers/binds the port up to ~30s, while
        // in the first seconds facts do not exist yet (no Done/port) - do not kill.
        // Without this: silence 10s into a new start -> "Process finished" -> STOPPED.
        val mySeq = try { InProcessServer.currentSeq() } catch (_: Exception) { -1L }
        serviceScope.launch {
            var emptyCount = 0
            var ticks = 0
            while (true) {
                delay(5000)
                try {
                    val server = repository.getServer(serverId).first()
                    if (server == null || server.status != ServerStatus.RUNNING) break
                    try {
                        if (InProcessServer.currentSeq() != mySeq) break
                    } catch (_: Exception) {}
                    ticks++
                    if (!EmbeddedProcessManager.isRunning(serverId)) {
                        if (ticks <= 6) continue
                        emptyCount++
                        if (emptyCount >= 2) {
                            log(serverId, "Process finished")
                            repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                            break
                        }
                    } else {
                        emptyCount = 0
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, PocketHostApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PocketHost")
            .setContentText("Built-in terminal running")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // H6: stopAll BEFORE cancel - process shutdown must finish writing logs/
        // sending stop before scope cancellation (otherwise background launches die early).
        try { EmbeddedProcessManager.stopAll() } catch (_: Exception) {}
        try { serviceScope.cancel() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_SERVER = "com.pockethost.app.START_SERVER"
        const val ACTION_STOP_SERVER = "com.pockethost.app.STOP_SERVER"
        const val EXTRA_SERVER_ID = "server_id"
    }
}
