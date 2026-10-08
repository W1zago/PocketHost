package com.pockethost.jvmhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * End-to-end check of the persistent interactive shell:
 * start -> wait for prompt -> send a command -> output lands in
 * server.log -> stop. Uses the real host shell backend.
 */
class LocalProcessManagerTest {

    @Test
    fun persistentShellEchoRoundTrip() {
        val mgr = LocalProcessManager()
        val dir = Files.createTempDirectory("pockethost-proc").toFile().apply { deleteOnExit() }
        val host = HostInfo.current()
        val marker = "pockethost-marker-519"
        val probe = if (host.os == OsKind.WINDOWS) "Write-Host $marker" else "echo $marker"

        assertTrue(mgr.start("test-shell", mgr.hostShellArgv(host), dir))
        try {
            assertTrue("shell did not start", waitForLog(dir, "PS |#|$".toRegex(), 20_000L))
            assertTrue(mgr.isRunning("test-shell"))
            assertTrue(mgr.sendInput("test-shell", probe))
            assertTrue("shell did not answer", waitForLog(dir, marker, 20_000L))
        } finally {
            assertTrue(mgr.stop("test-shell"))
        }
        assertFalse(mgr.isRunning("test-shell"))
        // Log survived the session and holds both the command and its output.
        val log = File(dir, LocalProcessManager.LOG_NAME).readText()
        assertTrue(log.contains(marker))
    }

    private fun waitForLog(dir: File, text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val log = File(dir, LocalProcessManager.LOG_NAME)
        while (System.currentTimeMillis() < deadline) {
            try {
                if (log.isFile && log.readText().contains(text)) return true
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(200)
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }

    private fun waitForLog(dir: File, pattern: Regex, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val log = File(dir, LocalProcessManager.LOG_NAME)
        while (System.currentTimeMillis() < deadline) {
            try {
                if (log.isFile && pattern.containsMatchIn(log.readText())) return true
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(200)
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }
}
