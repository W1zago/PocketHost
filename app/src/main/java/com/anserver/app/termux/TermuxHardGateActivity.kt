package com.anserver.app.termux

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
 * Legacy gate: більше не блокує. Вбудований термінал не потребує Termux.
 * Залишено для сумісності - одразу перенаправляє на MainActivity.
 */
@Deprecated("Вбудований термінал не потребує Termux")
class TermuxHardGateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try { finishAffinity() } catch (_: Exception) { try { finish() } catch (_: Exception) {} }
            }
        })
        // Автоміграція на вбудований термінал: жодних перевірок Termux
        try {
            startActivity(Intent(this, com.anserver.app.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
            return
        } catch (_: Exception) { /* fallback нижче покаже UI */ }
        try {
            buildGateUi()
        } catch (e: Exception) {
            android.util.Log.e("HardGate", "buildGateUi failed", e)
            // Fallback simple UI
            try {
                val tv = TextView(this).apply {
                    text = "Помилка Gate: ${e.message}\nСпробуй очистити дані додатку"
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
            text = "PocketHost вимагає Termux"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        val reasonText = TextView(this).apply {
            text = try { buildReasonText() } catch (e: Exception) { "Помилка перевірки: ${e.message}" }
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
            text = "Останній crash:\n$crashInfo"
            textSize = 11f
            setPadding(0, 0, 0, 16)
        } else null

        fun makeBtn(text: String, onClick: () -> Unit) = try {
            MaterialButton(this).apply {
                this.text = text
                setOnClickListener { try { onClick() } catch (e: Exception) { Toast.makeText(context, "Помилка: ${e.message}", Toast.LENGTH_SHORT).show() } }
            }
        } catch (e: Exception) {
            android.util.Log.e("HardGate", "MaterialButton failed, fallback", e)
            android.widget.Button(this).apply {
                this.text = text
                setOnClickListener { try { onClick() } catch (e2: Exception) { Toast.makeText(context, "Помилка: ${e2.message}", Toast.LENGTH_SHORT).show() } }
            }
        }

        val btnInstall = makeBtn("Встановити Termux (F-Droid)") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
            } catch (_: Exception) {
                Snackbar.make(root, "Не вдалося відкрити браузер", Snackbar.LENGTH_SHORT).show()
            }
        }
        val btnPermission = makeBtn("Дати дозвіл RUN_COMMAND") {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Snackbar.make(root, "Увімкніть Додаткові дозволи -> Run Command", Snackbar.LENGTH_LONG).show()
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
            }
        }
        val btnTermuxSettings = makeBtn("Відкрити налаштування Termux") {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", "com.termux", null))
                startActivity(intent)
            } catch (_: Exception) {}
        }
        val btnRecheck = makeBtn("Перевірити знову") {
            val ok = try { TermuxChecker.isAllOk(this@TermuxHardGateActivity) } catch (_: Exception) { false }
            if (ok) {
                try {
                    startActivity(Intent(this@TermuxHardGateActivity, com.anserver.app.ui.MainActivity::class.java))
                    finish()
                } catch (e: Exception) { Toast.makeText(this, "Не вдалося відкрити Main: ${e.message}", Toast.LENGTH_SHORT).show() }
            } else {
                reasonText.text = try { buildReasonText() } catch (e: Exception) { e.message }
                Snackbar.make(root, reasonText.text, Snackbar.LENGTH_LONG).show()
            }
        }
        val btnClear = makeBtn("Очистити старі сервери (SAF)") {
            lifecycleScope.launch {
                try {
                    com.anserver.app.ANServerApplication.serverRepository.clearAll()
                    // Also delete legacy filesDir
                    try { java.io.File(filesDir, "servers").deleteRecursively() } catch (_: Exception) {}
                    // Delete crash log
                    try { java.io.File(filesDir, "crash.log").delete() } catch (_: Exception) {}
                    Snackbar.make(root, "Очищено. Перезапусти додаток", Snackbar.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@TermuxHardGateActivity, "Помилка очищення: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnForceMain = makeBtn("Відкрити PocketHost без Termux (тест)") {
            try {
                startActivity(Intent(this@TermuxHardGateActivity, com.anserver.app.ui.MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) })
                finish()
            } catch (e: Exception) { Toast.makeText(this, "${e.message}", Toast.LENGTH_SHORT).show() }
        }
        val btnScan = makeBtn("Сканувати пристрій") {
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
                TermuxChecker.GateReason.NOT_INSTALLED -> "Termux не встановлено.\nВстановіть Termux з F-Droid (Play Store версія 0.101 застаріла і не має RUN_COMMAND)."
                TermuxChecker.GateReason.OUTDATED_PLAY_STORE -> "Встановлено застарілу Play Store версію Termux (versionCode <95).\nВидаліть Play Store версію, поставте F-Droid https://f-droid.org/packages/com.termux/"
                TermuxChecker.GateReason.PERMISSION_DENIED -> "Дозвіл RUN_COMMAND не надано.\nНа Samsung: Налаштування -> Додатки -> PocketHost -> Дозволи -> Інше (або Три крапки -> Додаткові дозволи) -> увімкни 'RUN_COMMAND' / 'Виконувати команди в Termux'. Це кастомний дозвіл Termux, тому Android показує його в категорії 'Інше'. Також перевір Termux -> termux-setup-storage.\n${TermuxChecker.getRunCommandDetails(this)}"
                TermuxChecker.GateReason.STORAGE_NOT_SETUP -> "Сховище Termux не налаштовано.\nВиконай в Termux: termux-setup-storage та дай дозвіл на файли."
                TermuxChecker.GateReason.OK -> "Все готово. Натисни Перевірити знову."
            }
        } catch (e: Exception) {
            "Помилка перевірки Termux: ${e.message}"
        }
    }

}
