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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverId = intent.getStringExtra("server_id")

        setupToolbar()
        setupButtons()
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

    private fun restartServer() { stopServer(); lifecycleScope.launch { delay(1000); startServer() } }

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
