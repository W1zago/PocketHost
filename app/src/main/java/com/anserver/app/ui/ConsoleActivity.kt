package com.anserver.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.databinding.ActivityConsoleBinding
import com.anserver.native.NativeBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class ConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsoleBinding
    private val repository = ANServerApplication.serverRepository
    private var serverId: String? = null
    private var processPid: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverId = intent.getStringExtra("server_id")

        setupToolbar()
        setupConsole()
        loadServerInfo()
        startOutputMonitoring()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Console"
    }

    private fun setupConsole() {
        binding.btnSend.setOnClickListener {
            val command = binding.inputCommand.text.toString()
            if (command.isNotEmpty()) {
                sendCommand(command)
                binding.inputCommand.text?.clear()
            }
        }
    }

    private fun loadServerInfo() {
        serverId?.let { id ->
            lifecycleScope.launch {
                try {
                    // Observe server changes
                    repository.getServer(id).collect { server ->
                        android.util.Log.d("ConsoleActivity", "Server updated: ${server?.name}, PID: ${server?.pid}")

                        server?.let {
                            if (processPid != it.pid && it.pid > 0) {
                                processPid = it.pid
                                android.util.Log.d("ConsoleActivity", "Process PID updated to: $processPid")
                                binding.consoleOutput.append("\n[Process started with PID: $processPid]\n")
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ConsoleActivity", "Error loading server info", e)
                    binding.consoleOutput.setText("ERROR loading console: ${e.message}\n")
                }
            }
        }
    }

    private fun startOutputMonitoring() {
        lifecycleScope.launch {
            while (true) {
                if (processPid > 0) {
                    try {
                        val output = NativeBridge.readProcessOutput(processPid)
                        if (output.isNotEmpty()) {
                            binding.consoleOutput.append(output)

                            // Auto-scroll to bottom
                            binding.consoleOutput.post {
                                val scrollAmount = binding.consoleOutput.layout?.getLineTop(binding.consoleOutput.lineCount) ?: 0
                                binding.consoleOutput.scrollTo(0, scrollAmount)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ConsoleActivity", "Error reading process output", e)
                    }
                }
                delay(100) // Update every 100ms
            }
        }
    }

    private fun sendCommand(command: String) {
        val output = "$ $command\n"
        binding.consoleOutput.append(output)

        // TODO: Implement command sending to process stdin
    }

    fun setProcessPid(pid: Int) {
        processPid = pid
        android.util.Log.d("ConsoleActivity", "Process PID set to: $pid")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
