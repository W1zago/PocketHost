package com.pockethost.jvmhost

import java.io.File
import java.util.Locale

/** Host operating system. */
enum class OsKind {
    LINUX,
    WINDOWS,
    MACOS,
    UNKNOWN
}

/** Host CPU architecture relevant for picking an Ubuntu rootfs. */
enum class CpuKind {
    AMD64,
    ARM64,
    UNSUPPORTED
}

/** Detected host description. */
data class Host(
    val os: OsKind,
    val cpu: CpuKind,
    val osName: String,
    val osArch: String
)

/**
 * Pure-JDK host detection and small process helpers for the PC port.
 *
 * No Android APIs, no third-party dependencies.
 */
object HostInfo {

    fun current(): Host {
        val osName = System.getProperty("os.name", "").lowercase(Locale.US)
        val osArch = System.getProperty("os.arch", "").lowercase(Locale.US)
        val os = when {
            osName.contains("linux") -> OsKind.LINUX
            osName.contains("windows") -> OsKind.WINDOWS
            osName.contains("mac") || osName.contains("darwin") -> OsKind.MACOS
            else -> OsKind.UNKNOWN
        }
        val cpu = when (osArch) {
            "amd64", "x86_64", "x64" -> CpuKind.AMD64
            "aarch64", "arm64" -> CpuKind.ARM64
            else -> CpuKind.UNSUPPORTED
        }
        return Host(os, cpu, System.getProperty("os.name", "?"), System.getProperty("os.arch", "?"))
    }

    /**
     * Base directory for PocketHost PC data:
     * `%LOCALAPPDATA%/PocketHost` on Windows, `~/.pockethost` elsewhere.
     */
    fun defaultBaseDir(): File {
        val host = current()
        if (host.os == OsKind.WINDOWS) {
            val localAppData = System.getenv("LOCALAPPDATA")
            if (!localAppData.isNullOrBlank()) {
                return File(localAppData, "PocketHost")
            }
        }
        return File(System.getProperty("user.home", "."), ".pockethost")
    }

    /** Free bytes usable under [dir], or -1 when unknown. */
    fun freeBytes(dir: File): Long {
        return try {
            dir.mkdirs()
            dir.usableSpace
        } catch (_: Exception) {
            -1L
        }
    }

    /** Finds an executable on PATH (honours PATHEXT on Windows). Returns null when missing. */
    fun findOnPath(name: String): File? {
        // Absolute path fast-path.
        try {
            val direct = File(name)
            if (direct.isAbsolute && direct.isFile && direct.canExecute()) return direct
        } catch (_: Exception) {
        }
        val pathEnv = System.getenv("PATH") ?: return null
        val isWindows = current().os == OsKind.WINDOWS
        val extensions = if (isWindows) {
            val pathext = System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD"
            pathext.split(';').map { it.lowercase(Locale.US) }.filter { it.isNotBlank() }
        } else {
            listOf("")
        }
        for (dirEntry in pathEnv.split(File.pathSeparator)) {
            if (dirEntry.isBlank()) continue
            val dir = try {
                File(dirEntry)
            } catch (_: Exception) {
                continue
            }
            for (ext in extensions) {
                val candidate = if (ext.isEmpty()) File(dir, name) else File(dir, name + ext)
                try {
                    if (candidate.isFile && candidate.canExecute()) return candidate
                } catch (_: Exception) {
                }
            }
            // On Windows the file may already carry its extension.
            if (isWindows) {
                try {
                    val exact = File(dir, name)
                    if (exact.isFile && exact.canExecute()) return exact
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    /** Captured result of a short-lived helper process. */
    data class ExecResult(val code: Int, val output: String)

    /**
     * Runs [argv] and captures merged stdout+stderr. Returns null when the
     * binary cannot be launched at all. Never throws.
     */
    fun runCapture(
        argv: List<String>,
        workDir: File? = null,
        timeoutMs: Long = 15_000L
    ): ExecResult? {
        if (argv.isEmpty()) return null
        return try {
            val pb = ProcessBuilder(argv).redirectErrorStream(true)
            if (workDir != null) pb.directory(workDir)
            val process = pb.start()
            // Drain output on a daemon thread so a chatty child cannot block on a full pipe.
            val buffer = StringBuilder()
            val drain = Thread({
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        val chunk = CharArray(8192)
                        while (true) {
                            val n = reader.read(chunk)
                            if (n <= 0) break
                            synchronized(buffer) { buffer.append(chunk, 0, n) }
                        }
                    }
                } catch (_: Exception) {
                }
            }, "hostinfo-drain").apply { isDaemon = true; start() }
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                try {
                    process.destroyForcibly()
                } catch (_: Exception) {
                }
                return ExecResult(-1, synchronized(buffer) { buffer.toString() }.take(2000))
            }
            try {
                drain.join(2000)
            } catch (_: Exception) {
            }
            try {
                process.outputStream.close()
            } catch (_: Exception) {
            }
            ExecResult(process.exitValue(), synchronized(buffer) { buffer.toString() }.take(8000))
        } catch (_: Exception) {
            null
        }
    }

    /** System proot binary (Linux only), or null when not installed. */
    fun systemProot(): File? {
        if (current().os != OsKind.LINUX) return null
        val bin = findOnPath("proot") ?: return null
        // Sanity: it must actually execute.
        val probe = runCapture(listOf(bin.absolutePath, "--version"))
        if (probe == null || probe.code != 0) return null
        return bin
    }

    fun prootHint(): String =
        "proot is not installed. Install it with your package manager " +
            "(Debian/Ubuntu: sudo apt install proot) and press Start again. " +
            "Without proot the server still runs as a host shell."

    /** True when `wsl` launcher exists (Windows only). */
    fun wslAvailable(): Boolean {
        if (current().os != OsKind.WINDOWS) return false
        return findOnPath("wsl") != null
    }

    /**
     * Installed WSL distributions via `wsl -l -q`.
     * That output is UTF-16LE; decode accordingly (falls back to UTF-8).
     * Empty list when WSL is missing or the query fails. Never throws.
     */
    fun wslDistros(): List<String> {
        return try {
            val wsl = findOnPath("wsl") ?: return emptyList()
            val process = ProcessBuilder(listOf(wsl.absolutePath, "-l", "-q"))
                .redirectErrorStream(true)
                .start()
            val raw = process.inputStream.readBytes()
            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                try {
                    process.destroyForcibly()
                } catch (_: Exception) {
                }
                return emptyList()
            }
            val text = decodeWslOutput(raw)
            if (text.contains("is not installed", ignoreCase = true)) return emptyList()
            return text.lines()
                .map { line -> line.trim().trim { c -> c.code == 0xFEFF || c.code == 0 } }
                .filter { it.isNotEmpty() }
                // Distro names are single tokens; help/error prose contains spaces.
                .filter { line -> !line.any { c -> c == ' ' || c == '\t' } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeWslOutput(raw: ByteArray): String {
        if (raw.isEmpty()) return ""
        // UTF-16LE heuristic: plenty of NUL bytes.
        val nulCount = raw.count { it == 0.toByte() }
        return try {
            if (nulCount > raw.size / 4) {
                String(raw, Charsets.UTF_16LE)
            } else {
                String(raw, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            try {
                String(raw, Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }
        }
    }
}
