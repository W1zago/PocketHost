package com.anserver.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.databinding.ActivityServerDetailBinding
import com.anserver.app.service.ServerService
import com.anserver.app.termux.TermuxBridge
import com.anserver.core.model.Server
import com.anserver.core.model.ServerStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
                    val uriStr = it.effectiveWorkDirUri.ifEmpty { it.workingDirectory }
                    if (uriStr.isNotEmpty()) {
                        try {
                            val uri = Uri.parse(uriStr)
                            val dir = DocumentFile.fromTreeUri(this@ServerDetailActivity, uri)
                            val logFile = dir?.findFile("server.log")
                            if (logFile != null) observeLogFile(logFile)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun observeLogFile(logFile: DocumentFile) {
        lifecycleScope.launch {
            var lastPosition = 0L
            while (true) {
                try {
                    val currentLength = logFile.length()
                    if (currentLength > lastPosition) {
                        val text = contentResolver.openInputStream(logFile.uri)?.bufferedReader()?.readText() ?: ""
                        val newContent = if (text.length > lastPosition) text.substring(lastPosition.toInt().coerceAtMost(text.length)) else ""
                        if (newContent.contains("Error", ignoreCase = true) || newContent.contains("Failed", ignoreCase = true)) {
                            runOnUiThread {
                                binding.tvErrorMessage.text = newContent.lines().lastOrNull {
                                    it.contains("Error", ignoreCase = true) || it.contains("Failed", ignoreCase = true)
                                } ?: ""
                                binding.tvErrorMessage.visibility = android.view.View.VISIBLE
                            }
                        }
                        lastPosition = currentLength
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
            // Show SAF paths
            serverIpAddress.text = "SAF: ${server.displayPath.ifEmpty { server.effectiveWorkDirUri }}"
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
                repository.deleteServer(id)
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }

    private fun updateIpAddress() {
        val ipAddress = getLocalIpAddress()
        android.util.Log.d("ServerDetailActivity", "IP $ipAddress")
        // Keep original ip text but also show SAF in same view? Use separate? We'll append
        // serverIpAddress already used for SAF, so we reuse another? For now show IP in errorMessage if needed
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
            // CPU not available in Termux mode
            binding.tvCpuUsage.text = "CPU: недоступний в Termux режимі"
            val runtime = Runtime.getRuntime()
            val totalMem = runtime.totalMemory() / 1024 / 1024
            val freeMem = runtime.freeMemory() / 1024 / 1024
            val used = totalMem - freeMem
            val max = runtime.maxMemory() / 1024 / 1024
            binding.tvMemoryUsage.text = "Memory: $used MB / $max MB (heap $used/$totalMem)"
            val cores = runtime.availableProcessors()
            binding.tvCpuCores.text = "CPU Cores: $cores"

            // Try to get disk via SAF termux path df
            serverId?.let { id ->
                lifecycleScope.launch {
                    val server = repository.getServer(id).first()
                    server?.let {
                        val uriStr = it.effectiveWorkDirUri
                        if (uriStr.isNotEmpty()) {
                            try {
                                val termuxPath = it.termuxPath
                                if (termuxPath.isNotEmpty()) {
                                    // Fire RUN_COMMAND df, result will be in server.log; we just trigger it occasionally
                                    // To avoid spam, only every 10 cycles. For now just show local storage via StatFs
                                    val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().absolutePath)
                                    val totalBytes = stat.totalBytes / 1024 / 1024 / 1024
                                    val freeBytes = stat.availableBytes / 1024 / 1024 / 1024
                                    binding.tvCpuUsage.text = "CPU: недоступний | Disk: ${totalBytes - freeBytes}GB / ${totalBytes}GB"
                                    // Also send df via Termux for log
                                    if ((System.currentTimeMillis() / 2000) % 10 == 0L) {
                                        TermuxBridge.sendRawCommand(this@ServerDetailActivity, termuxPath, "df -h ~/storage 2>&1 | tee -a server.log; cat /proc/meminfo 2>&1 | head -n 5 | tee -a server.log")
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
