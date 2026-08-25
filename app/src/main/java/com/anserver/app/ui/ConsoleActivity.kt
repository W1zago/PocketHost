package com.anserver.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.databinding.ActivityConsoleBinding
import com.anserver.app.util.ServerPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

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
                    repository.getServer(id).collect { server ->
                        server?.let {
                            if (processPid != it.pid && it.pid > 0) {
                                processPid = it.pid
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
        val id = serverId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val server = try {
                repository.getServer(id).first { it != null }
            } catch (e: Exception) {
                android.util.Log.e("ConsoleActivity", "Error loading server for console", e)
                null
            } ?: return@launch

            val logFile = File(
                ServerPaths.resolve(this@ConsoleActivity, server.workingDirectory),
                "server.log"
            )
            var offset = 0L
            if (logFile.exists()) {
                val length = logFile.length()
                offset = if (length > INITIAL_TAIL_SIZE) length - INITIAL_TAIL_SIZE else 0L
            }

            while (true) {
                try {
                    if (logFile.exists()) {
                        val length = logFile.length()
                        if (length < offset) {
                            offset = 0L
                        }
                        if (length > offset) {
                            val text = RandomAccessFile(logFile, "r").use { raf ->
                                raf.seek(offset)
                                val buffer = ByteArray((length - offset).toInt())
                                raf.readFully(buffer)
                                String(buffer)
                            }
                            offset = length
                            withContext(Dispatchers.Main) {
                                appendOutput(text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ConsoleActivity", "Error reading console log", e)
                }
                delay(300)
            }
        }
    }

    private fun appendOutput(output: String) {
        binding.consoleOutput.append(output)
        binding.consoleOutput.post {
            val scrollAmount = binding.consoleOutput.layout?.getLineTop(binding.consoleOutput.lineCount) ?: 0
            binding.consoleOutput.scrollTo(0, scrollAmount)
        }
    }

    private fun sendCommand(command: String) {
        val output = "$ $command\n"
        appendOutput(output)
    }

    fun setProcessPid(pid: Int) {
        processPid = pid
        android.util.Log.d("ConsoleActivity", "Process PID set to: $pid")
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    companion object {
        private const val INITIAL_TAIL_SIZE = 16 * 1024L
    }
}
