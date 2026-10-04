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
 * Р’Р±СѓРґРѕРІР°РЅРёР№ СЃРµСЂРІС–СЃ Р·Р°РїСѓСЃРєСѓ СЃРµСЂРІРµСЂС–РІ.
 * Р‘Р•Р— Р·РѕРІРЅС–С€РЅСЊРѕРіРѕ Termux, Р‘Р•Р— RUN_COMMAND, Р‘Р•Р— SAF.
 * Р’СЃРµ РІРёРєРѕРЅСѓС”С‚СЊСЃСЏ РІ РїСЂРёРІР°С‚РЅС–Р№ РїР°РїС†С– РґРѕРґР°С‚РєСѓ С‡РµСЂРµР· EmbeddedProcessManager.
 */
class ServerService : Service() {

    private val binder = ServerBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    // РљРµС€ СЂРѕР±РѕС‡РёС… РїР°РїРѕРє: Р»РѕРіРё РјР°СЋС‚СЊ РїРёСЃР°С‚РёСЃСЊ Сѓ СЃРїСЂР°РІР¶РЅСЋ РїР°РїРєСѓ СЃРµСЂРІРµСЂР°
    // (РІРєР»СЋС‡РЅРѕ Р· РєР°СЃС‚РѕРјРЅРѕСЋ), Р° РЅРµ РІРіР°РґСѓРІР°С‚РёСЃСЊ С‡РµСЂРµР· probe.
    private val workDirCache = ConcurrentHashMap<String, File>()
    // РЎРµСЂРІРµСЂРё С‰Рѕ Р·Р°СЂР°Р· РїСЂРѕС…РѕРґСЏС‚СЊ РґРѕРІРіРµ Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ (jar/JRE) РїРѕР·Р° Р»РѕРєРѕРј.
    // Р—Р°С…РёС‰РµРЅРѕ startMutex.
    private val startingIds = mutableSetOf<String>()
    // РЈРЅС–С„С–РєРѕРІР°РЅРѕ Р· MainActivity/CreateServerActivity: Р±РµР·РїРµС‡РЅРёР№ lazy-РґРѕСЃС‚СѓРї.
    // Service РЅРµ РјРѕР¶Рµ finish(), С‚РѕРјСѓ fallback вЂ” Р»РѕРєР°Р»СЊРЅРёР№ СЂРµРїРѕР·РёС‚РѕСЂС–Р№.
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
            // 1. РЁРІРёРґРєР° С„Р°Р·Р° РїС–Рґ Р»РѕРєРѕРј: РїРµСЂРµРІС–СЂРєРё + СЂРµР·РµСЂРІ СЃР»РѕС‚Р°, Р±РµР· РјРµСЂРµР¶РµРІРѕРіРѕ I/O.
            val reservedWorkDir: File? = startMutex.withLock {
                try {
                    Log.d("ServerService", "startServer embedded $serverId")
                    if (startingIds.contains(serverId)) {
                        log(serverId, "РЎРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓСЃРєР°С”С‚СЊСЃСЏ")
                        return@withLock null
                    }
                    val server = repository.getServer(serverId).first()
                    if (server == null) {
                        Log.e("ServerService", "Server not found $serverId")
                        return@withLock null
                    }
                    if (server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING) {
                        log(serverId, "РЎРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓС‰РµРЅРёР№")
                        return@withLock null
                    }
                    if (EmbeddedProcessManager.runningCount() >= EmbeddedProcessManager.MAX_SERVERS) {
                        log(serverId, "Р—СѓРїРёРЅРё РїРµСЂС€РёР№ СЃРµСЂРІРµСЂ (РјР°РєСЃРёРјСѓРј ${EmbeddedProcessManager.MAX_SERVERS})")
                        // РЅРµ Р±Р»РѕРєСѓС”РјРѕ РјКјСЋС‚РµРєСЃ РЅР° persist
                        serviceScope.launch { repository.updateServerStatus(serverId, ServerStatus.ERROR) }
                        return@withLock null
                    }
                    // C4: 1 JVM РЅР° РїСЂРѕС†РµСЃ вЂ” РґСЂСѓРіРёР№ Minecraft Р· С–РЅС€РёРј classpath РЅРµ СЃС‚Р°СЂС‚СѓС”.
                    // Р§РµСЃРЅР° РІС–РґРјРѕРІР° Р·Р°РјС–СЃС‚СЊ РјРѕРІС‡Р°Р·РЅРѕРіРѕ FAILED_DEFINITIVE РіР»РёР±РѕРєРѕ РІ InProcessServer.
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
                        log(serverId, "РЎРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓС‰РµРЅРёР№")
                        // FAIL 17:44: reserve РјРѕРІС‡РєРё Р±Р»РѕРєСѓРІР°РІ СЂРµСЃС‚Р°СЂС‚ ("РЎРµСЂРІРµСЂ РІР¶Рµ
                        // Р·Р°РїСѓС‰РµРЅРёР№", РїРѕСЂС‚ РїРѕСЂРѕР¶РЅС–Р№). Р¤Р°РєС‚Рё РІ logcat РїРѕРєР°Р·СѓСЋС‚СЊ С‡РѕРјСѓ.
                        try {
                            Log.i(
                                "ServerService",
                                "start reserve blocked $serverId facts=${InProcessServer.describeFacts()}"
                            )
                        } catch (_: Exception) {}
                        return@withLock null
                    }
                    startingIds.add(serverId)
                    // СЃС‚Р°С‚СѓСЃ STARTING РІРёСЃС‚Р°РІР»СЏС”РјРѕ РѕРґСЂР°Р·Сѓ С‰РѕР± UI РІС–РґСЂРµР°РіСѓРІР°РІ
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

            // 2. Р”РѕРІРіР° С„Р°Р·Р° РџРћР—Рђ Р»РѕРєРѕРј: С„Р°Р№Р»Рё, Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ jar/JRE, Р·Р°РїСѓСЃРє РїСЂРѕС†РµСЃСѓ.
            try {
                val server = repository.getServer(serverId).first()
                if (server == null) {
                    startMutex.withLock { startingIds.remove(serverId) }
                    return@launch
                }
                // Р‘Р°Рі A: stale isRunning РїС–СЃР»СЏ РїРѕРїРµСЂРµРґРЅСЊРѕРіРѕ СЃС‚РѕРїР° (Done Р±РµР· Closing)
                // Р±Р»РѕРєСѓРІР°РІ СЂРµСЃС‚Р°СЂС‚ РІ С‚РѕРјСѓ Р¶ РїСЂРѕС†РµСЃС–. Р¤Р°РєС‚Рё РІР¶Рµ РІРёРїСЂР°РІР»РµРЅС– РІ
                // InProcessServer (СЃРІС–Р¶РёР№ Done + РїРѕСЂС‚-first), С‚СѓС‚ Р»РёС€Рµ РґС–Р°РіРЅРѕСЃС‚РёРєР°.
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

                // РЎС…РѕРІРёС‰Рµ РјРѕРіР»Рё РІС‚СЂР°С‚РёС‚Рё РїС–СЃР»СЏ СЃС‚РІРѕСЂРµРЅРЅСЏ СЃРµСЂРІРµСЂР° (РІРёС‚СЏРіРЅСѓС‚Р° SD,
                // РІС–РґРєР»РёРєР°РЅРёР№ РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ). РЎС‚Р°СЂС‚СѓРІР°С‚Рё РІ С‡СѓР¶С–Р№/РїСѓСЃС‚С–Р№
                // РїР°РїС†С– РЅРµ РјРѕР¶РЅР° вЂ” С‡РµСЃРЅРѕ РІС–РґРјРѕРІР»СЏС”РјРѕ.
                val storedUri = server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                when (EmbeddedPaths.kindOfUri(storedUri)) {
                    EmbeddedPaths.StorageKind.EXTERNAL -> {
                        if (!EmbeddedPaths.isExternalStorageAvailable(this@ServerService)) {
                            log(serverId, "ERROR: Р·РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ РЅРµРґРѕСЃС‚СѓРїРЅРµ (РІРёС‚СЏРіРЅСѓС‚Р° SD-РєР°СЂС‚Р°?). Р’СЃС‚Р°РІ РєР°СЂС‚Сѓ Р°Р±Рѕ РїРµСЂРµРЅРµСЃРё СЃРµСЂРІРµСЂ Сѓ РІРЅСѓС‚СЂС–С€РЅС” СЃС…РѕРІРёС‰Рµ.")
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            return@launch
                        }
                    }
                    EmbeddedPaths.StorageKind.CUSTOM -> {
                        if (!EmbeddedPaths.hasAllFilesAccess(this@ServerService)) {
                            log(serverId, "ERROR: РЅРµРјР° РґРѕСЃС‚СѓРїСѓ РґРѕ РїР°РїРєРё СЃРµСЂРІРµСЂР°. РќР°РґР°Р№ В«РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІВ» Сѓ СЃРёСЃС‚РµРјРЅРёС… РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏС… С– СЃРїСЂРѕР±СѓР№ С‰Рµ СЂР°Р·.")
                            repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            return@launch
                        }
                    }
                    else -> {}
                }
                if (!EmbeddedPaths.isDirWritable(reservedWorkDir)) {
                    log(serverId, "ERROR: РїР°РїРєР° РЅРµРґРѕСЃС‚СѓРїРЅР° РґР»СЏ Р·Р°РїРёСЃСѓ: ${reservedWorkDir.absolutePath}")
                    repository.updateServerStatus(serverId, ServerStatus.ERROR)
                    return@launch
                }

                log(serverId, "Р—Р°РїСѓСЃРє СЃРµСЂРІРµСЂР° (РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»)...")
                log(serverId, "РЎРµСЂРІРµСЂ: ${server.name} type=${server.type} port=${server.port}")
                log(serverId, "РџР°РїРєР°: ${reservedWorkDir.absolutePath}")

                try {
                    val stat = StatFs(reservedWorkDir.absolutePath)
                    if (stat.availableBytes < 512L * 1024 * 1024) {
                        log(serverId, "РџРѕРїРµСЂРµРґР¶РµРЅРЅСЏ: РјР°Р»Рѕ РјС–СЃС†СЏ <512MB")
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

        // eula + properties Сѓ РІРЅСѓС‚СЂС–С€РЅС–Р№ РїР°РїС†С–.
        // online-mode / view-distance Р±РµСЂРµРјРѕ Р·С– Р·Р±РµСЂРµР¶РµРЅРѕРіРѕ РєРѕРЅС„С–РіСѓ РџР Р РљРћР–РќРћРњРЈ
        // СЃС‚Р°СЂС‚С– (РїР°С‚С‡РёРјРѕ С–СЃРЅСѓСЋС‡РёР№ С„Р°Р№Р», СЂРµС€С‚Сѓ РєР»СЋС‡С–РІ РЅРµ С‡С–РїР°С”РјРѕ) вЂ” С‚Р°Рє РїРѕРІР·СѓРЅРєРё
        // РїСЂР°С†СЋСЋС‚СЊ С– РґР»СЏ СЃРµСЂРІРµСЂС–РІ, СЃС‚РІРѕСЂРµРЅРёС… РґРѕ С—С… РїРѕСЏРІРё.
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

        // Provision jar Сѓ РІРЅСѓС‚СЂС–С€РЅСЋ РїР°РїРєСѓ (Р·РІРёС‡Р°Р№РЅРёР№ File I/O, Р±РµР· SAF)
        // Default 1.20.4: РІР±СѓРґРѕРІР°РЅРёР№ JRE17 Р·Р°РїСѓСЃРєР°С” Р»РёС€Рµ Minecraft <=1.20.4
        // (1.20.5+ РїРѕС‚СЂРµР±СѓС” Java 21 вЂ” РґРёРІ. advisory РЅРёР¶С‡Рµ).
        val version = server.config.minecraftVersion ?: "1.20.4"
        val loader = server.config.minecraftLoader
        if (EmbeddedJava.minJavaForMinecraft(version) > 17) {
            log(serverId, "РЈР’РђР“Рђ: $version РїРѕС‚СЂРµР±СѓС” Java 21+, Р° РІР±СѓРґРѕРІР°РЅРёР№ JRE вЂ” Java 17. РЎС‚Р°СЂС‚ С€РІРёРґС€Рµ Р·Р° РІСЃРµ РЅРµ РІРґР°СЃС‚СЊСЃСЏ; СЂРµРєРѕРјРµРЅРґРѕРІР°РЅРѕ 1.20.4 (Р°Р±Рѕ С–РјРїРѕСЂС‚СѓР№ JRE 21 РІСЂСѓС‡РЅСѓ РІ tools/jre21/ С‡РµСЂРµР· Files).")
        }
        // Р—Р°Р№РЅСЏС‚РёР№ РїРѕСЂС‚: СЏРєС‰Рѕ С†Рµ РќРђРЁ Р¶РёРІРёР№ СЃРµСЂРІРµСЂ вЂ” no-op, С–РЅР°РєС€Рµ ERROR.
        // Р‘РµР· С†СЊРѕРіРѕ РїРѕРІС‚РѕСЂРЅРёР№ Start Р·Р°РїСѓСЃРєР°РІ РґСЂСѓРіРёР№ Paper РїРѕРІРµСЂС… Р·Р°Р»РѕС‡РµРЅРѕРіРѕ
        // СЃРІС–С‚Сѓ -> OverlappingFileLockException.
        try {
            if (!NetworkUtils.isPortAvailable(server.port)) {
                if (EmbeddedProcessManager.isRunning(serverId) ||
                    InProcessServer.isRunning(serverId)
                ) {
                    log(serverId, "РЎРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓС‰РµРЅРёР№ (РїРѕСЂС‚ ${server.port} Р·Р°Р№РЅСЏС‚РёР№ РЅРёРј) вЂ” РїРѕРІС‚РѕСЂРЅРёР№ СЃС‚Р°СЂС‚ РїСЂРѕРїСѓС‰РµРЅРѕ")
                    try {
                        val cur = repository.getServer(serverId).first()
                        if (cur?.status != ServerStatus.RUNNING) {
                            repository.updateServerStatus(serverId, ServerStatus.RUNNING)
                        }
                    } catch (_: Exception) {}
                    return
                }
                log(serverId, "ERROR: РїРѕСЂС‚ ${server.port} РІР¶Рµ Р·Р°Р№РЅСЏС‚РёР№ С–РЅС€РёРј РїСЂРѕС†РµСЃРѕРј. Р—РјС–РЅРё РїРѕСЂС‚ Сѓ РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏС… СЃРµСЂРІРµСЂР°.")
                repository.updateServerStatus(serverId, ServerStatus.ERROR)
                return
            }
        } catch (_: Exception) {}
        val manager = MinecraftServerManager(workDir.parent ?: workDir.absolutePath)
        val foundBefore = EmbeddedProcessManager.findExistingJar(
            workDir, EmbeddedProcessManager.guessJarName(loader?.name)
        )
        // РќР°СЏРІРЅС–СЃС‚СЊ С„Р°Р№Р»Сѓ в‰  С†С–Р»С–СЃРЅС–СЃС‚СЊ: РѕР±С–СЂРІР°РЅРёР№ jar С‡РёСЃС‚РёРјРѕ С‡РµСЂРµР· provision Р·Р°РЅРѕРІРѕ.
        val hasJarBefore = foundBefore != null &&
            MinecraftServerManager.isValidServerJar(File(workDir, foundBefore))
        if (!hasJarBefore) {
            log(serverId, "Р—Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ ${loader ?: "PAPER"} $version (~50-100 MB)...")
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
                    log(serverId, "ERROR: РЅРµ РІРґР°Р»РѕСЃСЏ Р·Р°РІР°РЅС‚Р°Р¶РёС‚Рё jar. Р’С–РґРєСЂРёР№ Console/Files С‰РѕР± РїРѕР±Р°С‡РёС‚Рё РїСЂРёС‡РёРЅСѓ (HTTP-РєРѕРґ) РІРёС‰Рµ. РЇРєС‰Рѕ С–РЅС‚РµСЂРЅРµС‚ С” - СЃРїСЂРѕР±СѓР№ VANILLA/PURPUR/FABRIC.")
                    repository.updateServerStatus(serverId, ServerStatus.ERROR)
                    return
                }
            }
        }

        // Java Р· РІР±СѓРґРѕРІР°РЅРѕРіРѕ СЃРµСЂРµРґРѕРІРёС‰Р° (Р±РµР· Termux!)
        log(serverId, "РџРѕС€СѓРє РІР±СѓРґРѕРІР°РЅРѕС— Java...")
        val javaPath = EmbeddedBootstrap.ensureJava(this@ServerService, version) { msg -> log(serverId, msg) }
        if (javaPath == null) {
            log(serverId, "ERROR: Java РЅРµРґРѕСЃС‚СѓРїРЅР°. Р”РёРІРёСЃСЊ РїСЂРёС‡РёРЅСѓ РІРёС‰Рµ (HTTP-РєРѕРґ). Р”РµС‚Р°Р»С– РІ Console.")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        val preferred = EmbeddedProcessManager.guessJarName(loader?.name)
        val jar = EmbeddedProcessManager.findExistingJar(workDir, preferred)
        if (jar == null) {
            log(serverId, "ERROR: jar РЅРµ Р·РЅР°Р№РґРµРЅРѕ РІ ${workDir.absolutePath}")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        // W^X: РїСЂСЏРјРёР№ exec Р· РїР°РїРєРё РґРѕРґР°С‚РєСѓ Р·Р°Р±РѕСЂРѕРЅРµРЅРёР№ РїРѕР»С–С‚РёРєРѕСЋ Android РґР»СЏ
        // targetSdk 29+ (error=13). РћСЃРЅРѕРІРЅРёР№ С€Р»СЏС… вЂ” in-process JVM СЏРє Сѓ MojoLauncher
        // (dlopen libjvm.so + JNI_CreateJavaVM, Р±РµР· exec РІР·Р°РіР°Р»С–).
        // Legacy exec С‡РµСЂРµР· СЃРёСЃС‚РµРјРЅРёР№ Р»С–РЅРєРµСЂ Р»РёС€РµРЅРѕ СЏРє fallback РґР»СЏ retryable-РїРѕРјРёР»РѕРє.
        val jarFile = File(workDir, jar)
        val inProcessResult = try {
            log(serverId, "РЎС‚Р°СЂС‚ in-process JVM (Mojo-РїС–РґС…С–Рґ, Р±РµР· exec)...")
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
            log(serverId, "In-process СЃС‚Р°СЂС‚ РЅРµ РІРґР°РІСЃСЏ (${e.message}), РїСЂРѕР±СѓСЋ legacy exec...")
            InProcessServer.StartResult.FAILED_RETRYABLE
        }
        if (inProcessResult == InProcessServer.StartResult.ALREADY_RUNNING) {
            // No-op: Р¶РёРІРёР№ СЃРµСЂРІРµСЂ РІР¶Рµ С” (Paperclip main РїРѕРІРµСЂРЅСѓРІСЃСЏ, Р°Р»Рµ С„Р°РєС‚Рё
            // Р¶РёРІС–). РЎС‚Р°С‚СѓСЃ Р»РёС€Р°С”С‚СЊСЃСЏ RUNNING, РґСЂСѓРіРёР№ Paper РЅРµ СЃС‚Р°СЂС‚СѓС”РјРѕ.
            log(serverId, "РЎРµСЂРІРµСЂ РІР¶Рµ Р·Р°РїСѓС‰РµРЅРёР№ вЂ” РїРѕРІС‚РѕСЂРЅРёР№ СЃС‚Р°СЂС‚ РїСЂРѕРїСѓС‰РµРЅРѕ")
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
            log(serverId, "РЎРµСЂРІРµСЂ Р·Р°РїСѓС‰РµРЅРѕ (in-process JVM) jar=$jar")
            startLivenessPoll(serverId)
            // РЁРІРёРґРєРёР№ РєСЂР°С… (РЅРµСЃСѓРјС–СЃРЅР° Java, РєСЂРёРІРёР№ СЃРІС–С‚, РїРѕРІС‚РѕСЂРЅРёР№ Р±С–РЅРґ РїРѕСЂС‚Сѓ):
            // РЅРµ РІРёСЃС–С‚Рё РІ RUNNING вЂ” РїСЂРёС‡РёРЅР° РІР¶Рµ РІ Р»РѕРіСѓ РІРёС‰Рµ, С„С–РєСЃСѓС”РјРѕ ERROR.
            // Paperclip-РІС–РєРЅРѕ РІСЂР°С…РѕРІР°РЅРѕ: isRunning() С‚РµРїРµСЂ С„Р°РєС‚Рё-Р·Р°Р»РµР¶РЅРёР№
            // (РїРѕСЂС‚ + Done-РјР°СЂРєРµСЂ), С‚РѕР¶ Р¶РёРІРёР№ СЃРµСЂРІРµСЂ СЃСЋРґРё РЅРµ РїРѕС‚СЂР°РїРёС‚СЊ.
            // Р—Р°С‚СЂРёРјРєСѓ Р·Р±С–Р»СЊС€РµРЅРѕ РґРѕ 15СЃ вЂ” РїРѕРІС–Р»СЊРЅС– С‚РµР»РµС„РѕРЅРё РґРѕРІРіРѕ РІР°РЅС‚Р°Р¶Р°С‚СЊ СЃРІС–С‚.
            serviceScope.launch {
                delay(15000)
                try {
                    val s = repository.getServer(serverId).first()
                    if (s?.status == ServerStatus.RUNNING && !EmbeddedProcessManager.isRunning(serverId)) {
                        // РџРѕРґРІС–Р№РЅР° РїРµСЂРµРІС–СЂРєР° С„Р°РєС‚С–РІ РїРµСЂРµРґ ERROR: РїРѕСЂС‚ Р°Р±Рѕ Done
                        // РѕР·РЅР°С‡Р°СЋС‚СЊ "Р¶РёРІРёР№, РїСЂРѕСЃС‚Рѕ РїРѕРІС–Р»СЊРЅРёР№ СЃС‚Р°СЂС‚".
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
                                        // Р‘Р°Рі A: С‚С–Р»СЊРєРё РЎР’Р†Р–РР™ Done (РїС–СЃР»СЏ РѕСЃС‚Р°РЅРЅСЊРѕРіРѕ
                                        // Closing/stop/СЃРјРµСЂС‚С–), С–РЅР°РєС€Рµ stale Done
                                        // РїРѕРїРµСЂРµРґРЅСЊРѕРіРѕ Р±СѓС‚Р° РјР°СЃРєСѓС” РєСЂР°С… СЏРє "Р¶РёРІРёР№".
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
                                            "[Р·СѓРїРёРЅРµРЅРѕ РєРѕСЂРёСЃС‚СѓРІР°С‡РµРј]",
                                            "[РѕС‡С–РєСѓРІР°РЅРЅСЏ СЃРµСЂРІРµСЂР° Р·Р°РІРµСЂС€РµРЅРѕ"
                                        )
                                        val deathPos = tail.lastIndexOf("[РїСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ")
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
                            // STOP->START FIX 2026-09-07: С‡РµСЃРЅР° РІС–РґРјРѕРІР° -11 (СЃС‚Р°СЂРёР№
                            // РїРѕС‚С–Рє С‰Рµ РІРёС…РѕРґРёРІ, РЅРѕРІРёР№ Paper РЅРµ СЃС‚Р°СЂС‚СѓРІР°РІ) вЂ” С†Рµ РЅРµ
                            // РєСЂР°С…: STOPPED + РїСЂРѕСЃРёРјРѕ СЂРµС‚СЂР°Р№, Р° РЅРµ ERROR.
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
                                        if (String(b, 0, t).contains("РєРѕРґ -11")) sawBusyPrev = true
                                    }
                                }
                            } catch (_: Exception) {}
                            if (sawBusyPrev) {
                                log(serverId, "РџРѕРїРµСЂРµРґРЅС–Р№ РїРѕС‚С–Рє С‰Рµ Р·Р°РІРµСЂС€СѓРІР°РІСЃСЏ вЂ” С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·.")
                                repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                            } else {
                                log(serverId, "РЎРµСЂРІРµСЂ Р·Р°РІРµСЂС€РёРІСЃСЏ РѕРґСЂР°Р·Сѓ РїС–СЃР»СЏ СЃС‚Р°СЂС‚Сѓ вЂ” РґРёРІРёСЃСЊ РїСЂРёС‡РёРЅСѓ РІРёС‰Рµ (С‡Р°СЃС‚Рѕ: Р·Р°Р№РЅСЏС‚РёР№ РїРѕСЂС‚ Р°Р±Рѕ РЅРµСЃСѓРјС–СЃРЅР° Java).")
                                repository.updateServerStatus(serverId, ServerStatus.ERROR)
                            }
                        } else {
                            log(serverId, "РЎРµСЂРІРµСЂ С‰Рµ СЃС‚Р°СЂС‚СѓС” (РїРѕРІС–Р»СЊРЅРёР№ РїСЂРёСЃС‚СЂС–Р№) вЂ” С‡РµРєР°СЋ РґР°Р»С–...")
                        }
                    }
                } catch (_: Exception) {}
            }
            return
        }
        if (inProcessResult == InProcessServer.StartResult.FAILED_DEFINITIVE) {
            // Р‘РёС‚РёР№ jar / СЃС‚Р°СЂР° Java / Р·Р°Р№РЅСЏС‚Р° JVM вЂ” legacy exec РЅРµ РІРёРїСЂР°РІРёС‚СЊ.
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }

        log(serverId, "Fallback: legacy exec С‡РµСЂРµР· СЃРёСЃС‚РµРјРЅРёР№ Р»С–РЅРєРµСЂ...")
        val launch = com.pockethost.app.embedded.EmbeddedJava.probeJava(javaPath) { msg -> log(serverId, msg) }
        if (!launch.works) {
            log(serverId, "ERROR: Java РЅРµ Р·Р°РїСѓСЃРєР°С”С‚СЊСЃСЏ РЅС– РЅР°РїСЂСЏРјСѓ, РЅС– С‡РµСЂРµР· СЃРёСЃС‚РµРјРЅРёР№ Р»С–РЅРєРµСЂ (РґРµС‚Р°Р»С– РІРёС‰Рµ).")
            log(serverId, "РџСЂРёС‡РёРЅР° вЂ” Р·Р°Р±РѕСЂРѕРЅР° exec Р· РїР°РїРєРё РґРѕРґР°С‚РєСѓ (Android 10+, targetSdk 29+). Р’Р°СЂС–Р°РЅС‚Рё: СЂСѓС‡РЅРёР№ JRE Р· СЃРёСЃС‚РµРјРЅРёРј java Р°Р±Рѕ Р·Р±С–СЂРєР° Р· targetSdk 28.")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        if (launch.useLinker) {
            log(serverId, "Java: ${launch.version} (С‡РµСЂРµР· СЃРёСЃС‚РµРјРЅРёР№ Р»С–РЅРєРµСЂ вЂ” РїСЂСЏРјРёР№ Р·Р°РїСѓСЃРє Р·Р°Р±РѕСЂРѕРЅРµРЅРёР№ РїРѕР»С–С‚РёРєРѕСЋ Android)")
        } else {
            log(serverId, "Java: ${launch.version} (РїСЂСЏРјРёР№ Р·Р°РїСѓСЃРє)")
        }

        val cmd = EmbeddedProcessManager.buildMinecraftCommand(
            javaPath, jar, server.config.maxMemory, server.config.minMemory,
            server.config.javaArgs, launch.prefix
        )
        val startEnv = mutableMapOf("MC_PORT" to server.port.toString())
        // РћС‚РѕС‡РµРЅРЅСЏ, Р· СЏРєРёРј СЃРїСЂР°С†СЋРІР°РІ probe (LD_LIBRARY_PATH/JAVA_HOME РґР»СЏ Р»С–РЅРєРµСЂР°).
        startEnv.putAll(launch.env)
        log(serverId, "РЎС‚Р°СЂС‚: java -Xmx${server.config.maxMemory}M -jar $jar nogui")

        val ok = EmbeddedProcessManager.start(
            this@ServerService, serverId, cmd, workDir,
            startEnv
        )
        if (!ok) {
            log(serverId, "ERROR: РЅРµ РІРґР°Р»РѕСЃСЏ Р·Р°РїСѓСЃС‚РёС‚Рё РїСЂРѕС†РµСЃ")
            repository.updateServerStatus(serverId, ServerStatus.ERROR)
            return
        }
        repository.updateTermuxExecutionId(serverId, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        repository.updateServerStatus(serverId, ServerStatus.RUNNING)
        log(serverId, "РЎРµСЂРІРµСЂ Р·Р°РїСѓС‰РµРЅРѕ (РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р») jar=$jar")
        startLivenessPoll(serverId)
    }

    /**
     * РЎС‚РІРѕСЂСЋС” server.properties СЏРєС‰Рѕ РЅРµРјР°, С–РЅР°РєС€Рµ С‚РѕС‡РєРѕРІРѕ РѕРЅРѕРІР»СЋС” РєР»СЋС‡С–,
     * СЏРєРёРјРё РєРµСЂСѓС” UI (online-mode, view-distance, server-port).
     * Р РµС€С‚Сѓ С„Р°Р№Р»Р° (motd РіСЂР°РІС†СЏ, СЃРёРґ СЃРІС–С‚Сѓ С‚РѕС‰Рѕ) РЅРµ С‡С–РїР°С”.
     * Р›РѕРіС–РєР° РІРёРЅРµСЃРµРЅР° РІ [com.pockethost.app.util.ServerProperties].
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
        repository.updateTermuxExecutionId(serverId, (System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        repository.updateServerStatus(serverId, ServerStatus.RUNNING)
        if (useProot) {
            log(serverId, "\nUbuntu 22.04 started (proot, user-space, no root). IP: $ip\n")
            log(serverId, "Try in console: apt update && apt install -y python3 nodejs. /root/server mirrors the server folder.\n")
        } else {
            log(serverId, "\nBuilt-in Linux shell started. IP: $ip\n")
            log(serverId, "Type commands in the console below (sh -i). Full Ubuntu rootfs is available in server settings.\n")
        }
        startLivenessPoll(serverId)
    }

    private fun stopServer(serverId: String) {
        serviceScope.launch {
            // Р’РІС–С‡Р»РёРІРёР№ stop С‡РµСЂРµР· stdin + РѕС‡С–РєСѓРІР°РЅРЅСЏ РџРћР—Рђ Р»РѕРєРѕРј С‰РѕР± РЅРµ Р±Р»РѕРєСѓРІР°С‚Рё start().
            // In-process СЃРµСЂРІРµСЂСѓ С‚СЂРµР±Р° Р±С–Р»СЊС€Рµ С‡Р°СЃСѓ (Р·Р±РµСЂРµР¶РµРЅРЅСЏ СЃРІС–С‚Сѓ + System.exit
            // РїР°СЃС‚РєР° + Paperclip-РїР°СЂРєСѓРІР°РЅРЅСЏ), С‚РѕРјСѓ С‡РµРєР°С”РјРѕ РґРѕ 60СЃ.
            // "stop" С€Р»РµРјРѕ Р·Р° С„Р°РєС‚РѕРј Р¶РёРІРѕРіРѕ JVM (isRunning С„Р°РєС‚Рё-Р·Р°Р»РµР¶РЅРёР№),
            // С–РЅР°РєС€Рµ РєРѕРЅСЃРѕР»СЊ РІС–РґРјРѕРІР»СЏР»Р° РІ Paperclip-РІС–РєРЅС–.
            val wasRunning = EmbeddedProcessManager.isRunning(serverId)
            if (wasRunning) {
                try {
                    Log.i(
                        "ServerService",
                        "stopServer: sending stop to $serverId facts=${InProcessServer.describeFacts()}"
                    )
                    // FAIL 17:44: phase1 РњРђР„ СЃС‚Р°РІРёС‚Рё stopRequested РѕРґСЂР°Р·Сѓ.
                    // sendInput("stop") СЃР°Рј С„Р»Р°РіР° РЅРµ СЃС‚Р°РІРёРІ -> 60СЃ stopReq=false,
                    // РіС–Р»РєР° stopRequested+portFree РјРµСЂС‚РІР°, fast-path С‚СЂРёРјР°РІ alive=true.
                    try {
                        InProcessServer.requestStop(serverId)
                    } catch (_: Exception) {}
                    EmbeddedProcessManager.sendInput(serverId, "stop")
                    var waited = 0
                    while (waited < 60000 && EmbeddedProcessManager.isRunning(serverId)) {
                        delay(1000)
                        waited += 1000
                        // Р”С–Р°РіРЅРѕСЃС‚РёРєР° РєРѕР¶РЅС– 5СЃ (Р‘Р°Рі B + FAIL 17:44): С„Р°РєС‚Рё Р·
                        // donePos/stopPos С‰РѕР± Observer Р±Р°С‡РёРІ Closing РїС–СЃР»СЏ Done,
                        // Р° РЅРµ С‚С–Р»СЊРєРё "alive=true".
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
                        log(serverId, "РЎРµСЂРІРµСЂ РЅРµ РІС–РґРїРѕРІС–РІ РЅР° stop Р·Р° 60СЃ вЂ” С‡РµРєР°СЋ РґР°Р»С– Сѓ С„РѕРЅС– (СЃРІС–С‚ РјРѕР¶Рµ Р·Р±РµСЂС–РіР°С‚РёСЃСЊ)")
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
                        // РЎСЋРґРё РїРѕС‚СЂР°РїР»СЏС”РјРѕ С– РїС–СЃР»СЏ РІРІС–С‡Р»РёРІРѕРіРѕ СЃС‚РѕРїР° РІ phase1
                        // (РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ + Closing): С†Рµ СѓСЃРїС–С…, Р° РЅРµ "РЅРµ Р·Р°РїСѓС‰РµРЅРёР№".
                        if (wasRunning) {
                            log(serverId, "РЎРµСЂРІРµСЂ Р·СѓРїРёРЅРµРЅРѕ")
                        } else {
                            log(serverId, "РЎРµСЂРІРµСЂ РЅРµ Р·Р°РїСѓС‰РµРЅРёР№")
                        }
                        repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                        return@withLock
                    }
                    repository.updateServerStatus(serverId, ServerStatus.STOPPING)
                    log(serverId, "Р—СѓРїРёРЅРєР° СЃРµСЂРІРµСЂР°...")
                    EmbeddedProcessManager.stop(serverId)
                    repository.updateTermuxExecutionId(serverId, null)
                    repository.updateServerStatus(serverId, ServerStatus.STOPPED)
                    log(serverId, "РЎРµСЂРІРµСЂ Р·СѓРїРёРЅРµРЅРѕ")
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
        // РїРёС€РµРјРѕ РѕРґСЂР°Р·Сѓ Сѓ С„Р°Р№Р» СЃРїСЂР°РІР¶РЅСЊРѕС— СЂРѕР±РѕС‡РѕС— РїР°РїРєРё (Р±РµР· SAF!)
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
        // STOP->START FIX 2026-09-07: СЃС‚Р°СЂРёР№ poll РІР±РёРІР°РІ СЃРІС–Р¶РёР№ СЃС‚Р°СЂС‚.
        // Р”РІР° Р·Р°С…РёСЃС‚Рё: (Р°) stale-poll guard Р·Р° seq вЂ” poll РїРѕРїРµСЂРµРґРЅСЊРѕРіРѕ Р±СѓС‚Р°
        // РјРѕРІС‡РєРё РІРёС…РѕРґРёС‚СЊ РєРѕР»Рё seq Р·РјС–РЅРёРІСЃСЏ (РЅРѕРІРёР№ Р±СѓС‚ РјРѕРЅС–С‚РѕСЂРёС‚СЊ СЃРІС–Р№ poll);
        // (Р±) grace 30СЃ вЂ” СЃРІС–Р¶РёР№ Paper РїРёС€Рµ РјР°СЂРєРµСЂРё/Р±РёРЅРґРёС‚СЊ РїРѕСЂС‚ РґРѕ ~30СЃ, Р° РІ
        // РїРµСЂС€С– СЃРµРєСѓРЅРґРё С„Р°РєС‚С–РІ С‰Рµ РЅРµРјР° (РЅРµРјР° Done/РїРѕСЂС‚Сѓ) вЂ” РЅРµ РІР±РёРІР°С‚Рё.
        // Р‘РµР· С†СЊРѕРіРѕ: С‚РёС€Р° РЅРѕРІРѕРіРѕ СЃС‚Р°СЂС‚Сѓ 10СЃ -> "РџСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ" -> STOPPED.
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
                            log(serverId, "РџСЂРѕС†РµСЃ Р·Р°РІРµСЂС€РµРЅРѕ")
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
            .setContentText("Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р» РїСЂР°С†СЋС”")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // H6: stopAll Р”Рћ cancel вЂ” Р·СѓРїРёРЅРєР° РїСЂРѕС†РµСЃС–РІ РјР°С” РІСЃС‚РёРіРЅСѓС‚Рё РґРѕРїРёСЃР°С‚Рё Р»РѕРіРё/
        // РІС–РґРїСЂР°РІРёС‚Рё stop РґРѕ СЃРєР°СЃСѓРІР°РЅРЅСЏ scope (С–РЅР°РєС€Рµ С„РѕРЅРѕРІС– launch РІР±РёРІР°СЋС‚СЊСЃСЏ СЂР°РЅС–С€Рµ).
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
