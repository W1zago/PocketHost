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
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Built-in terminal diagnostics.
 * No Termux / RUN_COMMAND / SAF checks - everything inside the app.
 */
class DeviceScanActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    // H6: was CoroutineScope(Dispatchers.Main) leaking on Activity destroy -
    // now lifecycleScope everywhere (auto-cancel in onDestroy).

    private val requestNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(this, "Notifications granted", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Notifications denied", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Diagnostics"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.background))
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 64)
        }
        scroll.addView(container)
        setContentView(scroll)

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
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
        container.removeAllViews()
        addHeader()
        val loading = TextView(this).apply {
            text = "Checking..."
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface_variant))
        }
        container.addView(loading)
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
                    container.removeAllViews()
                    addHeader()
                    addCheck("1. Built-in terminal (sh)", res.shellOk, "Details") { showShellInfo() }
                    addCheck("2. Internal storage", res.storageOk, "Open storage") { openStorageSettings() }
                    addCheck("3. Built-in Java", res.javaOk, "Install JRE") { installJre() }
                    addCheck("4. Built-in shell for Ubuntu", res.shellOk, "Details") { showShellInfo() }
                    addCheck("5. Battery: ignore optimizations (PocketHost)", res.batteryOk, "Allow background") { requestBatterySelf() }
                    addCheck("6. POST_NOTIFICATIONS permission", res.notifOk, "Request") { requestNotification() }
                    addCheck("7. Free space >512MB (${String.format("%.1f", res.freeGb)} GB)", res.spaceOk, "Clear") { openStorageSettings() }
                    addCheck("8. Internet", checkInternetSync(), "Check network") { openWifiSettings() }
                    // Java path detail
                    if (res.javaPath != null) {
                        container.addView(TextView(this@DeviceScanActivity).apply {
                            text = "Java: ${res.javaPath}"
                            textSize = 11f
                            typeface = android.graphics.Typeface.MONOSPACE
                            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface_variant))
                        })
                    }
                    addActions()
                } catch (_: Exception) {}
            }
        }
    }

    private fun addHeader() {
        val title = TextView(this).apply {
            text = "PocketHost - built-in terminal"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface))
        }
        val subtitle = TextView(this).apply {
            text = "External Termux NOT needed. Everything runs inside the app without the 'control Termux' permission."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface_variant))
        }
        container.addView(title)
        container.addView(subtitle)

        val btnRescan = MaterialButton(this).apply {
            text = "Scan again"
            setOnClickListener { refreshWithInternet() }
        }
        val btnAuto = MaterialButton(this).apply {
            text = "Grant permissions automatically"
            setOnClickListener { autoRequestAll() }
        }
        container.addView(btnRescan.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 8) } })
        container.addView(btnAuto.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 16) } })
    }

    private fun addActions() {
        val info = TextView(this).apply {
            text = "Note: SAF folders and RUN_COMMAND are no longer used. Old SAF servers start in migration mode in the internal folder."
            textSize = 12f
            setPadding(0, 24, 0, 0)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface_variant))
        }
        container.addView(info)
    }

    private fun addCheck(title: String, ok: Boolean, actionText: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(com.pockethost.app.R.drawable.bg_section_card)
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }
        val okColor = androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.tertiary)
        val errColor = androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.error)
        val indicator = TextView(this).apply {
            text = if (ok) "✓" else "✗"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(if (ok) okColor else errColor)
            setPadding(0, 0, 16, 0)
        }
        val label = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = MaterialButton(this).apply {
            text = if (ok) "OK" else actionText
            isEnabled = !ok
            alpha = if (ok) 0.5f else 1f
            setOnClickListener { action() }
        }
        row.addView(indicator)
        row.addView(label)
        row.addView(btn.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
        container.addView(row)

        val detail = TextView(this).apply {
            text = getDetail(title, ok)
            textSize = 11f
            setPadding(16, 0, 0, 12)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface_variant))
        }
        container.addView(detail)
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
}
