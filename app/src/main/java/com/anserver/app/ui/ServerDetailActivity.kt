package com.anserver.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.format.Formatter
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.R
import com.anserver.app.databinding.ActivityServerDetailBinding
import com.anserver.app.embedded.EmbeddedPaths
import com.anserver.app.embedded.EmbeddedProcessManager
import com.anserver.app.service.ServerService
import com.anserver.app.util.ServerProperties
import com.anserver.core.model.Server
import com.anserver.core.model.ServerStatus
import com.anserver.core.model.ServerType
import com.google.android.material.slider.Slider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

class ServerDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ServerDetailActivity"
    }

    private lateinit var binding: ActivityServerDetailBinding
    // Уніфіковано з MainActivity/CreateServerActivity: безпечний lazy-доступ.
    // Якщо Application не встиг проініціалізувати репозиторій (пошкоджений DataStore),
    // створюємо локальний fallback замість крашу на lateinit.
    private val repository by lazy {
        runCatching { ANServerApplication.serverRepository }.getOrElse { e ->
            Log.e(TAG, "repo not ready, using fallback", e)
            com.anserver.core.repository.ServerRepository(this@ServerDetailActivity)
        }
    }
    private var serverId: String? = null
    private var currentServer: Server? = null
    private var deviceIp: String = "…"
    // Блокує збереження під час програмного заповнення контролів у updateUI
    private var suppressSettingsSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverId = intent.getStringExtra("server_id")

        setupToolbar()
        setupButtons()
        setupMinecraftSettings()
        observeServer()
        updateIpAddress()
        startResourceMonitoring()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Server Details"
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { startServer() }
        binding.btnStop.setOnClickListener { stopServer() }
        binding.btnRestart.setOnClickListener { restartServer() }
        binding.btnConsole.setOnClickListener { openConsole() }
        binding.btnFiles.setOnClickListener { openFileManager() }
        binding.btnDelete.setOnClickListener { deleteServer() }
        binding.btnOpenTerminal.setOnClickListener { openConsole() }
        binding.btnCopyAddress.setOnClickListener { copyBindAddress() }
        // Section tabs: real navigation (scroll within screen or open screen).
        binding.tabOverview.setOnClickListener { binding.scrollView.smoothScrollTo(0, 0) }
        binding.tabConsole.setOnClickListener { openConsole() }
        binding.tabFiles.setOnClickListener { openFileManager() }
        binding.tabTelemetry.setOnClickListener {
            binding.scrollView.post { binding.scrollView.smoothScrollTo(0, binding.resourcesCard.top) }
        }
        binding.tabConfig.setOnClickListener {
            binding.scrollView.post { binding.scrollView.smoothScrollTo(0, binding.minecraftSettingsCard.top) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_server_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_copy_address -> { copyBindAddress(); true }
            R.id.action_delete -> { deleteServer(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun copyBindAddress() {
        val server = currentServer ?: return
        val text = "$deviceIp:${server.port}"
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("server address", text))
            Toast.makeText(this, "Скопійовано: $text", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun setupMinecraftSettings() {
        binding.switchPirate.setOnCheckedChangeListener { _, _ ->
            if (suppressSettingsSave) return@setOnCheckedChangeListener
            saveMinecraftSettings()
        }
        binding.sliderViewDistance.addOnChangeListener { _, value, _ ->
            binding.tvViewDistance.text = "Дальність промальовки: ${value.toInt()} чанків"
        }
        // Зберігаємо лише коли палець відпущено, а не на кожен тік
        binding.sliderViewDistance.addOnSliderTouchListener(
            object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    if (suppressSettingsSave) return
                    saveMinecraftSettings()
                }
            }
        )
    }

    /**
     * Зберігає тумблер/повзунок у конфіг сервера.
     * На зупиненому — одразу патчить server.properties,
     * на запущеному — застосується при наступному старті (сервіс патчить сам).
     */
    private fun saveMinecraftSettings() {
        val server = currentServer ?: return
        if (server.type != ServerType.MINECRAFT) return
        val allowPirate = binding.switchPirate.isChecked
        val viewDistance = binding.sliderViewDistance.value.toInt().coerceIn(2, 32)
        lifecycleScope.launch {
            try {
                val updated = server.copy(
                    config = server.config.copy(
                        onlineMode = !allowPirate,
                        viewDistance = viewDistance
                    )
                )
                repository.updateServer(updated)
                currentServer = updated
                if (server.status == ServerStatus.RUNNING ||
                    server.status == ServerStatus.STARTING
                ) {
                    Toast.makeText(
                        this@ServerDetailActivity,
                        "Збережено. Застосується після рестарту сервера.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    try {
                        val workDir = EmbeddedPaths.resolveWorkDir(
                            this@ServerDetailActivity, server.id,
                            server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                        )
                        ServerProperties.patch(
                            workDir,
                            server.port,
                            !allowPirate,
                            viewDistance,
                            server.name
                        )
                        Toast.makeText(
                            this@ServerDetailActivity,
                            "Збережено і застосовано.",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Log.w(TAG, "props patch failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "save settings failed", e)
            }
        }
    }

    private fun observeServer() {
        serverId?.let { id ->
            lifecycleScope.launch {
                repository.getServer(id).collect { server ->
                    server?.let { updateUI(it) }
                }
            }
            lifecycleScope.launch {
                val server = repository.getServer(id).first()
                server?.let {
                    val workDir = EmbeddedPaths.resolveWorkDir(
                        this@ServerDetailActivity, it.id,
                        it.effectiveWorkDirUri.ifEmpty { it.workingDirectory }
                    )
                    observeLogFile(File(workDir, "server.log"))
                }
            }
        }
    }

    private fun observeLogFile(logFile: File) {
        lifecycleScope.launch {
            // Хвіст останніх 16KB щоб банер помилок бачив і історію, як ConsoleActivity.
            var offset = try {
                val len = logFile.length()
                if (len > 16 * 1024) len - 16 * 1024 else 0L
            } catch (_: Exception) { 0L }
            while (true) {
                try {
                    if (logFile.exists()) {
                        val currentLength = logFile.length()
                        if (currentLength < offset) offset = 0L
                        if (currentLength > offset) {
                            val newContent = try {
                                RandomAccessFile(logFile, "r").use { raf ->
                                    raf.seek(offset)
                                    val n = (currentLength - offset).toInt().coerceAtMost(64 * 1024)
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
                            if (newContent.contains("Error", ignoreCase = true) || newContent.contains("Failed", ignoreCase = true)) {
                                runOnUiThread {
                                    binding.tvErrorMessage.text = newContent.lines().lastOrNull {
                                        it.contains("Error", ignoreCase = true) || it.contains("Failed", ignoreCase = true)
                                    } ?: ""
                                    binding.tvErrorMessage.visibility = View.VISIBLE
                                }
                            }
                            // Console stream preview: real tail of server.log (last 8 lines).
                            val tail = newContent.lines().filter { it.isNotBlank() }.takeLast(8).joinToString("\n")
                            if (tail.isNotBlank()) {
                                runOnUiThread {
                                    binding.tvLogPreview.text = tail.take(1200)
                                    binding.tvLogLive.text = "● LIVE"
                                }
                            }
                            offset = currentLength
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading log", e)
                }
                delay(2000)
            }
        }
    }

    private fun updateUI(server: Server) {
        Log.d(TAG, "updateUI ${server.status}")
        currentServer = server
        binding.apply {
            serverName.text = server.name
            serverType.text = server.type.name
            serverPort.text = ":${server.port}"
            serverIcon.text = server.name.firstOrNull()?.uppercase() ?: "?"
            serverIcon.setTextColor(
                ContextCompat.getColor(
                    this@ServerDetailActivity,
                    if (server.type == ServerType.MINECRAFT) R.color.tertiary
                    else R.color.secondary
                )
            )
            // Status badge follows REAL state (same mapping as server list).
            val pillBgRes = when (server.status) {
                ServerStatus.RUNNING -> R.drawable.bg_status_running
                ServerStatus.STARTING, ServerStatus.STOPPING -> R.drawable.bg_status_warning
                ServerStatus.ERROR -> R.drawable.bg_status_error
                else -> R.drawable.bg_status_idle
            }
            val dotRes = when (server.status) {
                ServerStatus.RUNNING -> R.drawable.bg_dot_running
                ServerStatus.STARTING, ServerStatus.STOPPING -> R.drawable.bg_dot_warning
                ServerStatus.ERROR -> R.drawable.bg_dot_error
                else -> R.drawable.bg_dot_idle
            }
            val labelRes = when (server.status) {
                ServerStatus.RUNNING -> R.color.badge_running_fg
                ServerStatus.STARTING, ServerStatus.STOPPING -> R.color.badge_warning_fg
                ServerStatus.ERROR -> R.color.badge_error_fg
                else -> R.color.badge_idle_fg
            }
            val label = when (server.status) {
                ServerStatus.RUNNING -> "Running"
                ServerStatus.STARTING -> "Starting"
                ServerStatus.STOPPING -> "Stopping"
                ServerStatus.ERROR -> "Error"
                else -> "Stopped"
            }
            statusBadge.setBackgroundResource(pillBgRes)
            statusDot.setBackgroundResource(dotRes)
            serverStatus.text = label
            serverStatus.setTextColor(ContextCompat.getColor(this@ServerDetailActivity, labelRes))
            // Uptime is real: now - lastStarted while running.
            val lastStarted = server.lastStarted
            serverUptime.text = if ((server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING) && lastStarted != null) {
                "Up ${formatUptime(System.currentTimeMillis() - lastStarted)}"
            } else {
                "Up —"
            }
            btnStart.isEnabled = server.status == ServerStatus.STOPPED || server.status == ServerStatus.ERROR
            btnStop.isEnabled = server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING
            btnRestart.isEnabled = server.status == ServerStatus.RUNNING
            // Вбудований шлях замість SAF
            val workDirStr = try {
                EmbeddedPaths.resolveWorkDir(
                    this@ServerDetailActivity, server.id,
                    server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                ).absolutePath
            } catch (_: Exception) { server.displayPath.ifEmpty { server.effectiveWorkDirUri } }
            // IP -> IP view (serverIpAddress + tvBindAddress), папка -> folder view.
            // Було: serverIpAddress показував "Папка: /data/.../files/servers/..." — переплутана мітка.
            serverIpAddress.text = "$deviceIp:${server.port}"
            serverFolderPath.text = "Папка: $workDirStr"
            // LAN bind line uses the real device IP + this server's port.
            tvBindAddress.text = "$deviceIp:${server.port}"
            // Картка налаштувань — лише для Minecraft, значення зі збереженого конфігу
            if (server.type == ServerType.MINECRAFT) {
                minecraftSettingsCard.visibility = View.VISIBLE
                tabConfig.visibility = View.VISIBLE
                suppressSettingsSave = true
                try {
                    switchPirate.isChecked = !server.config.effectiveOnlineMode
                    try {
                        sliderViewDistance.value = server.config.effectiveViewDistance.toFloat().coerceIn(2f, 32f)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "snap viewDistance fallback", e)
                    }
                    tvViewDistance.text =
                        "Дальність промальовки: ${server.config.effectiveViewDistance} чанків"
                } catch (_: Exception) {
                } finally {
                    suppressSettingsSave = false
                }
            } else {
                minecraftSettingsCard.visibility = View.GONE
                tabConfig.visibility = View.GONE
            }
        }
    }

    private fun formatUptime(millis: Long): String {
        if (millis < 0) return "—"
        val minutes = millis / 60000
        if (minutes < 1) return "just now"
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ${minutes % 60}m"
        return "${hours / 24}d ${hours % 24}h"
    }

    private fun startServer() {
        serverId?.let { id ->
            val intent = Intent(this, ServerService::class.java).apply {
                action = "com.anserver.app.START_SERVER"
                putExtra("server_id", id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private fun stopServer() {
        serverId?.let { id ->
            val intent = Intent(this, ServerService::class.java).apply {
                action = "com.anserver.app.STOP_SERVER"
                putExtra("server_id", id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private fun restartServer() {
        stopServer()
        lifecycleScope.launch {
            // FAIL 17:44: рестарт через 1с впирався в reserve "Сервер вже
            // запущений" (запаркований future). Чекаємо реальної смерті за
            // фактами (новий isRunning без fast-path) до 15с, потім старт.
            var waited = 0
            while (waited < 15000) {
                delay(1000)
                waited += 1000
                try {
                    val id = serverId ?: break
                    if (!EmbeddedProcessManager.isRunning(id)) break
                } catch (_: Exception) { break }
            }
            startServer()
        }
    }

    private fun openConsole() {
        serverId?.let { id ->
            val intent = Intent(this, ConsoleActivity::class.java)
            intent.putExtra("server_id", id)
            startActivity(intent)
        }
    }

    private fun openFileManager() {
        serverId?.let { id ->
            lifecycleScope.launch {
                val server = repository.getServer(id).first()
                server?.let {
                    val intent = Intent(this@ServerDetailActivity, FileManagerActivity::class.java)
                    intent.putExtra("workDirUri", it.effectiveWorkDirUri.ifEmpty { it.workingDirectory })
                    startActivity(intent)
                }
            }
        }
    }

    private fun deleteServer() {
        serverId?.let { id ->
            lifecycleScope.launch {
                // Try to stop first if running
                repository.getServer(id).first()?.let { srv ->
                    if (srv.status == ServerStatus.RUNNING) {
                        val stop = Intent(this@ServerDetailActivity, ServerService::class.java).apply {
                            action = "com.anserver.app.STOP_SERVER"
                            putExtra("server_id", id)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(stop) else startService(stop)
                        delay(1000)
                    }
                }
                // Видаляємо і папку сервера — строго всередині пісочниці додатку
                // (внутрішнє сховище або app-external dirs). Кастомну папку — лише
                // якщо це точно та папка, що записана при створенні (захист від зносу чужого).
                try {
                    val srv = repository.getServer(id).first()
                    if (srv != null) {
                        val storedUri = srv.effectiveWorkDirUri.ifEmpty { srv.workingDirectory }
                        val dir = EmbeddedPaths.resolveWorkDir(
                            this@ServerDetailActivity, srv.id, storedUri
                        )
                        val dirCanon = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
                        var allowed = false
                        if (EmbeddedPaths.kindOfUri(storedUri) == EmbeddedPaths.StorageKind.CUSTOM) {
                            val recorded = try {
                                File(storedUri).canonicalPath
                            } catch (_: Exception) { storedUri }
                            allowed = dirCanon == recorded
                        } else {
                            val allowedRoots = mutableListOf<String>()
                            try {
                                allowedRoots.add(EmbeddedPaths.serversRoot(this@ServerDetailActivity).canonicalPath + File.separator)
                            } catch (_: Exception) {}
                            try {
                                EmbeddedPaths.externalServersRoot(this@ServerDetailActivity)?.let {
                                    allowedRoots.add(it.canonicalPath + File.separator)
                                }
                            } catch (_: Exception) {}
                            allowed = allowedRoots.any { dirCanon.startsWith(it) }
                        }
                        if (allowed) {
                            dir.deleteRecursively()
                        } else {
                            Log.w(TAG, "skip delete outside allowed roots: $dirCanon")
                        }
                    }
                } catch (_: Exception) {}
                repository.deleteServer(id)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Дрібниця (Баг A): детальний екран кешував RUNNING після стопа —
        // оновлюємо статус при поверненні (Flow міг пропустити емісію у фоні).
        serverId?.let { id ->
            lifecycleScope.launch {
                try {
                    val s = repository.getServer(id).first()
                    if (s != null) updateUI(s)
                    // FAIL 17:44: статус RUNNING при мертвих фактах (порт вільний +
                    // Closing після Done, новий isRunning без fast-path = false) —
                    // не показуємо stale RUNNING, синхронізуємо в STOPPED одразу,
                    // не чекаючи 10с liveness-poll сервіса.
                    try {
                        if (s != null && s.status == ServerStatus.RUNNING &&
                            !EmbeddedProcessManager.isRunning(id)
                        ) {
                            Log.i(
                                TAG,
                                "onResume: facts dead but status RUNNING, syncing STOPPED $id"
                            )
                            repository.updateServerStatus(id, ServerStatus.STOPPED)
                        }
                    } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }

    private fun updateIpAddress() {
        val ipAddress = getLocalIpAddress()
        deviceIp = ipAddress
        Log.d(TAG, "IP $ipAddress")
        try {
            val port = currentServer?.port
            binding.tvBindAddress.text = if (port != null) "$ipAddress:$port" else ipAddress
            // Hero-рядок serverIpAddress — теж IP (папка живе в serverFolderPath).
            binding.serverIpAddress.text = if (port != null) "$ipAddress:$port" else ipAddress
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        // H5: primary — NetworkInterface (без дозволів, без deprecated API).
        // WifiManager.connectionInfo — лише secondary best-effort в try/catch
        // (deprecated + потребує location-дозволу на Android 10+).
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                try {
                    if (!intf.isUp || intf.isLoopback) continue
                } catch (_: Exception) { continue }
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) {
                            // Приватні діапазони першими, але повернемо перший IPv4 взагалі.
                            if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.") ||
                                hostAddress.startsWith("172.")
                            ) return hostAddress
                        }
                    }
                }
            }
            // Другий прохід: будь-який IPv4 якщо приватного нема.
            for (intf in interfaces) {
                try {
                    if (!intf.isUp || intf.isLoopback) continue
                } catch (_: Exception) { continue }
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) return hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP via NetworkInterface", e)
        }
        // Secondary: WifiManager — лише якщо є location-дозвіл, інакше пропускаємо.
        try {
            val hasLocation = try {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
            if (hasLocation) {
                @Suppress("DEPRECATION")
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val wifiInfo = try { wifiManager.connectionInfo } catch (_: Exception) { null }
                val ipInt = wifiInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    return Formatter.formatIpAddress(ipInt)
                }
            } else {
                Log.d(TAG, "getLocalIpAddress: skip WifiManager (no location perm)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP via WifiManager", e)
        }
        return "Unknown"
    }

    private fun startResourceMonitoring() {
        lifecycleScope.launch {
            while (true) {
                updateSystemResources()
                delay(2000)
            }
        }
    }

    private fun updateSystemResources() {
        try {
            // Uptime tile: real, from lastStarted while running.
            val server = currentServer
            val lastStarted = server?.lastStarted
            binding.tileUptime.text = if (
                (server?.status == ServerStatus.RUNNING || server?.status == ServerStatus.STARTING) &&
                lastStarted != null
            ) {
                formatUptime(System.currentTimeMillis() - lastStarted)
            } else {
                "—"
            }
            val runtime = Runtime.getRuntime()
            val totalMem = runtime.totalMemory() / 1024 / 1024
            val freeMem = runtime.freeMemory() / 1024 / 1024
            val used = totalMem - freeMem
            val max = runtime.maxMemory() / 1024 / 1024
            binding.tileMemory.text = "$used/$max MB"
            val cores = runtime.availableProcessors()
            binding.tileCores.text = cores.toString()
            try {
                val stat = StatFs(filesDir.absolutePath)
                val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
                binding.tileDisk.text = String.format(Locale.US, "%.1f GB", freeGb)
            } catch (_: Exception) {
                binding.tileDisk.text = "—"
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateSystemResources failed", e)
        }
    }
}
