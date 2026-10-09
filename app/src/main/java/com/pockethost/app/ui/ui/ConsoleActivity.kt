package com.pockethost.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.util.Log
import android.view.ContextMenu
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
    private var autoScroll = true

    // In-console typing: rendered holds the read-only log, currentInput the
    // editable tail after inputBase. programmatic guards displayConsole().
    // prompt is the shell prefix (user@host:~$) from real facts, re-shown
    // before the input tail; promptColor is tertiary green.
    private val rendered = SpannableStringBuilder()
    private var currentInput = ""
    private var inputBase = 0
    private var programmatic = false
    private var prompt = ""
    private var promptColor = 0
    // True while the finger is down on the log (drag/tap in progress).
    private var touching = false

    companion object {
        private const val INITIAL_TAIL_SIZE = 16 * 1024L
        private const val MAX_BUFFER_LINES = 2000
        private val ANSI_REGEX = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        // Edge-to-edge: draw under system bars, pad the root with real insets.
        // IME included: when the keyboard opens, content squeezes above it.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, maxOf(sys.bottom, ime.bottom))
            insets
        }
        setContentView(binding.root)

        promptColor = ContextCompat.getColor(this, R.color.tertiary)
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

    /** Pause-on-scroll: scrolling up pauses follow, scrolling back to the
     * bottom resumes it (no floating button covering the log). */
    private fun setupFilters() {
        // Hard guard: never rest past the real text end.
        binding.scrollView.setOnScrollChangeListener { _, _, _, _, _ -> clampScroll() }
        // Manual scroll up pauses follow (standard console UX).
        binding.scrollView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE) {
                binding.scrollView.post {
                    autoScroll = binding.scrollView.scrollY >= contentMaxScroll() - 120
                }
            }
            false
        }
    }

    private fun scrollToBottom() {
        binding.consoleOutput.post {
            binding.scrollView.smoothScrollTo(0, contentMaxScroll())
        }
    }

    /** Real text end: child bottom + paddings - viewport (never past it). */
    private fun contentMaxScroll(): Int {
        return try {
            (binding.consoleOutput.bottom +
                binding.scrollView.paddingTop +
                binding.scrollView.paddingBottom -
                binding.scrollView.height).coerceAtLeast(0)
        } catch (_: Exception) { 0 }
    }

    /** Hard clamp: scroll can never go past the last line. */
    private fun clampScroll() {
        try {
            val max = contentMaxScroll()
            if (binding.scrollView.scrollY > max) binding.scrollView.scrollTo(0, max)
        } catch (_: Exception) {}
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
        rendered.clear()
        currentInput = ""
        displayConsole()
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
        // Touch policy: clean tap snaps the cursor to the input tail (posted,
        // so it runs after EditText's own placement); press-and-hold + drag
        // keeps framework text selection for copy; plain drag scrolls the log
        // manually (works even if the parent never intercepts), with fling.
        var downX = 0f
        var downY = 0f
        var downT = 0L
        var lastY = 0f
        var dragging = false
        var tracker: android.view.VelocityTracker? = null
        val vc = android.view.ViewConfiguration.get(this)
        val slop = vc.scaledTouchSlop.toFloat()
        binding.consoleOutput.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = event.x
                    downY = event.y
                    downT = event.eventTime
                    lastY = event.y
                    dragging = false
                    tracker?.recycle()
                    tracker = android.view.VelocityTracker.obtain()
                    tracker?.addMovement(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    try {
                        val s0 = binding.consoleOutput.selectionStart
                        val s1 = binding.consoleOutput.selectionEnd
                        // Active selection (copy flow): don't interfere.
                        if (s0 != s1) return@setOnTouchListener false
                    } catch (_: Exception) {}
                    val dist = kotlin.math.hypot(
                        (event.x - downX).toDouble(), (event.y - downY).toDouble()
                    )
                    if (!dragging && dist > slop) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        val dy = event.y - lastY
                        lastY = event.y
                        // Mirrored scroll (user request): content moves opposite
                        // the finger, like dragging a scrollbar thumb.
                        binding.scrollView.scrollBy(0, dy.toInt())
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    touching = false
                    tracker?.addMovement(event)
                    if (dragging) {
                        dragging = false
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        try {
                            tracker?.computeCurrentVelocity(1000, vc.scaledMaximumFlingVelocity.toFloat())
                            val vy = tracker?.yVelocity ?: 0f
                            if (kotlin.math.abs(vy) > vc.scaledMinimumFlingVelocity) {
                                // Mirrored to match the drag above: keep flying
                                // opposite the finger flick direction.
                                binding.scrollView.fling(vy.toInt())
                            }
                        } catch (_: Exception) {}
                        tracker?.recycle()
                        tracker = null
                        binding.scrollView.post {
                            try {
                                autoScroll = binding.scrollView.scrollY >= contentMaxScroll() - 120
                            } catch (_: Exception) {}
                        }
                        return@setOnTouchListener true
                    }
                    tracker?.recycle()
                    tracker = null
                    val dt = event.eventTime - downT
                    val dist = kotlin.math.hypot(
                        (event.x - downX).toDouble(), (event.y - downY).toDouble()
                    )
                    if (dt < 300 && dist < 24) {
                        binding.consoleOutput.post {
                            try {
                                val s0 = binding.consoleOutput.selectionStart
                                val s1 = binding.consoleOutput.selectionEnd
                                if (s0 == s1) {
                                    binding.consoleOutput.setSelection(
                                        binding.consoleOutput.text.length
                                    )
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    dragging = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    tracker?.recycle()
                    tracker = null
                }
            }
            false
        }
        binding.consoleOutput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onTerminalEdited(s)
            }
        })
        binding.consoleOutput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitTerminalInput()
                true
            } else false
        }
        binding.consoleOutput.requestFocus()
    }

    /** Shows rendered log + prompt + preserved input tail.
     * Cursor follows the end only if it was already there (reading the
     * history above must never yank the viewport away). */
    private fun displayConsole() {
        try {
            programmatic = true
            val prevLen = binding.consoleOutput.text.length
            val prevSel = try {
                binding.consoleOutput.selectionStart
            } catch (_: Exception) { prevLen }
            val sb = SpannableStringBuilder(rendered)
            if (prompt.isNotEmpty()) {
                val s = sb.length
                sb.append(prompt)
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(promptColor),
                    s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sb.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            sb.append(currentInput)
            binding.consoleOutput.setText(sb)
            inputBase = sb.length - currentInput.length
            // Follow the end only when actually following (not while the user
            // reads history or drags); otherwise the stream would rip the
            // viewport down and old messages would be unreachable.
            if (prevSel >= prevLen && autoScroll && !touching) {
                binding.consoleOutput.setSelection(sb.length)
            }
        } catch (_: Exception) {
        } finally {
            programmatic = false
        }
    }

    private fun onTerminalEdited(s: Editable?) {
        if (programmatic) return
        try {
            val text = s?.toString() ?: return
            // Edit touched read-only history/prompt -> revert to log + input tail.
            val guard = rendered.toString() + prompt
            if (text.length < inputBase || !text.startsWith(guard)) {
                displayConsole()
                return
            }
            currentInput = text.substring(inputBase)
            if (currentInput.contains("\n")) submitTerminalInput()
        } catch (_: Exception) {}
    }

    /** Sends every completed line, keeps the unfinished tail as input. */
    private fun submitTerminalInput() {
        try {
            val parts = currentInput.replace("\r", "").split("\n")
            currentInput = parts.last()
            for (i in 0 until parts.size - 1) {
                val line = parts[i]
                if (line.isNotEmpty()) sendCommand(line) else writeToProcess("\n")
            }
            displayConsole()
            if (autoScroll) scrollToBottom()
        } catch (_: Exception) {}
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

    private fun writeToProcessRaw(data: String) {
        val id = serverId ?: return
        val ok = try {
            // send raw bytes as-is without trailing \n
            if (data.endsWith("\n")) EmbeddedProcessManager.sendInput(id, data)
            else EmbeddedProcessManager.sendRaw(id, data.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) { false }
        if (!ok) appendOutput("[process not running]\n")
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

    private fun loadServerInfo() {
        serverId?.let { id ->
            lifecycleScope.launch {
                try {
                    repository.getServer(id).collect { server ->
                        server?.let {
                            val uriStr = it.effectiveWorkDirUri.ifEmpty { it.workingDirectory }
                            workDir = EmbeddedPaths.resolveWorkDir(this@ConsoleActivity, it.id, uriStr)
                            binding.tvServerName.text = it.name
                            prompt = buildPrompt(it)
                            displayConsole()
                            updateStatusPill(it.status)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ConsoleActivity", "loadServerInfo", e)
                    appendOutput("ERROR loading console: ${e.message}\n")
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
                        withContext(Dispatchers.Main) {
                            rendered.clear()
                            displayConsole()
                        }
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
            visible.add(line)
        }
        if (visible.isNotEmpty()) {
            // Fixed HH:mm column + hanging indent (design); raw text kept in logBuffer.
            val formatted = ConsoleLogFormat.formatLines(this, visible, hangingIndentPx())
            if (rendered.isEmpty()) rendered.append(formatted)
            else {
                rendered.append("\n")
                rendered.append(formatted)
            }
            displayConsole()
            if (autoScroll) scrollToBottom()
        }
    }

    private fun fullLogText(): String {
        return try {
            logBuffer.joinToString("\n")
        } catch (_: Exception) { "" }
    }

    private fun sendCommand(command: String) {
        appendOutput("$prompt$command\n")
        writeToProcess("$command\n")
    }

    /** Shell-style prompt from REAL facts (never sample values like kensur@homeserver).
     * Ubuntu: guest shell really runs as root (proot -0, HOME=/root).
     * Minecraft: no shell user exists, so the console identity is the server name.
     * Host: device codename (Build.DEVICE). Path: ~ (servers start in their
     * work dir = their home; the guest shell may cd afterwards). */
    private fun buildPrompt(server: com.pockethost.core.model.Server): String {
        val user = if (server.type == com.pockethost.core.model.ServerType.UBUNTU_SERVER) {
            "root"
        } else {
            server.name.trim().ifEmpty { "server" }
        }
        val host = try {
            android.os.Build.DEVICE.ifBlank { android.os.Build.MODEL }
        } catch (_: Exception) { "" }
        return "$user@${host.ifBlank { "device" }}:~$ "
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
