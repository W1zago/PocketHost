package com.anserver.app.ui

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.anserver.app.ANServerApplication
import com.anserver.app.databinding.ActivityConsoleBinding
import com.anserver.app.termux.TermuxBridge
import com.anserver.app.termux.TermuxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsoleBinding
    private val repository = ANServerApplication.serverRepository
    private var serverId: String? = null
    private var workDirUri: Uri? = null
    private var termuxPath: String = ""
    private var termuxSession: TermuxBridge.TermuxSession? = null
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
        binding.consoleOutput.setOnLongClickListener { false }
        binding.consoleOutput.setOnClickListener { }
        registerForContextMenu(binding.consoleOutput)
    }

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu?,
        v: android.view.View?,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        menu?.add(0, 1, 0, "Копіювати виділене")
        menu?.add(0, 2, 0, "Копіювати все")
        menu?.add(0, 3, 0, "Поділитися логом")
        menu?.add(0, 4, 0, "Очистити консоль")
    }

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { copySelection(); true }
            2 -> { copyAll(); true }
            3 -> { shareLog(); true }
            4 -> { clearConsole(); true }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(com.anserver.app.R.menu.menu_console, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            com.anserver.app.R.id.action_copy -> { copyAll(); true }
            com.anserver.app.R.id.action_select_all -> { selectAll(); true }
            com.anserver.app.R.id.action_clear -> { clearConsole(); true }
            com.anserver.app.R.id.action_share -> { shareLog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun copySelection() {
        val textView = binding.consoleOutput
        val start = textView.selectionStart
        val end = textView.selectionEnd
        val textToCopy = if (start >= 0 && end > start) {
            textView.text.substring(start, end).toString()
        } else textView.text.toString()
        copyToClipboard(textToCopy)
    }

    private fun copyAll() {
        copyToClipboard(binding.consoleOutput.text.toString())
        android.widget.Toast.makeText(this, "Скопійовано ${binding.consoleOutput.text.length} символів", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun selectAll() {
        binding.consoleOutput.post {
            val text = binding.consoleOutput.text
            if (text is android.text.Spannable) {
                android.text.Selection.selectAll(text as android.text.Spannable)
            } else copyAll()
        }
    }

    private fun clearConsole() {
        binding.consoleOutput.text = ""
        android.widget.Toast.makeText(this, "Консоль очищено (файл логу не видалено)", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val logText = binding.consoleOutput.text.toString()
        if (logText.isBlank()) {
            android.widget.Toast.makeText(this, "Лог порожній", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, logText)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "PocketHost server log")
        }
        startActivity(android.content.Intent.createChooser(intent, "Поділитися логом"))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("console", text)
        clipboard.setPrimaryClip(clip)
        android.widget.Toast.makeText(this, "Скопійовано в буфер", android.widget.Toast.LENGTH_SHORT).show()
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
                } else writeToProcess("\n")
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

    private fun toggleCtrl() { ctrlActive = !ctrlActive; updateModifierUI() }
    private fun toggleAlt() { altActive = !altActive; updateModifierUI() }

    private fun updateModifierUI() {
        binding.keyCtrl.setBackgroundColor(if (ctrlActive) 0xFF4A4A00.toInt() else 0xFF222222.toInt())
        binding.keyCtrl.setTextColor(if (ctrlActive) 0xFFFFFF55.toInt() else 0xFFFFFFFF.toInt())
        binding.keyAlt.setBackgroundColor(if (altActive) 0xFF4A4A00.toInt() else 0xFF222222.toInt())
        binding.keyAlt.setTextColor(if (altActive) 0xFFFFFF55.toInt() else 0xFFFFFFFF.toInt())
    }

    private fun sendKey(data: String) {
        var toSend = data
        if (altActive) toSend = "\u001B$toSend"
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
        if (wasModifier) { ctrlActive = false; altActive = false; updateModifierUI() }
        writeToProcess(toSend)
    }

    private fun writeToProcess(data: String) {
        val session = termuxSession
        if (session != null) {
            try {
                TermuxBridge.sendInput(this, session, data)
            } catch (e: Exception) {
                android.util.Log.e("ConsoleActivity", "sendInput failed", e)
            }
        } else {
            // fallback via raw termuxPath
            if (termuxPath.isNotEmpty()) {
                val fake = TermuxBridge.TermuxSession(serverId ?: "", -1, workDirUri?.toString() ?: "", termuxPath)
                TermuxBridge.sendInput(this, fake, data)
            }
        }
    }

    private fun loadServerInfo() {
        serverId?.let { id ->
            lifecycleScope.launch {
                try {
                    repository.getServer(id).collect { server ->
                        server?.let {
                            val uriStr = it.effectiveWorkDirUri.ifEmpty { it.workingDirectory }
                            workDirUri = try { Uri.parse(uriStr) } catch (_: Exception) { null }
                            termuxPath = it.termuxPath.ifEmpty {
                                workDirUri?.let { u -> TermuxPaths.safToTermux(this@ConsoleActivity, u) } ?: ""
                            }
                            termuxSession = TermuxBridge.TermuxSession(
                                serverId = it.id,
                                executionId = it.termuxExecutionId ?: -1,
                                workDirUri = uriStr,
                                termuxPath = termuxPath
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ConsoleActivity", "loadServerInfo", e)
                    binding.consoleOutput.setText("ERROR loading console: ${e.message}\n")
                }
            }
        }
    }

    private fun startOutputMonitoring() {
        val id = serverId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val server = try { repository.getServer(id).first { it != null } } catch (e: Exception) { null } ?: return@launch
            val uriStr = server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
            val dirUri = try { Uri.parse(uriStr) } catch (_: Exception) { return@launch }
            var offset = 0L
            // initial tail 16KB
            try {
                val dir = DocumentFile.fromTreeUri(this@ConsoleActivity, dirUri)
                val logFile = dir?.findFile("server.log")
                if (logFile != null) {
                    val len = logFile.length()
                    offset = if (len > INITIAL_TAIL_SIZE) len - INITIAL_TAIL_SIZE else 0L
                }
            } catch (_: Exception) {}
            if (offset == 0L) {
                withContext(Dispatchers.Main) { appendOutput("Connecting to Termux shell...\n") }
            }
            while (true) {
                try {
                    val dir = DocumentFile.fromTreeUri(this@ConsoleActivity, dirUri)
                    val logFile = dir?.findFile("server.log")
                    if (logFile != null && logFile.exists()) {
                        val length = logFile.length()
                        if (length < offset) {
                            offset = 0L
                            withContext(Dispatchers.Main) { binding.consoleOutput.text = "" }
                        }
                        if (length > offset) {
                            val text = contentResolver.openInputStream(logFile.uri)?.use { input ->
                                // skip offset
                                var skipped = 0L
                                while (skipped < offset) {
                                    val s = input.skip(offset - skipped)
                                    if (s <= 0) break
                                    skipped += s
                                }
                                val remaining = (length - offset).toInt().coerceAtMost(1024 * 1024)
                                val buffer = ByteArray(remaining)
                                var read = 0
                                var total = 0
                                while (total < remaining) {
                                    val r = input.read(buffer, total, remaining - total)
                                    if (r <= 0) break
                                    total += r
                                }
                                String(buffer, 0, total)
                            } ?: ""
                            offset = length
                            if (text.isNotEmpty()) {
                                withContext(Dispatchers.Main) { appendOutput(text) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ConsoleActivity", "Error reading log", e)
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

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    companion object {
        private const val INITIAL_TAIL_SIZE = 16 * 1024L
    }
}
