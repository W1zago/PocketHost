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
import com.anserver.app.service.ServerService
import com.anserver.core.model.Server
import com.anserver.core.model.ServerStatus
import com.anserver.native.NativeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
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
        binding.btnStart.setOnClickListener {
            startServer()
        }

        binding.btnStop.setOnClickListener {
            stopServer()
        }

        binding.btnRestart.setOnClickListener {
            restartServer()
        }

        binding.btnConsole.setOnClickListener {
            openConsole()
        }

        binding.btnFiles.setOnClickListener {
            openFileManager()
        }

        binding.btnDelete.setOnClickListener {
            deleteServer()
        }
    }

    private fun observeServer() {
        serverId?.let { id ->
            lifecycleScope.launch {
                repository.getServer(id).collect { server ->
                    server?.let { updateUI(it) }
                }
            }

            // Also observe log file for errors
            lifecycleScope.launch {
                val server = repository.getServer(id).first()
                server?.let {
                    val logFile = File(it.workingDirectory, "server.log")
                    observeLogFile(logFile)
                }
            }
        }
    }

    private fun observeLogFile(logFile: File) {
        lifecycleScope.launch {
            var lastPosition = 0L
            while (true) {
                try {
                    if (logFile.exists()) {
                        val currentLength = logFile.length()
                        if (currentLength > lastPosition) {
                            val content = logFile.readText()
                            val newContent = content.substring(lastPosition.toInt())

                            // Check for errors in log
                            if (newContent.contains("Error", ignoreCase = true) ||
                                newContent.contains("Failed", ignoreCase = true)) {
                                runOnUiThread {
                                    binding.tvErrorMessage.text = newContent.lines().lastOrNull {
                                        it.contains("Error", ignoreCase = true) ||
                                        it.contains("Failed", ignoreCase = true)
                                    } ?: ""
                                    binding.tvErrorMessage.visibility = android.view.View.VISIBLE
                                }
                            }

                            lastPosition = currentLength
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ServerDetailActivity", "Error reading log file", e)
                }
                delay(2000)
            }
        }
    }

    private fun updateUI(server: Server) {
        android.util.Log.d("ServerDetailActivity", "updateUI called with status: ${server.status}")
        binding.apply {
            serverName.text = server.name
            serverType.text = server.type.name
            serverPort.text = "Port: ${server.port}"
            serverStatus.text = server.status.name

            val statusColor = when (server.status) {
                ServerStatus.RUNNING -> android.graphics.Color.GREEN
                ServerStatus.STOPPED -> android.graphics.Color.GRAY
                ServerStatus.STARTING -> android.graphics.Color.rgb(255, 165, 0) // Orange
                ServerStatus.STOPPING -> android.graphics.Color.YELLOW
                ServerStatus.ERROR -> android.graphics.Color.RED
            }
            serverStatus.setTextColor(statusColor)

            btnStart.isEnabled = server.status == ServerStatus.STOPPED || server.status == ServerStatus.ERROR
            btnStop.isEnabled = server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING
            btnRestart.isEnabled = server.status == ServerStatus.RUNNING
        }
    }

    private fun startServer() {
        serverId?.let { id ->
            val intent = Intent(this, ServerService::class.java).apply {
                action = "com.anserver.app.START_SERVER"
                putExtra("server_id", id)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun stopServer() {
        serverId?.let { id ->
            val intent = Intent(this, ServerService::class.java).apply {
                action = "com.anserver.app.STOP_SERVER"
                putExtra("server_id", id)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun restartServer() {
        stopServer()
        startServer()
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
                repository.getServer(id).collect { server ->
                    server?.let {
                        val intent = Intent(this@ServerDetailActivity, FileManagerActivity::class.java)
                        intent.putExtra("server_id", id)
                        intent.putExtra("working_directory", it.workingDirectory)
                        startActivity(intent)
                    }
                }
            }
        }
    }

    private fun deleteServer() {
        serverId?.let { id ->
            lifecycleScope.launch {
                repository.deleteServer(id)
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private fun updateIpAddress() {
        val ipAddress = getLocalIpAddress()
        android.util.Log.d("ServerDetailActivity", "IP Address: $ipAddress")
        binding.serverIpAddress.text = "IP: $ipAddress"
    }

    private fun getLocalIpAddress(): String {
        try {
            // Try WiFi first
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            android.util.Log.d("ServerDetailActivity", "WiFi IP int: $ipInt")

            if (ipInt != 0) {
                val ip = Formatter.formatIpAddress(ipInt)
                android.util.Log.d("ServerDetailActivity", "WiFi IP formatted: $ip")
                return ip
            }

            // Try all network interfaces
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            android.util.Log.d("ServerDetailActivity", "Network interfaces count: ${interfaces.size}")

            for (intf in interfaces) {
                android.util.Log.d("ServerDetailActivity", "Interface: ${intf.name}, isUp: ${intf.isUp}, isLoopback: ${intf.isLoopback}")
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    android.util.Log.d("ServerDetailActivity", "Address: ${addr.hostAddress}, isLoopback: ${addr.isLoopbackAddress}")
                    if (!addr.isLoopbackAddress) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && hostAddress.indexOf(':') < 0) {
                            android.util.Log.d("ServerDetailActivity", "Found IP: $hostAddress")
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ServerDetailActivity", "Error getting IP address", e)
            e.printStackTrace()
        }
        return "Unknown"
    }

    private fun startResourceMonitoring() {
        lifecycleScope.launch {
            while (true) {
                updateSystemResources()
                delay(2000) // Update every 2 seconds
            }
        }
    }

    private fun updateSystemResources() {
        try {
            // Get CPU usage
            val cpuUsage = NativeBridge.getCpuUsage()
            binding.tvCpuUsage.text = "CPU: ${String.format("%.1f", cpuUsage)}%"

            // Get memory info
            val memInfo = NativeBridge.getMemoryInfo()
            if (memInfo.size >= 3) {
                val totalMem = memInfo[0] / 1024 / 1024 // Convert to MB
                val freeMem = memInfo[1] / 1024 / 1024
                val usedMem = totalMem - freeMem
                binding.tvMemoryUsage.text = "Memory: $usedMem MB / $totalMem MB"
            }

            // Get CPU cores
            val cpuCores = Runtime.getRuntime().availableProcessors()
            binding.tvCpuCores.text = "CPU Cores: $cpuCores"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
