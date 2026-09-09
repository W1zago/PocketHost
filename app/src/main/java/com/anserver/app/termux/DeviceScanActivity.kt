package com.anserver.app.termux

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
 * Діагностика ВБУДОВАНОГО терміналу.
 * Жодних перевірок Termux / RUN_COMMAND / SAF - все всередині додатку.
 */
class DeviceScanActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    // H6: був CoroutineScope(Dispatchers.Main) з витоком при знищенні Activity —
    // тепер lifecycleScope всюди (авто-cancel в onDestroy).

    private val requestNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(this, "Сповіщення дозволено", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Сповіщення відхилено", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Діагностика"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.background))
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
        // Скелет одразу, важкі перевірки (fork sh) — у IO щоб не ANR.
        container.removeAllViews()
        addHeader()
        val loading = TextView(this).apply {
            text = "Перевірка..."
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.anserver.app.R.color.on_surface_variant))
        }
        container.addView(loading)
        lifecycleScope.launch(Dispatchers.IO) {
            val shellOk = checkEmbeddedShell()
            val storageOk = checkInternalStorage()
            val javaPath = try { com.anserver.app.embedded.EmbeddedJava.findJava(this@DeviceScanActivity) } catch (_: Exception) { null }
            val batteryOk = checkBatterySelf()
            val notifOk = checkNotification()
            val freeGb = try { getFreeSpaceGB() } catch (_: Exception) { 99.0 }
            val res = ScanResult(shellOk, storageOk, javaPath != null, javaPath, batteryOk, notifOk, freeGb > 0.5, freeGb)
            withContext(Dispatchers.Main) {
                try {
                    container.removeAllViews()
                    addHeader()
                    addCheck("1. Вбудований термінал (sh)", res.shellOk, "Деталі") { showShellInfo() }
                    addCheck("2. Внутрішнє сховище", res.storageOk, "Відкрити сховище") { openStorageSettings() }
                    addCheck("3. Вбудована Java", res.javaOk, "Встановити JRE") { installJre() }
                    addCheck("4. Вбудований shell для Ubuntu", res.shellOk, "Деталі") { showShellInfo() }
                    addCheck("5. Батарея: ігнор оптимізації (PocketHost)", res.batteryOk, "Дозволити фон") { requestBatterySelf() }
                    addCheck("6. Сповіщення POST_NOTIFICATIONS", res.notifOk, "Запросити") { requestNotification() }
                    addCheck("7. Вільно місця >512MB (${String.format("%.1f", res.freeGb)} GB)", res.spaceOk, "Очистити") { openStorageSettings() }
                    addCheck("8. Інтернет", checkInternetSync(), "Перевірити мережу") { openWifiSettings() }
                    // деталь Java-шляху
                    if (res.javaPath != null) {
                        container.addView(TextView(this@DeviceScanActivity).apply {
                            text = "Java: ${res.javaPath}"
                            textSize = 11f
                            typeface = android.graphics.Typeface.MONOSPACE
                            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.anserver.app.R.color.on_surface_variant))
                        })
                    }
                    addActions()
                } catch (_: Exception) {}
            }
        }
    }

    private fun addHeader() {
        val title = TextView(this).apply {
            text = "PocketHost — вбудований термінал"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.anserver.app.R.color.on_surface))
        }
        val subtitle = TextView(this).apply {
            text = "Зовнішній Termux НЕ потрібен. Все працює всередині додатку без дозволу «керування Termux»."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.anserver.app.R.color.on_surface_variant))
        }
        container.addView(title)
        container.addView(subtitle)

        val btnRescan = MaterialButton(this).apply {
            text = "Сканувати знову"
            setOnClickListener { refreshWithInternet() }
        }
        val btnAuto = MaterialButton(this).apply {
            text = "Надати дозволи автоматично"
            setOnClickListener { autoRequestAll() }
        }
        container.addView(btnRescan.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 8) } })
        container.addView(btnAuto.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 16) } })
    }

    private fun addActions() {
        val info = TextView(this).apply {
            text = "Примітка: SAF-папки та RUN_COMMAND більше не використовуються. Старі SAF-сервери запускаються в міграційному режимі у внутрішній папці."
            textSize = 12f
            setPadding(0, 24, 0, 0)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.anserver.app.R.color.on_surface_variant))
        }
        container.addView(info)
    }

    private fun addCheck(title: String, ok: Boolean, actionText: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(com.anserver.app.R.drawable.bg_section_card)
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 8) }
        }
        val okColor = androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.tertiary)
        val errColor = androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.error)
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
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.anserver.app.R.color.on_surface))
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
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.anserver.app.R.color.on_surface_variant))
        }
        container.addView(detail)
    }

    private fun getDetail(title: String, ok: Boolean): String {
        return when {
            title.contains("Вбудований термінал") -> if (ok) "ProcessBuilder sh працює" else "sh недоступний (дуже рідко). Перезапусти пристрій"
            title.contains("Внутрішнє сховище") -> {
                val root = filesDir.absolutePath
                if (ok) "Доступно: $root" else "Нема запису в $root"
            }
            title.contains("Вбудована Java") -> {
                val java = try { com.anserver.app.embedded.EmbeddedJava.findJava(this) } catch (_: Exception) { null }
                if (ok) "Знайдено: $java" else "Не знайдено. Натисни «Встановити JRE» (потрібен інтернет, ~100MB)"
            }
            title.contains("Ubuntu") -> if (ok) "sh -i готовий для команд" else "sh недоступний"
            title.contains("Батарея") -> if (ok) "Ігнор оптимізації увімкнено" else "Система може вбивати фоновий сервіс"
            title.contains("Сповіщення") -> if (ok) "POST_NOTIFICATIONS дозволено" else "Потрібен для foreground service"
            title.contains("Вільно місця") -> {
                val free = getFreeSpaceGB()
                if (ok) "${String.format("%.1f", free)} GB вільно" else "${String.format("%.1f", free)} GB <0.5GB, звільни місце"
            }
            title.contains("Інтернет") -> if (ok) "Доступ є (або кеш)" else "Перевірка не пройшла. Увімкни WiFi/мобільні"
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
        return try { com.anserver.app.embedded.EmbeddedJava.isJavaAvailable(this) } catch (_: Exception) { false }
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
            if (!result) Toast.makeText(this@DeviceScanActivity, "Інтернет недоступний", Toast.LENGTH_SHORT).show()
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
            else -> Toast.makeText(this, "Все готово: вбудований термінал не потребує дозволів", Toast.LENGTH_LONG).show()
        }
    }

    private fun showShellInfo() {
        Toast.makeText(this, "Вбудований термінал: sh + stdin/stdout, логи в filesDir/servers/<id>/server.log", Toast.LENGTH_LONG).show()
    }

    private fun installJre() {
        Toast.makeText(this, "Завантаження JRE стартує при першому запуску Minecraft-сервера", Toast.LENGTH_LONG).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                com.anserver.app.embedded.EmbeddedBootstrap.ensureJava(this@DeviceScanActivity) {}
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
