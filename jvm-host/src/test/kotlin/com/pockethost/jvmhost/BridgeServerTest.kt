package com.pockethost.jvmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class BridgeServerTest {

    private fun get(url: String, timeoutMs: Int = 10_000): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Connection", "close")
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun pingAndStatusOnEphemeralPort() {
        val tmp = java.nio.file.Files.createTempDirectory("pockethost-bridge").toFile()
        try {
            val server = UbuntuServer(tmp, "bridge-test")
            val bridge = BridgeServer(server)
            // Port 0 would be ideal, but start() scans upward from a base;
            // pick a high base that is almost surely free.
            var base = 48765
            var started = false
            repeat(5) {
                if (bridge.start(base)) {
                    started = true
                    return@repeat
                }
                base += 20
            }
            assertTrue("bridge did not bind", started)
            val port = bridge.actualPort!!
            try {
                val (pingCode, pingBody) = get("http://127.0.0.1:$port/api/ping")
                assertEquals(200, pingCode)
                assertEquals("pong", pingBody.trim())

                val (stCode, stBody) = get("http://127.0.0.1:$port/api/status")
                assertEquals(200, stCode)
                assertTrue(stBody.contains("\"ok\":true"))
                assertTrue(stBody.contains("pockethost-ubuntu-server"))

                val (rootCode, rootBody) = get("http://127.0.0.1:$port/")
                assertEquals(200, rootCode)
                assertTrue(rootBody.contains("PocketHost"))
            } finally {
                assertTrue(bridge.stop())
                assertFalse(bridge.isRunning())
            }
        } finally {
            try {
                tmp.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun execEchoRoundTrip() {
        val tmp = java.nio.file.Files.createTempDirectory("pockethost-bridge-exec").toFile()
        try {
            val server = UbuntuServer(tmp, "bridge-exec")
            val bridge = BridgeServer(server)
            assertTrue(bridge.start(48865))
            val port = bridge.actualPort!!
            try {
                val host = HostInfo.current()
                val cmd = if (host.os == OsKind.WINDOWS) "echo hello-bridge" else "echo hello-bridge"
                val (code, body) = get(
                    "http://127.0.0.1:$port/api/exec?cmd=" +
                        java.net.URLEncoder.encode(cmd, "UTF-8")
                )
                assertEquals(200, code)
                assertTrue(body.contains("hello-bridge"))
            } finally {
                bridge.stop()
            }
        } finally {
            try {
                tmp.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun lanInfoNeverThrows() {
        val ips = LanInfo.lanIPv4()
        // May be empty on a locked-down CI box, but never null.
        assertTrue(ips.none { it.isBlank() })
        assertTrue(LanInfo.primaryLanIp().isNotBlank())
    }
}
