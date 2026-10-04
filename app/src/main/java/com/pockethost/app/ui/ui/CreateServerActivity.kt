package com.pockethost.app.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
 * РЎС‚РІРѕСЂРµРЅРЅСЏ СЃРµСЂРІРµСЂР° Р· РІРёР±РѕСЂРѕРј СЂРѕР·С‚Р°С€СѓРІР°РЅРЅСЏ: РІРЅСѓС‚СЂС–С€РЅС”, Р·РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ
 * РґРѕРґР°С‚РєСѓ Р°Р±Рѕ РґРѕРІС–Р»СЊРЅР° РїР°РїРєР° (РѕСЃС‚Р°РЅРЅСЏ вЂ” Р»РёС€Рµ Р· РґРѕСЃС‚СѓРїРѕРј РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ).
 * Р”РѕРІС–Р»СЊРЅС– РїР°РїРєРё С‚РёРїСѓ Downloads Р±РµР· С†СЊРѕРіРѕ РґРѕР·РІРѕР»Сѓ РЅРµРґРѕСЃС‚СѓРїРЅС– СЏРє СЂРѕР±РѕС‡Р°
 * РїР°РїРєР° РїСЂРѕС†РµСЃСѓ РЅР° СЃСѓС‡Р°СЃРЅРѕРјСѓ Android вЂ” С†Рµ РѕР±РјРµР¶РµРЅРЅСЏ СЃРёСЃС‚РµРјРё, РЅРµ РґРѕРґР°С‚РєСѓ.
 */
class CreateServerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CreateServerActivity"
        private val SAFE_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")
        // RAM-СЃР»Р°Р№РґРµСЂ (activity_create_server.xml): valueFrom=128, valueTo=3072, stepSize=64.
        // BaseSlider.validateValues РєРёРґР°С” IllegalStateException, СЏРєС‰Рѕ value РЅРµ РґРѕСЂС–РІРЅСЋС”
        // valueFrom + k*stepSize (РЅР°РїСЂ. 2613.0 = totalMem*0.35 вЂ” FATAL РїСЂРё С‚Р°РїС– MINECRAFT).
        const val RAM_MIN = 128f
        const val RAM_MAX = 3072f
        const val RAM_STEP = 64f

        /** РќРѕСЂРјР°Р»С–Р·СѓС” Р‘РЈР”Р¬-РЇРљР• Р·РЅР°С‡РµРЅРЅСЏ RAM РґРѕ РЅР°Р№Р±Р»РёР¶С‡РѕРіРѕ РІР°Р»С–РґРЅРѕРіРѕ РєСЂРѕРєСѓ СЃР»Р°Р№РґРµСЂР°. */
        fun snapRam(value: Float): Float {
            val coerced = value.coerceIn(RAM_MIN, RAM_MAX)
            val steps = ((coerced - RAM_MIN) / RAM_STEP).roundToInt()
            return RAM_MIN + steps * RAM_STEP
        }
    }

    private lateinit var binding: ActivityCreateServerBinding
    private val repository by lazy {
        // РЈРЅС–С„С–РєРѕРІР°РЅРёР№ Р±РµР·РїРµС‡РЅРёР№ РґРѕСЃС‚СѓРї (РґРёРІ. PocketHostApplication.getRepositoryOrNull).
        com.pockethost.app.PocketHostApplication.getRepositoryOrNull()
            ?: com.pockethost.core.repository.ServerRepository(this@CreateServerActivity)
    }
    private var selectedServerType: ServerType = ServerType.MINECRAFT
    private var selectedStorage = EmbeddedPaths.StorageKind.INTERNAL
    /** РљРѕСЂС–РЅСЊ РґРѕРІС–Р»СЊРЅРѕРіРѕ СЂРѕР·С‚Р°С€СѓРІР°РЅРЅСЏ (РІРёР±СЂР°РЅРёР№ Сѓ С„Р°Р№Р»РѕРІРѕРјСѓ РјРµРЅРµРґР¶РµСЂС–). */
    private var customRoot: String? = null
    /** РџС–СЃР»СЏ РїРѕРІРµСЂРЅРµРЅРЅСЏ Р· СЃРёСЃС‚РµРјРЅРёС… РЅР°Р»Р°С€С‚СѓРІР°РЅСЊ вЂ” РѕРґСЂР°Р·Сѓ РІС–РґРєСЂРёС‚Рё РІРёР±С–СЂ РїР°РїРєРё. */
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
            Snackbar.make(binding.root, "Р‘РµР· РґРѕР·РІРѕР»Сѓ РґРѕРІС–Р»СЊРЅСѓ РїР°РїРєСѓ РЅРµ РІРёР±СЂР°С‚Рё", Snackbar.LENGTH_LONG).show()
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

        // Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р» РіРµРЅРµСЂСѓС” РєРѕРјР°РЅРґСѓ СЃР°Рј
        binding.etStartCommand.isEnabled = false
        binding.etStartCommand.hint = "Р“РµРЅРµСЂСѓС”С‚СЊСЃСЏ Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ (РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»)"
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Create Server"
    }

    /** Runtime selector cards вЂ” same visual language as design, but only REAL backend types. */
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
        // Default 1.20.4: РІР±СѓРґРѕРІР°РЅРёР№ JRE17 Р·Р°РїСѓСЃРєР°С” Р»РёС€Рµ Minecraft <=1.20.4
        // (1.20.5+ РїРѕС‚СЂРµР±СѓС” Java 21). РЎРїРёСЃРѕРє Р»РёС€Р°С”РјРѕ РїРѕРІРЅРёРј вЂ” РґР»СЏ 1.21.x
        // СЃС‚Р°СЂС‚ С‡РµСЃРЅРѕ РІС–РґРјРѕРІРёС‚СЊ Р· РїС–РґРєР°Р·РєРѕСЋ РїСЂРѕ JRE 21.
        binding.spinnerMinecraftVersion.setText("1.20.4", false)

        val loaders = MinecraftLoader.entries.map { it.name }
        val loaderAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, loaders)
        binding.spinnerMinecraftLoader.setAdapter(loaderAdapter)
        binding.spinnerMinecraftLoader.setText(MinecraftLoader.PAPER.name, false)

        // Р”Р°Р»СЊРЅС–СЃС‚СЊ РїСЂРѕРјР°Р»СЊРѕРІРєРё: Р¶РёРІРёР№ РїС–РґРїРёСЃ РїС–Рґ РїРѕРІР·СѓРЅРєРѕРј
        binding.tvViewDistance.text = viewDistanceLabel(binding.sliderViewDistance.value.toInt())
        binding.sliderViewDistance.addOnChangeListener { _, value, _ ->
            binding.tvViewDistance.text = viewDistanceLabel(value.toInt())
        }

        // РџР°РјКјСЏС‚СЊ РїСЂРёСЃС‚СЂРѕСЋ (РЅРµ heap РґРѕРґР°С‚РєСѓ!): РґРѕС‡С–СЂРЅС–Р№ java-РїСЂРѕС†РµСЃ РјР°С” РІР»Р°СЃРЅСѓ РєСѓРїСѓ.
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
        // РћРєСЂСѓРіР»СЋС”РјРѕ РґРµС„РѕР»С‚ РґРѕ РІР°Р»С–РґРЅРѕРіРѕ РєСЂРѕРєСѓ СЃР»Р°Р№РґРµСЂР° (128 + k*64), С–РЅР°РєС€Рµ FATAL 2613.
        val snappedRecommended = snapRam(recommendedMax.toFloat()).toInt()
        binding.etMaxMemory.setText(snappedRecommended.toString())
        binding.etMinMemory.setText(recommendedMin.toString())
        binding.tvRamHost.text = "Host вЂў ${totalMb / 1024} GB total, $snappedRecommended MB recommended"
        binding.tvRamValue.text = "$snappedRecommended MB"
        setRamSliderSafe(snappedRecommended.toFloat())

        binding.btnUseMaxResources.setOnClickListener {
            val total = deviceTotalMb()
            val maxAllowed = (total * 0.5).toInt().coerceIn(512, 4096)
            val minAllowed = (maxAllowed * 0.5).toInt().coerceAtLeast(256)
            binding.etMaxMemory.setText(maxAllowed.toString())
            binding.etMinMemory.setText(minAllowed.toString())
            binding.tvRamValue.text = "$maxAllowed MB"
            // et Р»РёС€Р°С”РјРѕ С‚РѕС‡РЅРёРј РґР»СЏ backend (JVM РїСЂРёР№РјР°С” Р±СѓРґСЊ-СЏРєС– MB),
            // СЃР»Р°Р№РґРµСЂ вЂ” Р»РёС€Рµ snapped+coerced РїСЂРµРІ'СЋ (valueTo=3072, РєСЂРѕРє 64).
            setRamSliderSafe(maxAllowed.toFloat())
            Snackbar.make(binding.root, "Using device RAM share: $maxAllowed MB (Р· ${total} MB)", Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Р‘РµР·РїРµС‡РЅРµ РїСЂРѕРіСЂР°РјРЅРµ РІСЃС‚Р°РЅРѕРІР»РµРЅРЅСЏ RAM-СЃР»Р°Р№РґРµСЂР°.
     * coerceIn + snapToStep РїРµСЂРµРґ set, runCatching + Log.w вЂ” РќР†РљРћР›Р РЅРµ РєРёРґР°С”,
     * Р‘РЈР”Р¬-РЇРљР• Р·РЅР°С‡РµРЅРЅСЏ RAM (128..deviceMem) РЅРѕСЂРјР°Р»С–Р·СѓС”С‚СЊСЃСЏ.
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
                        // Р СѓС‡РЅРµ РІРІРµРґРµРЅРЅСЏ РјРѕР¶Рµ Р±СѓС‚Рё РЅРµ РєСЂР°С‚РЅРёРј 64 (РЅР°РїСЂ. 2613) вЂ” СЃРЅР°РїР°С”РјРѕ РїРµСЂРµРґ set.
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
                binding.tvPortStatus.text = "Invalid port (1вЂ“65535)"
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
        // Р’РёР±С–СЂ СЂРѕР·С‚Р°С€СѓРІР°РЅРЅСЏ: РІРЅСѓС‚СЂС–С€РЅС” Р°Р±Рѕ Р·РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ РґРѕРґР°С‚РєСѓ (Р±РµР· РґРѕР·РІРѕР»С–РІ).
        // РџР°РїРєР° СЃРµСЂРІРµСЂР° РІСЃРµСЂРµРґРёРЅС– СЃС‚РІРѕСЂСЋС”С‚СЊСЃСЏ Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ.
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
        "Р”Р°Р»СЊРЅС–СЃС‚СЊ РїСЂРѕРјР°Р»СЊРѕРІРєРё: $chunks С‡Р°РЅРєС–РІ"

    private fun storageShortName(kind: EmbeddedPaths.StorageKind): String = when (kind) {
        EmbeddedPaths.StorageKind.EXTERNAL -> "Р·РѕРІРЅС–С€РЅСЊРѕРјСѓ"
        EmbeddedPaths.StorageKind.CUSTOM -> "РІРєР°Р·Р°РЅС–Р№ РїР°РїС†С–"
        else -> "РІРЅСѓС‚СЂС–С€РЅСЊРѕРјСѓ"
    }

    private fun formatFree(bytes: Long): String {
        if (bytes < 0) return "РЅРµРІС–РґРѕРјРѕ"
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1) String.format(java.util.Locale.US, "%.1f Р“Р‘", gb)
        else "${bytes / 1024 / 1024} РњР‘"
    }

    private fun storageRootPath(kind: EmbeddedPaths.StorageKind): String {
        return try {
            if (kind == EmbeddedPaths.StorageKind.EXTERNAL) {
                EmbeddedPaths.externalServersRoot(this)?.absolutePath ?: "РЅРµРґРѕСЃС‚СѓРїРЅРµ"
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
                rootText = storageRootPath(EmbeddedPaths.StorageKind.EXTERNAL) + "/<Р°РІС‚Рѕ>"
                freeText = formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.EXTERNAL))
                label = "Р·РѕРІРЅС–С€РЅС”"
            }
            EmbeddedPaths.StorageKind.CUSTOM -> {
                val root = customRoot
                if (root.isNullOrEmpty()) {
                    rootText = "(РїР°РїРєСѓ С‰Рµ РЅРµ РІРёР±СЂР°РЅРѕ вЂ” РЅР°С‚РёСЃРЅРё РєРЅРѕРїРєСѓ РЅРёР¶С‡Рµ)"
                    freeText = "вЂ”"
                } else {
                    rootText = "$root/<РЅР°Р·РІР° СЃРµСЂРІРµСЂР°>"
                    freeText = formatFree(freeOfPath(root))
                }
                label = "РґРѕРІС–Р»СЊРЅР° РїР°РїРєР°"
            }
            else -> {
                rootText = storageRootPath(EmbeddedPaths.StorageKind.INTERNAL) + "/<Р°РІС‚Рѕ>"
                freeText = formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.INTERNAL))
                label = "РІРЅСѓС‚СЂС–С€РЅС”"
            }
        }
        binding.etWorkingDir.setText("$rootText вЂ” РІС–Р»СЊРЅРѕ $freeText")
        binding.btnBrowseDirectory.text = "Р РѕР·С‚Р°С€СѓРІР°РЅРЅСЏ: $label (Р·РјС–РЅРёС‚Рё)"
    }

    private fun freeOfPath(path: String): Long {
        return try { android.os.StatFs(path).availableBytes } catch (_: Exception) { -1 }
    }

    private fun showStoragePicker() {
        val kinds = mutableListOf<EmbeddedPaths.StorageKind>()
        val items = mutableListOf<String>()
        kinds.add(EmbeddedPaths.StorageKind.INTERNAL)
        items.add(
            "Р’РЅСѓС‚СЂС–С€РЅС” СЃС…РѕРІРёС‰Рµ (РІС–Р»СЊРЅРѕ ${formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.INTERNAL))})\n" +
                storageRootPath(EmbeddedPaths.StorageKind.INTERNAL)
        )
        val notes = mutableListOf<String>()
        val extRoot = try { EmbeddedPaths.externalServersRoot(this) } catch (_: Exception) { null }
        if (extRoot != null) {
            kinds.add(EmbeddedPaths.StorageKind.EXTERNAL)
            items.add(
                "Р—РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ вЂ” SD/USB, РІРёРґРЅРѕ С‡РµСЂРµР· USB (РІС–Р»СЊРЅРѕ ${formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.EXTERNAL))})\n" +
                    extRoot.absolutePath
            )
        } else {
            notes.add("Р—РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ РЅРµРґРѕСЃС‚СѓРїРЅРµ РЅР° С†СЊРѕРјСѓ РїСЂРёСЃС‚СЂРѕС— (РЅРµРјР° SD-РєР°СЂС‚Рё Р°Р±Рѕ СЃС…РѕРІРёС‰Рµ РІРёРјРєРЅРµРЅРµ).")
        }
        kinds.add(EmbeddedPaths.StorageKind.CUSTOM)
        items.add(
            "Р”РѕРІС–Р»СЊРЅР° РїР°РїРєР°вЂ¦\n" +
                (customRoot ?: "РЅР°РїСЂРёРєР»Р°Рґ Downloads (РїРѕС‚СЂС–Р±РµРЅ РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІ)")
        )
        if (!EmbeddedPaths.hasAllFilesAccess(this) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        ) {
            notes.add("Р”Р»СЏ РґРѕРІС–Р»СЊРЅРѕС— РїР°РїРєРё РїРѕС‚СЂС–Р±РµРЅ В«РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІВ» вЂ” РІС–РґРєСЂРёС”С‚СЊСЃСЏ СЃРёСЃС‚РµРјРЅРёР№ РµРєСЂР°РЅ.")
        }
        val checked = kinds.indexOf(selectedStorage).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Р РѕР·С‚Р°С€СѓРІР°РЅРЅСЏ СЃРµСЂРІРµСЂР°")
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
            .setNegativeButton("РЎРєР°СЃСѓРІР°С‚Рё", null)
            .show()
    }

    private fun onCustomFolderPick() {
        if (EmbeddedPaths.hasAllFilesAccess(this)) {
            openFolderPicker()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Р”РѕСЃС‚СѓРї РґРѕ С„Р°Р№Р»С–РІ")
                .setMessage("Р©РѕР± РѕР±СЂР°С‚Рё РґРѕРІС–Р»СЊРЅСѓ РїР°РїРєСѓ (РЅР°РїСЂРёРєР»Р°Рґ Downloads), РЅР°РґР°Р№ В«РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІВ» РЅР° РЅР°СЃС‚СѓРїРЅРѕРјСѓ СЃРёСЃС‚РµРјРЅРѕРјСѓ РµРєСЂР°РЅС–. Р‘РµР· РЅСЊРѕРіРѕ РґРѕСЃС‚СѓРїРЅС– РІРЅСѓС‚СЂС–С€РЅС” С‚Р° Р·РѕРІРЅС–С€РЅС” СЃС…РѕРІРёС‰Рµ РґРѕРґР°С‚РєСѓ.")
                .setPositiveButton("Р’С–РґРєСЂРёС‚Рё РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏ") { _, _ ->
                    pendingFolderPickAfterSettings = true
                    openAllFilesSettings()
                }
                .setNegativeButton("РЎРєР°СЃСѓРІР°С‚Рё", null)
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
        Snackbar.make(binding.root, "РќРµ РІРґР°Р»РѕСЃСЏ РІС–РґРєСЂРёС‚Рё РЅР°Р»Р°С€С‚СѓРІР°РЅРЅСЏ", Snackbar.LENGTH_LONG).show()
    }

    // H4: getExternalStorageDirectory deprecated вЂ” С‚СѓС‚ Р»РёС€Рµ СЃС‚Р°СЂС‚РѕРІР° РїР°РїРєР° РґР»СЏ picker.
    // TODO: РјС–РіСЂСѓРІР°С‚Рё РЅР° SAF (ACTION_OPEN_DOCUMENT_TREE) / MediaStore.
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
     * Р¦С–Р»СЊРѕРІР° РїР°РїРєР° + URI + display РґР»СЏ РЅРѕРІРѕРіРѕ СЃРµСЂРІРµСЂР°.
     * null = РїСЂРёС‡РёРЅСѓ РІР¶Рµ РїРѕРєР°Р·Р°РЅРѕ С‡РµСЂРµР· Snackbar.
     */
    private fun resolveTargetDir(serverId: String, serverName: String): Triple<File, String, String>? {
        if (selectedStorage == EmbeddedPaths.StorageKind.CUSTOM) {
            val rootPath = customRoot
            if (rootPath.isNullOrEmpty()) {
                Snackbar.make(binding.root, "РЎРїРѕС‡Р°С‚РєСѓ РѕР±РµСЂРё РґРѕРІС–Р»СЊРЅСѓ РїР°РїРєСѓ", Snackbar.LENGTH_LONG).show()
                return null
            }
            if (!EmbeddedPaths.hasAllFilesAccess(this)) {
                Snackbar.make(binding.root, "РќРµРјР° РґРѕСЃС‚СѓРїСѓ РґРѕ РїР°РїРєРё. РќР°РґР°Р№ В«РґРѕСЃС‚СѓРї РґРѕ РІСЃС–С… С„Р°Р№Р»С–РІВ».", Snackbar.LENGTH_LONG).show()
                return null
            }
            val root = File(rootPath)
            if (!root.isDirectory) {
                Snackbar.make(binding.root, "РџР°РїРєСѓ РЅРµ Р·РЅР°Р№РґРµРЅРѕ: $rootPath", Snackbar.LENGTH_LONG).show()
                return null
            }
            // РџС–РґРїР°РїРєР° Р·Р° РЅР°Р·РІРѕСЋ СЃРµСЂРІРµСЂР°, РїСЂРё Р·Р°Р№РЅСЏС‚РѕСЃС‚С– вЂ” Р· СЃСѓС„С–РєСЃРѕРј. РќРµРїРѕСЂРѕР¶РЅСЋ С‡СѓР¶Сѓ РїР°РїРєСѓ РЅРµ С‡С–РїР°С”РјРѕ.
            val base = serverName.replace(SAFE_NAME_REGEX, "_").take(48).ifEmpty { "server" }
            var dir = File(root, base)
            var i = 2
            while (dir.exists() && dir.list()?.isNotEmpty() == true && i < 100) {
                dir = File(root, "$base-$i")
                i++
            }
            if (EmbeddedPaths.prepareCustomDir(dir.absolutePath) == null) {
                Snackbar.make(binding.root, "РџР°РїРєР° РЅРµРґРѕСЃС‚СѓРїРЅР° РґР»СЏ Р·Р°РїРёСЃСѓ: ${dir.absolutePath}", Snackbar.LENGTH_LONG).show()
                return null
            }
            val abs = dir.absolutePath
            return Triple(dir, abs, abs)
        }
        val dir = try {
            EmbeddedPaths.serverDir(this, serverId, selectedStorage).apply { mkdirs() }
        } catch (e: Exception) {
            Snackbar.make(binding.root, "РќРµ РІРґР°Р»РѕСЃСЏ СЃС‚РІРѕСЂРёС‚Рё РїР°РїРєСѓ: ${e.message}", Snackbar.LENGTH_LONG).show()
            return null
        }
        if (!dir.exists() || !dir.canWrite()) {
            Snackbar.make(binding.root, "РЎС…РѕРІРёС‰Рµ РЅРµРґРѕСЃС‚СѓРїРЅРµ РґР»СЏ Р·Р°РїРёСЃСѓ. РћР±РµСЂРё С–РЅС€Рµ СЂРѕР·С‚Р°С€СѓРІР°РЅРЅСЏ.", Snackbar.LENGTH_LONG).show()
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
            Snackbar.make(binding.root, "Р’РІРµРґС–С‚СЊ РЅР°Р·РІСѓ СЃРµСЂРІРµСЂР°", Snackbar.LENGTH_SHORT).show(); return
        }
        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Snackbar.make(binding.root, "РќРµРІС–СЂРЅРёР№ РїРѕСЂС‚", Snackbar.LENGTH_SHORT).show(); return
        }
        if (selectedServerType == ServerType.MINECRAFT) {
            val loaderName = try { binding.spinnerMinecraftLoader.text.toString() } catch (_: Exception) { "PAPER" }
            val loader = try { MinecraftLoader.valueOf(loaderName) } catch (_: Exception) { MinecraftLoader.PAPER }
            if (loader == MinecraftLoader.SPIGOT || loader == MinecraftLoader.BUKKIT || loader == MinecraftLoader.FORGE || loader == MinecraftLoader.NEOFORGE) {
                AlertDialog.Builder(this)
                    .setTitle("РџРѕРїРµСЂРµРґР¶РµРЅРЅСЏ")
                    .setMessage("Р—Р°РІР°РЅС‚Р°Р¶СѓРІР°С‡ $loaderName РјРѕР¶Рµ РЅРµ РїС–РґС‚СЂРёРјСѓРІР°С‚РёСЃСЏ. Р РµРєРѕРјРµРЅРґСѓС”С‚СЊСЃСЏ PAPER, PURPUR, VANILLA С‡Рё FABRIC.\n\nРџСЂРѕРґРѕРІР¶РёС‚Рё Р· $loaderName?")
                    .setPositiveButton("РџСЂРѕРґРѕРІР¶РёС‚Рё") { _, _ -> proceedCreateMinecraft(name, port) }
                    .setNegativeButton("РЎРєР°СЃСѓРІР°С‚Рё") { d, _ -> d.dismiss() }
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

        // РџС–СЂР°С‚РєР° = РІРёРјРєРЅРµРЅР° РїРµСЂРµРІС–СЂРєР° Р»С–С†РµРЅР·С–С— (online-mode=false).
        // РџСЂРѕРјР°Р»СЊРѕРІРєР° 2..32 С‡Р°РЅРєРё, РґРµС„РѕР»С‚ 10.
        val allowPirate = binding.switchPirate.isChecked
        val viewDistance = binding.sliderViewDistance.value.toInt().coerceIn(2, 32)

        binding.btnCreate.isEnabled = false
        binding.btnCreate.text = "РЎС‚РІРѕСЂРµРЅРЅСЏ..."

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
                    Snackbar.make(binding.root, "РЎРµСЂРІРµСЂ \"$name\" СЃС‚РІРѕСЂРµРЅРѕ Сѓ ${storageShortName(selectedStorage)} СЃС…РѕРІРёС‰С–. РќР°С‚РёСЃРЅС–С‚СЊ Start ($version, $loader)", Snackbar.LENGTH_LONG).show()
                    showProvisionDialog(server, dir)
                }
            }
        }
    }

    private fun showProvisionDialog(server: Server, dir: File) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Р—Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ Minecraft")
            .setMessage("Р—Р°РІР°РЅС‚Р°Р¶РёС‚Рё ${server.config.minecraftLoader} ${server.config.minecraftVersion} Р·Р°СЂР°Р·?\n(~50-100 MB)\n\nРњРѕР¶РЅР° РїСЂРѕРїСѓСЃС‚РёС‚Рё - Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ РІС–РґР±СѓРґРµС‚СЊСЃСЏ РїСЂРё РїРµСЂС€РѕРјСѓ Start.")
            .setPositiveButton("Р—Р°РІР°РЅС‚Р°Р¶РёС‚Рё") { d, _ ->
                d.dismiss()
                startProvisioning(server, dir)
            }
            .setNegativeButton("РџС–Р·РЅС–С€Рµ") { d, _ ->
                d.dismiss()
                finish()
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    private fun startProvisioning(server: Server, dir: File) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Р—Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ...")
            .setMessage("РџС–РґРіРѕС‚РѕРІРєР° Minecraft СЃРµСЂРІРµСЂСѓ...\n${server.config.minecraftLoader} ${server.config.minecraftVersion}")
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
                withContext(Dispatchers.Main) { progressDialog.setMessage("РџРѕРјРёР»РєР°: ${e.message}") }
                false
            }
            progressDialog.dismiss()
            binding.btnCreate.isEnabled = true
            binding.btnCreate.text = getString(com.pockethost.app.R.string.create_server)
            if (ok) {
                Snackbar.make(binding.root, "Р“РѕС‚РѕРІРѕ! РќР°С‚РёСЃРЅС–С‚СЊ Start РґР»СЏ Р·Р°РїСѓСЃРєСѓ.", Snackbar.LENGTH_LONG).show()
                kotlinx.coroutines.delay(1200)
                finish()
            } else {
                val details = if (logLines.isNotEmpty()) logLines.takeLast(10).joinToString("\n") else lastMsg.ifEmpty { "РЅРµРІС–РґРѕРјР° РїРѕРјРёР»РєР°" }
                AlertDialog.Builder(this@CreateServerActivity)
                    .setTitle("РџРѕРјРёР»РєР° Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ")
                    .setMessage("РќРµ РІРґР°Р»РѕСЃСЏ Р·Р°РІР°РЅС‚Р°Р¶РёС‚Рё jar.\n\nР”РµС‚Р°Р»С–:\n$details\n\nРЇРєС‰Рѕ С–РЅС‚РµСЂРЅРµС‚ СѓРІС–РјРєРЅРµРЅРёР№ - СЃРїСЂРѕР±СѓР№ VANILLA/PURPUR/FABRIC Р·Р°РјС–СЃС‚СЊ PAPER, Р°Р±Рѕ РїРѕРєР»Р°РґРё jar РІСЂСѓС‡РЅСѓ С‡РµСЂРµР· Files.")
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
            Snackbar.make(binding.root, "РЎРµСЂРІРµСЂ СЃС‚РІРѕСЂРµРЅРѕ Сѓ ${storageShortName(selectedStorage)} СЃС…РѕРІРёС‰С– (РІР±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»)", Snackbar.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
