package com.pockethost.jvmhost

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * LAN-reachable HTTP bridge in front of [UbuntuServer].
 *
 * The phone (android-observer) has no way to see a bare local shell, so
 * this bridge proves the "local Ubuntu Server" is up and lets the
 * PocketHost Android app talk to it through its console:
 *
 *   curl http://<pcLanIp>:<port>/api/status
 *   curl "http://<pcLanIp>:<port>/api/exec?cmd=uname%20-a"
 *
 * Pure JDK (`com.sun.net.httpserver`, zero dependencies). Binds 0.0.0.0
 * explicitly so the LAN can reach it; every request is logged to the
 * server log. No auth - LAN only, same trust level as adb.
 */
class BridgeServer(
    val server: UbuntuServer,
    private val log: (String) -> Unit = server.log
) {

    @Volatile
    private var http: HttpServer? = null

    @Volatile
    var actualPort: Int? = null
        private set

    fun isRunning(): Boolean = http != null

    /**
     * Starts the bridge on 0.0.0.0:[port]. When [port] is busy, the next
     * 10 ports are tried so `serve` never dead-ends on a clash.
     * Returns true when a port was bound.
     */
    @Synchronized
    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (http != null) {
            log("Bridge is already running on port $actualPort.")
            return false
        }
        var lastErr = ""
        for (candidate in port..port + 10) {
            try {
                val h = HttpServer.create(InetSocketAddress("0.0.0.0", candidate), 64)
                h.createContext("/", ::handleRoot)
                h.createContext("/api/status", ::handleStatus)
                h.createContext("/api/ping", ::handlePing)
                h.createContext("/api/log", ::handleLog)
                h.createContext("/api/exec", ::handleExec)
                h.executor = Executors.newCachedThreadPool { r ->
                    Thread(r, "pockethost-bridge").apply { isDaemon = true }
                }
                h.start()
                http = h
                actualPort = candidate
                val ips = LanInfo.lanIPv4()
                log("PocketHost bridge listening on 0.0.0.0:$candidate")
                for (ip in ips) log("  http://$ip:$candidate/api/status")
                if (ips.isEmpty()) log("  (no LAN address found - phone must share the network)")
                return true
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        log("Bridge failed to bind $port..${port + 10} ($lastErr).")
        return false
    }

    @Synchronized
    fun stop(): Boolean {
        val h = http ?: return false
        return try {
            h.stop(0)
            try {
                (h.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
            } catch (_: Exception) {
            }
            http = null
            log("Bridge stopped (was port $actualPort).")
            actualPort = null
            true
        } catch (e: Exception) {
            log("Bridge stop error: ${e.message}")
            false
        }
    }

    // ---------- handlers ----------

    private fun handleRoot(ex: HttpExchange) {
        logRequest(ex)
        if (handleCorsPreflight(ex)) return
        if (ex.requestMethod.equals("GET", ignoreCase = true).not() &&
            ex.requestMethod.equals("HEAD", ignoreCase = true).not()
        ) {
            send(ex, 405, "Method not allowed", "text/plain")
            return
        }
        val st = server.status()
        val port = actualPort
        val ips = LanInfo.lanIPv4()
        val firstIp = ips.firstOrNull() ?: "127.0.0.1"
        // No-log selection: the root page is polled by browsers.
        val sel = server.selectBackend(st.host)
        val html = buildString {
            append("<!doctype html><html><head><meta charset=utf-8>")
            append("<meta name=viewport content=\"width=device-width,initial-scale=1\">")
            append("<title>PocketHost Ubuntu Server</title></head><body>")
            append("<h1>PocketHost Ubuntu Server</h1>")
            append("<p>Backend: <b>${sel.backend}</b>")
            append(" | shell running: <b>${st.running}</b>")
            append(" | image ready: <b>${st.installed}</b></p>")
            if (sel.degraded && sel.fallbackReason != null) {
                append("<p><b>Degraded mode (HOST_SHELL fallback):</b> ${escapeHtml(sel.fallbackReason)}</p>")
            }
            append("<p>Host: ${st.host.osName} (${st.host.osArch})</p>")
            append("<ul>")
            append("<li><a href=\"/api/status\">/api/status</a> - JSON status</li>")
            append("<li><a href=\"/api/ping\">/api/ping</a> - connectivity check</li>")
            append("<li><a href=\"/api/log?lines=50\">/api/log?lines=50</a> - server.log tail</li>")
            append("<li>/api/exec?cmd=uname%20-a - run one command</li>")
            append("</ul>")
            append("<p>From the phone (same Wi-Fi/LAN):</p>")
            append("<pre>curl http://$firstIp:$port/api/status\n")
            append("curl \"http://$firstIp:$port/api/exec?cmd=uname%20-a\"</pre>")
            append("</body></html>")
        }
        send(ex, 200, html, "text/html; charset=utf-8")
    }

    private fun handlePing(ex: HttpExchange) {
        logRequest(ex)
        if (handleCorsPreflight(ex)) return
        send(ex, 200, "pong", "text/plain")
    }

    private fun handleStatus(ex: HttpExchange) {
        logRequest(ex)
        if (handleCorsPreflight(ex)) return
        if (!allowGet(ex)) return
        val st = server.status()
        // No-log selection: /api/status is polled by the phone.
        val sel = server.selectBackend(st.host)
        val json = buildString {
            append("{")
            jsonField("app", "pockethost-ubuntu-server")
            jsonField("version", "1.0.0")
            jsonField("backend", sel.backend.name)
            jsonField("backendReady", st.installed)
            jsonField("shellRunning", st.running)
            jsonField("bridgeRunning", true)
            jsonField("degradedMode", sel.degraded)
            jsonField("fallbackReason", sel.fallbackReason ?: "")
            jsonField("port", actualPort ?: -1)
            jsonField("os", st.host.os.name)
            jsonField("cpu", st.host.cpu.name)
            jsonField("osName", st.host.osName)
            jsonField("lanIps", LanInfo.lanIPv4())
            jsonField("workDir", st.workDir.absolutePath)
            jsonField("time", System.currentTimeMillis())
            append("\"ok\":true}")
        }
        send(ex, 200, json, "application/json")
    }

    private fun handleLog(ex: HttpExchange) {
        logRequest(ex)
        if (handleCorsPreflight(ex)) return
        if (!allowGet(ex)) return
        val q = queryParams(ex)
        val lines = q["lines"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 200
        send(ex, 200, server.tailLog(lines), "text/plain; charset=utf-8")
    }

    private fun handleExec(ex: HttpExchange) {
        logRequest(ex)
        if (handleCorsPreflight(ex)) return
        val cmd: String? = when (ex.requestMethod.uppercase()) {
            "GET" -> queryParams(ex)["cmd"]
            "POST" -> {
                val body = ex.requestBody.bufferedReader(StandardCharsets.UTF_8).readText()
                    .take(MAX_CMD_BYTES).trim()
                if (body.startsWith("{")) extractJsonCmd(body) else body.ifEmpty { null }
            }
            else -> {
                send(ex, 405, "Method not allowed", "text/plain")
                return
            }
        }
        if (cmd.isNullOrBlank()) {
            send(ex, 400, "{\"ok\":false,\"error\":\"missing cmd (GET ?cmd=... or POST body)\"}", "application/json")
            return
        }
        if (cmd.length > MAX_CMD_BYTES) {
            send(ex, 413, "{\"ok\":false,\"error\":\"command too long\"}", "application/json")
            return
        }
        log("Bridge exec: $cmd")
        val logFile = File(server.workDir(), LocalProcessManager.LOG_NAME)
        val before = try {
            logFile.length()
        } catch (_: Exception) {
            0L
        }
        val code = server.runOneShot(cmd)
        val output = readAppended(logFile, before, MAX_OUTPUT_BYTES)
        val json = "{\"ok\":${code == 0},\"code\":$code," +
            "\"command\":${jsonStr(cmd.take(2000))}," +
            "\"output\":${jsonStr(output)}}"
        send(ex, 200, json, "application/json")
    }

    // ---------- plumbing ----------

    /**
     * CORS preflight for LAN browsers/WebViews: answer OPTIONS with 204 +
     * open headers so the phone console can POST /api/exec from any origin.
     * Returns true when the request was fully handled here.
     */
    private fun handleCorsPreflight(ex: HttpExchange): Boolean {
        if (!ex.requestMethod.equals("OPTIONS", ignoreCase = true)) return false
        try {
            val remote = ex.remoteAddress?.address?.hostAddress ?: "?"
            log("Bridge OPTIONS ${ex.requestURI} <- $remote (CORS preflight)")
        } catch (_: Exception) {
        }
        try {
            ex.responseHeaders.set("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            ex.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Accept, Origin")
            ex.responseHeaders.set("Access-Control-Max-Age", "86400")
            ex.sendResponseHeaders(204, -1)
        } catch (_: Exception) {
        } finally {
            try { ex.close() } catch (_: Exception) {}
        }
        return true
    }

    private fun logRequest(ex: HttpExchange) {
        try {
            val remote = ex.remoteAddress?.address?.hostAddress ?: "?"
            log("Bridge ${ex.requestMethod} ${ex.requestURI} <- $remote")
        } catch (_: Exception) {
        }
    }

    private fun allowGet(ex: HttpExchange): Boolean {
        if (ex.requestMethod.equals("GET", ignoreCase = true)) return true
        send(ex, 405, "Method not allowed", "text/plain")
        return false
    }

    private fun queryParams(ex: HttpExchange): Map<String, String> {
        val raw = ex.requestURI?.rawQuery ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val i = part.indexOf('=')
            if (i < 0) {
                out[decode(part)] = ""
            } else {
                out[decode(part.substring(0, i))] = decode(part.substring(i + 1))
            }
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        s
    }

    private fun extractJsonCmd(body: String): String? {
        // Tiny tolerant parse of {"cmd":"..."} without a JSON dependency.
        return try {
            val i = body.indexOf("\"cmd\"")
            if (i < 0) return null
            val colon = body.indexOf(':', i + 5)
            if (colon < 0) return null
            val q1 = body.indexOf('"', colon + 1)
            if (q1 < 0) return null
            val sb = StringBuilder()
            var j = q1 + 1
            while (j < body.length) {
                val c = body[j]
                if (c == '\\' && j + 1 < body.length) {
                    val n = body[j + 1]
                    when (n) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> {
                            sb.append('\\')
                            sb.append(n)
                        }
                    }
                    j += 2
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                    j++
                }
            }
            sb.toString().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun readAppended(logFile: File, before: Long, maxBytes: Int): String {
        return try {
            if (!logFile.isFile) return ""
            val len = logFile.length()
            if (len <= before) return ""
            val start = maxOf(before, len - maxBytes)
            RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(start)
                val n = (len - start).coerceAtMost(maxBytes.toLong()).toInt()
                val buf = ByteArray(n)
                var tot = 0
                while (tot < n) {
                    val r = raf.read(buf, tot, n - tot)
                    if (r <= 0) break
                    tot += r
                }
                String(buf, 0, tot, StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun StringBuilder.jsonField(name: String, value: String) {
        append("\"").append(name).append("\":")
        append(jsonStr(value)).append(",")
    }

    private fun StringBuilder.jsonField(name: String, value: Boolean) {
        append("\"").append(name).append("\":").append(value).append(",")
    }

    private fun StringBuilder.jsonField(name: String, value: Int) {
        append("\"").append(name).append("\":").append(value).append(",")
    }

    private fun StringBuilder.jsonField(name: String, value: Long) {
        append("\"").append(name).append("\":").append(value).append(",")
    }

    private fun StringBuilder.jsonField(name: String, value: List<String>) {
        append("\"").append(name).append("\":[")
        value.forEachIndexed { i, s ->
            if (i > 0) append(",")
            append(jsonStr(s))
        }
        append("],")
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun escapeHtml(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private fun send(ex: HttpExchange, code: Int, body: String, contentType: String) {
        try {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", contentType)
            // Open CORS for LAN: the phone console (WebView/fetch) must reach
            // the bridge from any origin on the same Wi-Fi.
            ex.responseHeaders.set("Access-Control-Allow-Origin", "*")
            ex.responseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            ex.responseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Accept, Origin")
            ex.responseHeaders.set("Connection", "close")
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        } catch (_: Exception) {
        } finally {
            try {
                ex.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MAX_CMD_BYTES = 8 * 1024
        private const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}
