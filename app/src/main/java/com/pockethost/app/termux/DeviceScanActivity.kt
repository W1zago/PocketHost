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
 * Р”С–Р°РіРЅРѕСЃС‚РёРєР° Р’Р‘РЈР”РћР’РђРќРћР“Рћ С‚РµСЂРјС–РЅР°Р»Сѓ.
 * Р–РѕРґРЅРёС… РїРµСЂРµРІС–СЂРѕРє Termux / RUN_COMMAND / SAF - РІСЃРµ РІСЃРµСЂРµРґРёРЅС– РґРѕРґР°С‚РєСѓ.
 */
class DeviceScanActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    // H6: Р±СѓРІ CoroutineScope(Dispatchers.Main) Р· РІРёС‚РѕРєРѕРј РїСЂРё Р·РЅРёС‰РµРЅРЅС– Activity вЂ”
    // С‚РµРїРµСЂ lifecycleScope РІСЃСЋРґРё (Р°РІС‚Рѕ-cancel РІ onDestroy).

    private val requestNotification = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(this, "РЎРїРѕРІС–С‰РµРЅРЅСЏ РґРѕР·РІРѕР»РµРЅРѕ", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "РЎРїРѕРІС–С‰РµРЅРЅСЏ РІС–РґС…РёР»РµРЅРѕ", Toast.LENGTH_SHORT).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "Р”С–Р°РіРЅРѕСЃС‚РёРєР°"
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
        // РЎРєРµР»РµС‚ РѕРґСЂР°Р·Сѓ, РІР°Р¶РєС– РїРµСЂРµРІС–СЂРєРё (fork sh) вЂ” Сѓ IO С‰РѕР± РЅРµ ANR.
        container.removeAllViews()
        addHeader()
        val loading = TextView(this).apply {
            text = "РџРµСЂРµРІС–СЂРєР°..."
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
                    addCheck("1. Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р» (sh)", res.shellOk, "Р”РµС‚Р°Р»С–") { showShellInfo() }
                    addCheck("2. Р’РЅСѓС‚СЂС–С€РЅС” СЃС…РѕРІРёС‰Рµ", res.storageOk, "Р’С–РґРєСЂРёС‚Рё СЃС…РѕРІРёС‰Рµ") { openStorageSettings() }
                    addCheck("3. Р’Р±СѓРґРѕРІР°РЅР° Java", res.javaOk, "Р’СЃС‚Р°РЅРѕРІРёС‚Рё JRE") { installJre() }
                    addCheck("4. Р’Р±СѓРґРѕРІР°РЅРёР№ shell РґР»СЏ Ubuntu", res.shellOk, "Р”РµС‚Р°Р»С–") { showShellInfo() }
                    addCheck("5. Р‘Р°С‚Р°СЂРµСЏ: С–РіРЅРѕСЂ РѕРїС‚РёРјС–Р·Р°С†С–С— (PocketHost)", res.batteryOk, "Р”РѕР·РІРѕР»РёС‚Рё С„РѕРЅ") { requestBatterySelf() }
                    addCheck("6. РЎРїРѕРІС–С‰РµРЅРЅСЏ POST_NOTIFICATIONS", res.notifOk, "Р—Р°РїСЂРѕСЃРёС‚Рё") { requestNotification() }
                    addCheck("7. Р’С–Р»СЊРЅРѕ РјС–СЃС†СЏ >512MB (${String.format("%.1f", res.freeGb)} GB)", res.spaceOk, "РћС‡РёСЃС‚РёС‚Рё") { openStorageSettings() }
                    addCheck("8. Р†РЅС‚РµСЂРЅРµС‚", checkInternetSync(), "РџРµСЂРµРІС–СЂРёС‚Рё РјРµСЂРµР¶Сѓ") { openWifiSettings() }
                    // РґРµС‚Р°Р»СЊ Java-С€Р»СЏС…Сѓ
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
            text = "PocketHost вЂ” РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface))
        }
        val subtitle = TextView(this).apply {
            text = "Р—РѕРІРЅС–С€РЅС–Р№ Termux РќР• РїРѕС‚СЂС–Р±РµРЅ. Р’СЃРµ РїСЂР°С†СЋС” РІСЃРµСЂРµРґРёРЅС– РґРѕРґР°С‚РєСѓ Р±РµР· РґРѕР·РІРѕР»Сѓ В«РєРµСЂСѓРІР°РЅРЅСЏ TermuxВ»."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@DeviceScanActivity, com.pockethost.app.R.color.on_surface_variant))
        }
        container.addView(title)
        container.addView(subtitle)

        val btnRescan = MaterialButton(this).apply {
            text = "РЎРєР°РЅСѓРІР°С‚Рё Р·РЅРѕРІСѓ"
            setOnClickListener { refreshWithInternet() }
        }
        val btnAuto = MaterialButton(this).apply {
            text = "РќР°РґР°С‚Рё РґРѕР·РІРѕР»Рё Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ"
            setOnClickListener { autoRequestAll() }
        }
        container.addView(btnRescan.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 8) } })
        container.addView(btnAuto.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 16) } })
    }

    private fun addActions() {
        val info = TextView(this).apply {
            text = "РџСЂРёРјС–С‚РєР°: SAF-РїР°РїРєРё С‚Р° RUN_COMMAND Р±С–Р»СЊС€Рµ РЅРµ РІРёРєРѕСЂРёСЃС‚РѕРІСѓСЋС‚СЊСЃСЏ. РЎС‚Р°СЂС– SAF-СЃРµСЂРІРµСЂРё Р·Р°РїСѓСЃРєР°СЋС‚СЊСЃСЏ РІ РјС–РіСЂР°С†С–Р№РЅРѕРјСѓ СЂРµР¶РёРјС– Сѓ РІРЅСѓС‚СЂС–С€РЅС–Р№ РїР°РїС†С–."
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
            text = if (ok) "вњ“" else "вњ—"
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
            title.contains("Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»") -> if (ok) "ProcessBuilder sh РїСЂР°С†СЋС”" else "sh РЅРµРґРѕСЃС‚СѓРїРЅРёР№ (РґСѓР¶Рµ СЂС–РґРєРѕ). РџРµСЂРµР·Р°РїСѓСЃС‚Рё РїСЂРёСЃС‚СЂС–Р№"
            title.contains("Р’РЅСѓС‚СЂС–С€РЅС” СЃС…РѕРІРёС‰Рµ") -> {
                val root = filesDir.absolutePath
                if (ok) "Р”РѕСЃС‚СѓРїРЅРѕ: $root" else "РќРµРјР° Р·Р°РїРёСЃСѓ РІ $root"
            }
            title.contains("Р’Р±СѓРґРѕРІР°РЅР° Java") -> {
                val java = try { com.pockethost.app.embedded.EmbeddedJava.findJava(this) } catch (_: Exception) { null }
                if (ok) "Р—РЅР°Р№РґРµРЅРѕ: $java" else "РќРµ Р·РЅР°Р№РґРµРЅРѕ. РќР°С‚РёСЃРЅРё В«Р’СЃС‚Р°РЅРѕРІРёС‚Рё JREВ» (РїРѕС‚СЂС–Р±РµРЅ С–РЅС‚РµСЂРЅРµС‚, ~100MB)"
            }
            title.contains("Ubuntu") -> if (ok) "sh -i РіРѕС‚РѕРІРёР№ РґР»СЏ РєРѕРјР°РЅРґ" else "sh РЅРµРґРѕСЃС‚СѓРїРЅРёР№"
            title.contains("Р‘Р°С‚Р°СЂРµСЏ") -> if (ok) "Р†РіРЅРѕСЂ РѕРїС‚РёРјС–Р·Р°С†С–С— СѓРІС–РјРєРЅРµРЅРѕ" else "РЎРёСЃС‚РµРјР° РјРѕР¶Рµ РІР±РёРІР°С‚Рё С„РѕРЅРѕРІРёР№ СЃРµСЂРІС–СЃ"
            title.contains("РЎРїРѕРІС–С‰РµРЅРЅСЏ") -> if (ok) "POST_NOTIFICATIONS РґРѕР·РІРѕР»РµРЅРѕ" else "РџРѕС‚СЂС–Р±РµРЅ РґР»СЏ foreground service"
            title.contains("Р’С–Р»СЊРЅРѕ РјС–СЃС†СЏ") -> {
                val free = getFreeSpaceGB()
                if (ok) "${String.format("%.1f", free)} GB РІС–Р»СЊРЅРѕ" else "${String.format("%.1f", free)} GB <0.5GB, Р·РІС–Р»СЊРЅРё РјС–СЃС†Рµ"
            }
            title.contains("Р†РЅС‚РµСЂРЅРµС‚") -> if (ok) "Р”РѕСЃС‚СѓРї С” (Р°Р±Рѕ РєРµС€)" else "РџРµСЂРµРІС–СЂРєР° РЅРµ РїСЂРѕР№С€Р»Р°. РЈРІС–РјРєРЅРё WiFi/РјРѕР±С–Р»СЊРЅС–"
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
            if (!result) Toast.makeText(this@DeviceScanActivity, "Р†РЅС‚РµСЂРЅРµС‚ РЅРµРґРѕСЃС‚СѓРїРЅРёР№", Toast.LENGTH_SHORT).show()
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
            else -> Toast.makeText(this, "Р’СЃРµ РіРѕС‚РѕРІРѕ: РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р» РЅРµ РїРѕС‚СЂРµР±СѓС” РґРѕР·РІРѕР»С–РІ", Toast.LENGTH_LONG).show()
        }
    }

    private fun showShellInfo() {
        Toast.makeText(this, "Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»: sh + stdin/stdout, Р»РѕРіРё РІ filesDir/servers/<id>/server.log", Toast.LENGTH_LONG).show()
    }

    private fun installJre() {
        Toast.makeText(this, "Р—Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ JRE СЃС‚Р°СЂС‚СѓС” РїСЂРё РїРµСЂС€РѕРјСѓ Р·Р°РїСѓСЃРєСѓ Minecraft-СЃРµСЂРІРµСЂР°", Toast.LENGTH_LONG).show()
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
