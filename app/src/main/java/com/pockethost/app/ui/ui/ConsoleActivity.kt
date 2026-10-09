package com.pockethost.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.text.Spannable
import android.text.TextWatcher
import android.util.Log
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pockethost.app.PocketHostApplication
import com.pockethost.app.R
import com.pockethost.app.databinding.ActivityConsoleBinding
import com.pockethost.app.embedded.EmbeddedPaths
import com.pockethost.app.embedded.EmbeddedProcessManager
import com.pockethost.core.model.ServerStatus
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Built-in console: reads filesDir/servers/<id>/server.log
 * and writes input directly to the process stdin via EmbeddedProcessManager.
 * No SAF / RUN_COMMAND permissions.
 */
class ConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsoleBinding
    // Unified with MainActivity/CreateServerActivity: safe lazy access.
    private val repository by lazy {
        runCatching { PocketHostApplication.serverRepository }.getOrElse { e ->
            Log.e("ConsoleActivity", "repo not ready, using fallback", e)
            com.pockethost.core.repository.ServerRepository(this@ConsoleActivity)
        }
    }
    private var serverId: String? = null
    private var workDir: File? = null
    private var ctrlActive = false
    private var altActive = false

    // Log buffer + filters (all real: derived from actual backend output).
    private val logBuffer: ArrayDeque<String> = ArrayDeque()
    private var infoCount = 0
    private var warnCount = 0
    private var errorCount = 0
    private var filterLevel: String = "ALL"
    private var filterQuery: String = ""
    private var autoScroll = true
    private var footerTick = 0

    // Sent-command history (local recall, like terminal history).
    private val sentHistory: ArrayDeque<String> = ArrayDeque()
    private var historyPos = -1

    companion object {
        private const val INITIAL_TAIL_SIZE = 16 * 1024L
        private const val MAX_BUFFER_LINES = 2000
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
        private val ERROR_PATTERN = Regex("error|exception|failed|severe|fatal|caused by", RegexOption.IGNORE_CASE)
        private val WARN_PATTERN = Regex("warn", RegexOption.IGNORE_CASE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        // Edge-to-edge: draw under system bars, pad the root with real insets.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
        setContentView(binding.root)

        serverId = intent.getStringExtra("server_id")

        setupToolbar()
        setupConsole()
        setupExtraKeys()
        setupFilters()
        loadServerInfo()
        startOutputMonitoring()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnCopy.setOnClickListener { copyAll() }
        binding.btnShare.setOnClickListener { shareLog() }
        binding.btnClear.setOnClickListener { clearConsole() }
        binding.btnMenu.setOnClickListener { showOverflow(it) }
        binding.consoleOutput.setOnLongClickListener { false }
        binding.consoleOutput.setOnClickListener { }
        registerForContextMenu(binding.consoleOutput)
    }

    /** Overflow popup: same actions as the old toolbar menu (Select all lives here). */
    private fun showOverflow(anchor: View) {
        try {
            val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.menu_console, popup.menu)
            popup.setOnMenuItemClickListener { item -> handleMenuAction(item.itemId) }
            popup.show()
        } catch (_: Exception) {}
    }

    private fun handleMenuAction(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_copy -> { copyAll(); true }
            R.id.action_select_all -> { selectAll(); true }
            R.id.action_clear -> { clearConsole(); true }
            R.id.action_share -> { shareLog(); true }
            else -> false
        }
    }

    /** Log search + level filter + follow toggle (all computed from real output). */
    private fun setupFilters() {
        binding.chipLevelGroup.setOnCheckedStateChangeListener { group, _ ->
            val chip = group.findViewById<Chip>(group.checkedChipId)
            filterLevel = chip?.tag?.toString() ?: "ALL"
            renderFiltered()
        }
        binding.etLogFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterQuery = s?.toString()?.trim()?.lowercase() ?: ""
                renderFiltered()
            }
        })
        binding.btnAutoScroll.setOnClickListener {
            autoScroll = !autoScroll
            updateFollowPill()
            binding.btnJumpLive.visibility = if (autoScroll) View.GONE else View.VISIBLE
            if (autoScroll) scrollToBottom()
        }
        binding.btnJumpLive.setOnClickListener {
            autoScroll = true
            updateFollowPill()
            binding.btnJumpLive.visibility = View.GONE
            scrollToBottom()
        }
        updateFollowPill()
        // Manual scroll up pauses follow (standard console UX).
        binding.scrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE && autoScroll) {
                binding.scrollView.post {
                    val max = binding.consoleOutput.bottom - binding.scrollView.height
                    if (binding.scrollView.scrollY < max - 120) {
                        autoScroll = false
                        updateFollowPill()
                        binding.btnJumpLive.visibility = View.VISIBLE
                    }
                }
            }
            false
        }
    }

    /** Follow pill states (design: primary translucent ON, outline OFF). */
    private fun updateFollowPill() {
        try {
            val on = autoScroll
            binding.btnAutoScroll.text = if (on) "Follow: ON" else "Follow: OFF"
            binding.btnAutoScroll.setTextColor(
                ContextCompat.getColor(this, if (on) R.color.primary else R.color.on_surface_variant)
            )
            binding.btnAutoScroll.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.follow_on_bg else R.color.surface_high)
                )
            binding.btnAutoScroll.strokeColor =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.follow_on_border else R.color.outline_variant)
                )
            binding.btnAutoScroll.setIconResource(
                if (on) R.drawable.bg_dot_running else R.drawable.bg_dot_idle
            )
            binding.btnAutoScroll.iconTint =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, if (on) R.color.primary else R.color.outline)
                )
        } catch (_: Exception) {}
    }

    private fun scrollToBottom() {
        binding.consoleOutput.post {
            binding.scrollView.smoothScrollTo(
                0,
                (binding.consoleOutput.bottom - binding.scrollView.height).coerceAtLeast(0)
            )
        }
    }

    override fun onCreateContextMenu(
        menu: ContextMenu?,
        v: View?,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        menu?.add(0, 1, 0, "Copy selection")
        menu?.add(0, 2, 0, "Copy all")
        menu?.add(0, 3, 0, "Share log")
        menu?.add(0, 4, 0, "Clear console")
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { copySelection(); true }
            2 -> { copyAll(); true }
            3 -> { shareLog(); true }
            4 -> { clearConsole(); true }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
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
        copyToClipboard(fullLogText())
        Toast.makeText(this, "Copied ${fullLogText().length} chars", Toast.LENGTH_SHORT).show()
    }

    private fun selectAll() {
        binding.consoleOutput.post {
            val text = binding.consoleOutput.text
            if (text is Spannable) {
                Selection.selectAll(text as Spannable)
            } else copyAll()
        }
    }

    private fun clearConsole() {
        logBuffer.clear()
        infoCount = 0
        warnCount = 0
        errorCount = 0
        binding.consoleOutput.text = ""
        updateLevelCounts()
        Toast.makeText(this, "Console cleared (log file kept)", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val logText = fullLogText()
        if (logText.isBlank()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show(); return
        }
        val intent = android.content.Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText)
            putExtra(Intent.EXTRA_SUBJECT, "PocketHost server log")
        }
        startActivity(Intent.createChooser(intent, "Share log"))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("console", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
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
        binding.btnHistory.setOnClickListener { recallHistory() }
        binding.inputCommand.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
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
        binding.keyCtrl.setBackgroundResource(
            if (ctrlActive) R.drawable.bg_extra_key_active else R.drawable.bg_extra_key
        )
        binding.keyCtrl.setTextColor(
            ContextCompat.getColor(this, if (ctrlActive) R.color.primary else R.color.on_surface)
        )
        binding.keyAlt.setBackgroundResource(
            if (altActive) R.drawable.bg_extra_key_active else R.drawable.bg_extra_key
        )
        binding.keyAlt.setTextColor(
            ContextCompat.getColor(this, if (altActive) R.color.primary else R.color.on_surface)
        )
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
        writeToProcessRaw(toSend)
    }

    private fun writeToProcess(data: String) {
        val id = serverId ?: return
        val ok = try {
            EmbeddedProcessManager.sendInput(id, data)
        } catch (e: Exception) {
            Log.e("ConsoleActivity", "sendInput failed", e)
            false
        }
        if (!ok) {
            // process not running - show a hint, but never ask for permissions
            appendOutput("[process not running, press Start]\n")
        }
    }

    private fun writeToProcessRaw(data: String) {
        val id = serverId ?: return
        val ok = try {
            // send raw bytes as-is without trailing \n
            if (data.endsWith("\n")) EmbeddedProcessManager.sendInput(id, data)
            else EmbeddedProcessManager.sendRaw(id, data.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) { false }
        if (!ok) appendOutput("[process not running]\n")
    }

    private fun loadServerInfo() {
        serverId?.let { id ->
            lifecycleScope.launch {
                try {
                    repository.getServer(id).collect { server ->
                        server?.let {
                            val uriStr = it.effectiveWorkDirUri.ifEmpty { it.workingDirectory }
                            workDir = EmbeddedPaths.resolveWorkDir(this@ConsoleActivity, it.id, uriStr)
                            binding.tvServerName.text = it.name
                            updateStatusPill(it.status)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ConsoleActivity", "loadServerInfo", e)
                    binding.consoleOutput.setText("ERROR loading console: ${e.message}\n")
                }
            }
        }
    }

    private fun updateStatusPill(status: com.pockethost.core.model.ServerStatus) {
        val pillBgRes = when (status) {
            ServerStatus.RUNNING -> R.drawable.bg_status_running
            ServerStatus.STARTING,
            ServerStatus.STOPPING -> R.drawable.bg_status_warning
            ServerStatus.ERROR -> R.drawable.bg_status_error
            else -> R.drawable.bg_status_idle
        }
        val dotRes = when (status) {
            ServerStatus.RUNNING -> R.drawable.bg_dot_running
            ServerStatus.STARTING,
            ServerStatus.STOPPING -> R.drawable.bg_dot_warning
            ServerStatus.ERROR -> R.drawable.bg_dot_error
            else -> R.drawable.bg_dot_idle
        }
        val labelRes = when (status) {
            ServerStatus.RUNNING -> R.color.badge_running_fg
            ServerStatus.STARTING,
            ServerStatus.STOPPING -> R.color.badge_warning_fg
            ServerStatus.ERROR -> R.color.badge_error_fg
            else -> R.color.badge_idle_fg
        }
        binding.statusBadge.setBackgroundResource(pillBgRes)
        binding.statusDot.setBackgroundResource(dotRes)
        binding.statusText.text = when (status) {
            ServerStatus.RUNNING -> "Running"
            ServerStatus.STARTING -> "Starting"
            ServerStatus.STOPPING -> "Stopping"
            ServerStatus.ERROR -> "Error"
            else -> "Stopped"
        }
        binding.statusText.setTextColor(ContextCompat.getColor(this, labelRes))
    }

    private fun startOutputMonitoring() {
        val id = serverId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            // one-shot dir resolve: getServer().first() returns null when missing,
            // never hangs (StateFlow always emits). Retries only on exception.
            var dir: File? = null
            repeat(5) {
                try {
                    val server = repository.getServer(id).first()
                    if (server != null) {
                        dir = EmbeddedPaths.resolveWorkDir(
                            this@ConsoleActivity, server.id,
                            server.effectiveWorkDirUri.ifEmpty { server.workingDirectory }
                        )
                        workDir = dir
                        return@repeat
                    } else {
                        // no server in repository - fallback to embedded dir, no more retries
                        return@repeat
                    }
                } catch (_: Exception) {
                    delay(250)
                }
            }
            val workDirFile = dir ?: EmbeddedPaths.lookupServerDir(this@ConsoleActivity, id)
            val logFile = File(workDirFile, "server.log")
            try { if (!logFile.exists()) logFile.createNewFile() } catch (_: Exception) {}

            var offset = 0L
            try {
                val len = logFile.length()
                offset = if (len > INITIAL_TAIL_SIZE) len - INITIAL_TAIL_SIZE else 0L
            } catch (_: Exception) {}
            if (offset == 0L) {
                withContext(Dispatchers.Main) { appendOutput("Built-in terminal ready.\n") }
            }
            // legacy SAF log: for old servers (content://) show a migration hint
            try {
                val server = repository.getServer(id).first()
                val uriStr = server?.effectiveWorkDirUri ?: ""
                if (uriStr.startsWith("content://")) {
                    withContext(Dispatchers.Main) {
                        appendOutput("[old SAF server: launch now runs in the internal folder ${workDirFile.absolutePath}]\n")
                    }
                }
            } catch (_: Exception) {}

            while (true) {
                try {
                    if (!logFile.exists()) {
                        delay(300); continue
                    }
                    val length = logFile.length()
                    if (length < offset) {
                        offset = 0L
                        withContext(Dispatchers.Main) { binding.consoleOutput.text = "" }
                    }
                    if (length > offset) {
                        val text = try {
                            RandomAccessFile(logFile, "r").use { raf ->
                                raf.seek(offset)
                                val remaining = (length - offset).toInt().coerceAtMost(1024 * 1024)
                                val buffer = ByteArray(remaining)
                                var total = 0
                                while (total < remaining) {
                                    val r = raf.read(buffer, total, remaining - total)
                                    if (r <= 0) break
                                    total += r
                                }
                                String(buffer, 0, total)
                            }
                        } catch (_: Exception) { "" }
                        offset = length
                        if (text.isNotEmpty()) {
                            withContext(Dispatchers.Main) { appendOutput(text) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ConsoleActivity", "Error reading log", e)
                }
                delay(200)
            }
        }
    }

    /** Width of the fixed "HH:mm  " time column in px (hanging indent). */
    private fun hangingIndentPx(): Int = try {
        binding.consoleOutput.paint.measureText("HH:mm  ").toInt().coerceAtLeast(0)
    } catch (_: Exception) { 0 }

    private fun appendOutput(output: String) {
        // Strip ANSI escapes so control codes never render as garbage; keep plain text.
        val clean = try {
            ANSI_REGEX.replace(output, "")
        } catch (_: Exception) { output }
        val newLines = clean.lines()
        val visible = mutableListOf<String>()
        for (line in newLines) {
            // Preserve blank-line spacing in buffer only if it carries content around it.
            if (line.isEmpty() && logBuffer.isEmpty()) continue
            logBuffer.addLast(line)
            if (logBuffer.size > MAX_BUFFER_LINES) logBuffer.removeFirst()
            when (lineLevel(line)) {
                "ERROR" -> errorCount++
                "WARN" -> warnCount++
                else -> infoCount++
            }
            if (matchesFilter(line)) visible.add(line)
        }
        if (visible.isNotEmpty()) {
            // Fixed HH:mm column + hanging indent (design); raw text kept in logBuffer.
            val formatted = ConsoleLogFormat.formatLines(this, visible, hangingIndentPx())
            if (binding.consoleOutput.text.isEmpty()) binding.consoleOutput.append(formatted)
            else {
                binding.consoleOutput.append("\n")
                binding.consoleOutput.append(formatted)
            }
            if (autoScroll) scrollToBottom()
        }
        updateLevelCounts()
        updateFooter()
    }

    private fun lineLevel(line: String): String {
        return when {
            ERROR_PATTERN.containsMatchIn(line) -> "ERROR"
            WARN_PATTERN.containsMatchIn(line) -> "WARN"
            else -> "INFO"
        }
    }

    private fun matchesFilter(line: String): Boolean {
        if (filterQuery.isNotEmpty() && !line.lowercase().contains(filterQuery)) return false
        return when (filterLevel) {
            "INFO" -> lineLevel(line) == "INFO"
            "WARN" -> lineLevel(line) == "WARN"
            "ERROR" -> lineLevel(line) == "ERROR"
            else -> true
        }
    }

    /** Full re-render from buffer (used when the filter changes). */
    private fun renderFiltered() {
        try {
            binding.consoleOutput.text =
                ConsoleLogFormat.formatLines(
                    this, logBuffer.filter { matchesFilter(it) }, hangingIndentPx()
                )
            if (autoScroll) scrollToBottom()
        } catch (e: Exception) {
            Log.e("ConsoleActivity", "renderFiltered failed", e)
        }
    }

    private fun fullLogText(): String {
        return try {
            logBuffer.joinToString("\n")
        } catch (_: Exception) { "" }
    }

    private fun updateLevelCounts() {
        try {
            val total = infoCount + warnCount + errorCount
            binding.chipAll.text = "ALL $total"
            binding.chipInfo.text = "INFO $infoCount"
            binding.chipWarn.text = "WARN $warnCount"
            binding.chipError.text = "ERROR $errorCount"
        } catch (_: Exception) {}
    }

    /** Footer shows the REAL process state (throttled: isRunning reads live facts). */
    private fun updateFooter() {
        footerTick++
        if (footerTick % 10 != 0) return
        val id = serverId ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val running = try {
                EmbeddedProcessManager.isRunning(id)
            } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                try {
                    val state = if (running) "● running" else "● stopped"
                    binding.tvFooterStatus.text = "$state • server.log • ${logBuffer.size} lines"
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendCommand(command: String) {
        recordHistory(command)
        appendOutput("$ $command\n")
        writeToProcess("$command\n")
    }

    private fun recordHistory(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        if (sentHistory.lastOrNull() != cmd) {
            sentHistory.addLast(cmd)
            if (sentHistory.size > 50) sentHistory.removeFirst()
        }
        historyPos = -1
    }

    /** Cycles previously sent commands into the input (terminal-style history). */
    private fun recallHistory() {
        if (sentHistory.isEmpty()) return
        historyPos = (historyPos + 1) % sentHistory.size
        val cmd = sentHistory[sentHistory.size - 1 - historyPos]
        binding.inputCommand.setText(cmd)
        binding.inputCommand.setSelection(cmd.length)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
