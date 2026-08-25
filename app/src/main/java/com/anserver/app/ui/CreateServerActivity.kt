package com.anserver.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.databinding.ActivityCreateServerBinding
import com.anserver.core.model.MinecraftLoader
import com.anserver.core.model.Server
import com.anserver.core.model.ServerConfig
import com.anserver.core.model.ServerType
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File
import com.anserver.core.util.JavaUtils

class CreateServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateServerBinding
    private val repository = ANServerApplication.serverRepository
    private var selectedServerType: ServerType = ServerType.MINECRAFT
    private var selectedDirectory: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupServerTypeSpinner()
        setupMinecraftSettings()
        setupDirectoryPicker()
        setupCreateButton()
        checkJavaAvailability()

        // Get server type from intent if provided
        intent.getStringExtra("server_type")?.let { typeName ->
            selectedServerType = ServerType.valueOf(typeName)
            binding.spinnerServerType.setText(typeName, false)
            updateDefaultValues()
            updateMinecraftSettingsVisibility()
        }
    }

    private fun checkJavaAvailability() {
        val javaInfo = JavaUtils.checkJavaAvailability()
        if (javaInfo == null) {
            Snackbar.make(
                binding.root,
                "Warning: Java runtime not found. Install Termux with Java to run Minecraft servers.",
                Snackbar.LENGTH_LONG
            ).show()
        } else {
            android.util.Log.d("CreateServerActivity", "Java found at: ${javaInfo.path}, version: ${javaInfo.version}")
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Create Server"
    }

    private fun setupServerTypeSpinner() {
        // Only show Minecraft and Ubuntu Server
        val allowedServerTypes = listOf(
            ServerType.MINECRAFT,
            ServerType.UBUNTU_SERVER
        )
        val serverTypeNames = allowedServerTypes.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, serverTypeNames)
        binding.spinnerServerType.setAdapter(adapter)
        binding.spinnerServerType.setText(selectedServerType.name, false)

        binding.spinnerServerType.setOnItemClickListener { _, _, position, _ ->
            selectedServerType = allowedServerTypes[position]
            updateDefaultValues()
            updateMinecraftSettingsVisibility()
        }

        updateDefaultValues()
        updateMinecraftSettingsVisibility()
    }

    private fun setupMinecraftSettings() {
        // Minecraft versions (popular versions)
        val minecraftVersions = listOf(
            "1.21.4", "1.21.3", "1.21.1", "1.21",
            "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.20",
            "1.19.4", "1.19.3", "1.19.2", "1.19.1", "1.19",
            "1.18.2", "1.18.1", "1.18",
            "1.17.1", "1.17",
            "1.16.5", "1.16.4", "1.16.3", "1.16.2", "1.16.1",
            "1.12.2", "1.8.9", "1.7.10"
        )
        val versionAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, minecraftVersions)
        binding.spinnerMinecraftVersion.setAdapter(versionAdapter)
        binding.spinnerMinecraftVersion.setText("1.21.4", false)

        // Minecraft loaders
        val loaders = MinecraftLoader.values().map { it.name }
        val loaderAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, loaders)
        binding.spinnerMinecraftLoader.setAdapter(loaderAdapter)
        binding.spinnerMinecraftLoader.setText(MinecraftLoader.VANILLA.name, false)

        // Set default memory based on device capabilities
        val runtime = Runtime.getRuntime()
        val maxMemoryMB = (runtime.maxMemory() / 1024 / 1024).toInt()
        val recommendedMax = (maxMemoryMB * 0.7).toInt() // Use 70% of available memory
        val recommendedMin = (recommendedMax * 0.5).toInt() // Min is 50% of max

        binding.etMaxMemory.setText(recommendedMax.toString())
        binding.etMinMemory.setText(recommendedMin.toString())

        // Use Max Resources button
        binding.btnUseMaxResources.setOnClickListener {
            val runtime = Runtime.getRuntime()
            val maxMemoryMB = (runtime.maxMemory() / 1024 / 1024).toInt()
            val maxAllowed = (maxMemoryMB * 0.9).toInt() // Use 90% of available memory
            val minAllowed = (maxAllowed * 0.5).toInt()

            binding.etMaxMemory.setText(maxAllowed.toString())
            binding.etMinMemory.setText(minAllowed.toString())

            Snackbar.make(binding.root, "Using maximum resources: $maxAllowed MB", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun setupDirectoryPicker() {
        // Disable directory picker - use internal storage only
        binding.etWorkingDir.isEnabled = false
        binding.btnBrowseDirectory.visibility = View.GONE
    }

    private fun openDirectoryPicker() {
        // Removed - using internal storage only
    }

    private fun updateMinecraftSettingsVisibility() {
        binding.minecraftSettings.visibility = if (selectedServerType == ServerType.MINECRAFT) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun updateDefaultValues() {
        // Use app's internal storage directory
        val baseDir = File(getExternalFilesDir(null), "servers")

        // Find available Java executable
        val javaPath = JavaUtils.findJavaExecutable() ?: "java"

        when (selectedServerType) {
            ServerType.MINECRAFT -> {
                binding.etPort.setText("25565")
                selectedDirectory = File(baseDir, "minecraft").absolutePath
                binding.etWorkingDir.setText(selectedDirectory)
                binding.etStartCommand.setText("$javaPath -Xmx1024M -Xms512M -jar server.jar nogui")
            }
            ServerType.UBUNTU_SERVER -> {
                binding.etPort.setText("22")
                selectedDirectory = File(baseDir, "ubuntu").absolutePath
                binding.etWorkingDir.setText(selectedDirectory)
                binding.etStartCommand.setText("proot --link2symlink -0 -r ubuntu-fs -b /dev -b /proc -b /sys /bin/bash")
            }
        }
    }

    private fun setupCreateButton() {
        binding.btnCreate.setOnClickListener {
            createServer()
        }
    }

    private fun createServer() {
        val name = binding.etServerName.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()
        val workingDir = if (selectedDirectory.isNotEmpty()) {
            selectedDirectory
        } else {
            binding.etWorkingDir.text.toString().trim()
        }
        val startCommand = binding.etStartCommand.text.toString().trim()

        if (name.isEmpty()) {
            Snackbar.make(binding.root, "Please enter server name", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (portStr.isEmpty()) {
            Snackbar.make(binding.root, "Please enter port", Snackbar.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            Snackbar.make(binding.root, "Invalid port number", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (workingDir.isEmpty()) {
            Snackbar.make(binding.root, "Please enter working directory", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (startCommand.isEmpty()) {
            Snackbar.make(binding.root, "Please enter start command", Snackbar.LENGTH_SHORT).show()
            return
        }

        // Create working directory if it doesn't exist
        val dir = File(workingDir)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val config = if (selectedServerType == ServerType.MINECRAFT) {
            val minecraftVersion = binding.spinnerMinecraftVersion.text.toString()
            val loaderName = binding.spinnerMinecraftLoader.text.toString()
            val loader = MinecraftLoader.valueOf(loaderName)
            val maxMemory = binding.etMaxMemory.text.toString().toIntOrNull() ?: 1024
            val minMemory = binding.etMinMemory.text.toString().toIntOrNull() ?: 512

            // Get Java path from Android Runtime
            val javaPath = JavaUtils.findJavaExecutable() ?: "java"

            // Update start command based on loader
            val updatedCommand = when (loader) {
                MinecraftLoader.VANILLA -> "$javaPath -Xmx${maxMemory}M -Xms${minMemory}M -jar server.jar nogui"
                MinecraftLoader.FORGE -> "$javaPath -Xmx${maxMemory}M -Xms${minMemory}M -jar forge-installer.jar --installServer"
                MinecraftLoader.FABRIC -> "$javaPath -Xmx${maxMemory}M -Xms${minMemory}M -jar fabric-server-launch.jar nogui"
                MinecraftLoader.NEOFORGE -> "$javaPath -Xmx${maxMemory}M -Xms${minMemory}M -jar neoforge-installer.jar --installServer"
                MinecraftLoader.PAPER -> "$javaPath -Xmx${maxMemory}M -Xms${minMemory}M -jar paper.jar nogui"
                MinecraftLoader.SPIGOT -> "$javaPath -Xmx${maxMemory}M -Xms${minMemory}M -jar spigot.jar nogui"
                MinecraftLoader.BUKKIT -> "$javaPath -Xmx${maxMemory}M -Xms${minMemory}M -jar craftbukkit.jar nogui"
                MinecraftLoader.PURPUR -> "$javaPath -Xmx${maxMemory}M -Xms${minMemory}M -jar purpur.jar nogui"
            }

            binding.etStartCommand.setText(updatedCommand)

            ServerConfig(
                startCommand = updatedCommand,
                maxMemory = maxMemory,
                minMemory = minMemory,
                minecraftVersion = minecraftVersion,
                minecraftLoader = loader
            )
        } else {
            ServerConfig(
                startCommand = startCommand
            )
        }

        val server = Server(
            name = name,
            type = selectedServerType,
            port = port,
            workingDirectory = workingDir,
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
