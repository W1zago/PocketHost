package com.anserver.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.anserver.app.ANServerApplication
import com.anserver.app.R
import com.anserver.app.databinding.ActivityMainBinding
import com.anserver.app.service.ServerService
import com.anserver.app.termux.DeviceScanActivity
import com.anserver.app.ui.adapter.ServerAdapter
import com.anserver.core.model.ServerType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serverAdapter: ServerAdapter
    private val repository by lazy {
        try { ANServerApplication.serverRepository } catch (e: Exception) {
            android.util.Log.e("MainActivity", "repo init failed", e)
            com.anserver.core.repository.ServerRepository(this)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
        private const val REQUEST_BATTERY = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Вбудований термінал: жодного гейту Termux / RUN_COMMAND більше нема.
        // Додаток працює одразу, без зовнішніх залежностей.

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFab()
        setupScanButton()
        checkPermissions()
        autoRequestBatteryIfNeeded()
        observeServers()
        autoRequestOnStartup()
    }

    override fun onResume() {
        super.onResume()
        // гейт видалено: вбудований термінал не потребує Termux
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupRecyclerView() {
        serverAdapter = ServerAdapter(
            onServerClick = { server ->
                val intent = Intent(this, ServerDetailActivity::class.java)
                intent.putExtra("server_id", server.id)
                startActivity(intent)
            },
            onServerStart = { server ->
                val intent = Intent(this, ServerService::class.java).apply {
                    action = "com.anserver.app.START_SERVER"
                    putExtra("server_id", server.id)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            },
            onServerStop = { server ->
                val intent = Intent(this, ServerService::class.java).apply {
                    action = "com.anserver.app.STOP_SERVER"
                    putExtra("server_id", server.id)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = serverAdapter
        }
    }

    private fun setupFab() {
        binding.fab.setOnClickListener { showCreateServerDialog() }
    }

    private fun setupScanButton() {
        try {
            binding.btnScan.setOnClickListener {
                startActivity(Intent(this, DeviceScanActivity::class.java))
            }
            // Long press for auto-request all
            binding.btnScan.setOnLongClickListener {
                autoRequestAllPermissions()
                true
            }
        } catch (_: Exception) {}
    }

    private fun showCreateServerDialog() {
        val types = ServerType.values().map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Create Server")
            .setItems(types) { _, which ->
                val selectedType = ServerType.values()[which]
                navigateToServerCreation(selectedType)
            }
            .setNeutralButton("Сканувати пристрій") { _, _ ->
                startActivity(Intent(this, DeviceScanActivity::class.java))
            }
            .show()
    }

    private fun navigateToServerCreation(type: ServerType) {
        val intent = Intent(this, CreateServerActivity::class.java)
        intent.putExtra("server_type", type.name)
        startActivity(intent)
    }

    private fun observeServers() {
        lifecycleScope.launch {
            try {
                repository.getAllServers().collect { servers ->
                    try {
                        val safe = servers.filter { it.name.isNotEmpty() }
                        serverAdapter.submitList(safe)
                        binding.emptyView.visibility = if (safe.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "submitList failed", e)
                        binding.emptyView.visibility = android.view.View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "observeServers failed, clearing", e)
                try {
                    lifecycleScope.launch {
                        try { repository.clearAll() } catch (_: Exception) {}
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(this@MainActivity, "Очищено пошкоджені сервери. Створіть заново", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun autoRequestBatteryIfNeeded() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val pkg = packageName
            if (!pm.isIgnoringBatteryOptimizations(pkg)) {
                // Show snackbar with action instead of auto-popup to avoid spam
                Snackbar.make(binding.root, "Дозволити PocketHost працювати в фоні?", Snackbar.LENGTH_LONG)
                    .setAction("Дозволити") { requestBattery(pkg) }
                    .show()
            }
            // Зовнішній Termux більше не використовується - запит для com.termux прибрано
        } catch (_: Exception) {}
    }

    private fun autoRequestAllPermissions() {
        // Вбудований термінал: лише notification + battery для самого додатку
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMISSIONS)
                return
            }
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            requestBattery(packageName)
            return
        }
        // Все готово - відкриваємо діагностику вбудованого терміналу
        startActivity(Intent(this, DeviceScanActivity::class.java))
        Snackbar.make(binding.root, "Все готово: вбудований термінал не потребує дозволів", Snackbar.LENGTH_LONG).show()
    }

    private fun autoRequestOnStartup() {
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1200)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_PERMISSIONS)
                        return@launch
                    }
                }
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    // Auto-request battery for app
                    Snackbar.make(binding.root, "Запитуємо дозвіл на фоновий режим...", Snackbar.LENGTH_SHORT).show()
                    requestBattery(packageName)
                    return@launch
                }
                // com.termux більше не потрібен
            } catch (_: Exception) {}
        }
    }

    private fun requestBattery(pkg: String) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            }
            startActivityForResult(intent, REQUEST_BATTERY)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Snackbar.make(binding.root, "Сповіщення відхилено. Сервіс може не показати статус", Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(binding.root, "Сповіщення дозволено", Snackbar.LENGTH_SHORT).show()
            }
            // Chain to battery
            autoRequestBatteryIfNeeded()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_BATTERY) {
            // Continue chain
            lifecycleScope.launch {
                kotlinx.coroutines.delay(500)
                autoRequestAllPermissions()
            }
        }
    }
}
