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
import androidx.lifecycle.lifecycleScope
import com.pockethost.app.PocketHostApplication
import com.pockethost.app.R
import com.pockethost.app.databinding.ActivityServerDetailBinding
import com.pockethost.app.embedded.EmbeddedPaths
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
    // РЈРЅС–С„С–РєРѕРІР°РЅРѕ Р· MainActivity/CreateServerActivity: Р±РµР·РїРµС‡РЅРёР№ lazy-РґРѕСЃС‚СѓРї.
    // РЇРєС‰Рѕ Application РЅРµ РІСЃС‚РёРі РїСЂРѕС–РЅС–С†С–Р°Р»С–Р·СѓРІР°С‚Рё СЂРµРїРѕР·РёС‚РѕСЂС–Р№ (РїРѕС€РєРѕРґР¶РµРЅРёР№ DataStore),
    // СЃС‚РІРѕСЂСЋС”РјРѕ Р»РѕРєР°Р»СЊРЅРёР№ fallback Р·Р°РјС–СЃС‚СЊ РєСЂР°С€Сѓ РЅР° lateinit.
    private val repository by lazy {
        runCatching { PocketHostApplication.serverRepository }.getOrElse { e ->
            Log.e(TAG, "repo not ready, using fallback", e)
            com.pockethost.core.repository.ServerRepository(this@ServerDetailActivity)
        }
    }
    private var serverId: String? = null
    private var currentServer: Server? = null
    private var deviceIp: String = "вЂ¦"
    // Р‘Р»РѕРєСѓС” Р·Р±РµСЂРµР¶РµРЅРЅСЏ РїС–Рґ С‡Р°СЃ РїСЂРѕРіСЂР°РјРЅРѕРіРѕ Р·Р°РїРѕРІРЅРµРЅРЅСЏ РєРѕРЅС‚СЂРѕР»С–РІ Сѓ updateUI
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
            Toast.makeText(this, "РЎРєРѕРїС–Р№РѕРІР°РЅРѕ: $text", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun setupMinecraftSettings() {
        binding.switchPirate.setOnCheckedChangeListener { _, _ ->
            if (suppressSettingsSave) return@setOnCheckedChangeListener
            saveMinecraftSettings()
        }
        binding.sliderViewDistance.addOnChangeListener { _, value, _ ->
            binding.tvViewDistance.text = "Р”Р°Р»СЊРЅС–СЃС‚СЊ РїСЂРѕРјР°Р»СЊРѕРІРєРё: ${value.toInt()} С‡Р°РЅРєС–РІ"
        }
        // Р—Р±РµСЂС–РіР°С”РјРѕ Р»РёС€Рµ РєРѕР»Рё РїР°Р»РµС†СЊ РІС–РґРїСѓС‰РµРЅРѕ, Р° РЅРµ РЅР° РєРѕР¶РµРЅ С‚С–Рє
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
     * Р—Р±РµСЂС–РіР°С” С‚СѓРјР±Р»РµСЂ/РїРѕРІР·СѓРЅРѕРє Сѓ РєРѕРЅС„С–Рі СЃРµСЂРІРµСЂР°.
     * РќР° Р·СѓРїРёРЅРµРЅРѕРјСѓ вЂ” РѕРґСЂР°Р·Сѓ РїР°С‚С‡РёС‚СЊ server.properties,
     * РЅР° Р·Р°РїСѓС‰РµРЅРѕРјСѓ вЂ” Р·Р°СЃС‚РѕСЃСѓС”С‚СЊСЃСЏ РїСЂРё РЅР°СЃС‚СѓРїРЅРѕРјСѓ СЃС‚Р°СЂС‚С– (СЃРµСЂРІС–СЃ РїР°С‚С‡РёС‚СЊ СЃР°Рј).
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
                        "Р—Р±РµСЂРµР¶РµРЅРѕ. Р—Р°СЃС‚РѕСЃСѓС”С‚СЊСЃСЏ РїС–СЃР»СЏ СЂРµСЃС‚Р°СЂС‚Сѓ СЃРµСЂРІРµСЂР°.",
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
                            "Р—Р±РµСЂРµР¶РµРЅРѕ С– Р·Р°СЃС‚РѕСЃРѕРІР°РЅРѕ.",
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
            // РҐРІС–СЃС‚ РѕСЃС‚Р°РЅРЅС–С… 16KB С‰РѕР± Р±Р°РЅРµСЂ РїРѕРјРёР»РѕРє Р±Р°С‡РёРІ С– С–СЃС‚РѕСЂС–СЋ, СЏРє ConsoleActivity.
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
                                    binding.tvLogLive.text = "в—Џ LIVE"
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
                "Up вЂ”"
            }
            btnStart.isEnabled = server.status == ServerStatus.STOPPED || server.status == ServerStatus.ERROR
            btnStop.isEnabled = server.status == ServerStatus.RUNNING || server.status == ServerStatus.STARTING
            btnRestart.isEnabled = server.status == ServerStatus.RUNNING
            // Р’Р±СѓРґРѕРІР°РЅРёР№ С€Р»СЏС… Р·Р°РјС–СЃС‚СЊ SAF
            val workDirStr = try {
                EmbeddedPaths.resolveWorkDir(
                    this@ServerDetailActivity, server.id,
                    server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                ).absolutePath
            } catch (_: Exception) { server.displayPath.ifEmpty { server.effectiveWorkDirUri } }
            // IP -> IP view (serverIpAddress + tvBindAddress), РїР°РїРєР° -> folder view.
            // Р‘СѓР»Рѕ: serverIpAddress РїРѕРєР°Р·СѓРІР°РІ "РџР°РїРєР°: /data/.../files/servers/..." вЂ” РїРµСЂРµРїР»СѓС‚Р°РЅР° РјС–С‚РєР°.
            serverIpAddress.text = "$deviceIp:${server.port}"
            serverFolderPath.text = "РџР°РїРєР°: $workDirStr"
            // LAN bind line uses the real device IP + this server's port.
            tvBindAddress.text = "$deviceIp:${server.port}"
            // РљР°СЂС‚РєР° РЅР°Р»Р°С€С‚СѓРІР°РЅСЊ вЂ” Р»РёС€Рµ РґР»СЏ Minecraft, Р·РЅР°С‡РµРЅРЅСЏ Р·С– Р·Р±РµСЂРµР¶РµРЅРѕРіРѕ РєРѕРЅС„С–РіСѓ
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
                        "Р”Р°Р»СЊРЅС–СЃС‚СЊ РїСЂРѕРјР°Р»СЊРѕРІРєРё: ${server.config.effectiveViewDistance} С‡Р°РЅРєС–РІ"
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
        if (millis < 0) return "вЂ”"
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

    private fun restartServer() {
        stopServer()
        lifecycleScope.launch {
            // FAIL 17:44: СЂРµСЃС‚Р°СЂС‚ С‡РµСЂРµР· 1СЃ РІРїРёСЂР°РІСЃСЏ РІ reserve "РЎРµСЂРІРµСЂ РІР¶Рµ
            // Р·Р°РїСѓС‰РµРЅРёР№" (Р·Р°РїР°СЂРєРѕРІР°РЅРёР№ future). Р§РµРєР°С”РјРѕ СЂРµР°Р»СЊРЅРѕС— СЃРјРµСЂС‚С– Р·Р°
            // С„Р°РєС‚Р°РјРё (РЅРѕРІРёР№ isRunning Р±РµР· fast-path) РґРѕ 15СЃ, РїРѕС‚С–Рј СЃС‚Р°СЂС‚.
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
                            action = "com.pockethost.app.STOP_SERVER"
                            putExtra("server_id", id)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(stop) else startService(stop)
                        delay(1000)
                    }
                }
                // Р’РёРґР°Р»СЏС”РјРѕ С– РїР°РїРєСѓ СЃРµСЂРІРµСЂР° вЂ” СЃС‚СЂРѕРіРѕ РІСЃРµСЂРµРґРёРЅС– РїС–СЃРѕС‡РЅРёС†С– РґРѕРґР°С‚РєСѓ
                // (РІРЅСѓС‚СЂС–С€РЅС” СЃС…РѕРІРёС‰Рµ Р°Р±Рѕ app-external dirs). РљР°СЃС‚РѕРјРЅСѓ РїР°РїРєСѓ вЂ” Р»РёС€Рµ
                // СЏРєС‰Рѕ С†Рµ С‚РѕС‡РЅРѕ С‚Р° РїР°РїРєР°, С‰Рѕ Р·Р°РїРёСЃР°РЅР° РїСЂРё СЃС‚РІРѕСЂРµРЅРЅС– (Р·Р°С…РёСЃС‚ РІС–Рґ Р·РЅРѕСЃСѓ С‡СѓР¶РѕРіРѕ).
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
        // Р”СЂС–Р±РЅРёС†СЏ (Р‘Р°Рі A): РґРµС‚Р°Р»СЊРЅРёР№ РµРєСЂР°РЅ РєРµС€СѓРІР°РІ RUNNING РїС–СЃР»СЏ СЃС‚РѕРїР° вЂ”
        // РѕРЅРѕРІР»СЋС”РјРѕ СЃС‚Р°С‚СѓСЃ РїСЂРё РїРѕРІРµСЂРЅРµРЅРЅС– (Flow РјС–Рі РїСЂРѕРїСѓСЃС‚РёС‚Рё РµРјС–СЃС–СЋ Сѓ С„РѕРЅС–).
        serverId?.let { id ->
            lifecycleScope.launch {
                try {
                    val s = repository.getServer(id).first()
                    if (s != null) updateUI(s)
                    // FAIL 17:44: СЃС‚Р°С‚СѓСЃ RUNNING РїСЂРё РјРµСЂС‚РІРёС… С„Р°РєС‚Р°С… (РїРѕСЂС‚ РІС–Р»СЊРЅРёР№ +
                    // Closing РїС–СЃР»СЏ Done, РЅРѕРІРёР№ isRunning Р±РµР· fast-path = false) вЂ”
                    // РЅРµ РїРѕРєР°Р·СѓС”РјРѕ stale RUNNING, СЃРёРЅС…СЂРѕРЅС–Р·СѓС”РјРѕ РІ STOPPED РѕРґСЂР°Р·Сѓ,
                    // РЅРµ С‡РµРєР°СЋС‡Рё 10СЃ liveness-poll СЃРµСЂРІС–СЃР°.
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
            // Hero-СЂСЏРґРѕРє serverIpAddress вЂ” С‚РµР¶ IP (РїР°РїРєР° Р¶РёРІРµ РІ serverFolderPath).
            binding.serverIpAddress.text = if (port != null) "$ipAddress:$port" else ipAddress
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String {
        // H5: primary вЂ” NetworkInterface (Р±РµР· РґРѕР·РІРѕР»С–РІ, Р±РµР· deprecated API).
        // WifiManager.connectionInfo вЂ” Р»РёС€Рµ secondary best-effort РІ try/catch
        // (deprecated + РїРѕС‚СЂРµР±СѓС” location-РґРѕР·РІРѕР»Сѓ РЅР° Android 10+).
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
                            // РџСЂРёРІР°С‚РЅС– РґС–Р°РїР°Р·РѕРЅРё РїРµСЂС€РёРјРё, Р°Р»Рµ РїРѕРІРµСЂРЅРµРјРѕ РїРµСЂС€РёР№ IPv4 РІР·Р°РіР°Р»С–.
                            if (hostAddress.startsWith("192.168.") || hostAddress.startsWith("10.") ||
                                hostAddress.startsWith("172.")
                            ) return hostAddress
                        }
                    }
                }
            }
            // Р”СЂСѓРіРёР№ РїСЂРѕС…С–Рґ: Р±СѓРґСЊ-СЏРєРёР№ IPv4 СЏРєС‰Рѕ РїСЂРёРІР°С‚РЅРѕРіРѕ РЅРµРјР°.
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
        // Secondary: WifiManager вЂ” Р»РёС€Рµ СЏРєС‰Рѕ С” location-РґРѕР·РІС–Р», С–РЅР°РєС€Рµ РїСЂРѕРїСѓСЃРєР°С”РјРѕ.
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
                "вЂ”"
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
                binding.tileDisk.text = "вЂ”"
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateSystemResources failed", e)
        }
    }
}
