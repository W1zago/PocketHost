package com.pockethost.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.pockethost.app.PocketHostApplication
import com.pockethost.app.R
import com.pockethost.app.databinding.ActivityMainBinding
import com.pockethost.app.embedded.EmbeddedProcessManager
import com.pockethost.app.service.ServerService
import com.pockethost.app.termux.DeviceScanActivity
import com.pockethost.app.ui.adapter.ServerAdapter
import com.pockethost.core.model.Server
import com.pockethost.core.model.ServerStatus
import com.pockethost.core.model.ServerType
import com.pockethost.core.repository.ServerRepository
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serverAdapter: ServerAdapter
    // Full unfiltered list from backend; applyFilters() derives what the list shows.
    private var fullServers: List<Server> = emptyList()
    private var filterQuery: String = ""
    private var filterTag: String = "ALL"
    private var sortMode: String = "By Status"
    private val repository by lazy {
        // Unified safe access (see PocketHostApplication.getRepositoryOrNull).
        PocketHostApplication.getRepositoryOrNull() ?: run {
            Log.e("MainActivity", "repo not ready, using fallback")
            ServerRepository(this@MainActivity)
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Continue chain (same logic as old onActivityResult REQUEST_BATTERY)
        lifecycleScope.launch {
            delay(500)
            autoRequestAllPermissions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Built-in terminal: no Termux / RUN_COMMAND gate anymore.
        // The app works immediately, no external dependencies.

        binding = ActivityMainBinding.inflate(layoutInflater)
        // Edge-to-edge: draw under system bars, pad the root with real insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupFilters()
        setupFab()
        setupScanButton()
        checkPermissions()
        autoRequestBatteryIfNeeded()
        observeServers()
        autoRequestOnStartup()
    }

    override fun onResume() {
        super.onResume()
        // gate removed: built-in terminal does not need Termux
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        // Overflow is a real popup menu (same actions as the bottom buttons).
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_scan -> {
                    startActivity(Intent(this, DeviceScanActivity::class.java)); true
                }
                else -> false
            }
        }
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
                    action = "com.pockethost.app.START_SERVER"
                    putExtra("server_id", server.id)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            },
            onServerStop = { server ->
                val intent = Intent(this, ServerService::class.java).apply {
                    action = "com.pockethost.app.STOP_SERVER"
                    putExtra("server_id", server.id)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            },
            onServerRestart = { server -> restartServer(server.id) },
            onServerConsole = { server ->
                val intent = Intent(this, ConsoleActivity::class.java)
                intent.putExtra("server_id", server.id)
                startActivity(intent)
            },
            onServerFiles = { server -> openServerFiles(server.id) }
        )
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = serverAdapter
        }
    }

    private fun setupFilters() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterQuery = s?.toString()?.trim() ?: ""
                binding.btnClearSearch.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                applyFilters()
            }
        })
        binding.btnClearSearch.setOnClickListener { binding.searchInput.text?.clear() }
        binding.filterGroup.setOnCheckedStateChangeListener { group, _ ->
            val chip = group.findViewById<Chip>(group.checkedChipId)
            filterTag = chip?.tag?.toString() ?: "ALL"
            applyFilters()
        }
        val sorts = listOf("By Status", "By Name", "By Port")
        val sortAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sorts)
        binding.sortInput.setAdapter(sortAdapter)
        binding.sortInput.setOnItemClickListener { _, _, position, _ ->
            sortMode = sorts[position]
            applyFilters()
        }
    }

    /** Client-side search + filter + sort over the REAL backend list. */
    private fun applyFilters() {
        try {
            var list = fullServers.filter { it.name.isNotEmpty() }
            when (filterTag) {
                "RUNNING" -> list = list.filter {
                    it.status == ServerStatus.RUNNING ||
                        it.status == ServerStatus.STARTING
                }
                "STOPPED" -> list = list.filter {
                    it.status == ServerStatus.STOPPED ||
                        it.status == ServerStatus.ERROR
                }
                "ALL" -> {}
                else -> list = list.filter { it.type.name == filterTag }
            }
            if (filterQuery.isNotEmpty()) {
                val q = filterQuery.lowercase()
                list = list.filter {
                    it.name.lowercase().contains(q) ||
                        it.port.toString().contains(q) ||
                        it.type.name.lowercase().contains(q)
                }
            }
            list = when (sortMode) {
                "By Name" -> list.sortedBy { it.name.lowercase() }
                "By Port" -> list.sortedBy { it.port }
                else -> list.sortedWith(
                    compareBy(
                        {
                            when (it.status) {
                                ServerStatus.RUNNING -> 0
                                ServerStatus.STARTING -> 1
                                ServerStatus.STOPPING -> 2
                                ServerStatus.ERROR -> 3
                                else -> 4
                            }
                        },
                        { it.name.lowercase() }
                    )
                )
            }
            serverAdapter.submitList(list)
            val isEmpty = list.isEmpty()
            binding.emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
            // Hide INSTANCES + sort row when there are no servers at all —
            // otherwise it peeks from behind the empty card (see screenshot bug).
            binding.listHeader.visibility =
                if (fullServers.isEmpty()) View.GONE else View.VISIBLE
            updateChipCounts()
        } catch (e: Exception) {
            Log.e("MainActivity", "applyFilters failed", e)
        }
    }

    /** Chip counters show REAL backend totals per category (design). */
    private fun updateChipCounts() {
        try {
            val all = fullServers
            val running = all.count {
                it.status == ServerStatus.RUNNING || it.status == ServerStatus.STARTING
            }
            val stopped = all.count {
                it.status == ServerStatus.STOPPED || it.status == ServerStatus.ERROR
            }
            val mc = all.count { it.type == ServerType.MINECRAFT }
            val ubu = all.count { it.type == ServerType.UBUNTU_SERVER }
            binding.chipAll.text = "All ${all.size}"
            binding.chipRunning.text = "Running $running"
            binding.chipStopped.text = "Stopped $stopped"
            binding.chipMinecraft.text = "Minecraft $mc"
            binding.chipUbuntu.text = "Ubuntu $ubu"
        } catch (_: Exception) {}
    }

    private fun updateAlivePill(servers: List<Server>) {        try {
            val running = servers.count { it.status == ServerStatus.RUNNING }
            binding.aliveText.text = "$running/${servers.size} Alive"
            if (running > 0) {
                binding.alivePill.setBackgroundResource(R.drawable.bg_status_running)
                binding.aliveDot.setBackgroundResource(R.drawable.bg_dot_running)
                binding.aliveText.setTextColor(ContextCompat.getColor(this, R.color.badge_running_fg))
            } else {
                binding.alivePill.setBackgroundResource(R.drawable.bg_status_idle)
                binding.aliveDot.setBackgroundResource(R.drawable.bg_dot_idle)
                binding.aliveText.setTextColor(ContextCompat.getColor(this, R.color.badge_idle_fg))
            }
        } catch (_: Exception) {}
    }

    private fun restartServer(serverId: String) {
        Toast.makeText(this, "Restarting...", Toast.LENGTH_SHORT).show()
        val stop = Intent(this, ServerService::class.java).apply {
            action = "com.pockethost.app.STOP_SERVER"
            putExtra("server_id", serverId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(stop) else startService(stop)
        lifecycleScope.launch {
            // Same fix as ServerDetail: wait for STOPPED + !isRunning up to 60s,
            // start only when truly stopped - otherwise service refuses start
            // as "already running" and Restart looks like Stop.
            var stopped = false
            var waited = 0
            while (waited < 60_000) {
                delay(1000)
                waited += 1000
                try {
                    val s = repository.getServer(serverId).first()
                    val running = try {
                        EmbeddedProcessManager.isRunning(serverId)
                    } catch (_: Exception) { true }
                    if ((s?.status == ServerStatus.STOPPED || s?.status == ServerStatus.ERROR) && !running) {
                        stopped = true
                        break
                    }
                } catch (_: Exception) {}
            }
            if (stopped) {
                delay(2000)
                val start = Intent(this@MainActivity, ServerService::class.java).apply {
                    action = "com.pockethost.app.START_SERVER"
                    putExtra("server_id", serverId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(start) else startService(start)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Still stopping (world save). Press Start when Stopped.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openServerFiles(serverId: String) {
        lifecycleScope.launch {
            try {
                val server = repository.getServer(serverId).first()
                server?.let {
                    val intent = Intent(this@MainActivity, FileManagerActivity::class.java)
                    intent.putExtra("workDirUri", it.effectiveWorkDirUri.ifEmpty { it.workingDirectory })
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "openServerFiles failed", e)
            }
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
        try {
            binding.btnEmptyScan.setOnClickListener {
                startActivity(Intent(this, DeviceScanActivity::class.java))
            }
        } catch (_: Exception) {}
    }

    /** Create Instance bottom sheet: type picker + Dismiss (design). */
    private fun showCreateServerDialog() {
        val dialog = BottomSheetDialog(
            this, com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog
        )
        val view = layoutInflater.inflate(R.layout.dialog_create_instance, null)
        dialog.setContentView(view)
        try {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet
            )?.setBackgroundResource(android.R.color.transparent)
        } catch (_: Exception) {}
        view.findViewById<android.view.View>(R.id.sheetClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<android.view.View>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        view.findViewById<android.view.View>(R.id.optionMinecraft).setOnClickListener {
            dialog.dismiss()
            navigateToServerCreation(ServerType.MINECRAFT)
        }
        view.findViewById<android.view.View>(R.id.optionUbuntu).setOnClickListener {
            dialog.dismiss()
            navigateToServerCreation(ServerType.UBUNTU_SERVER)
        }
        dialog.show()
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
                        fullServers = safe
                        applyFilters()
                        updateAlivePill(safe)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "submitList failed", e)
                        binding.emptyView.visibility = View.VISIBLE
                        binding.recyclerView.visibility = View.GONE
                        binding.listHeader.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "observeServers failed, clearing", e)
                try {
                    lifecycleScope.launch {
                        try { repository.clearAll() } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Cleared corrupted servers. Recreate them", Toast.LENGTH_LONG).show()
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
                Snackbar.make(binding.root, "Allow PocketHost to run in background?", Snackbar.LENGTH_LONG)
                    .setAction("Allow") { requestBattery(pkg) }
                    .show()
            }
            // External Termux is no longer used - the com.termux request is removed
        } catch (_: Exception) {}
    }

    private fun autoRequestAllPermissions() {
        // Built-in terminal: only notification + battery for the app itself
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
        // Everything ready - open the built-in terminal diagnostics
        startActivity(Intent(this, DeviceScanActivity::class.java))
        Snackbar.make(binding.root, "All set: built-in terminal needs no permissions", Snackbar.LENGTH_LONG).show()
    }

    private fun autoRequestOnStartup() {
        lifecycleScope.launch {
            delay(1200)
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
                    Snackbar.make(binding.root, "Requesting background permission...", Snackbar.LENGTH_SHORT).show()
                    requestBattery(packageName)
                    return@launch
                }
                // com.termux no longer needed
            } catch (_: Exception) {}
        }
    }

    private fun requestBattery(pkg: String) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            }
            batteryLauncher.launch(intent)
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                Snackbar.make(binding.root, "Notifications denied. Service may not show status", Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(binding.root, "Notifications granted", Snackbar.LENGTH_SHORT).show()
            }
            // Chain to battery
            autoRequestBatteryIfNeeded()
        }
    }
}
