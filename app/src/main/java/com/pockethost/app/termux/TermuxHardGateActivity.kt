package com.pockethost.app.termux

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Legacy gate: Р±С–Р»СЊС€Рµ РЅРµ Р±Р»РѕРєСѓС”. Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р» РЅРµ РїРѕС‚СЂРµР±СѓС” Termux.
 * Р—Р°Р»РёС€РµРЅРѕ РґР»СЏ СЃСѓРјС–СЃРЅРѕСЃС‚С– - РѕРґСЂР°Р·Сѓ РїРµСЂРµРЅР°РїСЂР°РІР»СЏС” РЅР° MainActivity.
 */
@Deprecated("Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р» РЅРµ РїРѕС‚СЂРµР±СѓС” Termux")
class TermuxHardGateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try { finishAffinity() } catch (_: Exception) { try { finish() } catch (_: Exception) {} }
            }
        })
        // РђРІС‚РѕРјС–РіСЂР°С†С–СЏ РЅР° РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»: Р¶РѕРґРЅРёС… РїРµСЂРµРІС–СЂРѕРє Termux
        try {
            startActivity(Intent(this, com.pockethost.app.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
            return
        } catch (_: Exception) { /* fallback РЅРёР¶С‡Рµ РїРѕРєР°Р¶Рµ UI */ }
        try {
            buildGateUi()
        } catch (e: Exception) {
            android.util.Log.e("HardGate", "buildGateUi failed", e)
            // Fallback simple UI
            try {
                val tv = TextView(this).apply {
                    text = "РџРѕРјРёР»РєР° Gate: ${e.message}\nРЎРїСЂРѕР±СѓР№ РѕС‡РёСЃС‚РёС‚Рё РґР°РЅС– РґРѕРґР°С‚РєСѓ"
                    setPadding(32, 32, 32, 32)
                }
                setContentView(tv)
            } catch (_: Exception) {
                finishAffinity()
            }
        }
    }

    private fun buildGateUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "PocketHost РІРёРјР°РіР°С” Termux"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        val reasonText = TextView(this).apply {
            text = try { buildReasonText() } catch (e: Exception) { "РџРѕРјРёР»РєР° РїРµСЂРµРІС–СЂРєРё: ${e.message}" }
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        // Show last crash if exists (diagnostic for Samsung A34)
        val crashInfo = try {
            val f = java.io.File(filesDir, "crash.log")
            if (f.exists() && f.length() > 0 && f.length() < 5000) f.readText().take(1000) else null
        } catch (_: Exception) { null }
        val crashView = if (crashInfo != null) TextView(this).apply {
            text = "РћСЃС‚Р°РЅРЅС–Р№ crash:\n$crashInfo"
            textSize = 11f
            setPadding(0, 0, 0, 16)
        } else null

        fun makeBtn(text: String, onClick: () -> Unit) = try {
            MaterialButton(this).apply {
                this.text = text
                setOnClickListener { try { onClick() } catch (e: Exception) { Toast.makeText(context, "РџРѕРјРёР»РєР°: ${e.message}", Toast.LENGTH_SHORT).show() } }
            }
        } catch (e: Exception) {
            android.util.Log.e("HardGate", "MaterialButton failed, fallback", e)
            android.widget.Button(this).apply {
                this.text = text
                setOnClickListener { try { onClick() } catch (e2: Exception) { Toast.makeText(context, "РџРѕРјРёР»РєР°: ${e2.message}", Toast.LENGTH_SHORT).show() } }
            }
        }

        val btnInstall = makeBtn("Р’СЃС‚Р°РЅРѕРІРёС‚Рё Termux (F-Droid)") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
            } catch (_: Exception) {
                Snackbar.make(root, "РќРµ РІРґР°Р»РѕСЃСЏ РІС–РґРєСЂРёС‚Рё Р±СЂР°СѓР·РµСЂ", Snackbar.LENGTH_SHORT).show()
            }
        }
        val btnPermission = makeBtn("Р”Р°С‚Рё РґРѕР·РІС–Р» RUN_COMMAND") {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Snackbar.make(root, "РЈРІС–РјРєРЅС–С‚СЊ Р”РѕРґР°С‚РєРѕРІС– РґРѕР·РІРѕР»Рё -> Run Command", Snackbar.LENGTH_LONG).show()
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
            }
        }
        val btnTermuxSettings = makeBtn("Р’С–РґРєСЂРёС‚Рё РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏ Termux") {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", "com.termux", null))
                startActivity(intent)
            } catch (_: Exception) {}
        }
        val btnRecheck = makeBtn("РџРµСЂРµРІС–СЂРёС‚Рё Р·РЅРѕРІСѓ") {
            val ok = try { TermuxChecker.isAllOk(this@TermuxHardGateActivity) } catch (_: Exception) { false }
            if (ok) {
                try {
                    startActivity(Intent(this@TermuxHardGateActivity, com.pockethost.app.ui.MainActivity::class.java))
                    finish()
                } catch (e: Exception) { Toast.makeText(this, "РќРµ РІРґР°Р»РѕСЃСЏ РІС–РґРєСЂРёС‚Рё Main: ${e.message}", Toast.LENGTH_SHORT).show() }
            } else {
                reasonText.text = try { buildReasonText() } catch (e: Exception) { e.message }
                Snackbar.make(root, reasonText.text, Snackbar.LENGTH_LONG).show()
            }
        }
        val btnClear = makeBtn("РћС‡РёСЃС‚РёС‚Рё СЃС‚Р°СЂС– СЃРµСЂРІРµСЂРё (SAF)") {
            lifecycleScope.launch {
                try {
                    com.pockethost.app.PocketHostApplication.serverRepository.clearAll()
                    // Also delete legacy filesDir
                    try { java.io.File(filesDir, "servers").deleteRecursively() } catch (_: Exception) {}
                    // Delete crash log
                    try { java.io.File(filesDir, "crash.log").delete() } catch (_: Exception) {}
                    Snackbar.make(root, "РћС‡РёС‰РµРЅРѕ. РџРµСЂРµР·Р°РїСѓСЃС‚Рё РґРѕРґР°С‚РѕРє", Snackbar.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@TermuxHardGateActivity, "РџРѕРјРёР»РєР° РѕС‡РёС‰РµРЅРЅСЏ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnForceMain = makeBtn("Р’С–РґРєСЂРёС‚Рё PocketHost Р±РµР· Termux (С‚РµСЃС‚)") {
            try {
                startActivity(Intent(this@TermuxHardGateActivity, com.pockethost.app.ui.MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) })
                finish()
            } catch (e: Exception) { Toast.makeText(this, "${e.message}", Toast.LENGTH_SHORT).show() }
        }
        val btnScan = makeBtn("РЎРєР°РЅСѓРІР°С‚Рё РїСЂРёСЃС‚СЂС–Р№") {
            try { startActivity(Intent(this@TermuxHardGateActivity, DeviceScanActivity::class.java)) } catch (e: Exception) { Toast.makeText(this, "${e.message}", Toast.LENGTH_SHORT).show() }
        }

        root.removeAllViews()
        root.addView(title)
        root.addView(reasonText)
        crashView?.let { root.addView(it) }
        listOf(btnInstall, btnPermission, btnTermuxSettings, btnRecheck, btnClear, btnForceMain, btnScan).forEach { btn ->
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 12, 0, 12)
            btn.layoutParams = lp
            root.addView(btn)
        }

        val scroll = androidx.core.widget.NestedScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun buildReasonText(): String {
        return try {
            when (TermuxChecker.getGateReason(this)) {
                TermuxChecker.GateReason.NOT_INSTALLED -> "Termux РЅРµ РІСЃС‚Р°РЅРѕРІР»РµРЅРѕ.\nР’СЃС‚Р°РЅРѕРІС–С‚СЊ Termux Р· F-Droid (Play Store РІРµСЂСЃС–СЏ 0.101 Р·Р°СЃС‚Р°СЂС–Р»Р° С– РЅРµ РјР°С” RUN_COMMAND)."
                TermuxChecker.GateReason.OUTDATED_PLAY_STORE -> "Р’СЃС‚Р°РЅРѕРІР»РµРЅРѕ Р·Р°СЃС‚Р°СЂС–Р»Сѓ Play Store РІРµСЂСЃС–СЋ Termux (versionCode <95).\nР’РёРґР°Р»С–С‚СЊ Play Store РІРµСЂСЃС–СЋ, РїРѕСЃС‚Р°РІС‚Рµ F-Droid https://f-droid.org/packages/com.termux/"
                TermuxChecker.GateReason.PERMISSION_DENIED -> "Р”РѕР·РІС–Р» RUN_COMMAND РЅРµ РЅР°РґР°РЅРѕ.\nРќР° Samsung: РќР°Р»Р°С€С‚СѓРІР°РЅРЅСЏ -> Р”РѕРґР°С‚РєРё -> PocketHost -> Р”РѕР·РІРѕР»Рё -> Р†РЅС€Рµ (Р°Р±Рѕ РўСЂРё РєСЂР°РїРєРё -> Р”РѕРґР°С‚РєРѕРІС– РґРѕР·РІРѕР»Рё) -> СѓРІС–РјРєРЅРё 'RUN_COMMAND' / 'Р’РёРєРѕРЅСѓРІР°С‚Рё РєРѕРјР°РЅРґРё РІ Termux'. Р¦Рµ РєР°СЃС‚РѕРјРЅРёР№ РґРѕР·РІС–Р» Termux, С‚РѕРјСѓ Android РїРѕРєР°Р·СѓС” Р№РѕРіРѕ РІ РєР°С‚РµРіРѕСЂС–С— 'Р†РЅС€Рµ'. РўР°РєРѕР¶ РїРµСЂРµРІС–СЂ Termux -> termux-setup-storage.\n${TermuxChecker.getRunCommandDetails(this)}"
                TermuxChecker.GateReason.STORAGE_NOT_SETUP -> "РЎС…РѕРІРёС‰Рµ Termux РЅРµ РЅР°Р»Р°С€С‚РѕРІР°РЅРѕ.\nР’РёРєРѕРЅР°Р№ РІ Termux: termux-setup-storage С‚Р° РґР°Р№ РґРѕР·РІС–Р» РЅР° С„Р°Р№Р»Рё."
                TermuxChecker.GateReason.OK -> "Р’СЃРµ РіРѕС‚РѕРІРѕ. РќР°С‚РёСЃРЅРё РџРµСЂРµРІС–СЂРёС‚Рё Р·РЅРѕРІСѓ."
            }
        } catch (e: Exception) {
            "РџРѕРјРёР»РєР° РїРµСЂРµРІС–СЂРєРё Termux: ${e.message}"
        }
    }

}
