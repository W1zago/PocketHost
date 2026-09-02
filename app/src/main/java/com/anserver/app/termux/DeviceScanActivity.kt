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
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class DeviceScanActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val scope = CoroutineScope(Dispatchers.Main)

    private val requestNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(this, "Сповіщення дозволено", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Сповіщення відхилено", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private val safPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                SafHelper.persistPermission(this, uri)
                Toast.makeText(this, "SAF дозвіл збережено: ${SafHelper.getDisplayPath(this, uri)}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Помилка SAF: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Сканування пристрою"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 64)
        }
        scroll.addView(container)
        setContentView(scroll)

        // Auto-request all on first open? We do not auto, user clicks button
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

    private fun refresh() {
        container.removeAllViews()
        addHeader()
        addCheck("1. Termux встановлено", checkTermuxInstalled(), "Встановити F-Droid") { openFdroid() }
        addCheck("2. Termux версія >=95 (F-Droid)", checkTermuxVersion(), "Оновити F-Droid") { openFdroid() }
        addCheck("3. RUN_COMMAND дозвіл", checkRunCommand(), "Надати дозвіл") { openAppDetails() }
        addCheck("4. SAF папка вибрана", checkSaf(), "Вибрати SAF папку") { safPicker.launch(null) }
        addCheck("5. Сховище Termux (termux-setup-storage)", checkTermuxStorage(), "Інструкція") { openTermuxSettings() }
        addCheck("6. Батарея: ігнор оптимізації (PocketHost)", checkBatterySelf(), "Дозволити фон") { requestBatterySelf() }
        addCheck("7. Батарея: ігнор для Termux", checkBatteryTermux(), "Дозволити Termux") { requestBatteryTermux() }
        addCheck("8. Сповіщення POST_NOTIFICATIONS", checkNotification(), "Запросити") { requestNotification() }
        addCheck("9. Вільно місця >2GB", checkSpace(), "Очистити") { openStorageSettings() }
        addCheck("10. Інтернет (8.8.8.8)", checkInternetSync(), "Перевірити мережу") { openWifiSettings() }
        addActions()
    }

    private fun addHeader() {
        val title = TextView(this).apply {
            text = "Перевірка пристрою для PocketHost Pure Termux"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        val subtitle = TextView(this).apply {
            text = "Зелений = OK, Червоний = треба дія. Натисни «Надати всі дозволи» щоб додаток сам запросив що може."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        container.addView(title)
        container.addView(subtitle)

        val btnRescan = MaterialButton(this).apply {
            text = "Сканувати знову"
            setOnClickListener { refreshWithInternet() }
        }
        val btnAuto = MaterialButton(this).apply {
            text = "Надати всі дозволи автоматично"
            setOnClickListener { autoRequestAll() }
        }
        container.addView(btnRescan.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 8) } })
        container.addView(btnAuto.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 16) } })
    }

    private fun addActions() {
        val info = TextView(this).apply {
            text = "Примітка: RUN_COMMAND та SAF не можна надати автоматично без переходу в Налаштування — додаток відкриє потрібний екран сам."
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        container.addView(info)
    }

    private fun addCheck(title: String, ok: Boolean, actionText: String, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val indicator = TextView(this).apply {
            text = if (ok) "✓" else "✗"
            textSize = 20f
            setTextColor(if (ok) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
            setPadding(0, 0, 16, 0)
        }
        val label = TextView(this).apply {
            text = title
            textSize = 14f
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

        // Details line
        val detail = TextView(this).apply {
            text = getDetail(title, ok)
            textSize = 11f
            setPadding(48, 0, 0, 8)
            setTextColor(0xFF666666.toInt())
        }
        container.addView(detail)
        val divider = TextView(this).apply { text = "─".repeat(50); textSize = 8f; setTextColor(0xFFDDDDDD.toInt()) }
        container.addView(divider)
    }

    private fun getDetail(title: String, ok: Boolean): String {
        return when {
            title.contains("Termux встановлено") -> if (ok) "Пакет com.termux знайдено" else "Не знайдено com.termux. Потрібен F-Droid 0.118+"
            title.contains("версія") -> if (ok) "versionCode=${TermuxChecker.getVersionCode(this)} >=95" else "versionCode=${TermuxChecker.getVersionCode(this)} <95 або Play Store. Видали Play, постав F-Droid"
            title.contains("RUN_COMMAND") -> if (ok) "Дозвіл GRANTED (${TermuxChecker.getRunCommandDetails(this)})" else "Нема доступу. На Samsung: Налаштування -> Додатки -> PocketHost -> Дозволи -> Інше (або Додаткові дозволи) -> увімкни 'Виконувати команди в Termux' / RUN_COMMAND. ${TermuxChecker.getRunCommandDetails(this)}"
            title.contains("SAF") -> {
                val count = try { contentResolver.persistedUriPermissions.size } catch (_: Exception) { 0 }
                if (ok) "Збережено $count SAF дозволів" else "Нема жодного persistedUriPermissions. Вибери /Download або /Documents"
            }
            title.contains("Сховище Termux") -> if (ok) "Ймовірно налаштовано (є SAF)" else "Виконай в Termux: termux-setup-storage"
            title.contains("Батарея") && title.contains("PocketHost") -> if (ok) "Ігнор оптимізації увімкнено" else "Система може вбивати фоновий сервіс"
            title.contains("Батарея") && title.contains("Termux") -> if (ok) "Termux не оптимізується" else "Termux може вбиватися. Запроси ігнор"
            title.contains("Сповіщення") -> if (ok) "POST_NOTIFICATIONS дозволено" else "Потрібен для foreground service"
            title.contains("Вільно місця") -> {
                val free = getFreeSpaceGB()
                if (ok) "${String.format("%.1f", free)} GB вільно" else "${String.format("%.1f", free)} GB <2GB, звільни місце"
            }
            title.contains("Інтернет") -> if (ok) "Доступ до 8.8.8.8 є (або кеш)" else "Перевірка не пройшла (може бути кеш). Увімкни WiFi/мобільні"
            else -> ""
        }
    }

    private fun checkTermuxInstalled() = try { TermuxChecker.isInstalled(this) } catch (_: Exception) { false }
    private fun checkTermuxVersion() = try { TermuxChecker.isVersionOk(this) } catch (_: Exception) { false }
    private fun checkRunCommand() = try { TermuxChecker.isRunCommandGranted(this) } catch (_: Exception) { false }
    private fun checkSaf(): Boolean {
        return try {
            val perms = contentResolver.persistedUriPermissions
            perms.any { it.isReadPermission && it.isWritePermission && SafHelper.isOnPrimaryStorage(this, it.uri) }
        } catch (_: Exception) { false }
    }
    private fun checkTermuxStorage(): Boolean {
        // Heuristic: if SAF exists assume storage setup done; real check via RUN_COMMAND ls ~/storage requires async
        return checkSaf()
    }
    private fun checkBatterySelf(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) { false }
    }
    private fun checkBatteryTermux(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations("com.termux")
        } catch (_: Exception) { false }
    }
    private fun checkNotification(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
    }
    private fun checkSpace(): Boolean {
        return try { getFreeSpaceGB() > 2.0 } catch (_: Exception) { true }
    }
    private fun getFreeSpaceGB(): Double {
        val stat = StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024.0 * 1024 * 1024)
    }
    private var internetCache: Boolean? = null
    private fun checkInternetSync(): Boolean {
        internetCache?.let { return it }
        return true // default true until async check finishes
    }
    private fun refreshWithInternet() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { checkInternet() }
            internetCache = result
            refresh()
            if (!result) Toast.makeText(this@DeviceScanActivity, "Інтернет недоступний (8.8.8.8)", Toast.LENGTH_SHORT).show()
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
                // Fallback ping
                Runtime.getRuntime().exec("ping -c 1 -W 2 8.8.8.8").waitFor() == 0
            } catch (_: Exception) { false }
        }
    }

    private fun autoRequestAll() {
        // Sequential auto-request for fixable permissions
        when {
            !checkNotification() -> requestNotification()
            !checkBatterySelf() -> requestBatterySelf()
            !checkBatteryTermux() -> requestBatteryTermux()
            !checkTermuxInstalled() -> openFdroid()
            !checkTermuxVersion() -> openFdroid()
            !checkRunCommand() -> openAppDetails()
            !checkSaf() -> safPicker.launch(null)
            else -> Toast.makeText(this, "Всі авто-дозволи вже надані. Перевір RUN_COMMAND/SAF вручну", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestBatterySelf() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun requestBatteryTermux() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:com.termux")
            }
            startActivity(intent)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { Toast.makeText(this, "Відкрий вручну: Батарея -> Неоптимізовані -> Termux", Toast.LENGTH_LONG).show() }
        }
    }

    private fun openFdroid() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))) } catch (_: Exception) {}
    }
    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            Toast.makeText(this, "Увімкни: Дозволи -> Додаткові дозволи -> Run Command", Toast.LENGTH_LONG).show()
        } catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) }
    }
    private fun openTermuxSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", "com.termux", null))) } catch (_: Exception) {}
    }
    private fun openStorageSettings() {
        try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    private fun openWifiSettings() {
        try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}
