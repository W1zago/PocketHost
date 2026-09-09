package com.anserver.app.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anserver.app.databinding.ActivityCreateServerBinding
import com.anserver.app.embedded.EmbeddedPaths
import com.anserver.core.model.MinecraftLoader
import com.anserver.core.model.Server
import com.anserver.core.model.ServerConfig
import com.anserver.core.model.ServerType
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Створення сервера з вибором розташування: внутрішнє, зовнішнє сховище
 * додатку або довільна папка (остання — лише з доступом до всіх файлів).
 * Довільні папки типу Downloads без цього дозволу недоступні як робоча
 * папка процесу на сучасному Android — це обмеження системи, не додатку.
 */
class CreateServerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CreateServerActivity"
        private val SAFE_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")
        // RAM-слайдер (activity_create_server.xml): valueFrom=128, valueTo=3072, stepSize=64.
        // BaseSlider.validateValues кидає IllegalStateException, якщо value не дорівнює
        // valueFrom + k*stepSize (напр. 2613.0 = totalMem*0.35 — FATAL при тапі MINECRAFT).
        const val RAM_MIN = 128f
        const val RAM_MAX = 3072f
        const val RAM_STEP = 64f

        /** Нормалізує БУДЬ-ЯКЕ значення RAM до найближчого валідного кроку слайдера. */
        fun snapRam(value: Float): Float {
            val coerced = value.coerceIn(RAM_MIN, RAM_MAX)
            val steps = ((coerced - RAM_MIN) / RAM_STEP).roundToInt()
            return RAM_MIN + steps * RAM_STEP
        }
    }

    private lateinit var binding: ActivityCreateServerBinding
    private val repository by lazy {
        // Уніфікований безпечний доступ (див. ANServerApplication.getRepositoryOrNull).
        com.anserver.app.ANServerApplication.getRepositoryOrNull()
            ?: com.anserver.core.repository.ServerRepository(this@CreateServerActivity)
    }
    private var selectedServerType: ServerType = ServerType.MINECRAFT
    private var selectedStorage = EmbeddedPaths.StorageKind.INTERNAL
    /** Корінь довільного розташування (вибраний у файловому менеджері). */
    private var customRoot: String? = null
    /** Після повернення з системних налаштувань — одразу відкрити вибір папки. */
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
            Snackbar.make(binding.root, "Без дозволу довільну папку не вибрати", Snackbar.LENGTH_LONG).show()
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

        // Вбудований термінал генерує команду сам
        binding.etStartCommand.isEnabled = false
        binding.etStartCommand.hint = "Генерується автоматично (вбудований термінал)"
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Create Server"
    }

    /** Runtime selector cards — same visual language as design, but only REAL backend types. */
    private fun setupRuntimeCards() {
        binding.cardMinecraft.setOnClickListener { selectRuntime(ServerType.MINECRAFT) }
        binding.cardUbuntu.setOnClickListener { selectRuntime(ServerType.UBUNTU_SERVER) }
        selectRuntime(selectedServerType)
    }

    private fun selectRuntime(type: ServerType) {
        selectedServerType = type
        val selectedStroke = androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.primary)
        val idleStroke = androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.outline_variant)
        val selectedBg = androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.surface_high)
        val idleBg = androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.surface_container)
        val selectedCheck = androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.on_primary)
        val mcSelected = type == ServerType.MINECRAFT
        binding.cardMinecraft.strokeColor = if (mcSelected) selectedStroke else idleStroke
        binding.cardMinecraft.strokeWidth = if (mcSelected) 2 else 1
        binding.cardMinecraft.setCardBackgroundColor(if (mcSelected) selectedBg else idleBg)
        binding.checkMinecraft.setBackgroundResource(
            if (mcSelected) com.anserver.app.R.drawable.bg_check_selected else com.anserver.app.R.drawable.bg_check_idle
        )
        binding.checkMinecraft.setTextColor(if (mcSelected) selectedCheck else android.graphics.Color.TRANSPARENT)
        binding.cardUbuntu.strokeColor = if (!mcSelected) selectedStroke else idleStroke
        binding.cardUbuntu.strokeWidth = if (!mcSelected) 2 else 1
        binding.cardUbuntu.setCardBackgroundColor(if (!mcSelected) selectedBg else idleBg)
        binding.checkUbuntu.setBackgroundResource(
            if (!mcSelected) com.anserver.app.R.drawable.bg_check_selected else com.anserver.app.R.drawable.bg_check_idle
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
        // Default 1.20.4: вбудований JRE17 запускає лише Minecraft <=1.20.4
        // (1.20.5+ потребує Java 21). Список лишаємо повним — для 1.21.x
        // старт чесно відмовить з підказкою про JRE 21.
        binding.spinnerMinecraftVersion.setText("1.20.4", false)

        val loaders = MinecraftLoader.entries.map { it.name }
        val loaderAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, loaders)
        binding.spinnerMinecraftLoader.setAdapter(loaderAdapter)
        binding.spinnerMinecraftLoader.setText(MinecraftLoader.PAPER.name, false)

        // Дальність промальовки: живий підпис під повзунком
        binding.tvViewDistance.text = viewDistanceLabel(binding.sliderViewDistance.value.toInt())
        binding.sliderViewDistance.addOnChangeListener { _, value, _ ->
            binding.tvViewDistance.text = viewDistanceLabel(value.toInt())
        }

        // Памʼять пристрою (не heap додатку!): дочірній java-процес має власну купу.
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
        // Округлюємо дефолт до валідного кроку слайдера (128 + k*64), інакше FATAL 2613.
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
            // et лишаємо точним для backend (JVM приймає будь-які MB),
            // слайдер — лише snapped+coerced прев'ю (valueTo=3072, крок 64).
            setRamSliderSafe(maxAllowed.toFloat())
            Snackbar.make(binding.root, "Using device RAM share: $maxAllowed MB (з ${total} MB)", Snackbar.LENGTH_SHORT).show()
        }
    }

    /**
     * Безпечне програмне встановлення RAM-слайдера.
     * coerceIn + snapToStep перед set, runCatching + Log.w — НІКОЛИ не кидає,
     * БУДЬ-ЯКЕ значення RAM (128..deviceMem) нормалізується.
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
                        // Ручне введення може бути не кратним 64 (напр. 2613) — снапаємо перед set.
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
                    androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.badge_error_fg)
                )
                return@setOnClickListener
            }
            val free = try {
                com.anserver.core.util.NetworkUtils.isPortAvailable(port)
            } catch (_: Exception) { true }
            if (free) {
                binding.tvPortStatus.text = "Port $port available"
                binding.tvPortStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.tertiary)
                )
            } else {
                binding.tvPortStatus.text = "Port $port is in use"
                binding.tvPortStatus.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this, com.anserver.app.R.color.badge_error_fg)
                )
            }
        }
    }

    private fun setupCancelButton() {
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun setupDirectorySection() {
        // Вибір розташування: внутрішнє або зовнішнє сховище додатку (без дозволів).
        // Папка сервера всередині створюється автоматично.
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
        "Дальність промальовки: $chunks чанків"

    private fun storageShortName(kind: EmbeddedPaths.StorageKind): String = when (kind) {
        EmbeddedPaths.StorageKind.EXTERNAL -> "зовнішньому"
        EmbeddedPaths.StorageKind.CUSTOM -> "вказаній папці"
        else -> "внутрішньому"
    }

    private fun formatFree(bytes: Long): String {
        if (bytes < 0) return "невідомо"
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return if (gb >= 1) String.format(java.util.Locale.US, "%.1f ГБ", gb)
        else "${bytes / 1024 / 1024} МБ"
    }

    private fun storageRootPath(kind: EmbeddedPaths.StorageKind): String {
        return try {
            if (kind == EmbeddedPaths.StorageKind.EXTERNAL) {
                EmbeddedPaths.externalServersRoot(this)?.absolutePath ?: "недоступне"
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
                rootText = storageRootPath(EmbeddedPaths.StorageKind.EXTERNAL) + "/<авто>"
                freeText = formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.EXTERNAL))
                label = "зовнішнє"
            }
            EmbeddedPaths.StorageKind.CUSTOM -> {
                val root = customRoot
                if (root.isNullOrEmpty()) {
                    rootText = "(папку ще не вибрано — натисни кнопку нижче)"
                    freeText = "—"
                } else {
                    rootText = "$root/<назва сервера>"
                    freeText = formatFree(freeOfPath(root))
                }
                label = "довільна папка"
            }
            else -> {
                rootText = storageRootPath(EmbeddedPaths.StorageKind.INTERNAL) + "/<авто>"
                freeText = formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.INTERNAL))
                label = "внутрішнє"
            }
        }
        binding.etWorkingDir.setText("$rootText — вільно $freeText")
        binding.btnBrowseDirectory.text = "Розташування: $label (змінити)"
    }

    private fun freeOfPath(path: String): Long {
        return try { android.os.StatFs(path).availableBytes } catch (_: Exception) { -1 }
    }

    private fun showStoragePicker() {
        val kinds = mutableListOf<EmbeddedPaths.StorageKind>()
        val items = mutableListOf<String>()
        kinds.add(EmbeddedPaths.StorageKind.INTERNAL)
        items.add(
            "Внутрішнє сховище (вільно ${formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.INTERNAL))})\n" +
                storageRootPath(EmbeddedPaths.StorageKind.INTERNAL)
        )
        val notes = mutableListOf<String>()
        val extRoot = try { EmbeddedPaths.externalServersRoot(this) } catch (_: Exception) { null }
        if (extRoot != null) {
            kinds.add(EmbeddedPaths.StorageKind.EXTERNAL)
            items.add(
                "Зовнішнє сховище — SD/USB, видно через USB (вільно ${formatFree(EmbeddedPaths.freeBytes(this, EmbeddedPaths.StorageKind.EXTERNAL))})\n" +
                    extRoot.absolutePath
            )
        } else {
            notes.add("Зовнішнє сховище недоступне на цьому пристрої (нема SD-карти або сховище вимкнене).")
        }
        kinds.add(EmbeddedPaths.StorageKind.CUSTOM)
        items.add(
            "Довільна папка…\n" +
                (customRoot ?: "наприклад Downloads (потрібен доступ до всіх файлів)")
        )
        if (!EmbeddedPaths.hasAllFilesAccess(this) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        ) {
            notes.add("Для довільної папки потрібен «доступ до всіх файлів» — відкриється системний екран.")
        }
        val checked = kinds.indexOf(selectedStorage).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Розташування сервера")
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
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun onCustomFolderPick() {
        if (EmbeddedPaths.hasAllFilesAccess(this)) {
            openFolderPicker()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Доступ до файлів")
                .setMessage("Щоб обрати довільну папку (наприклад Downloads), надай «доступ до всіх файлів» на наступному системному екрані. Без нього доступні внутрішнє та зовнішнє сховище додатку.")
                .setPositiveButton("Відкрити налаштування") { _, _ ->
                    pendingFolderPickAfterSettings = true
                    openAllFilesSettings()
                }
                .setNegativeButton("Скасувати", null)
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
        Snackbar.make(binding.root, "Не вдалося відкрити налаштування", Snackbar.LENGTH_LONG).show()
    }

    // H4: getExternalStorageDirectory deprecated — тут лише стартова папка для picker.
    // TODO: мігрувати на SAF (ACTION_OPEN_DOCUMENT_TREE) / MediaStore.
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
     * Цільова папка + URI + display для нового сервера.
     * null = причину вже показано через Snackbar.
     */
    private fun resolveTargetDir(serverId: String, serverName: String): Triple<File, String, String>? {
        if (selectedStorage == EmbeddedPaths.StorageKind.CUSTOM) {
            val rootPath = customRoot
            if (rootPath.isNullOrEmpty()) {
                Snackbar.make(binding.root, "Спочатку обери довільну папку", Snackbar.LENGTH_LONG).show()
                return null
            }
            if (!EmbeddedPaths.hasAllFilesAccess(this)) {
                Snackbar.make(binding.root, "Нема доступу до папки. Надай «доступ до всіх файлів».", Snackbar.LENGTH_LONG).show()
                return null
            }
            val root = File(rootPath)
            if (!root.isDirectory) {
                Snackbar.make(binding.root, "Папку не знайдено: $rootPath", Snackbar.LENGTH_LONG).show()
                return null
            }
            // Підпапка за назвою сервера, при зайнятості — з суфіксом. Непорожню чужу папку не чіпаємо.
            val base = serverName.replace(SAFE_NAME_REGEX, "_").take(48).ifEmpty { "server" }
            var dir = File(root, base)
            var i = 2
            while (dir.exists() && dir.list()?.isNotEmpty() == true && i < 100) {
                dir = File(root, "$base-$i")
                i++
            }
            if (EmbeddedPaths.prepareCustomDir(dir.absolutePath) == null) {
                Snackbar.make(binding.root, "Папка недоступна для запису: ${dir.absolutePath}", Snackbar.LENGTH_LONG).show()
                return null
            }
            val abs = dir.absolutePath
            return Triple(dir, abs, abs)
        }
        val dir = try {
            EmbeddedPaths.serverDir(this, serverId, selectedStorage).apply { mkdirs() }
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Не вдалося створити папку: ${e.message}", Snackbar.LENGTH_LONG).show()
            return null
        }
        if (!dir.exists() || !dir.canWrite()) {
            Snackbar.make(binding.root, "Сховище недоступне для запису. Обери інше розташування.", Snackbar.LENGTH_LONG).show()
            return null
        }
        val uri = EmbeddedPaths.storageUri(serverId, selectedStorage)
        return Triple(dir, uri, EmbeddedPaths.displayPath(this, serverId, selectedStorage))
    }

    private fun updateCreateButtonState() {
        val nameValid = binding.etServerName.text?.toString()?.trim()?.isNotEmpty() == true
        val portValid = binding.etPort.text?.toString()?.trim()?.toIntOrNull()?.let { it in 1..65535 } == true
        binding.btnCreate.isEnabled = nameValid && portValid
    }

    private fun updateMinecraftSettingsVisibility() {
        binding.minecraftSettings.visibility = if (selectedServerType == ServerType.MINECRAFT) View.VISIBLE else View.GONE
    }

    private fun updateDefaultValues() {
        when (selectedServerType) {
            ServerType.MINECRAFT -> {
                if (binding.etPort.text.isNullOrEmpty()) binding.etPort.setText("25565")
                binding.etStartCommand.setText("")
            }
            ServerType.UBUNTU_SERVER -> {
                if (binding.etPort.text.isNullOrEmpty()) binding.etPort.setText("22")
                binding.etStartCommand.setText("sh -i (вбудований)")
            }
        }
        updateCreateButtonState()
    }

    private fun setupCreateButton() {
        binding.btnCreate.setOnClickListener { createServer() }
    }

    private fun createServer() {
        val name = binding.etServerName.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()
        if (name.isEmpty()) {
            Snackbar.make(binding.root, "Введіть назву сервера", Snackbar.LENGTH_SHORT).show(); return
        }
        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Snackbar.make(binding.root, "Невірний порт", Snackbar.LENGTH_SHORT).show(); return
        }
        if (selectedServerType == ServerType.MINECRAFT) {
            val loaderName = try { binding.spinnerMinecraftLoader.text.toString() } catch (_: Exception) { "PAPER" }
            val loader = try { MinecraftLoader.valueOf(loaderName) } catch (_: Exception) { MinecraftLoader.PAPER }
            if (loader == MinecraftLoader.SPIGOT || loader == MinecraftLoader.BUKKIT || loader == MinecraftLoader.FORGE || loader == MinecraftLoader.NEOFORGE) {
                AlertDialog.Builder(this)
                    .setTitle("Попередження")
                    .setMessage("Завантажувач $loaderName може не підтримуватися. Рекомендується PAPER, PURPUR, VANILLA чи FABRIC.\n\nПродовжити з $loaderName?")
                    .setPositiveButton("Продовжити") { _, _ -> proceedCreateMinecraft(name, port) }
                    .setNegativeButton("Скасувати") { d, _ -> d.dismiss() }
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

        // Піратка = вимкнена перевірка ліцензії (online-mode=false).
        // Промальовка 2..32 чанки, дефолт 10.
        val allowPirate = binding.switchPirate.isChecked
        val viewDistance = binding.sliderViewDistance.value.toInt().coerceIn(2, 32)

        binding.btnCreate.isEnabled = false
        binding.btnCreate.text = "Створення..."

        val serverId = UUID.randomUUID().toString()
        val target = resolveTargetDir(serverId, name) ?: run {
            binding.btnCreate.isEnabled = true
            binding.btnCreate.text = getString(com.anserver.app.R.string.create_server)
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
                    Snackbar.make(binding.root, "Сервер \"$name\" створено у ${storageShortName(selectedStorage)} сховищі. Натисніть Start ($version, $loader)", Snackbar.LENGTH_LONG).show()
                    showProvisionDialog(server, dir)
                }
            }
        }
    }

    private fun showProvisionDialog(server: Server, dir: File) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Завантаження Minecraft")
            .setMessage("Завантажити ${server.config.minecraftLoader} ${server.config.minecraftVersion} зараз?\n(~50-100 MB)\n\nМожна пропустити - завантаження відбудеться при першому Start.")
            .setPositiveButton("Завантажити") { d, _ ->
                d.dismiss()
                startProvisioning(server, dir)
            }
            .setNegativeButton("Пізніше") { d, _ ->
                d.dismiss()
                finish()
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    private fun startProvisioning(server: Server, dir: File) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Завантаження...")
            .setMessage("Підготовка Minecraft серверу...\n${server.config.minecraftLoader} ${server.config.minecraftVersion}")
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
                withContext(Dispatchers.Main) { progressDialog.setMessage("Помилка: ${e.message}") }
                false
            }
            progressDialog.dismiss()
            binding.btnCreate.isEnabled = true
            binding.btnCreate.text = getString(com.anserver.app.R.string.create_server)
            if (ok) {
                Snackbar.make(binding.root, "Готово! Натисніть Start для запуску.", Snackbar.LENGTH_LONG).show()
                kotlinx.coroutines.delay(1200)
                finish()
            } else {
                val details = if (logLines.isNotEmpty()) logLines.takeLast(10).joinToString("\n") else lastMsg.ifEmpty { "невідома помилка" }
                AlertDialog.Builder(this@CreateServerActivity)
                    .setTitle("Помилка завантаження")
                    .setMessage("Не вдалося завантажити jar.\n\nДеталі:\n$details\n\nЯкщо інтернет увімкнений - спробуй VANILLA/PURPUR/FABRIC замість PAPER, або поклади jar вручну через Files.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
        }
    }

    private suspend fun provisionInternal(server: Server, dir: File, log: (String) -> Unit): Boolean {
        return try {
            val version = server.config.minecraftVersion ?: "1.20.4"
            val loader = server.config.minecraftLoader ?: MinecraftLoader.PAPER
            val manager = com.anserver.plugins.minecraft.MinecraftServerManager(dir.parent ?: dir.absolutePath)
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
            Snackbar.make(binding.root, "Сервер створено у ${storageShortName(selectedStorage)} сховищі (вбудований термінал)", Snackbar.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
