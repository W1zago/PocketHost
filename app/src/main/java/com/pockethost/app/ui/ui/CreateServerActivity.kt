package com.pockethost.app.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pockethost.app.databinding.ActivityCreateServerBinding
import com.pockethost.app.embedded.EmbeddedPaths
import com.pockethost.core.model.MinecraftLoader
import com.pockethost.core.model.Server
import com.pockethost.core.model.ServerConfig
import com.pockethost.core.model.ServerType
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Creating a server with placement choice: internal, external app storage,
 * or an arbitrary folder (the last one - only with all-files access).
 * Arbitrary Download-type folders without this permission are unavailable as a process
 * work folder on modern Android - a system limitation, not the app’s.
 */
class CreateServerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CreateServerActivity"
        private val SAFE_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")
        // RAM slider (activity_create_server.xml): valueFrom=128, valueTo=3072, stepSize=64.
        // BaseSlider.validateValues throws IllegalStateException when value is not
        // valueFrom + k*stepSize (e.g. 2613.0 = totalMem*0.35 - FATAL on MINECRAFT tap).
        const val RAM_MIN = 128f
        const val RAM_MAX = 3072f
        const val RAM_STEP = 64f

        /** Normalizes ANY RAM value to the nearest valid slider step. */
        fun snapRam(value: Float): Float {
            val coerced = value.coerceIn(RAM_MIN, RAM_MAX)
            val steps = ((coerced - RAM_MIN) / RAM_STEP).roundToInt()
            return RAM_MIN + steps * RAM_STEP
        }
    }

    private lateinit var binding: ActivityCreateServerBinding
    private val repository by lazy {
        // Unified safe access (see PocketHostApplication.getRepositoryOrNull).
        com.pockethost.app.PocketHostApplication.getRepositoryOrNull()
            ?: com.pockethost.core.repository.ServerRepository(this@CreateServerActivity)
    }
    private var selectedServerType: ServerType = ServerType.MINECRAFT
    private var selectedStorage = EmbeddedPaths.StorageKind.INTERNAL
    /** Custom-placement root (picked in the file manager). */
    private var customRoot: String? = null
    /** After returning from system settings - open folder picker at once. */
    private var pendingFolderPickAfterSettings = false

    private val folderPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val path = result.data?.getStringExtra(FileManagerActivity.RESULT_SELECTED_PATH)
            if (!path.isNullOrEmpty()) {
                customRoot = path
                selectedStorage = EmbeddedPaths.StorageKind.CUSTOM
            }
        }
        updateStoragePreview()
    }

    private val writePermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openFolderPicker()
        else {
            Snackbar.make(binding.root, "No permission - cannot pick a custom folder", Snackbar.LENGTH_LONG).show()
            updateStoragePreview()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStoragePreview()
        if (pendingFolderPickAfterSettings) {
            pendingFolderPickAfterSettings = false
            if (EmbeddedPaths.hasAllFilesAccess(this)) openFolderPicker()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateServerBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
        setContentView(binding.root)

        setupToolbar()
        setupRuntimeCards()
        setupMinecraftSettings()
        setupRamSlider()
        setupPortVerify()
        setupDirectorySection()
        setupCreateButton()
        setupCancelButton()

        intent.getStringExtra("server_type")?.let { typeName ->
            try {
                selectedServerType = ServerType.valueOf(typeName)
            } catch (_: Exception) {}
            selectRuntime(selectedServerType)
        }

        // Built-in terminal generates the command itself
        binding.etStartCommand.isEnabled = false
        binding.etStartCommand.hint = "Generated automatically (built-in terminal)"
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Create Server"
    }

    /** Runtime selector cards - same visual language as design, but only REAL backend types. */
    private fun setupRuntimeCards() {
        binding.cardMinecraft.setOnClickListener { selectRuntime(ServerType.MINECRAFT) }
        binding.cardUbuntu.setOnClickListener { selectRuntime(ServerType.UBUNTU_SERVER) }
        selectRuntime(selectedServerType)
    }

    private fun selectRuntime(type: ServerType) {
        selectedServerType = type
        val selectedStroke = androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.primary)
        val idleStroke = androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.outline_variant)
        val selectedBg = androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.surface_high)
        val idleBg = androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.surface_container)
        val selectedCheck = androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.on_primary)
        val mcSelected = type == ServerType.MINECRAFT
        binding.cardMinecraft.strokeColor = if (mcSelected) selectedStroke else idleStroke
        binding.cardMinecraft.strokeWidth = if (mcSelected) 2 else 1
        binding.cardMinecraft.setCardBackgroundColor(if (mcSelected) selectedBg else idleBg)
        binding.checkMinecraft.setBackgroundResource(
            if (mcSelected) com.pockethost.app.R.drawable.bg_check_selected else com.pockethost.app.R.drawable.bg_check_idle
        )
        binding.checkMinecraft.setTextColor(if (mcSelected) selectedCheck else android.graphics.Color.TRANSPARENT)
        binding.cardUbuntu.strokeColor = if (!mcSelected) selectedStroke else idleStroke
        binding.cardUbuntu.strokeWidth = if (!mcSelected) 2 else 1
        binding.cardUbuntu.setCardBackgroundColor(if (!mcSelected) selectedBg else idleBg)
        binding.checkUbuntu.setBackgroundResource(
            if (!mcSelected) com.pockethost.app.R.drawable.bg_check_selected else com.pockethost.app.R.drawable.bg_check_idle
        )
        binding.checkUbuntu.setTextColor(if (!mcSelected) selectedCheck else android.graphics.Color.TRANSPARENT)
        updateDefaultValues()
        updateMinecraftSettingsVisibility()
        updateCreateButtonState()
    }

    private fun setupMinecraftSettings() {
        val versions = listOf(
            "1.21.4", "1.21.3", "1.21.1", "1.21",
            "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.20",
            "1.19.4", "1.19.3", "1.19.2", "1.19",
            "1.18.2", "1.18.1", "1.18",
            "1.17.1", "1.17",
            "1.16.5", "1.12.2", "1.8.9"
        )
        val verAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, versions)
        binding.spinnerMinecraftVersion.setAdapter(verAdapter)
        // Default 1.20.4: bundled JRE17 runs only Minecraft <=1.20.4
        // (1.20.5+ needs Java 21). Keep the list full - for 1.21.x
        // start honestly refuses with a JRE 21 hint.
        binding.spinnerMinecraftVersion.setText("1.20.4", false)

        val loaders = MinecraftLoader.entries.map { it.name }
        val loaderAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, loaders)
        binding.spinnerMinecraftLoader.setAdapter(loaderAdapter)
        binding.spinnerMinecraftLoader.setText(MinecraftLoader.PAPER.name, false)

        // Live render distance: live caption under the slider
        binding.mcSettings.tvViewDistance.text = viewDistanceLabel(binding.mcSettings.sliderViewDistance.value.toInt())
        binding.mcSettings.sliderViewDistance.addOnChangeListener { _, value, _ ->
            binding.mcSettings.tvViewDistance.text = viewDistanceLabel(value.toInt())
        }

        // Device memory (not app heap!): a child java process has its own heap.
        fun deviceTotalMb(): Int {
            return try {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                (mi.totalMem / 1024 / 1024).toInt().coerceAtLeast(1024)
            } catch (_: Exception) {
                (Runtime.getRuntime().maxMemory() / 1024 / 1024).toInt().coerceAtLeast(1024)
            }
        }
        val totalMb = deviceTotalMb()
        val recommendedMax = (totalMb * 0.35).toInt().coerceIn(512, 3072)
        val recommendedMin = (recommendedMax * 0.5).toInt().coerceAtLeast(256)
        // Round the default to a valid slider step (128 + k*64), otherwise FATAL 2613.
        val snappedRecommended = snapRam(recommendedMax.toFloat()).toInt()
        binding.etMaxMemory.setText(snappedRecommended.toString())
        binding.etMinMemory.setText(recommendedMin.toString())
        binding.tvRamHost.text = "Host • ${totalMb / 1024} GB total, $snappedRecommended MB recommended"
        binding.tvRamValue.text = "$snappedRecommended MB"
        setRamSliderSafe(snappedRecommended.toFloat())

        binding.btnUseMaxResources.setOnClickListener {
            val total = deviceTotalMb()
            val maxAllowed = (total * 0.5).toInt().coerceIn(512, 4096)
            val minAllowed = (maxAllowed * 0.5).toInt().coerceAtLeast(256)
            binding.etMaxMemory.setText(maxAllowed.toString())
            binding.etMinMemory.setText(minAllowed.toString())
            binding.tvRamValue.text = "$maxAllowed MB"
            // et stays exact for backend (JVM accepts any MB),
            // slider is only snapped+coerced preview (valueTo=3072, step 64).
            setRamSliderSafe(maxAllowed.toFloat())
            Snackbar.make(binding.root, "Using device RAM share: $maxAllowed MB (of ${total} MB)", Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Safe programmatic RAM-slider set.
     * coerceIn + snapToStep before set, runCatching + Log.w - NEVER throws,
     * ANY RAM value (128..deviceMem) is normalized.
     */
    private fun setRamSliderSafe(valueMb: Float) {
        val snapped = snapRam(valueMb)
        try {
            binding.sliderRam.value = snapped
        } catch (e: IllegalStateException) {
            Log.w(TAG, "setRamSliderSafe fallback: $valueMb -> $snapped", e)
            runCatching {
                binding.sliderRam.valueFrom = RAM_MIN
                binding.sliderRam.valueTo = RAM_MAX
                binding.sliderRam.stepSize = RAM_STEP
                binding.sliderRam.value = snapped
            }
        }
    }

    /** Two-way sync between RAM slider (design) and Max Memory field (backend value). */
    private var syncingRam = false

    private fun setupRamSlider() {
        binding.sliderRam.addOnChangeListener { _, value, _ ->
            if (syncingRam) return@addOnChangeListener
            syncingRam = true
            try {
                binding.etMaxMemory.setText(value.toInt().toString())
                binding.tvRamValue.text = "${value.toInt()} MB"
            } finally {
                syncingRam = false
            }
        }
        binding.etMaxMemory.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (syncingRam) return
                val v = s?.toString()?.toIntOrNull() ?: return
                if (v in 128..3072) {
                    syncingRam = true
                    try {
                        // Manual input may not be a multiple of 64 (e.g. 2613) - snap before set.
                        setRamSliderSafe(v.toFloat())
                        binding.tvRamValue.text = "$v MB"
                    } finally {
                        syncingRam = false
                    }
                }
            }
        })
    }

    /** Real port availability check via NetworkUtils (same check ServerService uses). */
    private fun setupPortVerify() {
        binding.btnVerifyPort.setOnClickListener {
            val port = binding.etPort.text?.toString()?.trim()?.toIntOrNull()
            if (port == null || port !in 1..65535) {
                binding.tvPortStatus.text = "Invalid port (1–65535)"
                binding.tvPortStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.badge_error_fg)
                )
                return@setOnClickListener
            }
            val free = try {
                com.pockethost.core.util.NetworkUtils.isPortAvailable(port)
            } catch (_: Exception) { true }
            if (free) {
                binding.tvPortStatus.text = "Port $port available"
                binding.tvPortStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.tertiary)
                )
            } else {
                binding.tvPortStatus.text = "Port $port is in use"
                binding.tvPortStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, com.pockethost.app.R.color.badge_error_fg)
                )
            }
        }
    }

    private fun setupCancelButton() {
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun setupDirectorySection() {
        // Placement choice: internal or app-external storage (no permissions).
        // Server folder inside is created automatically.
        binding.etWorkingDir.isEnabled = false
        binding.etWorkingDir.isFocusable = false
        binding.etWorkingDir.isClickable = false
        binding.btnBrowseDirectory.visibility = View.VISIBLE
        binding.btnBrowseDirectory.setOnClickListener { showStoragePicker() }
        updateStoragePreview()
        binding.btnCreate.isEnabled = false
        binding.etServerName.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateCreateButtonState() }
        })
        binding.etPort.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateCreateButtonState() }
        })
        updateCreateButtonState()
    }

    private fun viewDistanceLabel(chunks: Int): String =
        "$chunks chunks"

    private fun storageShortName(kind: EmbeddedPaths.StorageKind): String = when (kind) {
        EmbeddedPaths.StorageKind.EXTERNAL -> "external"
        EmbeddedPaths.StorageKind.CUSTOM -> "custom folder"
        else -> "internal"
    }

    private fun formatFree(bytes: Long): String {
        if (bytes < 0) return "unknown"
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1) String.format(java.util.Locale.US, "%.1f GB", gb)
        else "${bytes / 1024 / 1024} MB"
    }

    private fun storageRootPath(kind: EmbeddedPaths.StorageKind): String {
        return try {
            if (kind == EmbeddedPaths.StorageKind.EXTERNAL) {
                EmbeddedPaths.externalServersRoot(this)?.absolutePath ?: "unavailable"
            } else {
                EmbeddedPaths.serversRoot(this).absolutePath
            }
        } catch (_: Exception) { "?" }
    }

    private fun updateStoragePreview() {
        if (!::binding.isInitialized) return
        val rootText: String
        val freeText: String
        val label: String
        when (selectedStorage) {
            EmbeddedPaths.StorageKind.EXTERNAL -> {
                rootText = storageRootPath(EmbeddedPaths.StorageKind.EXTERNAL) + "/<auto>"
                freeText = formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.EXTERNAL))
                label = "external"
            }
            EmbeddedPaths.StorageKind.CUSTOM -> {
                val root = customRoot
                if (root.isNullOrEmpty()) {
                    rootText = "(folder not picked yet - press the button below)"
                    freeText = "—"
                } else {
                    rootText = "$root/<server name>"
                    freeText = formatFree(freeOfPath(root))
                }
                label = "custom folder"
            }
            else -> {
                rootText = storageRootPath(EmbeddedPaths.StorageKind.INTERNAL) + "/<auto>"
                freeText = formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.INTERNAL))
                label = "internal"
            }
        }
        binding.etWorkingDir.setText("$rootText - free $freeText")
        binding.btnBrowseDirectory.text = "Location: $label (change)"
    }

    private fun freeOfPath(path: String): Long {
        return try { android.os.StatFs(path).availableBytes } catch (_: Exception) { -1 }
    }

    private fun showStoragePicker() {
        val kinds = mutableListOf<EmbeddedPaths.StorageKind>()
        val items = mutableListOf<String>()
        kinds.add(EmbeddedPaths.StorageKind.INTERNAL)
        items.add(
            "Internal storage (free ${formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.INTERNAL))})\n" +
                storageRootPath(EmbeddedPaths.StorageKind.INTERNAL)
        )
        val notes = mutableListOf<String>()
        val extRoot = try { EmbeddedPaths.externalServersRoot(this) } catch (_: Exception) { null }
        if (extRoot != null) {
            kinds.add(EmbeddedPaths.StorageKind.EXTERNAL)
            items.add(
                "External storage - SD/USB, visible over USB (free ${formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.EXTERNAL))})\n" +
                    extRoot.absolutePath
            )
        } else {
            notes.add("External storage unavailable on this device (no SD card or storage ejected).")
        }
        kinds.add(EmbeddedPaths.StorageKind.CUSTOM)
        items.add(
            "Custom folder...\n" +
                (customRoot ?: "e.g. Downloads (needs all-files access)")
        )
        if (!EmbeddedPaths.hasAllFilesAccess(this) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        ) {
            notes.add("A custom folder needs 'all files access' - a system screen will open.")
        }
        val checked = kinds.indexOf(selectedStorage).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Server location")
            .setMessage(if (notes.isEmpty()) null else notes.joinToString("\n\n"))
            .setSingleChoiceItems(items.toTypedArray(), checked) { d, which ->
                val picked = kinds[which]
                d.dismiss()
                if (picked == EmbeddedPaths.StorageKind.CUSTOM) onCustomFolderPick()
                else {
                    selectedStorage = picked
                    updateStoragePreview()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onCustomFolderPick() {
        if (EmbeddedPaths.hasAllFilesAccess(this)) {
            openFolderPicker()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Files access")
                .setMessage("To pick a custom folder (e.g. Downloads), grant 'all files access' on the next system screen. Without it only the app internal and external storage are available.")
                .setPositiveButton("Open settings") { _, _ ->
                    pendingFolderPickAfterSettings = true
                    openAllFilesSettings()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            writePermLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun openAllFilesSettings() {
        for (intent in EmbeddedPaths.allFilesAccessIntents(this)) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        pendingFolderPickAfterSettings = false
        Snackbar.make(binding.root, "Failed to open settings", Snackbar.LENGTH_LONG).show()
    }

    // H4: getExternalStorageDirectory deprecated - here only the start folder for picker.
    // TODO: migrate folder picking to SAF (ACTION_OPEN_DOCUMENT_TREE) / MediaStore.
    @Suppress("DEPRECATION")
    private fun openFolderPicker() {
        val startDir = customRoot?.let {
            try {
                File(it).takeIf { f -> f.isDirectory }?.absolutePath
                    ?: File(it).parent
            } catch (_: Exception) { null }
        } ?: try {
            android.os.Environment.getExternalStorageDirectory().absolutePath
        } catch (_: Exception) {
            "/storage/emulated/0"
        }
        folderPickerLauncher.launch(FileManagerActivity.createSelectIntent(this, startDir))
    }

    /**
     * Target folder + URI + display for the new server.
     * null = reason already shown via Snackbar.
     */
    private fun resolveTargetDir(serverId: String, serverName: String): Triple<File, String, String>? {
        if (selectedStorage == EmbeddedPaths.StorageKind.CUSTOM) {
            val rootPath = customRoot
            if (rootPath.isNullOrEmpty()) {
                Snackbar.make(binding.root, "Pick a custom folder first", Snackbar.LENGTH_LONG).show()
                return null
            }
            if (!EmbeddedPaths.hasAllFilesAccess(this)) {
                Snackbar.make(binding.root, "No access to the folder. Grant 'all files access'.", Snackbar.LENGTH_LONG).show()
                return null
            }
            val root = File(rootPath)
            if (!root.isDirectory) {
                Snackbar.make(binding.root, "Folder not found: $rootPath", Snackbar.LENGTH_LONG).show()
                return null
            }
            // Subfolder by server name, on collision - with suffix. Never touch a foreign non-empty folder.
            val base = serverName.replace(SAFE_NAME_REGEX, "_").take(48).ifEmpty { "server" }
            var dir = File(root, base)
            var i = 2
            while (dir.exists() && dir.list()?.isNotEmpty() == true && i < 100) {
                dir = File(root, "$base-$i")
                i++
            }
            if (EmbeddedPaths.prepareCustomDir(dir.absolutePath) == null) {
                Snackbar.make(binding.root, "Folder not writable: ${dir.absolutePath}", Snackbar.LENGTH_LONG).show()
                return null
            }
            val abs = dir.absolutePath
            return Triple(dir, abs, abs)
        }
        val dir = try {
            EmbeddedPaths.serverDir(this, serverId, selectedStorage).apply { mkdirs() }
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Failed to create folder: ${e.message}", Snackbar.LENGTH_LONG).show()
            return null
        }
        if (!dir.exists() || !dir.canWrite()) {
            Snackbar.make(binding.root, "Storage not writable. Pick another location.", Snackbar.LENGTH_LONG).show()
            return null
        }
        val uri = EmbeddedPaths.storageUri(serverId, selectedStorage)
        return Triple(dir, uri, EmbeddedPaths.displayPath(this, serverId, selectedStorage))
    }

    private fun updateCreateButtonState() {
        val nameValid = binding.etServerName.text?.toString()?.trim()?.isNotEmpty() == true
        // Ubuntu shell/proot binds no port, so the field is hidden and skipped.
        val portValid = selectedServerType == ServerType.UBUNTU_SERVER ||
            binding.etPort.text?.toString()?.trim()?.toIntOrNull()?.let { it in 1..65535 } == true
        binding.btnCreate.isEnabled = nameValid && portValid
    }

    private fun updateMinecraftSettingsVisibility() {
        val isMinecraft = selectedServerType == ServerType.MINECRAFT
        binding.minecraftSettings.visibility = if (isMinecraft) View.VISIBLE else View.GONE
        // No listener port for Ubuntu: hide the whole row, keep stored default 22.
        binding.portRow.visibility = if (isMinecraft) View.VISIBLE else View.GONE
        binding.tvPortStatus.visibility = if (isMinecraft) View.VISIBLE else View.GONE
    }

    private fun updateDefaultValues() {
        when (selectedServerType) {
            ServerType.MINECRAFT -> {
                if (binding.etPort.text.isNullOrEmpty()) binding.etPort.setText("25565")
                binding.etStartCommand.setText("")
            }
            ServerType.UBUNTU_SERVER -> {
                if (binding.etPort.text.isNullOrEmpty()) binding.etPort.setText("22")
                binding.etStartCommand.setText("proot Ubuntu 22.04 (auto)")
            }
        }
        updateCreateButtonState()
    }

    private fun setupCreateButton() {
        binding.btnCreate.setOnClickListener { createServer() }
    }

    private fun createServer() {
        val name = binding.etServerName.text.toString().trim()
        if (selectedServerType == ServerType.UBUNTU_SERVER) {
            if (name.isEmpty()) {
                Snackbar.make(binding.root, "Enter server name", Snackbar.LENGTH_SHORT).show(); return
            }
            proceedCreateGeneric(name, 22)
            return
        }
        val portStr = binding.etPort.text.toString().trim()
        if (name.isEmpty()) {
            Snackbar.make(binding.root, "Enter server name", Snackbar.LENGTH_SHORT).show(); return
        }
        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Snackbar.make(binding.root, "Bad port", Snackbar.LENGTH_SHORT).show(); return
        }
        if (selectedServerType == ServerType.MINECRAFT) {
            val loaderName = try { binding.spinnerMinecraftLoader.text.toString() } catch (_: Exception) { "PAPER" }
            val loader = try { MinecraftLoader.valueOf(loaderName) } catch (_: Exception) { MinecraftLoader.PAPER }
            if (loader == MinecraftLoader.SPIGOT || loader == MinecraftLoader.BUKKIT || loader == MinecraftLoader.FORGE || loader == MinecraftLoader.NEOFORGE) {
                AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage("The $loaderName downloader may be unsupported. PAPER, PURPUR, VANILLA or FABRIC recommended.\n\nContinue with $loaderName?")
                    .setPositiveButton("Continue") { _, _ -> proceedCreateMinecraft(name, port) }
                    .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                    .show()
                return
            }
            proceedCreateMinecraft(name, port)
        } else {
            proceedCreateGeneric(name, port)
        }
    }

    private fun proceedCreateMinecraft(name: String, port: Int) {
        val maxMemory = binding.etMaxMemory.text.toString().toIntOrNull() ?: 1024
        val minMemory = binding.etMinMemory.text.toString().toIntOrNull() ?: 512
        val version = try { binding.spinnerMinecraftVersion.text.toString() } catch (_: Exception) { "1.20.4" }
        val loaderName = try { binding.spinnerMinecraftLoader.text.toString() } catch (_: Exception) { "PAPER" }
        val loader = try { MinecraftLoader.valueOf(loaderName) } catch (_: Exception) { MinecraftLoader.PAPER }

        // Cracked = license check disabled (online-mode=false).
        // Render distance 2..32 chunks, default 10.
        val allowPirate = binding.mcSettings.switchPirate.isChecked
        val viewDistance = binding.mcSettings.sliderViewDistance.value.toInt().coerceIn(2, 32)

        binding.btnCreate.isEnabled = false
        binding.btnCreate.text = "Creating..."

        val serverId = UUID.randomUUID().toString()
        val target = resolveTargetDir(serverId, name) ?: run {
            binding.btnCreate.isEnabled = true
            binding.btnCreate.text = getString(com.pockethost.app.R.string.create_server)
            return
        }
        val (dir, storageUri, display) = target

        val config = ServerConfig(
            startCommand = "",
            maxMemory = maxMemory,
            minMemory = minMemory,
            minecraftVersion = version,
            minecraftLoader = loader,
            onlineMode = !allowPirate,
            viewDistance = viewDistance
        )
        val server = Server(
            id = serverId,
            name = name,
            type = ServerType.MINECRAFT,
            port = port,
            workingDirectory = storageUri,
            workDirUri = storageUri,
            displayPath = display,
            termuxPath = dir.absolutePath,
            config = config
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                File(dir, "eula.txt").writeText("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).\neula=true")
                val props = """
                    server-port=$port
                    max-players=20
                    gamemode=survival
                    difficulty=normal
                    pvp=true
                    online-mode=${!allowPirate}
                    motd=A Minecraft Server - $name
                    view-distance=$viewDistance
                    spawn-protection=16
                    enable-command-block=false
                    allow-flight=false
                    white-list=false
                    enforce-whitelist=false
                """.trimIndent()
                File(dir, "server.properties").writeText(props)
                File(dir, "server.log").createNewFile()
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                lifecycleScope.launch {
                    repository.createServer(server)
                    Snackbar.make(binding.root, "Server \"$name\" created in ${storageShortName(selectedStorage)} storage. Press Start ($version, $loader)", Snackbar.LENGTH_LONG).show()
                    showProvisionDialog(server, dir)
                }
            }
        }
    }

    private fun showProvisionDialog(server: Server, dir: File) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Downloading Minecraft")
            .setMessage("Download ${server.config.minecraftLoader} ${server.config.minecraftVersion} now?\n(~50-100 MB)\n\nYou may skip - download happens on first Start.")
            .setPositiveButton("Download") { d, _ ->
                d.dismiss()
                startProvisioning(server, dir)
            }
            .setNegativeButton("Later") { d, _ ->
                d.dismiss()
                finish()
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    private fun startProvisioning(server: Server, dir: File) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Downloading...")
            .setMessage("Preparing Minecraft server...\n${server.config.minecraftLoader} ${server.config.minecraftVersion}")
            .setCancelable(false)
            .create()
        progressDialog.show()
        lifecycleScope.launch {
            val logLines = mutableListOf<String>()
            var lastMsg = ""
            val ok = try {
                provisionInternal(server, dir) { msg ->
                    lastMsg = msg
                    logLines.add(msg)
                    if (logLines.size > 30) logLines.removeAt(0)
                    runOnUiThread { progressDialog.setMessage(logLines.takeLast(8).joinToString("\n")) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { progressDialog.setMessage("Error: ${e.message}") }
                false
            }
            progressDialog.dismiss()
            binding.btnCreate.isEnabled = true
            binding.btnCreate.text = getString(com.pockethost.app.R.string.create_server)
            if (ok) {
                Snackbar.make(binding.root, "Ready! Press Start to launch.", Snackbar.LENGTH_LONG).show()
                kotlinx.coroutines.delay(1200)
                finish()
            } else {
                val details = if (logLines.isNotEmpty()) logLines.takeLast(10).joinToString("\n") else lastMsg.ifEmpty { "unknown error" }
                AlertDialog.Builder(this@CreateServerActivity)
                    .setTitle("Download error")
                    .setMessage("Failed to download jar.\n\nDetails:\n$details\n\nWith internet on - try VANILLA/PURPUR/FABRIC instead of PAPER, or drop the jar manually via Files.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
        }
    }

    private suspend fun provisionInternal(server: Server, dir: File, log: (String) -> Unit): Boolean {
        return try {
            val version = server.config.minecraftVersion ?: "1.20.4"
            val loader = server.config.minecraftLoader ?: MinecraftLoader.PAPER
            val manager = com.pockethost.plugins.minecraft.MinecraftServerManager(dir.parent ?: dir.absolutePath)
            manager.provisionIfNeeded(dir.absolutePath, version, loader, server.config.maxMemory, server.config.minMemory, server.port, log)
        } catch (e: Exception) {
            log("Provision exception: ${e.message}")
            false
        }
    }

    private fun proceedCreateGeneric(name: String, port: Int) {
        binding.btnCreate.isEnabled = false
        val serverId = UUID.randomUUID().toString()
        val target = resolveTargetDir(serverId, name) ?: run {
            binding.btnCreate.isEnabled = true
            return
        }
        val (dir, storageUri, display) = target
        try { File(dir, "server.log").createNewFile() } catch (_: Exception) {}
        val config = ServerConfig(startCommand = "sh -i")
        val server = Server(
            id = serverId,
            name = name,
            type = ServerType.UBUNTU_SERVER,
            port = port,
            workingDirectory = storageUri,
            workDirUri = storageUri,
            displayPath = display,
            termuxPath = dir.absolutePath,
            config = config
        )
        lifecycleScope.launch {
            repository.createServer(server)
            Snackbar.make(binding.root, "Server created in ${storageShortName(selectedStorage)} storage (built-in terminal)", Snackbar.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
