package com.pockethost.app.remote

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Minimal HTTP client for the jvm-host BridgeServer (PC Ubuntu Server).
 *
 *   GET  /api/status  - JSON status (backend, shellRunning, lanIps...)
 *   GET  /api/ping    - "pong" connectivity check
 *   GET  /api/exec?cmd=...  - one command (short commands)
 *   POST /api/exec {"cmd":"..."} - console input path (any length)
 *
 * Pure HttpURLConnection, no new dependencies. All blocking - call from IO.
 */
object RemoteBridgeClient {

    private const val TAG = "RemoteBridgeClient"

    const val DEFAULT_HOST = "192.168.0.200:8765"

    /**
     * Short human-readable cause of the last failed call
     * (exception class + message + URL). Null when the last call
     * succeeded or none was made yet. Shown in RemoteConsoleActivity
     * so "FAILED: no pong" is never bare again - cleartext blocks,
     * wrong IP and dead bridge look different here.
     */
    @Volatile
    var lastError: String? = null
        private set

    fun clearLastError() {
        lastError = null
    }

    private fun recordError(where: String, url: String, e: Throwable) {
        val msg = "$where $url failed: ${e.javaClass.simpleName}: ${e.message}"
        lastError = msg
        Log.e(TAG, msg, e)
    }

    data class ExecResult(
        val ok: Boolean,
        val code: Int,
        val output: String,
        val raw: String
    )

    /** "192.168.0.200:8765" -> "http://192.168.0.200:8765", trims spaces/slashes. */
    fun normalizeBase(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) s = DEFAULT_HOST
        s = s.removeSuffix("/")
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        return s
    }

    fun ping(baseUrl: String, timeoutMs: Int = 5000): Boolean {
        lastError = null
        return try {
            val body = get(normalizeBase(baseUrl) + "/api/ping", timeoutMs)
            body?.trim() == "pong"
        } catch (e: Exception) {
            recordError("ping", baseUrl, e)
            false
        }
    }

    /** Raw JSON from /api/status, or null on failure. */
    fun status(baseUrl: String, timeoutMs: Int = 8000): String? {
        lastError = null
        return try {
            get(normalizeBase(baseUrl) + "/api/status", timeoutMs)
        } catch (e: Exception) {
            recordError("status", baseUrl, e)
            null
        }
    }

    /** Raw tail of server.log via /api/log, or null on failure. */
    fun log(baseUrl: String, lines: Int = 100, timeoutMs: Int = 8000): String? {
        lastError = null
        return try {
            get(normalizeBase(baseUrl) + "/api/log?lines=$lines", timeoutMs)
        } catch (e: Exception) {
            recordError("log", baseUrl, e)
            null
        }
    }

    /**
     * Console path: POST {"cmd":"..."} to /api/exec.
     * Falls back to GET ?cmd=... when POST fails (tiny commands).
     */
    fun exec(baseUrl: String, cmd: String, timeoutMs: Int = 120_000): ExecResult {
        lastError = null
        val base = normalizeBase(baseUrl)
        val post = try {
            postJson(base + "/api/exec", "{\"cmd\":${jsonStr(cmd)}}", timeoutMs)
        } catch (e: Exception) {
            recordError("exec POST", baseUrl, e)
            null
        }
        if (post != null) return parseExec(post)
        // Fallback for very small commands when POST is blocked.
        return try {
            val enc = URLEncoder.encode(cmd, StandardCharsets.UTF_8.name())
            val body = get("$base/api/exec?cmd=$enc", timeoutMs) ?: ""
            parseExec(body)
        } catch (e: Exception) {
            recordError("exec GET", baseUrl, e)
            ExecResult(false, -1, "", "exec failed: ${e.message} (lastError=$lastError)")
        }
    }

    // ---------- plumbing ----------

    private fun get(url: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: return null
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            // Was swallowed by callers (bare "no pong"); log with the URL so
            // cleartext blocks vs wrong-IP vs dead bridge are distinguishable.
            recordError("GET", url, e)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun postJson(url: String, json: String, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                instanceFollowRedirects = false
            }
            conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: return null
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (e: Exception) {
            recordError("POST", url, e)
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parseExec(raw: String): ExecResult {
        if (raw.isEmpty()) return ExecResult(false, -1, "", raw)
        return try {
            val ok = raw.contains("\"ok\":true")
            val code = Regex("\"code\":(-?\\d+)").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: if (ok) 0 else -1
            val output = extractJsonString(raw, "output") ?: ""
            ExecResult(ok, code, output, raw)
        } catch (_: Exception) {
            ExecResult(false, -1, raw, raw)
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        return try {
            val i = json.indexOf("\"$key\"")
            if (i < 0) return null
            val colon = json.indexOf(':', i + key.length + 2)
            if (colon < 0) return null
            val q1 = json.indexOf('"', colon + 1)
            if (q1 < 0) return null
            val sb = StringBuilder()
            var j = q1 + 1
            while (j < json.length) {
                val c = json[j]
                if (c == '\\' && j + 1 < json.length) {
                    when (json[j + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'u' -> {
                            if (j + 5 < json.length) {
                                val hex = json.substring(j + 2, j + 6)
                                val cp = hex.toIntOrNull(16)
                                if (cp != null) sb.append(cp.toChar()) else sb.append("\\u$hex")
                                j += 6
                                continue
                            } else sb.append(c)
                        }
                        else -> { sb.append('\\'); sb.append(json[j + 1]) }
                    }
                    j += 2
                } else if (c == '"') {
                    break
                } else {
                    sb.append(c)
                    j++
                }
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
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
}
