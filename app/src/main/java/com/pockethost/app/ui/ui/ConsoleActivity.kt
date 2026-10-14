package com.pockethost.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.Editable
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

    // Local input history: Up/Down recall past commands like everywhere
    // (Termux, shells). Minecraft stdin is line-buffered and ignores ESC[A/B,
    // so history must be local, not via escape sequences.
    private val inputHistory = ArrayList<String>(100)
    private var historyIndex = 0

    // Log buffer + follow state (all real: derived from actual backend output).
    private val logBuffer: ArrayDeque<String> = ArrayDeque()
    // Follow mode: true = live tail (new lines pin to bottom), false = paused
    // while the user reads history. Never force-scroll while paused.
    private var followLive = true
    private var pendingLines = 0
    // True while a finger is down on the log (native ScrollView handles the
    // gesture itself; this flag only suppresses auto-scroll mid-gesture).
    private var userTouching = false

    // Log-only rendering: logView is a read-only TextView, the command input
    // lives in a separate small EditText (inputField). Typing therefore never
    // re-lays-out the whole log — that single-EditText design was the lag root
    // cause (O(N) guard copy + full setText on every keystroke/log chunk).
    // prompt is the shell prefix shown in promptView (input bar), not in the log.
    private val rendered = SpannableStringBuilder()
    private var prompt = ""
    private var promptColor = 0
    private var inputProgrammatic = false

    companion object {
        private const val INITIAL_TAIL_SIZE = 256 * 1024L
        // 2000 formatted lines is plenty for a phone screen and keeps
        // TextView layout cheap. Was 8000 — same content in one EditText
        // made every keystroke re-measure megabytes of spans.
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
        registerForContextMenu(binding.logView)
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

    /**
     * Scroll system, rebuilt from zero for maximum convenience:
     * - 100% native finger scroll + fling (ScrollView handles gestures, code
     *   never drags content manually, so direction/speed always feel system).
     * - Live follow: new lines pin to the bottom only while the user is at
     *   the bottom and not touching. Scrolling up pauses (overlay appears),
     *   scrolling back to the bottom resumes automatically.
     * - Overlay: "Paused" pill + [Top] / [Bottom · N new] jumps, so a long
     *   log is reachable without endless swiping.
     * - PgUp/PgDn extra keys page the viewport (long-press still sends the
     *   terminal sequence for vim/htop).
     */
    private fun setupFilters() {
        // The log is an editable terminal: never let its cursor/focus pull the
        // viewport (that "pins" scroll to the last line). All viewport moves
        // are explicit below; finger scroll + fling stay native.
        binding.scrollView.allowChildScroll = false
        binding.scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            onLogScrolled(scrollY)
        }
        // Track finger state on the scroller itself (return false: gesture
        // stays fully native).
        binding.scrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> userTouching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    // Re-evaluate after the fling settles.
                    binding.scrollView.post { onLogScrolled(binding.scrollView.scrollY) }
                    binding.scrollView.postDelayed(
                        { onLogScrolled(binding.scrollView.scrollY) }, 350
                    )
                }
            }
            false
        }
        binding.btnToBottom.setOnClickListener { resumeFollow(smooth = true) }
        binding.btnToTop.setOnClickListener { scrollToTop(smooth = true) }
        setupFastScroller()
        updateScrollOverlay()
    }

    /**
     * Draggable fast-scroll strip: dragging the thumb (or tapping the track)
     * scrolls the log directly, bypassing the terminal text field entirely,
     * so it always works — even mid-selection or while output streams in.
     * Thumb height = viewport/content ratio, position = scroll fraction.
     */
    private fun setupFastScroller() {
        binding.fastScrollTrack.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    userTouching = true
                    try { binding.fastScrollThumb.alpha = 1f } catch (_: Exception) {}
                    fastScrollTo(event.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    fastScrollTo(event.y)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    try { binding.fastScrollThumb.alpha = 0.9f } catch (_: Exception) {}
                    binding.scrollView.post { onLogScrolled(binding.scrollView.scrollY) }
                    true
                }
                else -> false
            }
        }
        // Thumb geometry follows content changes.
        binding.logView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateFastThumb()
        }
        binding.fastScrollTrack.post { updateFastThumb() }
    }

    /** Jump the viewport to the fraction of the log under the finger. */
    private fun fastScrollTo(touchY: Float) {
        try {
            val trackH = binding.fastScrollTrack.height.toFloat()
            if (trackH <= 0) return
            val thumbH = binding.fastScrollThumb.layoutParams.height.toFloat()
                .coerceIn(1f, trackH)
            val max = contentMaxScroll()
            if (max <= 0) return
            val f = ((touchY - thumbH / 2f) / (trackH - thumbH)).coerceIn(0f, 1f)
            binding.scrollView.scrollTo(0, (f * max).toInt())
        } catch (_: Exception) {}
    }

    /** Resize/reposition the thumb; hide the strip when nothing scrolls.
     * Also pins short logs to the bottom: with no layout_gravity on the
     * child (it would collapse the scroll range), a short field is stretched
     * to the viewport via minimumHeight and its text sits low via
     * android:gravity="bottom". */
    private fun updateFastThumb() {
        try {
            val track = binding.fastScrollTrack
            val thumb = binding.fastScrollThumb
            val trackH = track.height
            if (trackH <= 0) return
            val viewportH = binding.scrollView.height
            // Stretch a short log to the viewport so the prompt sits low;
            // release the stretch as soon as real scrolling exists.
            val minH = (viewportH -
                binding.scrollView.paddingTop - binding.scrollView.paddingBottom)
                .coerceAtLeast(0)
            val contentH = try {
                binding.logView.height +
                    binding.scrollView.paddingTop + binding.scrollView.paddingBottom
            } catch (_: Exception) { 0 }
            if (contentH <= viewportH || viewportH <= 0) {
                track.visibility = View.GONE
                try {
                    if (binding.logView.minimumHeight != minH) {
                        binding.logView.minimumHeight = minH
                    }
                } catch (_: Exception) {}
                return
            }
            try {
                if (binding.logView.minimumHeight != 0) binding.logView.minimumHeight = 0
            } catch (_: Exception) {}
            track.visibility = View.VISIBLE
            val minThumb = (48 * resources.displayMetrics.density).toInt()
            val thumbH = (trackH.toFloat() * viewportH / contentH)
                .toInt().coerceIn(minThumb, trackH)
            if (thumb.layoutParams.height != thumbH) {
                thumb.layoutParams.height = thumbH
                thumb.requestLayout()
            }
            val max = (contentH - viewportH).coerceAtLeast(1)
            val f = (binding.scrollView.scrollY.toFloat() / max).coerceIn(0f, 1f)
            thumb.translationY = f * (trackH - thumbH)
        } catch (_: Exception) {}
    }

    /** Threshold in px: "at bottom" = within ~16dp of the real text end. */
    private fun followThresholdPx(): Int =
        (16 * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    /** Real text end: child bottom + paddings - viewport (never negative). */
    private fun contentMaxScroll(): Int {
        return try {
            val childBottom = try { binding.logView.bottom } catch (_: Exception) { 0 }
            (childBottom +
                binding.scrollView.paddingTop +
                binding.scrollView.paddingBottom -
                binding.scrollView.height).coerceAtLeast(0)
        } catch (_: Exception) { 0 }
    }

    private fun isAtBottom(marginPx: Int = followThresholdPx()): Boolean {
        return try {
            binding.scrollView.scrollY >= contentMaxScroll() - marginPx
        } catch (_: Exception) { true }
    }

    /** Central state update, called from scroll listener / touch end / output. */
    private fun onLogScrolled(scrollY: Int) {
        try {
            val max = contentMaxScroll()
            // Safety clamp only when far past the end (content shrank after
            // clear/rotation); normal overscroll glow is left to the system.
            if (scrollY > max + 2 && max >= 0) {
                binding.scrollView.scrollTo(0, max)
                return
            }
            val atBottom = scrollY >= max - followThresholdPx()
            if (atBottom) {
                if (!followLive) {
                    followLive = true
                    pendingLines = 0
                }
            } else {
                followLive = false
            }
            updateScrollOverlay()
            updateFastThumb()
        } catch (_: Exception) {}
    }

    private fun pauseFollow() {
        followLive = false
        updateScrollOverlay()
    }

    private fun resumeFollow(smooth: Boolean) {
        followLive = true
        pendingLines = 0
        updateScrollOverlay()
        scrollToBottom(smooth = smooth)
    }

    private fun scrollToBottom(smooth: Boolean = false) {
        binding.scrollView.post {
            try {
                val max = contentMaxScroll()
                if (smooth) binding.scrollView.smoothScrollTo(0, max)
                else binding.scrollView.scrollTo(0, max)
            } catch (_: Exception) {}
        }
    }

    private fun scrollToTop(smooth: Boolean = true) {
        pauseFollow()
        try {
            if (smooth) binding.scrollView.smoothScrollTo(0, 0)
            else binding.scrollView.scrollTo(0, 0)
        } catch (_: Exception) {}
        binding.scrollView.post { updateScrollOverlay() }
    }

    /** Page the viewport by a fraction of the visible height (PgUp/PgDn keys). */
    private fun scrollPage(up: Boolean) {
        try {
            val h = binding.scrollView.height
            val delta = ((h * 0.9).toInt()).let { if (up) -it else it }
            binding.scrollView.smoothScrollBy(0, delta)
            // State follows from the scroll listener; force a refresh in case
            // the page lands exactly on an edge.
            binding.scrollView.postDelayed(
                { onLogScrolled(binding.scrollView.scrollY) }, 250
            )
        } catch (_: Exception) {}
    }

    /** Overlay visibility + "N new" badge. */
    private fun updateScrollOverlay() {
        try {
            val paused = !followLive
            binding.scrollOverlay.visibility = if (paused) View.VISIBLE else View.GONE
            binding.btnToBottom.text = if (pendingLines > 0) {
                "↓ Bottom · $pendingLines new"
            } else {
                "↓ Bottom"
            }
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
        val textView = binding.logView
        val text = textView.text?.toString() ?: ""
        val start = try { textView.selectionStart } catch (_: Exception) { -1 }
        val end = try { textView.selectionEnd } catch (_: Exception) { -1 }
        val textToCopy = if (start >= 0 && end > start) {
            try { text.substring(start.coerceIn(0, text.length), end.coerceIn(0, text.length)) }
            catch (_: Exception) { text }
        } else text
        copyToClipboard(textToCopy)
    }

    private fun copyAll() {
        copyToClipboard(fullLogText())
        Toast.makeText(this, "Copied ${fullLogText().length} chars", Toast.LENGTH_SHORT).show()
    }

    private fun selectAll() {
        binding.logView.post {
            try {
                val t = binding.logView.text
                if (t is Spannable) {
                    try { binding.logView.requestFocus() } catch (_: Exception) {}
                    Selection.selectAll(t)
                } else copyAll()
            } catch (_: Exception) { copyAll() }
        }
    }

    private fun clearConsole() {
        logBuffer.clear()
        rendered.clear()
        pendingLines = 0
        followLive = true
        historyIndex = inputHistory.size
        updateScrollOverlay()
        try {
            binding.logView.text = ""
        } catch (_: Exception) {}
        try {
            inputProgrammatic = true
            binding.inputField.text?.clear()
        } catch (_: Exception) {
        } finally {
            inputProgrammatic = false
        }
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
        // Log is read-only: native ScrollView gestures just work, no custom
        // drag/fling code needed (the old EditText swallowed touches).
        // Input lives in inputField below — its TextWatcher only sees a few
        // dozen chars, never the whole log.
        try {
            binding.promptView.text = prompt.ifEmpty { "$ " }
            binding.promptView.setTextColor(promptColor)
        } catch (_: Exception) {}
        try { binding.logView.text = "" } catch (_: Exception) {}
        binding.inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (inputProgrammatic) return
                try {
                    val text = s?.toString() ?: return
                    if (text.contains("\n")) submitInput()
                } catch (_: Exception) {}
            }
        })
        binding.inputField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitInput()
                true
            } else false
        }
        // Hardware Enter / keyboards that send key events instead of actions.
        // Hardware DPAD Up/Down: same as on-screen arrows - local history.
        binding.inputField.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        submitInput()
                        return@setOnKeyListener true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        navigateHistory(up = true)
                        return@setOnKeyListener true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        navigateHistory(up = false)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
        // Tap on empty log area focuses the input (selection still works via
        // long-press because the TextView is selectable).
        binding.logView.setOnClickListener {
            try {
                val s0 = binding.logView.selectionStart
                val s1 = binding.logView.selectionEnd
                if (s0 == s1) binding.inputField.requestFocus()
            } catch (_: Exception) {
                try { binding.inputField.requestFocus() } catch (_: Exception) {}
            }
        }
        binding.inputField.requestFocus()
    }

    /** Full refresh of the read-only log (clear / truncate / prompt change).
     * Viewport policy: finger down -> hands off completely; live-follow pins
     * to the bottom; paused history keeps its position (clamped on shrink). */
    private fun displayConsole() {
        try {
            val savedY = try { binding.scrollView.scrollY } catch (_: Exception) { 0 }
            try {
                binding.logView.text = SpannableStringBuilder(rendered)
            } catch (_: Exception) {
                try { binding.logView.text = rendered.toString() } catch (_: Exception) {}
            }
            try {
                binding.promptView.text = prompt.ifEmpty { "$ " }
                binding.promptView.setTextColor(promptColor)
            } catch (_: Exception) {}
            if (userTouching) return
            if (followLive) {
                scrollToBottom(smooth = false)
            } else {
                binding.scrollView.post {
                    try {
                        val max = contentMaxScroll()
                        if (savedY > max) binding.scrollView.scrollTo(0, max)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /** Incremental append: avoids full setText() on every log chunk.
     * Both the source-of-truth [rendered] and the TextView grow by the same
     * formatted span; layout cost stays proportional to the chunk. */
    private fun appendFormatted(formatted: Spanned) {
        try {
            if (rendered.isEmpty()) rendered.append(formatted)
            else {
                rendered.append("\n")
                rendered.append(formatted)
            }
        } catch (_: Exception) {}
        try {
            if (binding.logView.length() == 0) {
                binding.logView.text = SpannableStringBuilder(rendered)
            } else {
                binding.logView.append("\n")
                binding.logView.append(formatted)
            }
        } catch (_: Exception) {
            // Fallback: full refresh (never lose output).
            try { displayConsole() } catch (_: Exception) {}
        }
        if (userTouching) return
        if (followLive) {
            scrollToBottom(smooth = false)
        }
    }

    /** Sends the input field content line-by-line, keeps nothing buffered.
     * The field is tiny, so substring/split here is O(chars typed), not O(log). */
    private fun submitInput() {
        try {
            val raw = try { binding.inputField.text?.toString() ?: "" } catch (_: Exception) { "" }
            if (raw.isEmpty()) return
            inputProgrammatic = true
            try { binding.inputField.text?.clear() } catch (_: Exception) {}
            inputProgrammatic = false
            val parts = raw.replace("\r", "").split("\n")
            for (line in parts) {
                if (line.isNotEmpty()) {
                    pushHistory(line)
                    sendCommand(line)
                } else writeToProcess("\n")
            }
            // Sending means the user is at the tail: resume live follow.
            followLive = true
            pendingLines = 0
            updateScrollOverlay()
            if (followLive && !userTouching) scrollToBottom(smooth = false)
            try { binding.inputField.requestFocus() } catch (_: Exception) {}
        } catch (_: Exception) {
            try { inputProgrammatic = false } catch (_: Exception) {}
        }
    }

    /** Inserts printable helper text at the input cursor (/, —, TAB).
     * Control sequences (arrows/ESC/…) still go raw to the process. */
    private fun insertIntoInput(text: String) {
        try {
            val field = binding.inputField
            field.requestFocus()
            val start = try { field.selectionStart.coerceAtLeast(0) } catch (_: Exception) { field.text?.length ?: 0 }
            val end = try { field.selectionEnd.coerceAtLeast(start) } catch (_: Exception) { start }
            try { field.text?.replace(start, end, text) } catch (_: Exception) {
                try { field.append(text) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Local command history (like Termux/shells): Up recalls previous,
     * Down goes forward. Long-press still sends raw ESC for vim/htop. */
    private fun pushHistory(cmd: String) {
        try {
            if (cmd.isBlank()) return
            if (inputHistory.isNotEmpty() && inputHistory.last() == cmd) {
                historyIndex = inputHistory.size
                return
            }
            inputHistory.add(cmd)
            if (inputHistory.size > 100) inputHistory.removeAt(0)
            historyIndex = inputHistory.size
        } catch (_: Exception) {}
    }

    private fun navigateHistory(up: Boolean) {
        try {
            if (inputHistory.isEmpty()) return
            val field = binding.inputField
            val current = try { field.text?.toString() ?: "" } catch (_: Exception) { "" }
            // User typed something new manually - start browsing from the end.
            if (historyIndex in inputHistory.indices && current != inputHistory[historyIndex]) {
                historyIndex = inputHistory.size
            }
            historyIndex = if (up) (historyIndex - 1).coerceAtLeast(0)
            else (historyIndex + 1).coerceAtMost(inputHistory.size)
            inputProgrammatic = true
            try {
                if (historyIndex in inputHistory.indices) {
                    field.setText(inputHistory[historyIndex])
                    try { field.setSelection(field.text?.length ?: 0) } catch (_: Exception) {}
                } else {
                    field.text?.clear()
                }
            } finally {
                inputProgrammatic = false
            }
            try { field.requestFocus() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun setupExtraKeys() {
        binding.keyEsc.setOnClickListener { sendKey(27.toChar().toString()) }
        // Printable helpers type into the input field (line-buffered);
        // control sequences still go raw straight to the process.
        binding.keySlash.setOnClickListener { insertIntoInput("/") }
        binding.keyDash.setOnClickListener { insertIntoInput("-") }
        binding.keyHome.setOnClickListener { sendKey("\u001B[H") }
        // Tap = local history (works in Minecraft + shell), long-press = raw
        // ESC sequence for full-screen apps (vim/htop).
        binding.keyUp.setOnClickListener { navigateHistory(up = true) }
        binding.keyUp.setOnLongClickListener { sendKey("\u001B[A"); true }
        binding.keyEnd.setOnClickListener { sendKey("\u001B[F") }
        // PGUP/PGDN page the viewport for convenience (tap); long-press still
        // sends the terminal sequence for full-screen apps (vim/htop).
        binding.keyPgUp.setOnClickListener { scrollPage(up = true) }
        binding.keyPgUp.setOnLongClickListener { sendKey("\u001B[5~"); true }

        binding.keyTab.setOnClickListener { insertIntoInput("\t") }
        binding.keyLeft.setOnClickListener { sendKey("\u001B[D") }
        binding.keyDown.setOnClickListener { navigateHistory(up = false) }
        binding.keyDown.setOnLongClickListener { sendKey("\u001B[B"); true }
        binding.keyRight.setOnClickListener { sendKey("\u001B[C") }
        binding.keyPgDn.setOnClickListener { scrollPage(up = false) }
        binding.keyPgDn.setOnLongClickListener { sendKey("\u001B[6~"); true }

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
                            try {
                                binding.promptView.text = prompt.ifEmpty { "$ " }
                                binding.promptView.setTextColor(promptColor)
                            } catch (_: Exception) {}
                            updateStatusPill(it.status)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Normal on close/rotate - not an error.
                    throw e
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
                            logBuffer.clear()
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
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Normal on close/rotate - not an error, just rethrow.
                    throw e
                } catch (e: Exception) {
                    Log.e("ConsoleActivity", "Error reading log", e)
                }
                delay(200)
            }
        }
    }

    private fun appendOutput(output: String) {
        // Strip ANSI escapes so control codes never render as garbage; keep plain text.
        val clean = try {
            ANSI_REGEX.replace(output, "")
        } catch (_: Exception) { output }
        val newLines = clean.lines()
        val visible = mutableListOf<String>()
        var removed = 0
        for (line in newLines) {
            // Preserve blank-line spacing in buffer only if it carries content around it.
            if (line.isEmpty() && logBuffer.isEmpty()) continue
            logBuffer.addLast(line)
            if (logBuffer.size > MAX_BUFFER_LINES) {
                logBuffer.removeFirst()
                removed++
            }
            visible.add(line)
        }
        var truncated = false
        // Keep the rendered spans 1:1 with the buffer: drop the same lines.
        if (removed > 0 && rendered.isNotEmpty()) {
            try {
                var cut = 0
                var seen = 0
                while (seen < removed && cut < rendered.length) {
                    if (rendered[cut] == '\n') seen++
                    cut++
                }
                if (cut > 0) rendered.delete(0, cut)
                truncated = true
            } catch (_: Exception) {}
        }
        if (visible.isNotEmpty()) {
            // hangingIndentPx is 0: ConsoleLogFormat ignores it by design
            // (flat left edge); avoids a paint.measure on every chunk.
            val formatted = ConsoleLogFormat.formatLines(this, visible, 0)
            if (truncated) {
                // Buffer head was cut: one full refresh to stay in sync,
                // then continue incrementally.
                try {
                    if (rendered.isEmpty()) rendered.append(formatted)
                    else {
                        rendered.append("\n")
                        rendered.append(formatted)
                    }
                } catch (_: Exception) {}
                displayConsole()
                if (!followLive || userTouching) {
                    pendingLines += visible.size
                    updateScrollOverlay()
                }
            } else if (followLive && !userTouching) {
                appendFormatted(formatted) // pins to bottom inside
            } else {
                // Paused: count unread, keep viewport exactly where it was.
                // Still append incrementally (cheap) instead of full setText().
                pendingLines += visible.size
                try {
                    if (rendered.isEmpty()) rendered.append(formatted)
                    else {
                        rendered.append("\n")
                        rendered.append(formatted)
                    }
                } catch (_: Exception) {}
                try {
                    if (binding.logView.length() == 0) {
                        binding.logView.text = SpannableStringBuilder(rendered)
                    } else {
                        binding.logView.append("\n")
                        binding.logView.append(formatted)
                    }
                } catch (_: Exception) {
                    try { displayConsole() } catch (_: Exception) {}
                }
                updateScrollOverlay()
            }
        } else if (truncated) {
            displayConsole()
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
