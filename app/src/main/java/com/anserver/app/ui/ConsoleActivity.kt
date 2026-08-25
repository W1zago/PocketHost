package com.anserver.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.databinding.ActivityConsoleBinding
import com.anserver.app.util.ServerPaths
import com.anserver.native.NativeBridge
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
    private var ctrlActive = false
    private var altActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverId = intent.getStringExtra("server_id")

        setupToolbar()
        setupConsole()
        setupExtraKeys()
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
            } else {
                writeToProcess("\n")
            }
        }
        binding.inputCommand.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                val command = binding.inputCommand.text.toString()
                if (command.isNotEmpty()) {
                    sendCommand(command)
                    binding.inputCommand.text?.clear()
                } else {
                    writeToProcess("\n")
                }
                return@setOnEditorActionListener true
            }
            false
        }
        binding.inputCommand.requestFocus()
    }

    private fun setupExtraKeys() {
        binding.keyEsc.setOnClickListener { sendKey(27.toChar().toString()) }
        binding.keySlash.setOnClickListener { sendKey("/") }
        binding.keyDash.setOnClickListener { sendKey("-") }
        binding.keyHome.setOnClickListener { sendKey("\u001B[H") }
        binding.keyUp.setOnClickListener { sendKey("\u001B[A") }
        binding.keyEnd.setOnClickListener { sendKey("\u001B[F") }
        binding.keyPgUp.setOnClickListener { sendKey("\u001B[5~") }

        binding.keyTab.setOnClickListener { sendKey("\t") }
        binding.keyLeft.setOnClickListener { sendKey("\u001B[D") }
        binding.keyDown.setOnClickListener { sendKey("\u001B[B") }
        binding.keyRight.setOnClickListener { sendKey("\u001B[C") }
        binding.keyPgDn.setOnClickListener { sendKey("\u001B[6~") }

        binding.keyCtrl.setOnClickListener { toggleCtrl() }
        binding.keyAlt.setOnClickListener { toggleAlt() }
        updateModifierUI()
    }

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        updateModifierUI()
    }

    private fun toggleAlt() {
        altActive = !altActive
        updateModifierUI()
    }

    private fun updateModifierUI() {
        binding.keyCtrl.setBackgroundColor(if (ctrlActive) 0xFF4A4A00.toInt() else 0xFF222222.toInt())
        binding.keyCtrl.setTextColor(if (ctrlActive) 0xFFFFFF55.toInt() else 0xFFFFFFFF.toInt())
        binding.keyAlt.setBackgroundColor(if (altActive) 0xFF4A4A00.toInt() else 0xFF222222.toInt())
        binding.keyAlt.setTextColor(if (altActive) 0xFFFFFF55.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun sendKey(data: String) {
        var toSend = data
        if (altActive) {
            toSend = "\u001B$toSend"
        }
        if (ctrlActive && toSend.length == 1) {
            val c = toSend[0]
            toSend = when {
                c in 'a'..'z' -> (c.code - 'a'.code + 1).toChar().toString()
                c in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar().toString()
                c == '/' -> 31.toChar().toString()
                else -> toSend
            }
        }
        val wasModifier = ctrlActive || altActive
        if (wasModifier) {
            ctrlActive = false
            altActive = false
            updateModifierUI()
        }
        writeToProcess(toSend)
    }

    private fun writeToProcess(data: String) {
        if (processPid > 0) {
            try {
                NativeBridge.writeProcessInput(processPid, data)
            } catch (e: Exception) {
                android.util.Log.e("ConsoleActivity", "writeProcessInput failed", e)
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
                                android.util.Log.d("ConsoleActivity", "Process PID updated to: $processPid")
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
            if (!logFile.exists() || logFile.length() == 0L) {
                withContext(Dispatchers.Main) { appendOutput("Connecting to Ubuntu shell...\n") }
            }

            while (true) {
                try {
                    if (logFile.exists()) {
                        val length = logFile.length()
                        if (length < offset) {
                            offset = 0L
                            withContext(Dispatchers.Main) { binding.consoleOutput.text = "" }
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
                delay(200)
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
        appendOutput("$ $command\n")
        writeToProcess("$command\n")
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
