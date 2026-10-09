package com.pockethost.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pockethost.app.R
import com.pockethost.app.databinding.ActivityRemoteConsoleBinding
import com.pockethost.app.remote.RemoteBridgeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Remote console to the PC jvm-host BridgeServer over LAN.
 *
 * Default IP:PORT is 192.168.0.200:8765 (editable - the user reconnected
 * the network, the PC address may change). Flow:
 * Connect -> GET /api/ping + /api/status, status pill shows the result.
 * Console input -> POST /api/exec {"cmd":"..."}, output appended below.
 * Disconnect clears the session (no background polling kept).
 */
class RemoteConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteConsoleBinding

    private var connectedBase: String? = null
    private var connecting = false

    // Convenient viewport: native fling, pause-on-scroll, resume on Bottom.
    private var followLive = true
    private var pendingLines = 0
    private var userTouching = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteConsoleBinding.inflate(layoutInflater)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            insets
        }
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "PC Console (remote)"

        binding.etRemoteHost.setText(loadSavedHost())
        updateUi()

        binding.btnConnect.setOnClickListener { connect() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnPing.setOnClickListener { ping() }
        binding.btnRemoteSend.setOnClickListener { sendCurrent() }
        binding.inputRemoteCommand.setOnEditorActionListener { _, _, _ ->
            sendCurrent()
            true
        }
        binding.btnRemoteUname.setOnClickListener { quickExec("uname -a") }
        binding.btnRemotePwd.setOnClickListener { quickExec("pwd") }
        binding.btnRemoteLs.setOnClickListener { quickExec("ls -la") }
        binding.btnRemoteLog.setOnClickListener { fetchLog() }
        setupLogScroll()
    }

    private fun loadSavedHost(): String {
        return try {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_HOST, RemoteBridgeClient.DEFAULT_HOST)
                ?: RemoteBridgeClient.DEFAULT_HOST
        } catch (_: Exception) {
            RemoteBridgeClient.DEFAULT_HOST
        }
    }

    private fun saveHost(host: String) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_HOST, host).apply()
        } catch (_: Exception) {}
    }

    private fun currentHostInput(): String {
        val raw = try {
            binding.etRemoteHost.text?.toString()?.trim()
        } catch (_: Exception) {
            null
        }
        return if (raw.isNullOrEmpty()) RemoteBridgeClient.DEFAULT_HOST else raw
    }

    private fun connect() {
        if (connecting) return
        val raw = currentHostInput()
        saveHost(raw)
        val base = RemoteBridgeClient.normalizeBase(raw)
        connecting = true
        updateUi()
        appendOutput("Connecting to $base ...\n")
        lifecycleScope.launch(Dispatchers.IO) {
            val pong = RemoteBridgeClient.ping(base)
            val status = if (pong) RemoteBridgeClient.status(base) else null
            val cause = RemoteBridgeClient.lastError
            withContext(Dispatchers.Main) {
                connecting = false
                if (pong) {
                    connectedBase = base
                    appendOutput("Connected: $base\n")
                    if (!status.isNullOrBlank()) {
                        appendOutput(status.take(2000) + "\n")
                    }
                    Toast.makeText(this@RemoteConsoleActivity, "Connected to $base", Toast.LENGTH_SHORT).show()
                } else {
                    appendOutput("FAILED: no pong from $base/api/ping. Check Wi-Fi + `serve` on the PC.\n")
                    if (!cause.isNullOrBlank()) {
                        // Never bare again: cleartext block, wrong IP and dead
                        // bridge have different signatures here.
                        appendOutput("Cause: $cause\n")
                    }
                    Toast.makeText(this@RemoteConsoleActivity, "No connection - see log", Toast.LENGTH_LONG).show()
                }
                updateUi()
            }
        }
    }

    private fun disconnect() {
        connectedBase = null
        appendOutput("[disconnected]\n")
        updateUi()
    }

    private fun ping() {
        val base = connectedBase ?: RemoteBridgeClient.normalizeBase(currentHostInput())
        lifecycleScope.launch(Dispatchers.IO) {
            val pong = RemoteBridgeClient.ping(base)
            val cause = RemoteBridgeClient.lastError
            withContext(Dispatchers.Main) {
                if (pong) appendOutput("ping $base -> pong\n")
                else {
                    appendOutput("ping $base -> FAILED\n")
                    if (!cause.isNullOrBlank()) appendOutput("Cause: $cause\n")
                }
                if (pong && connectedBase == null) {
                    connectedBase = base
                    updateUi()
                }
            }
        }
    }

    private fun quickExec(cmd: String) {
        binding.inputRemoteCommand.setText(cmd)
        sendCurrent()
    }

    private fun fetchLog() {
        val base = connectedBase ?: run {
            appendOutput("[not connected - press Connect first]\n")
            return
        }
        appendOutput("\$ tail server.log ...\n")
        lifecycleScope.launch(Dispatchers.IO) {
            val tail = RemoteBridgeClient.log(base, 60)
            withContext(Dispatchers.Main) {
                if (tail == null) appendOutput("[log fetch failed]\n")
                else appendOutput(tail.take(8000) + "\n")
                // Explicit user request: jump to the fresh output.
                followLive = true
                pendingLines = 0
                updateJumpButtons()
                scrollToBottom(instant = false)
            }
        }
    }

    private fun sendCurrent() {
        val base = connectedBase ?: run {
            appendOutput("[not connected - press Connect first]\n")
            return
        }
        val cmd = binding.inputRemoteCommand.text?.toString() ?: ""
        if (cmd.isBlank()) return
        binding.inputRemoteCommand.text?.clear()
        appendOutput("\$ $cmd\n")
        binding.btnRemoteSend.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val res = RemoteBridgeClient.exec(base, cmd)
            withContext(Dispatchers.Main) {
                binding.btnRemoteSend.isEnabled = true
                if (res.output.isNotEmpty()) appendOutput(res.output.trimEnd() + "\n")
                appendOutput("[exit ${res.code}]\n")
                // Answer to the user's own command: show it.
                followLive = true
                pendingLines = 0
                updateJumpButtons()
                scrollToBottom(instant = false)
                if (!res.ok && res.code == -1 && res.raw.contains("failed")) {
                    appendOutput("Hint: PC unreachable - re-check IP:PORT and LAN.\n")
                }
            }
        }
    }

    private fun appendOutput(text: String) {
        try {
            val clean = text.replace("\r\n", "\n")
            val added = clean.lines().size.coerceAtLeast(1)
            binding.remoteOutput.append(
                if (binding.remoteOutput.text.isEmpty()) clean else clean
            )
            if (followLive && !userTouching) {
                scrollToBottom(instant = true)
            } else {
                pendingLines += added
                updateJumpButtons()
                // Paused: keep viewport where it was (TextView append keeps
                // scrollY, so nothing to restore — just don't auto-scroll).
            }
        } catch (_: Exception) {}
    }

    /**
     * Scroll subsystem (same UX as the built-in console, adapted to the
     * fixed-height log card): native gestures, pause when the user scrolls
     * up, Bottom/Top jumps, "N new" badge while paused.
     */
    private fun setupLogScroll() {
        try {
            // Inner card scrolls natively inside the page: don't let the outer
            // NestedScrollView steal the gesture mid-drag.
            binding.remoteScroll.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        userTouching = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        userTouching = false
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        binding.remoteScroll.post {
                            onRemoteScrolled(binding.remoteScroll.scrollY)
                        }
                        binding.remoteScroll.postDelayed(
                            { onRemoteScrolled(binding.remoteScroll.scrollY) }, 350
                        )
                    }
                }
                false
            }
            binding.remoteScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                onRemoteScrolled(scrollY)
            }
            binding.btnRemoteBottom.setOnClickListener {
                followLive = true
                pendingLines = 0
                updateJumpButtons()
                scrollToBottom(instant = false)
            }
            binding.btnRemoteTop.setOnClickListener {
                followLive = false
                updateJumpButtons()
                try { binding.remoteScroll.smoothScrollTo(0, 0) } catch (_: Exception) {}
            }
            updateJumpButtons()
        } catch (_: Exception) {}
    }

    private fun remoteMaxScroll(): Int {
        return try {
            (binding.remoteOutput.bottom +
                binding.remoteScroll.paddingTop +
                binding.remoteScroll.paddingBottom -
                binding.remoteScroll.height).coerceAtLeast(0)
        } catch (_: Exception) { 0 }
    }

    private fun onRemoteScrolled(scrollY: Int) {
        try {
            val max = remoteMaxScroll()
            if (scrollY > max + 2 && max >= 0) {
                binding.remoteScroll.scrollTo(0, max)
                return
            }
            val threshold = (16 * resources.displayMetrics.density).toInt().coerceAtLeast(8)
            val atBottom = scrollY >= max - threshold
            if (atBottom) {
                followLive = true
                pendingLines = 0
            } else {
                followLive = false
            }
            updateJumpButtons()
        } catch (_: Exception) {}
    }

    private fun updateJumpButtons() {
        try {
            binding.btnRemoteBottom.text = if (!followLive && pendingLines > 0) {
                "↓ Bottom · $pendingLines new"
            } else {
                "↓ Bottom"
            }
        } catch (_: Exception) {}
    }

    private fun scrollToBottom(instant: Boolean) {
        binding.remoteOutput.post {
            try {
                val max = remoteMaxScroll()
                if (instant) binding.remoteScroll.scrollTo(0, max)
                else binding.remoteScroll.smoothScrollTo(0, max)
            } catch (_: Exception) {}
        }
    }

    private fun updateUi() {
        val connected = connectedBase != null
        try {
            binding.tvRemoteStatus.text = when {
                connecting -> "Connecting…"
                connected -> "Connected: $connectedBase"
                else -> "Disconnected"
            }
            val pillBg = when {
                connected -> R.drawable.bg_status_running
                connecting -> R.drawable.bg_status_warning
                else -> R.drawable.bg_status_idle
            }
            val dotBg = when {
                connected -> R.drawable.bg_dot_running
                connecting -> R.drawable.bg_dot_warning
                else -> R.drawable.bg_dot_idle
            }
            val fg = when {
                connected -> R.color.badge_running_fg
                connecting -> R.color.badge_warning_fg
                else -> R.color.badge_idle_fg
            }
            binding.statusBadge.setBackgroundResource(pillBg)
            binding.statusDot.setBackgroundResource(dotBg)
            binding.tvRemoteStatus.setTextColor(ContextCompat.getColor(this, fg))
            binding.btnConnect.isEnabled = !connected && !connecting
            binding.btnDisconnect.isEnabled = connected
            binding.btnRemoteSend.isEnabled = connected
            binding.inputRemoteCommand.isEnabled = connected
            binding.etRemoteHost.isEnabled = !connecting
            binding.progressBar.visibility = if (connecting) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        private const val PREFS = "remote_bridge"
        private const val KEY_HOST = "host"
    }
}
