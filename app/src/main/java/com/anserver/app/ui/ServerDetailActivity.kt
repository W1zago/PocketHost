package com.anserver.app.ui

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.databinding.ActivityServerDetailBinding
import com.anserver.app.embedded.EmbeddedPaths
import com.anserver.app.service.ServerService
import com.anserver.core.model.Server
import com.anserver.core.model.ServerStatus
import com.anserver.core.model.ServerType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.net.NetworkInterface
import java.util.Collections

class ServerDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerDetailBinding
    private val repository = ANServerApplication.serverRepository
    private var serverId: String? = null
    private var currentServer: Server? = null
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
            object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
                override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
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
                    android.widget.Toast.makeText(
                        this@ServerDetailActivity,
                        "Збережено. Застосується після рестарту сервера.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    try {
                        val workDir = EmbeddedPaths.resolveWorkDir(
                            this@ServerDetailActivity, server.id,
                            server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                        )
                        com.anserver.app.util.ServerProperties.patch(
                            workDir,
                            server.port,
                            !allowPirate,
                            viewDistance,
                            server.name
                        )
                        android.widget.Toast.makeText(
                            this@ServerDetailActivity,
                            "Збережено і застосовано.",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        android.util.Log.w("ServerDetail", "props patch failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerDetail", "save settings failed", e)
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
                                    binding.tvErrorMessage.visibility = android.view.View.VISIBLE
                                }
                            }
                            offset = currentLength
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ServerDetailActivity", "Error reading log", e)
                }
                delay(2000)
            }
        }
    }

    private fun updateUI(server: Server) {
        android.util.Log.d("ServerDetailActivity", "updateUI ${server.status}")
        currentServer = server
        binding.apply {
            serverName.text = server.name
            serverType.text = server.type.name
            serverPort.text = "Port: ${server.port}"
            serverStatus.text = server.status.name
            val statusColor = when (server.status) {
                ServerStatus.RUNNING -> android.graphics.Color.GREEN
                ServerStatus.STOPPED -> android.graphics.Color.GRAY
                ServerStatus.STARTING -> android.graphics.Color.rgb(255, 165, 0)
                ServerStatus.STOPPING -> android.graphics.Color.YELLOW
                ServerStatus.ERROR -> android.graphics.Color.RED
            }
            serverStatus.setTextColor(statusColor)
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
            serverIpAddress.text = "Папка: $workDirStr"
            // Картка налаштувань — лише для Minecraft, значення зі збереженого конфігу
            if (server.type == ServerType.MINECRAFT) {
                minecraftSettingsCard.visibility = android.view.View.VISIBLE
                suppressSettingsSave = true
                try {
                    switchPirate.isChecked = !server.config.effectiveOnlineMode
                    sliderViewDistance.value = server.config.effectiveViewDistance.toFloat()
                    tvViewDistance.text =
                        "Дальність промальовки: ${server.config.effectiveViewDistance} чанків"
                } catch (_: Exception) {
                } finally {
                    suppressSettingsSave = false
                }
            } else {
                minecraftSettingsCard.visibility = android.view.View.GONE
            }
        }
    }

    private fun startServer() {
        serverId?.let { id ->
            val intent = Intent(this, ServerService::class.java).apply {
                action = "com.anserver.app.START_SERVER"
                putExtra("server_id", id)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private fun stopServer() {
        serverId?.let { id ->
            val intent = Intent(this, ServerService::class.java).apply {
                action = "com.anserver.app.STOP_SERVER"
                putExtra("server_id", id)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
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
                    if (!com.anserver.app.embedded.EmbeddedProcessManager.isRunning(id)) break
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
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(stop) else startService(stop)
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
                            android.util.Log.w("ServerDetail", "skip delete outside allowed roots: $dirCanon")
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
                            !com.anserver.app.embedded.EmbeddedProcessManager.isRunning(id)
                        ) {
                            android.util.Log.i(
                                "ServerDetailActivity",
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
        android.util.Log.d("ServerDetailActivity", "IP $ipAddress")
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                val ip = Formatter.formatIpAddress(ipInt)
                return ip
            }
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) return hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ServerDetailActivity", "Error getting IP", e)
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
            binding.tvCpuUsage.text = "Термінал: вбудований (без Termux)"
            val runtime = Runtime.getRuntime()
            val totalMem = runtime.totalMemory() / 1024 / 1024
            val freeMem = runtime.freeMemory() / 1024 / 1024
            val used = totalMem - freeMem
            val max = runtime.maxMemory() / 1024 / 1024
            binding.tvMemoryUsage.text = "Memory: $used MB / $max MB (heap $used/$totalMem)"
            val cores = runtime.availableProcessors()
            binding.tvCpuCores.text = "CPU Cores: $cores"
            try {
                val stat = android.os.StatFs(filesDir.absolutePath)
                val totalBytes = stat.totalBytes / 1024 / 1024 / 1024
                val freeBytes = stat.availableBytes / 1024 / 1024 / 1024
                binding.tvCpuUsage.text = "Вбудований термінал | Disk: ${totalBytes - freeBytes}GB / ${totalBytes}GB"
            } catch (_: Exception) {}
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
