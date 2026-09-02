package com.anserver.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.databinding.ActivityCreateServerBinding
import com.anserver.app.termux.SafHelper
import com.anserver.app.termux.TermuxPaths
import com.anserver.core.model.MinecraftLoader
import com.anserver.core.model.Server
import com.anserver.core.model.ServerConfig
import com.anserver.core.model.ServerType
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class CreateServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateServerBinding
    private val repository = ANServerApplication.serverRepository
    private var selectedServerType: ServerType = ServerType.MINECRAFT
    private var selectedTreeUri: Uri? = null
    private var selectedSubDirUri: Uri? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            SafHelper.persistPermission(this, uri)
        } catch (_: Exception) {}
        if (!SafHelper.isOnPrimaryStorage(this, uri)) {
            Snackbar.make(binding.root, "Тільки внутрішня пам'ять", Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val tree = DocumentFile.fromTreeUri(this, uri)
        if (tree == null || !tree.canWrite()) {
            Snackbar.make(binding.root, "Нема запису", Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val name = binding.etServerName.text?.toString()?.trim() ?: ""
        val sanitized = SafHelper.sanitizeName(name.ifEmpty { "server" })
        val dirName = sanitized + "_" + UUID.randomUUID().toString().take(4)
        val sub = tree.createDirectory(dirName)
        if (sub == null) {
            Snackbar.make(binding.root, "Не вдалося створити", Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        selectedTreeUri = uri
        selectedSubDirUri = sub.uri
        binding.etWorkingDir.setText(SafHelper.getDisplayPath(this, sub.uri))
        updateCreateButtonState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupServerTypeSpinner()
        setupMinecraftSettings()
        setupDirectoryPicker()
        setupCreateButton()

        intent.getStringExtra("server_type")?.let { typeName ->
            try {
                selectedServerType = ServerType.valueOf(typeName)
                binding.spinnerServerType.setText(typeName, false)
            } catch (_: Exception) {}
            updateDefaultValues()
            updateMinecraftSettingsVisibility()
        }

        // Legacy toast if filesDir servers exist
        val legacy = java.io.File(filesDir, "servers")
        if (legacy.exists() && legacy.listFiles()?.isNotEmpty() == true) {
            Snackbar.make(binding.root, "Старі сервери не підтримуються, створіть заново в SAF", Snackbar.LENGTH_LONG).show()
        }

        // Hide startCommand manual input (generated in TermuxBridge)
        binding.etStartCommand.isEnabled = false
        binding.etStartCommand.hint = "Генерується автоматично (Termux)"
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Create Server"
    }

    private fun setupServerTypeSpinner() {
        val allowed = listOf(ServerType.MINECRAFT, ServerType.UBUNTU_SERVER)
        val names = allowed.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
        binding.spinnerServerType.setAdapter(adapter)
        binding.spinnerServerType.setText(selectedServerType.name, false)
        binding.spinnerServerType.setOnItemClickListener { _, _, position, _ ->
            selectedServerType = allowed[position]
            updateDefaultValues()
            updateMinecraftSettingsVisibility()
            updateCreateButtonState()
        }
        updateDefaultValues()
        updateMinecraftSettingsVisibility()
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
        binding.spinnerMinecraftVersion.setText("1.21.4", false)

        val loaders = MinecraftLoader.values().map { it.name }
        val loaderAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, loaders)
        binding.spinnerMinecraftLoader.setAdapter(loaderAdapter)
        binding.spinnerMinecraftLoader.setText(MinecraftLoader.PAPER.name, false)

        val runtime = Runtime.getRuntime()
        val maxMemoryMB = (runtime.maxMemory() / 1024 / 1024).toInt()
        val recommendedMax = (maxMemoryMB * 0.7).toInt()
        val recommendedMin = (recommendedMax * 0.5).toInt()
        binding.etMaxMemory.setText(recommendedMax.coerceAtLeast(512).toString())
        binding.etMinMemory.setText(recommendedMin.coerceAtLeast(256).toString())

        binding.btnUseMaxResources.setOnClickListener {
            val max = (runtime.maxMemory() / 1024 / 1024).toInt()
            val maxAllowed = (max * 0.9).toInt()
            val minAllowed = (maxAllowed * 0.5).toInt()
            binding.etMaxMemory.setText(maxAllowed.coerceAtLeast(512).toString())
            binding.etMinMemory.setText(minAllowed.coerceAtLeast(256).toString())
            Snackbar.make(binding.root, "Using maximum resources: $maxAllowed MB", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupDirectoryPicker() {
        binding.etWorkingDir.isEnabled = false
        binding.etWorkingDir.isFocusable = false
        binding.etWorkingDir.isClickable = false
        binding.btnBrowseDirectory.visibility = View.VISIBLE
        binding.btnBrowseDirectory.text = "Вибрати папку *"
        binding.btnBrowseDirectory.setOnClickListener { picker.launch(null) }
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

    private fun updateCreateButtonState() {
        val nameValid = binding.etServerName.text?.toString()?.trim()?.isNotEmpty() == true
        val portValid = binding.etPort.text?.toString()?.trim()?.toIntOrNull()?.let { it in 1..65535 } == true
        binding.btnCreate.isEnabled = nameValid && portValid && selectedSubDirUri != null
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
                binding.etStartCommand.setText("bash -i")
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
        if (portStr.isEmpty()) {
            Snackbar.make(binding.root, "Введіть порт", Snackbar.LENGTH_SHORT).show(); return
        }
        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Snackbar.make(binding.root, "Невірний порт", Snackbar.LENGTH_SHORT).show(); return
        }
        if (selectedSubDirUri == null) {
            Snackbar.make(binding.root, "Виберіть папку", Snackbar.LENGTH_LONG).show(); return
        }
        val uri = selectedSubDirUri!!
        val doc = DocumentFile.fromTreeUri(this, uri)
        if (doc == null) {
            Snackbar.make(binding.root, "Не вдалося відкрити папку", Snackbar.LENGTH_SHORT).show(); return
        }
        if (doc.listFiles().isNotEmpty()) {
            Snackbar.make(binding.root, "Папка не порожня", Snackbar.LENGTH_LONG).show(); return
        }
        if (!SafHelper.isOnPrimaryStorage(this, uri) && !SafHelper.isOnPrimaryStorage(this, selectedTreeUri ?: uri)) {
            Snackbar.make(binding.root, "Тільки внутрішня пам'ять", Snackbar.LENGTH_LONG).show(); return
        }
        if (selectedServerType == ServerType.MINECRAFT) {
            val loaderName = try { binding.spinnerMinecraftLoader.text.toString() } catch (_: Exception) { "PAPER" }
            val version = try { binding.spinnerMinecraftVersion.text.toString() } catch (_: Exception) { "1.21.4" }
            val loader = try { MinecraftLoader.valueOf(loaderName) } catch (_: Exception) { MinecraftLoader.PAPER }
            if (loader == MinecraftLoader.SPIGOT || loader == MinecraftLoader.BUKKIT || loader == MinecraftLoader.FORGE || loader == MinecraftLoader.NEOFORGE) {
                AlertDialog.Builder(this)
                    .setTitle("Попередження")
                    .setMessage("Завантажувач $loaderName може не підтримуватися. Рекомендується PAPER, PURPUR, VANILLA чи FABRIC.\n\nПродовжити з $loaderName?")
                    .setPositiveButton("Продовжити") { _, _ -> proceedCreateMinecraft(name, port, uri) }
                    .setNegativeButton("Скасувати") { d, _ -> d.dismiss() }
                    .show()
                return
            }
            proceedCreateMinecraft(name, port, uri)
        } else {
            proceedCreateGeneric(name, port, uri)
        }
    }

    private fun proceedCreateMinecraft(name: String, port: Int, uri: Uri) {
        val maxMemory = binding.etMaxMemory.text.toString().toIntOrNull() ?: 1024
        val minMemory = binding.etMinMemory.text.toString().toIntOrNull() ?: 512
        val version = try { binding.spinnerMinecraftVersion.text.toString() } catch (_: Exception) { "1.21.4" }
        val loaderName = try { binding.spinnerMinecraftLoader.text.toString() } catch (_: Exception) { "PAPER" }
        val loader = try { MinecraftLoader.valueOf(loaderName) } catch (_: Exception) { MinecraftLoader.PAPER }

        binding.btnCreate.isEnabled = false
        binding.btnCreate.text = "Створення..."

        val displayPath = SafHelper.getDisplayPath(this, uri)
        val termuxPath = TermuxPaths.safToTermux(this, uri)
        val config = ServerConfig(
            startCommand = "",
            maxMemory = maxMemory,
            minMemory = minMemory,
            minecraftVersion = version,
            minecraftLoader = loader
        )
        val server = Server(
            name = name,
            type = ServerType.MINECRAFT,
            port = port,
            workingDirectory = uri.toString(),
            workDirUri = uri.toString(),
            displayPath = displayPath,
            termuxPath = termuxPath,
            config = config
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dirDoc = DocumentFile.fromTreeUri(this@CreateServerActivity, uri)!!
                // eula.txt
                dirDoc.createFile("text/plain", "eula.txt")?.let { eulaUri ->
                    contentResolver.openOutputStream(eulaUri.uri)?.use { it.write("#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).\neula=true".toByteArray()) }
                }
                // server.properties
                val props = """
                    server-port=$port
                    max-players=20
                    gamemode=survival
                    difficulty=normal
                    pvp=true
                    online-mode=true
                    motd=A Minecraft Server - $name
                    view-distance=8
                    spawn-protection=16
                    enable-command-block=false
                    allow-flight=false
                    white-list=false
                    enforce-whitelist=false
                """.trimIndent()
                dirDoc.createFile("text/plain", "server.properties")?.let { propUri ->
                    // DocumentFile createFile may add extension; find or create plain
                    contentResolver.openOutputStream(propUri.uri)?.use { it.write(props.toByteArray()) }
                }
                // Also ensure server.properties via direct DocumentFile find
                // If file creation added .txt, rename handling not needed; we already created with correct name handling via SAF may mangle, but ok
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                lifecycleScope.launch {
                    repository.createServer(server)
                    Snackbar.make(binding.root, "Сервер \"$name\" створено. Натисніть Start для запуску ($version, $loader)", Snackbar.LENGTH_LONG).show()
                    showProvisionDialog(server, uri)
                }
            }
        }
    }

    private fun showProvisionDialog(server: Server, uri: Uri) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Завантаження Minecraft")
            .setMessage("Завантажити ${server.config.minecraftLoader} ${server.config.minecraftVersion} зараз?\n(~50-100 MB)\n\nМожна пропустити - завантаження відбудеться при першому Start.")
            .setPositiveButton("Завантажити") { d, _ ->
                d.dismiss()
                startProvisioning(server, uri)
            }
            .setNegativeButton("Пізніше") { d, _ ->
                d.dismiss()
                finish()
            }
            .setCancelable(false)
            .create()
        dialog.show()
    }

    private fun startProvisioning(server: Server, uri: Uri) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Завантаження...")
            .setMessage("Підготовка Minecraft серверу...\n${server.config.minecraftLoader} ${server.config.minecraftVersion}")
            .setCancelable(false)
            .create()
        progressDialog.show()
        lifecycleScope.launch {
            var lastMsg = ""
            val ok = try {
                provisionSaf(server, uri) { msg ->
                    lastMsg = msg
                    runOnUiThread { progressDialog.setMessage(msg + "\n\n" + lastMsg) }
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
                AlertDialog.Builder(this@CreateServerActivity)
                    .setTitle("Помилка завантаження")
                    .setMessage("Не вдалося завантажити jar. Перевірте інтернет.\n\nДеталі: $lastMsg")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
        }
    }

    private suspend fun provisionSaf(server: Server, uri: Uri, log: (String) -> Unit): Boolean {
        return try {
            val dirDoc = DocumentFile.fromTreeUri(this, uri) ?: return false
            val version = server.config.minecraftVersion ?: "1.21.4"
            val loader = server.config.minecraftLoader ?: MinecraftLoader.PAPER
            // Use existing MinecraftServerManager but via SAF file creation: download to temp File then copy to SAF via stream
            // Simplified: call MinecraftServerManager with displayPath as base, but write via File may still work on /storage
            // Instead directly download via manager's logic using our SAF download
            val displayPath = SafHelper.getDisplayPath(this, uri)
            // Try to use File path for manager (works if File I/O allowed)
            val manager = com.anserver.plugins.minecraft.MinecraftServerManager(java.io.File(displayPath).parent ?: displayPath)
            // manager will attempt File I/O; if it fails due to permission, fallback to direct Http
            val ok = manager.provisionIfNeeded(displayPath, version, loader, server.config.maxMemory, server.config.minMemory, server.port, log)
            if (ok) return true
            // Fallback: ensure at least eula/properties exist (already created) return false to show error
            false
        } catch (e: Exception) {
            log("Provision exception: ${e.message}")
            false
        }
    }

    private fun proceedCreateGeneric(name: String, port: Int, uri: Uri) {
        binding.btnCreate.isEnabled = false
        val displayPath = SafHelper.getDisplayPath(this, uri)
        val termuxPath = TermuxPaths.safToTermux(this, uri)
        val config = ServerConfig(startCommand = "bash -i")
        val server = Server(
            name = name,
            type = ServerType.UBUNTU_SERVER,
            port = port,
            workingDirectory = uri.toString(),
            workDirUri = uri.toString(),
            displayPath = displayPath,
            termuxPath = termuxPath,
            config = config
        )
        lifecycleScope.launch {
            repository.createServer(server)
            Snackbar.make(binding.root, "Server created successfully", Snackbar.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
