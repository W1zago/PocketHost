package com.pockethost.app.ui

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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pockethost.app.PocketHostApplication
import com.pockethost.app.R
import com.pockethost.app.databinding.ActivityServerDetailBinding
import com.pockethost.app.embedded.EmbeddedBootstrap
import com.pockethost.app.embedded.EmbeddedPaths
import com.pockethost.app.embedded.EmbeddedUbuntu
import com.pockethost.app.embedded.EmbeddedProcessManager
import com.pockethost.app.service.ServerService
import com.pockethost.app.util.ServerProperties
import com.pockethost.core.model.Server
import com.pockethost.core.model.ServerStatus
import com.pockethost.core.model.ServerType
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
    // Unified with MainActivity/CreateServerActivity: safe lazy access.
    // When Application failed to init the repository (corrupted DataStore),
    // create a local fallback instead of crashing on lateinit.
    private val repository by lazy {
        runCatching { PocketHostApplication.serverRepository }.getOrElse { e ->
            Log.e(TAG, "repo not ready, using fallback", e)
            com.pockethost.core.repository.ServerRepository(this@ServerDetailActivity)
        }
    }
    private var serverId: String? = null
    private var currentServer: Server? = null
    private var deviceIp: String = "…"
    // Blocks settings save while controls are filled programmatically in updateUI
    private var suppressSettingsSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerDetailBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
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
        supportActionBar?.title = "Server Studio"
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { startServer() }
        binding.btnStop.setOnClickListener { stopServer() }
        binding.btnRestart.setOnClickListener { restartServer() }
        binding.btnConsole.setOnClickListener { openConsole() }
        binding.btnFiles.setOnClickListener { openFileManager() }
        binding.btnDelete.setOnClickListener { deleteServer() }
        binding.btnCopyAddress.setOnClickListener { copyBindAddress() }
        binding.btnCopyGateway.setOnClickListener { copyBindAddress() }
        binding.btnInstallUbuntu.setOnClickListener { installUbuntu() }
        // Section tabs: real navigation (open screen).
        binding.tabConsole.setOnClickListener { openConsole() }
        binding.tabFiles.setOnClickListener { openFileManager() }
    }

    /** Active tab pill (design: primary-container) vs idle tabs. */
    private fun setActiveTab(active: View) {
        try {
            val tabs = listOf(
                binding.tabConsole, binding.tabFiles
            )
            val onBg = ContextCompat.getColor(this, R.color.primary_container)
            val onFg = ContextCompat.getColor(this, R.color.on_primary_container)
            val offBg = ContextCompat.getColor(this, R.color.surface_high)
            val offFg = ContextCompat.getColor(this, R.color.on_surface_variant)
            for (tab in tabs) {
                val on = tab == active
                tab.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) onBg else offBg)
                tab.setTextColor(if (on) onFg else offFg)
                tab.iconTint = android.content.res.ColorStateList.valueOf(if (on) onFg else offFg)
            }
        } catch (_: Exception) {}
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
            Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun setupMinecraftSettings() {
        binding.mcSettings.switchPirate.setOnCheckedChangeListener { _, _ ->
            if (suppressSettingsSave) return@setOnCheckedChangeListener
            saveMinecraftSettings()
        }
        binding.mcSettings.sliderViewDistance.addOnChangeListener { _, value, _ ->
            binding.mcSettings.tvViewDistance.text = "${value.toInt()} chunks"
        }
        // Save only when the thumb is released, not on every tick
        binding.mcSettings.sliderViewDistance.addOnSliderTouchListener(
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
     * Saves the tumbler/slider into the server config.
     * On a stopped server - patches server.properties at once,
     * on a running one - applies on next start (service patches itself).
     */
    private fun saveMinecraftSettings() {
        val server = currentServer ?: return
        if (server.type != ServerType.MINECRAFT) return
        val allowPirate = binding.mcSettings.switchPirate.isChecked
        val viewDistance = binding.mcSettings.sliderViewDistance.value.toInt().coerceIn(2, 32)
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
                        "Saved. Applies after server restart.",
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
                            "Saved and applied.",
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
            // Tail of last 16KB so the error banner sees history too, like ConsoleActivity.
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
            // Subtitle from REAL config: loader + version (design), never fake.
            serverType.text = if (server.type == ServerType.MINECRAFT) {
                val loader = server.config.minecraftLoader?.name?.lowercase()
                    ?.replaceFirstChar { it.uppercase() } ?: "Minecraft"
                val ver = server.config.minecraftVersion ?: ""
                "Minecraft $loader $ver".trim()
            } else {
                "Ubuntu PRoot sandbox"
            }
            serverPort.text = ":${server.port}"
            val letter = server.name.firstOrNull()?.uppercase() ?: "?"
            serverIcon.text = "[$letter]"
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
                formatUptime(System.currentTimeMillis() - lastStarted)
            } else {
                "—"
            }
            btnStart.isEnabled = server.status == ServerStatus.STOPPED || server.status == ServerStatus.ERROR
            btnStop.isEnabled = server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING
            btnRestart.isEnabled = server.status == ServerStatus.RUNNING
            // IP -> IP view (serverIpAddress + tvBindAddress).
            serverIpAddress.text = "$deviceIp:${server.port}"
            // LAN bind line uses the real device IP + this server's port.
            tvBindAddress.text = "$deviceIp:${server.port}"
            // Bound socket row: real wildcard bind + port (TCP).
            tvBoundSocket.text = "0.0.0.0:${server.port} [TCP/UDP]"
            // LAN bind line uses the real device IP + this server port.
            if (server.type == ServerType.MINECRAFT) {
                minecraftSettingsCard.visibility = View.VISIBLE
                ubuntuSettingsCard.visibility = View.GONE
                suppressSettingsSave = true
                try {
                    mcSettings.switchPirate.isChecked = !server.config.effectiveOnlineMode
                    try {
                        mcSettings.sliderViewDistance.value = server.config.effectiveViewDistance.toFloat().coerceIn(2f, 32f)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "snap viewDistance fallback", e)
                    }
                    mcSettings.tvViewDistance.text =
                        "${server.config.effectiveViewDistance} chunks"
                } catch (_: Exception) {
                } finally {
                    suppressSettingsSave = false
                }
            } else {
                minecraftSettingsCard.visibility = View.GONE
                ubuntuSettingsCard.visibility = View.VISIBLE
                refreshUbuntuCard()
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
                action = "com.pockethost.app.START_SERVER"
                putExtra("server_id", id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private fun stopServer() {
        serverId?.let { id ->
            val intent = Intent(this, ServerService::class.java).apply {
                action = "com.pockethost.app.STOP_SERVER"
                putExtra("server_id", id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private var restarting = false

    private fun restartServer() {
        val id = serverId ?: return
        if (restarting) return
        restarting = true
        binding.btnRestart.isEnabled = false
        try { binding.btnRestart.text = "Restarting..." } catch (_: Exception) {}
        Toast.makeText(this, "Restarting...", Toast.LENGTH_SHORT).show()
        stopServer()
        lifecycleScope.launch {
            // Old code waited only isRunning up to 15s and always called
            // startServer() - even when stop was still in progress (world save
            // up to 60s). Service then refused start as "already running"
            // (status RUNNING/STARTING or isRunning true), so Restart looked
            // like Stop. Now wait for BOTH: status STOPPED/ERROR + !isRunning,
            // up to 60s, and start only when truly stopped.
            var stopped = false
            var waited = 0
            while (waited < 60_000) {
                delay(1000)
                waited += 1000
                try {
                    val s = repository.getServer(id).first()
                    val running = try {
                        EmbeddedProcessManager.isRunning(id)
                    } catch (_: Exception) { true }
                    if ((s?.status == ServerStatus.STOPPED || s?.status == ServerStatus.ERROR) && !running) {
                        stopped = true
                        break
                    }
                } catch (_: Exception) {}
            }
            if (stopped) {
                // Let session.lock / static state settle before same-JVM start.
                delay(2000)
                startServer()
            } else {
                Toast.makeText(
                    this@ServerDetailActivity,
                    "Still stopping (world save). Press Start when Stopped.",
                    Toast.LENGTH_LONG
                ).show()
                try { repository.getServer(id).first()?.let { updateUI(it) } } catch (_: Exception) {}
            }
            restarting = false
            try { binding.btnRestart.text = getString(R.string.restart) } catch (_: Exception) {
                try { binding.btnRestart.text = "Restart" } catch (_: Exception) {}
            }
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
                            action = "com.pockethost.app.STOP_SERVER"
                            putExtra("server_id", id)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(stop) else startService(stop)
                        delay(1000)
                    }
                }
                // Also delete the server folder - strictly inside the app sandbox
                // (internal storage or app-external dirs). A custom folder - only
                // when it is exactly the recorded one (guard against wiping foreign).
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

    private fun refreshUbuntuCard() {
        lifecycleScope.launch {
            val status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    if (EmbeddedUbuntu.isInstalled(this@ServerDetailActivity)) {
                        "installed"
                    } else if (!EmbeddedUbuntu.isSupported()) {
                        "unsupported"
                    } else {
                        "missing"
                    }
                } catch (_: Exception) {
                    "missing"
                }
            }
            try {
                when (status) {
                    "installed" -> {
                        binding.tvUbuntuStatus.text =
                            "Ubuntu ${EmbeddedUbuntu.UBUNTU_VERSION} installed (proot, apt works)."
                        binding.btnInstallUbuntu.text = "Reinstall Ubuntu"
                        binding.btnInstallUbuntu.isEnabled = true
                    }
                    "unsupported" -> {
                        binding.tvUbuntuStatus.text = EmbeddedUbuntu.supportHint()
                        binding.btnInstallUbuntu.isEnabled = false
                    }
                    else -> {
                        binding.tvUbuntuStatus.text =
                            "Not installed (~30MB download, ~150MB on disk). Start downloads it automatically."
                        binding.btnInstallUbuntu.text = "Download Ubuntu (~30MB)"
                        binding.btnInstallUbuntu.isEnabled = true
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun installUbuntu() {
        binding.btnInstallUbuntu.isEnabled = false
        binding.btnInstallUbuntu.text = "Downloading..."
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    EmbeddedBootstrap.installUbuntuFull(this@ServerDetailActivity) { msg ->
                        try {
                            runOnUiThread { binding.tvUbuntuStatus.text = msg.takeLast(300) }
                        } catch (_: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "installUbuntu failed", e)
                    false
                }
            }
            try {
                android.widget.Toast.makeText(
                    this@ServerDetailActivity,
                    if (ok) "Ubuntu installed" else "Ubuntu install did not finish, see status",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
            }
            refreshUbuntuCard()
        }
    }

    override fun onResume() {
        super.onResume()
        // Trivia (Bug A): details screen cached RUNNING after stop -
        // refresh status on return (Flow could miss the background emission).
        serverId?.let { id ->
            lifecycleScope.launch {
                try {
                    val s = repository.getServer(id).first()
                    if (s != null) updateUI(s)
                    // FAIL 17:44: RUNNING status with dead facts (free port +
                    // Closing after Done, new isRunning without fast-path = false) -
                    // do not show stale RUNNING, sync to STOPPED at once,
                    // without waiting 10s for the service liveness-poll.
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
            // Hero row serverIpAddress is also IP.
            binding.serverIpAddress.text = if (port != null) "$ipAddress:$port" else ipAddress
            if (port != null) binding.tvBoundSocket.text = "0.0.0.0:$port [TCP/UDP]"
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        // H5: primary - NetworkInterface (no permissions, no deprecated API).
        // WifiManager.connectionInfo is only a secondary best-effort in try/catch
        // (deprecated + needs location permission on Android 10+).
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
                            // Private ranges first, but return the first IPv4 overall.
                            if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.") ||
                                hostAddress.startsWith("172.")
                            ) return hostAddress
                        }
                    }
                }
            }
            // Second pass: any IPv4 when no private one exists.
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
            // Secondary: WifiManager - only with location permission, otherwise skip.
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
            val heapPct = if (max > 0) ((used * 100) / max).toInt().coerceIn(0, 100) else 0
            binding.tileMemoryPct.text = "$heapPct%"
            binding.heapBar.progress = heapPct
            val cores = runtime.availableProcessors()
            binding.tileCores.text = cores.toString()
            try {
                val stat = StatFs(filesDir.absolutePath)
                val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
                val totalGb = stat.totalBytes / 1024.0 / 1024.0 / 1024.0
                binding.tileDisk.text = String.format(Locale.US, "%.1f GB", freeGb)
                binding.tileDiskSub.text =
                    "free of ${String.format(Locale.US, "%.0f", totalGb)} GB"
                val usedPct = if (totalGb > 0) (((totalGb - freeGb) * 100) / totalGb).toInt().coerceIn(0, 100) else 0
                binding.diskBar.progress = usedPct
            } catch (_: Exception) {
                binding.tileDisk.text = "—"
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateSystemResources failed", e)
        }
    }
}
