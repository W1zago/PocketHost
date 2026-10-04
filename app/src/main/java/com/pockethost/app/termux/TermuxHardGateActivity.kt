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
 * Legacy gate: no longer blocks. Built-in terminal does not need Termux.
 * Kept for compatibility - immediately redirects to MainActivity.
 */
@Deprecated("Built-in terminal does not need Termux")
class TermuxHardGateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                try { finishAffinity() } catch (_: Exception) { try { finish() } catch (_: Exception) {} }
            }
        })
        // Auto-migration to the built-in terminal: no Termux checks
        try {
            startActivity(Intent(this, com.pockethost.app.ui.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
            return
        } catch (_: Exception) { /* fallback shows UI below */ }
        try {
            buildGateUi()
        } catch (e: Exception) {
            android.util.Log.e("HardGate", "buildGateUi failed", e)
            // Fallback simple UI
            try {
                val tv = TextView(this).apply {
                    text = "Gate error: ${e.message}\nTry clearing app data"
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
            text = "PocketHost requires Termux"
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        val reasonText = TextView(this).apply {
            text = try { buildReasonText() } catch (e: Exception) { "Check error: ${e.message}" }
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
            text = "Last crash:\n$crashInfo"
            textSize = 11f
            setPadding(0, 0, 0, 16)
        } else null

        fun makeBtn(text: String, onClick: () -> Unit) = try {
            MaterialButton(this).apply {
                this.text = text
                setOnClickListener { try { onClick() } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() } }
            }
        } catch (e: Exception) {
            android.util.Log.e("HardGate", "MaterialButton failed, fallback", e)
            android.widget.Button(this).apply {
                this.text = text
                setOnClickListener { try { onClick() } catch (e2: Exception) { Toast.makeText(context, "Error: ${e2.message}", Toast.LENGTH_SHORT).show() } }
            }
        }

        val btnInstall = makeBtn("Install Termux (F-Droid)") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
            } catch (_: Exception) {
                Snackbar.make(root, "Failed to open browser", Snackbar.LENGTH_SHORT).show()
            }
        }
        val btnPermission = makeBtn("Grant RUN_COMMAND permission") {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Snackbar.make(root, "Enable Add-ons permissions -> Run Command", Snackbar.LENGTH_LONG).show()
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
            }
        }
        val btnTermuxSettings = makeBtn("Open Termux settings") {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", "com.termux", null))
                startActivity(intent)
            } catch (_: Exception) {}
        }
        val btnRecheck = makeBtn("Check again") {
            val ok = try { TermuxChecker.isAllOk(this@TermuxHardGateActivity) } catch (_: Exception) { false }
            if (ok) {
                try {
                    startActivity(Intent(this@TermuxHardGateActivity, com.pockethost.app.ui.MainActivity::class.java))
                    finish()
                } catch (e: Exception) { Toast.makeText(this, "Failed to open Main: ${e.message}", Toast.LENGTH_SHORT).show() }
            } else {
                reasonText.text = try { buildReasonText() } catch (e: Exception) { e.message }
                Snackbar.make(root, reasonText.text, Snackbar.LENGTH_LONG).show()
            }
        }
        val btnClear = makeBtn("Clear old servers (SAF)") {
            lifecycleScope.launch {
                try {
                    com.pockethost.app.PocketHostApplication.serverRepository.clearAll()
                    // Also delete legacy filesDir
                    try { java.io.File(filesDir, "servers").deleteRecursively() } catch (_: Exception) {}
                    // Delete crash log
                    try { java.io.File(filesDir, "crash.log").delete() } catch (_: Exception) {}
                    Snackbar.make(root, "Cleared. Restart the app", Snackbar.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@TermuxHardGateActivity, "Clear error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val btnForceMain = makeBtn("Open PocketHost without Termux (test)") {
            try {
                startActivity(Intent(this@TermuxHardGateActivity, com.pockethost.app.ui.MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP) })
                finish()
            } catch (e: Exception) { Toast.makeText(this, "${e.message}", Toast.LENGTH_SHORT).show() }
        }
        val btnScan = makeBtn("Scan device") {
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
                TermuxChecker.GateReason.NOT_INSTALLED -> "Termux is not installed.\nInstall Termux from F-Droid (the Play Store 0.101 build is outdated and lacks RUN_COMMAND)."
                TermuxChecker.GateReason.OUTDATED_PLAY_STORE -> "Outdated Play Store Termux build (versionCode <95).\nRemove the Play Store build, install F-Droid https://f-droid.org/packages/com.termux/"
                TermuxChecker.GateReason.PERMISSION_DENIED -> "RUN_COMMAND permission not granted.\nOn Samsung: Settings -> Apps -> PocketHost -> Permissions -> Other (or Three dots -> App permissions) -> enable 'RUN_COMMAND' / 'Execute commands in Termux'. This is a Termux custom permission, so Android lists it under 'Other'. Also check Termux -> termux-setup-storage.\n${TermuxChecker.getRunCommandDetails(this)}"
                TermuxChecker.GateReason.STORAGE_NOT_SETUP -> "Termux storage is not set up.\nRun in Termux: termux-setup-storage and grant files permission."
                TermuxChecker.GateReason.OK -> "All set. Press Check again."
            }
        } catch (e: Exception) {
            "Termux check error: ${e.message}"
        }
    }

}
