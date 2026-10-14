package com.pockethost.app.termux

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pockethost.app.R
import com.pockethost.app.databinding.ActivityDeviceScanBinding
import com.pockethost.app.databinding.ItemDeviceCheckBinding
import com.pockethost.app.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Built-in terminal diagnostics (8 checks), now in an XML layout that matches
 * the Stitch design: header, system-health summary, checklist rows, bottom bar.
 * No Termux / RUN_COMMAND / SAF checks - everything inside the app.
 */
class DeviceScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceScanBinding
    // H6: was CoroutineScope(Dispatchers.Main) leaking on Activity destroy -
    // now lifecycleScope everywhere (auto-cancel in onDestroy).

    private val requestNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(this, "Notifications granted", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Notifications denied", Toast.LENGTH_SHORT).show()
        refresh()
    }

    // ---- Optional in-app update (universal APK, Session API, never forced) ----
    private var pendingUpdate: AppUpdater.UpdateInfo? = null
    private var downloadedApk: File? = null
    private var updateJob: Job? = null

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from "Allow from this source" — retry install only if
        // the permission is now granted AND we already have the APK.
        val apk = downloadedApk
        if (apk != null && apk.exists() && AppUpdater.canRequestInstall(this)) {
            installDownloadedApk()
        } else if (!AppUpdater.canRequestInstall(this)) {
            Toast.makeText(this, "Install from this source not allowed", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceScanBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Device Scan"

        binding.btnRescan.setOnClickListener { refreshWithInternet() }
        binding.btnAutoGrant.setOnClickListener { autoRequestAll() }

        setupUpdateCard()

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private data class ScanResult(
        val shellOk: Boolean,
        val storageOk: Boolean,
        val javaOk: Boolean,
        val javaPath: String?,
        val batteryOk: Boolean,
        val notifOk: Boolean,
        val spaceOk: Boolean,
        val freeGb: Double
    )

    private fun refresh() {
        // Skeleton immediately, heavy checks (fork sh) - in IO to avoid ANR.
        binding.checkContainer.removeAllViews()
        binding.healthTitle.text = "Checking…"
        binding.healthSubtitle.text = ""
        binding.healthStatusChip.visibility = View.GONE
        binding.warnCard.visibility = View.GONE
        binding.autoCheckAge.text = ""
        lifecycleScope.launch(Dispatchers.IO) {
            val shellOk = checkEmbeddedShell()
            val storageOk = checkInternalStorage()
            val javaPath = try { com.pockethost.app.embedded.EmbeddedJava.findJava(this@DeviceScanActivity) } catch (_: Exception) { null }
            val batteryOk = checkBatterySelf()
            val notifOk = checkNotification()
            val freeGb = try { getFreeSpaceGB() } catch (_: Exception) { 99.0 }
            val res = ScanResult(shellOk, storageOk, javaPath != null, javaPath, batteryOk, notifOk, freeGb > 0.5, freeGb)
            withContext(Dispatchers.Main) {
                try {
                    renderResults(res)
                } catch (_: Exception) {}
            }
        }
    }

    private fun renderResults(res: ScanResult) {
        val checks = listOf(
            Triple("Built-in terminal (sh)", res.shellOk, "Details" to { showShellInfo() }),
            Triple("Internal storage", res.storageOk, "Open storage" to { openStorageSettings() }),
            Triple("Built-in Java", res.javaOk, "Install JRE" to { installJre() }),
            Triple("Built-in shell for Ubuntu", res.shellOk, "Details" to { showShellInfo() }),
            Triple("Battery: ignore optimizations (PocketHost)", res.batteryOk, "Allow background" to { requestBatterySelf() }),
            Triple("POST_NOTIFICATIONS permission", res.notifOk, "Request" to { requestNotification() }),
            Triple("Free space >512MB (${String.format("%.1f", res.freeGb)} GB)", res.spaceOk, "Clear" to { openStorageSettings() }),
            Triple("Internet", checkInternetSync(), "Check network" to { openWifiSettings() })
        )
        binding.checkContainer.removeAllViews()
        var passed = 0
        for ((title, ok, actionPair) in checks) {
            if (ok) passed++
            addCheck(title, ok, actionPair.first, actionPair.second)
        }
        // Java path detail (real path, middle-truncated by the row layout).
        if (res.javaPath != null) {
            addNote("Java: ${res.javaPath}")
        }
        // Health summary bound to the real pass counts.
        binding.healthTitle.text = "$passed/8 Passed"
        if (passed == checks.size) {
            binding.healthSubtitle.text = "All systems operational"
            binding.healthSubtitle.setTextColor(ContextCompat.getColor(this, R.color.tertiary))
            binding.healthStatusChip.visibility = View.VISIBLE
            binding.healthStatusChip.text = "$passed operational"
            binding.healthStatusChip.setTextColor(ContextCompat.getColor(this, R.color.tertiary))
            binding.healthStatusChip.setBackgroundResource(R.drawable.bg_scan_ok)
            binding.warnCard.visibility = View.GONE
        } else {
            val missing = checks.size - passed
            binding.healthSubtitle.text = "$missing issue${if (missing == 1) "" else "s"} require${if (missing == 1) "s" else ""} attention"
            binding.healthSubtitle.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.healthStatusChip.visibility = View.VISIBLE
            binding.healthStatusChip.text = "$missing issue${if (missing == 1) "" else "s"} require attention"
            binding.healthStatusChip.setTextColor(ContextCompat.getColor(this, R.color.error))
            binding.healthStatusChip.setBackgroundResource(R.drawable.bg_scan_fail)
            binding.warnCard.visibility = View.VISIBLE
        }
        binding.autoCheckAge.text = "Auto-check: just now"
    }

    private fun addCheck(title: String, ok: Boolean, actionText: String, action: () -> Unit) {
        val row = ItemDeviceCheckBinding.inflate(LayoutInflater.from(this), binding.checkContainer, false)
        row.checkIcon.text = if (ok) "✓" else "✗"
        row.checkIcon.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.tertiary else R.color.error)
        )
        row.checkIcon.setBackgroundResource(
            if (ok) R.drawable.bg_scan_ok else R.drawable.bg_scan_fail
        )
        row.checkTitle.text = title
        row.checkStatus.visibility = View.VISIBLE
        row.checkStatus.text = if (ok) "OK" else "FAILED"
        row.checkStatus.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.tertiary else R.color.error)
        )
        row.checkStatus.setBackgroundResource(
            if (ok) R.drawable.bg_scan_ok else R.drawable.bg_scan_fail
        )
        row.checkAction.text = if (ok) "OK" else actionText
        row.checkAction.isEnabled = !ok
        row.checkAction.alpha = if (ok) 0.5f else 1f
        row.checkAction.setOnClickListener { action() }
        row.checkDetail.text = getDetail(title, ok)
        binding.checkContainer.addView(row.root)
    }

    private fun addNote(text: String) {
        val row = ItemDeviceCheckBinding.inflate(LayoutInflater.from(this), binding.checkContainer, false)
        row.checkIcon.text = "i"
        row.checkIcon.setTextColor(ContextCompat.getColor(this, R.color.secondary))
        row.checkIcon.setBackgroundResource(R.drawable.bg_port_chip)
        row.checkTitle.text = text
        row.checkStatus.visibility = View.GONE
        row.checkAction.visibility = View.GONE
        row.checkDetail.text = ""
        binding.checkContainer.addView(row.root)
    }

    private fun getDetail(title: String, ok: Boolean): String {
        return when {
            title.contains("Built-in terminal") -> if (ok) "ProcessBuilder sh works" else "sh unavailable (very rare). Reboot the device"
            title.contains("Internal storage") -> {
                val root = filesDir.absolutePath
                if (ok) "Available: $root" else "Not writable: $root"
            }
            title.contains("Built-in Java") -> {
                val java = try { com.pockethost.app.embedded.EmbeddedJava.findJava(this) } catch (_: Exception) { null }
                if (ok) "Found: $java" else "Not found. Press Install JRE (needs internet, ~100MB)"
            }
            title.contains("Ubuntu") -> if (ok) "sh -i ready for commands" else "sh unavailable"
            title.contains("Battery") -> if (ok) "Optimization ignore enabled" else "System may kill the background service"
            title.contains("POST_NOTIFICATIONS") -> if (ok) "POST_NOTIFICATIONS granted" else "Required for foreground service"
            title.contains("Free space") -> {
                val free = getFreeSpaceGB()
                if (ok) "${String.format("%.1f", free)} GB free" else "${String.format("%.1f", free)} GB <0.5GB, free up space"
            }
            title.contains("Internet") -> if (ok) "Reachable (or cached)" else "Check failed. Enable WiFi/mobile data"
            else -> ""
        }
    }

    private fun checkEmbeddedShell(): Boolean {
        return try {
            val proc = ProcessBuilder("sh", "-c", "echo ok").start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out.contains("ok")
        } catch (_: Exception) { false }
    }

    private fun checkInternalStorage(): Boolean {
        return try {
            val test = java.io.File(filesDir, ".write_test")
            test.writeText("ok")
            val ok = test.exists()
            try { test.delete() } catch (_: Exception) {}
            ok && filesDir.canWrite()
        } catch (_: Exception) { false }
    }

    private fun checkJava(): Boolean {
        return try { com.pockethost.app.embedded.EmbeddedJava.isJavaAvailable(this) } catch (_: Exception) { false }
    }

    private fun checkUbuntuShell(): Boolean = checkEmbeddedShell()

    private fun checkBatterySelf(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) { false }
    }

    private fun checkNotification(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkSpace(): Boolean {
        return try { getFreeSpaceGB() > 0.5 } catch (_: Exception) { true }
    }

    private fun getFreeSpaceGB(): Double {
        val stat = StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024.0 * 1024 * 1024)
    }

    private var internetCache: Boolean? = null
    private fun checkInternetSync(): Boolean {
        internetCache?.let { return it }
        return true
    }

    private fun refreshWithInternet() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { checkInternet() }
            internetCache = result
            refresh()
            if (!result) Toast.makeText(this@DeviceScanActivity, "Internet unavailable", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun checkInternet(): Boolean {
        return try {
            val url = URL("https://8.8.8.8")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "HEAD"
            conn.responseCode in 200..399
        } catch (_: Exception) {
            try {
                Runtime.getRuntime().exec("ping -c 1 -W 2 8.8.8.8").waitFor() == 0
            } catch (_: Exception) { false }
        }
    }

    private fun autoRequestAll() {
        when {
            !checkNotification() -> requestNotification()
            !checkBatterySelf() -> requestBatterySelf()
            !checkJava() -> installJre()
            else -> Toast.makeText(this, "All set: built-in terminal needs no permissions", Toast.LENGTH_LONG).show()
        }
    }

    private fun showShellInfo() {
        Toast.makeText(this, "Built-in terminal: sh + stdin/stdout, logs in filesDir/servers/<id>/server.log", Toast.LENGTH_LONG).show()
    }

    private fun installJre() {
        Toast.makeText(this, "JRE download starts on first Minecraft server launch", Toast.LENGTH_LONG).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                com.pockethost.app.embedded.EmbeddedBootstrap.ensureJava(this@DeviceScanActivity) {}
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private fun requestBatterySelf() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun openStorageSettings() {
        try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun openWifiSettings() {
        try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    // ---------- Optional app update card (manual only) ----------

    private fun setupUpdateCard() {
        try {
            binding.updateTitle.text = "Installed ${AppUpdater.currentLabel()}"
            binding.updateSubtitle.text = "Tap Check for updates — optional, universal APK."
            binding.updateProgress.visibility = View.GONE
            // Only Check is visible at first; Install appears when an update
            // is found and downloads + installs in one tap.
            binding.btnInstallUpdate.visibility = View.GONE
            binding.btnCheckUpdate.setOnClickListener { checkForAppUpdate() }
            binding.btnInstallUpdate.setOnClickListener { onInstallClicked() }
        } catch (_: Exception) {}
    }

    private fun checkForAppUpdate() {
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch {
            try {
                binding.btnCheckUpdate.isEnabled = false
                // Keep Install visible only if we already have an APK on disk.
                binding.btnInstallUpdate.visibility =
                    if (downloadedApk?.exists() == true) View.VISIBLE else View.GONE
                binding.updateSubtitle.text = "Checking GitHub releases…"
                val info = withContext(Dispatchers.IO) { AppUpdater.checkForUpdate() }
                if (info == null) {
                    pendingUpdate = null
                    binding.updateTitle.text = "Installed ${AppUpdater.currentLabel()} — up to date"
                    binding.updateSubtitle.text = "No newer release found."
                } else {
                    pendingUpdate = info
                    val sizeMb = if (info.assetSize > 0) info.assetSize / 1024 / 1024 else -1
                    binding.updateTitle.text = "Update available: ${info.tag} (${info.assetName})"
                    binding.updateSubtitle.text = buildString {
                        append("Installed ${AppUpdater.currentLabel()}")
                        if (sizeMb >= 0) append(" • ~${sizeMb} MB")
                        if (info.body.isNotBlank()) append("\n${info.body.take(300)}")
                    }
                    binding.btnInstallUpdate.visibility = View.VISIBLE
                    binding.btnInstallUpdate.isEnabled = true
                    Toast.makeText(this@DeviceScanActivity, "Update ${info.tag} found (optional)", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                try { binding.updateSubtitle.text = "Check failed — no internet?" } catch (_: Exception) {}
            } finally {
                try { binding.btnCheckUpdate.isEnabled = true } catch (_: Exception) {}
            }
        }
    }

    /**
     * Single-tap Install: reuses the cached APK when present,
     * otherwise downloads it first and commits to the installer right away.
     */
    private fun onInstallClicked() {
        val info = pendingUpdate ?: run {
            Toast.makeText(this, "Check for updates first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AppUpdater.canRequestInstall(this)) {
            // One-time system gate for "install unknown apps from this source".
            try {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                unknownSourcesLauncher.launch(intent)
                Toast.makeText(this, "Allow installs from PocketHost, then tap Install again", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Allow installs from this source in Settings", Toast.LENGTH_LONG).show()
            }
            return
        }
        val cached = downloadedApk
        if (cached != null && cached.exists()) {
            installDownloadedApk()
            return
        }
        if (updateJob?.isActive == true) return
        updateJob = lifecycleScope.launch {
            try {
                binding.btnCheckUpdate.isEnabled = false
                binding.btnInstallUpdate.isEnabled = false
                binding.updateProgress.visibility = View.VISIBLE
                binding.updateProgress.isIndeterminate = false
                binding.updateProgress.progress = 0
                val file = AppUpdater.downloadApk(this@DeviceScanActivity, info) { pct, doneMb, totalMb ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        try {
                            binding.updateProgress.progress = pct
                            binding.updateSubtitle.text = "Downloading… $pct% ($doneMb/$totalMb MB)"
                        } catch (_: Exception) {}
                    }
                }
                binding.updateProgress.visibility = View.GONE
                if (file != null) {
                    downloadedApk = file
                    installDownloadedApk()
                } else {
                    binding.updateSubtitle.text = "Download failed — try again."
                    binding.btnInstallUpdate.isEnabled = true
                }
            } catch (_: Exception) {
                try {
                    binding.updateProgress.visibility = View.GONE
                    binding.updateSubtitle.text = "Download failed — try again."
                    binding.btnInstallUpdate.isEnabled = true
                } catch (_: Exception) {}
            } finally {
                try { binding.btnCheckUpdate.isEnabled = true } catch (_: Exception) {}
            }
        }
    }

    private fun installDownloadedApk() {
        val apk = downloadedApk
        if (apk == null || !apk.exists()) return
        lifecycleScope.launch {
            try {
                binding.btnInstallUpdate.isEnabled = false
                binding.updateSubtitle.text = "Sending to installer… (system will ask)"
                val ok = AppUpdater.installViaSession(this@DeviceScanActivity, apk)
                if (!ok) {
                    binding.updateSubtitle.text = "Installer rejected the session — try again."
                    binding.btnInstallUpdate.isEnabled = true
                }
                // On success the system consent dialog takes over; nothing else to do.
            } catch (_: Exception) {
                try {
                    binding.updateSubtitle.text = "Install failed — try again."
                    binding.btnInstallUpdate.isEnabled = true
                } catch (_: Exception) {}
            }
        }
    }
}
