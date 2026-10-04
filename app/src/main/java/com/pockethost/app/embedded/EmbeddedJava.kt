package com.pockethost.app.embedded

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Finds Java inside the app. No external dependencies.
 *
 * Order:
 * 1. filesDir/tools/jre/bin/java (bundled JRE, auto-downloaded)
 * 2. filesDir/jre/bin/java (legacy)
 * 3. system java in PATH (some firmwares ship one)
 * 4. "java" from PATH (try to launch if present at all)
 */
object EmbeddedJava {

    private const val TAG = "EmbeddedJava"

    fun bundledJreDir(context: Context): File = File(EmbeddedPaths.toolsDir(context), "jre")

    /**
     * JRE 21 home (for Minecraft 1.20.5+). No stable JRE21 auto-install
     * (Pojav release archived, JRE21 only as CI artifacts), so the user
     * drops JRE21 manually via Files into tools/jre21/bin/java.
     * In-process and prepare pick it automatically by game version.
     */
    fun bundledJre21Dir(context: Context): File = File(EmbeddedPaths.toolsDir(context), "jre21")

    fun bundledJavaBinary(context: Context): File = File(bundledJreDir(context), "bin/java")

    fun bundledJava21Binary(context: Context): File = File(bundledJre21Dir(context), "bin/java")

    /** JRE home from a bin/java path (.../jre21/bin/java -> .../jre21). Null when not JRE-like. */
    fun runtimeHomeFor(javaPath: String?): File? {
        return try {
            if (javaPath.isNullOrEmpty()) return null
            val bin = File(javaPath).parentFile ?: return null
            if (bin.name != "bin") return null
            val home = bin.parentFile ?: return null
            if (!home.isDirectory) return null
            home
        } catch (_: Exception) { null }
    }

    private fun bundledCandidate(f: File): String? {
        return try {
            if (!f.exists()) return null
            try { f.setExecutable(true) } catch (_: Exception) {}
            if (f.canExecute()) return f.absolutePath
            // File exists but exec is W^X-denied: still fine for in-process
            // (libjvm is read via dlopen, exec is not needed).
            if (f.isFile) return f.absolutePath
            null
        } catch (_: Exception) { null }
    }

    fun findJava(context: Context): String? {
        // 1. bundled JRE (jre21 first so the new runtime has priority,
        // if the user dropped both manually)
        try {
            bundledCandidate(bundledJava21Binary(context))?.let { return it }
            val bundled = bundledJavaBinary(context)
            if (bundled.exists() && bundled.canExecute()) return bundled.absolutePath
            // may lack +x after extraction
            if (bundled.exists()) {
                try { bundled.setExecutable(true) } catch (_: Exception) {}
                if (bundled.canExecute()) return bundled.absolutePath
                // W^X: exec denied, but in-process (dlopen) still works
                return bundled.absolutePath
            }
            // legacy location
            val legacy = File(context.filesDir, "jre/bin/java")
            if (legacy.exists()) {
                try { legacy.setExecutable(true) } catch (_: Exception) {}
                if (legacy.canExecute()) return legacy.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "bundled check failed", e)
        }

        // 2. system java via which
        try {
            val proc = ProcessBuilder("sh", "-c", "command -v java").start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isNotEmpty() && out.contains("java")) {
                // verify it actually launches
                return out.lineSequence().first().trim()
            }
        } catch (_: Exception) {}

        // 3. known system paths (rare, but free to check)
        val systemCandidates = listOf(
            "/system/bin/java",
            "/system/xbin/java",
            "/apex/com.android.runtime/bin/java"
        )
        for (c in systemCandidates) {
            try {
                if (File(c).canExecute()) return c
            } catch (_: Exception) {}
        }
        return null
    }

    fun isJavaAvailable(context: Context): Boolean = findJava(context) != null

    /**
     * Java for a specific Minecraft version.
     * 1.20.5+ (need 21): tools/jre21 first, then the normal lookup
     * (JRE17 is returned as fallback - prepare honestly refuses with Definitive
     * and hints at manual JRE21 import instead of a silent -8).
     * <=1.20.4: tools/jre21 also fits (Java 21 runs older versions),
     * but JRE17 keeps priority to avoid breaking verified setups.
     */
    fun findJavaForVersion(context: Context, minecraftVersion: String?): String? {
        val need = try { minJavaForMinecraft(minecraftVersion) } catch (_: Exception) { 17 }
        if (need >= 21) {
            try {
                bundledCandidate(bundledJava21Binary(context))?.let { return it }
            } catch (_: Exception) {}
        }
        return findJava(context)
    }

    /**
     * How java is launched.
     *
     * Background: on Android 10+ with targetSdk 29+ the kernel denies execve()
     * of files from the app dir (W^X, b/112357170) - direct launch fails with
     * "error=13, Permission denied", and no chmod fixes it.
     * Workaround (termux-exec pattern): launch via the system linker -
     * the kernel sees an exec of a system file (allowed), and mmap of the binary
     * is allowed just like dlopen().
     */
    data class JavaLaunch(
        val javaPath: String,
        /** Command prefix, e.g. ["/system/bin/linker64"]. Empty = direct launch. */
        val prefix: List<String> = emptyList(),
        val version: String? = null,
        /** Extra environment (LD_LIBRARY_PATH/JAVA_HOME for linker mode). */
        val env: Map<String, String> = emptyMap()
    ) {
        val useLinker: Boolean get() = prefix.isNotEmpty()
        val works: Boolean get() = version != null
    }

    /** Looks like a real `java -version` output, not a linker error. */
    private val VERSION_RE = Regex("(?i)\\b(openjdk|java)(\\s+version|\\s+runtime)?\\b[^\\n]*\"?\\d")

    private fun looksLikeJavaVersion(line: String?): Boolean =
        line != null && VERSION_RE.containsMatchIn(line)

    /** System linker for this ABI (for the exec-ban workaround). */
    fun systemLinker(): String? {
        val abi64 = abi() == "arm64-v8a" || abi() == "x86_64"
        val ordered = if (abi64) {
            listOf("/system/bin/linker64", "/system/bin/linker")
        } else {
            listOf("/system/bin/linker", "/system/bin/linker64")
        }
        return ordered.firstOrNull {
            try {
                val f = File(it)
                f.exists() && f.canExecute()
            } catch (_: Exception) { false }
        }
    }

    /** First non-empty output line, or null (never started / timeout / empty). */
    private fun runAndFirstLine(
        cmd: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20
    ): String? {
        var proc: Process? = null
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            if (env.isNotEmpty()) {
                try { pb.environment().putAll(env) } catch (_: Exception) {}
            }
            proc = pb.start()
            val finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) return null
            proc.inputStream.bufferedReader().readText()
                .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        } catch (_: Exception) {
            null
        } finally {
            try { proc?.destroyForcibly() } catch (_: Exception) {}
        }
    }

    /**
     * Probes how java actually launches and picks the working command shape.
     * P1: direct. P2: linker + env (server shape but WITHOUT -Xmx: the heap flag must
     * never sit where the executable goes - otherwise the linker fails with
     * `expected absolute path: "-Xmx64M"`). P3: same without env.
     * Every step is logged: the device itself shows what suits it.
     *
     * P0 FORK-fix: argv[0] must be the absolute java path (relative "java"
     * from PATH yields `expected absolute path: "-Xmx64M"` - the linker skips a non-absolute
     * argv[1] and grabs the next one). Also checks libjli.so before exec (otherwise
     * `CANNOT LINK EXECUTABLE ... library "libjli.so" not found`).
     * library "libjli.so" not found`).
     */
    fun probeJava(javaPath: String, log: (String) -> Unit = {}): JavaLaunch {
        // argv[0] must be absolute - otherwise the linker takes -Xmx for the executable.
        if (!File(javaPath).isAbsolute) {
            log("Java exec: non-absolute path ($javaPath) - exec via linker impossible, absolute path required")
            return JavaLaunch(javaPath)
        }
        if (!hasLibJli(javaPath)) {
            log("Java exec: WARN libjli.so not found near $javaPath - linker will fail CANNOT LINK EXECUTABLE")
        // Do not refuse yet: in-process (dlopen) may still work, probe below will show.
        }
        val directOut = runAndFirstLine(listOf(javaPath, "-version"))
        val directVer = directOut?.takeIf { looksLikeJavaVersion(it) }
        if (directVer != null) {
            log("Java exec: direct launch OK")
            return JavaLaunch(javaPath, emptyList(), directVer)
        }
        if (directOut != null) log("Java exec: direct attempt: $directOut")
        else log("Java exec: direct launch impossible (error=13 - W^X exec ban)")
        val linker = systemLinker()
        if (linker == null) {
            log("Java exec: system linker not found")
            return JavaLaunch(javaPath)
        }
        val env = linkerEnv(javaPath)
        // P2: shape `linker <abs java> -version` + env. WITHOUT -Xmx: it must never
        // stand where the linker expects the executable (otherwise `expected absolute path: "-Xmx64M"`).
        // ProcessBuilder cannot substitute argv[0] separately from the executable (like execve),
        // so the only correct call is linker first, absolute java second.
        val shape = listOf(linker, javaPath, "-version")
        val vEnvRaw = runAndFirstLine(shape, env)
        val vEnv = vEnvRaw?.takeIf { looksLikeJavaVersion(it) }
        if (vEnv != null) {
            log("Java exec: linker + env OK")
            return JavaLaunch(javaPath, listOf(linker), vEnv, env)
        }
        log("Java exec: linker + env: ${vEnvRaw ?: "no output"}")
        // P3: same shape without our env.
        val vPlainRaw = runAndFirstLine(shape)
        val vPlain = vPlainRaw?.takeIf { looksLikeJavaVersion(it) }
        if (vPlain != null) {
            log("Java exec: linker OK but only WITHOUT our env - launching without it")
            return JavaLaunch(javaPath, listOf(linker), vPlain)
        }
        log("Java exec: linker without env: ${vPlainRaw ?: "no output"}")
        return JavaLaunch(javaPath)
    }

    /**
     * Environment for linker launch: so libjli/libjvm resolve via LD_LIBRARY_PATH
     * regardless of what the launcher sees in argv[0].
     * P0: must include jre/lib/server + jre/lib (+ jre/lib/jli where libjli.so lives),
     * otherwise the linker fails `library "libjli.so" not found`. JAVA_HOME is mandatory.
     */
    fun linkerEnv(javaPath: String): Map<String, String> {
        return try {
            val bin = File(javaPath).parentFile ?: return emptyMap()
            if (bin.name != "bin") return emptyMap()
            val home = bin.parentFile ?: return emptyMap()
            // Order as in PocketJvm.ldLibraryPathFor, but lib/server first
            // (where libjvm.so lives) + lib + lib/jli (where libjli.so lives on Pojav-JRE).
            val dirs = listOf(
                File(home, "lib/server"),
                File(home, "lib"),
                File(home, "lib/jli")
            )
                .filter { it.isDirectory }
                .joinToString(":") { it.absolutePath }
            val env = mutableMapOf("JAVA_HOME" to home.absolutePath)
            if (dirs.isNotEmpty()) env["LD_LIBRARY_PATH"] = dirs
            env
        } catch (_: Exception) { emptyMap() }
    }

    /**
     * P0: is libjli.so next to java (check before exec via linker).
     * Looks in lib/jli/libjli.so, lib/libjli.so, lib/server/libjli.so.
     */
    fun hasLibJli(javaPath: String): Boolean {
        return try {
            val bin = File(javaPath).parentFile ?: return false
            if (bin.name != "bin") return false
            val home = bin.parentFile ?: return false
            listOf(
                File(home, "lib/jli/libjli.so"),
                File(home, "lib/libjli.so"),
                File(home, "lib/server/libjli.so")
            ).any { it.isFile && it.length() > 0 }
        } catch (_: Exception) { false }
    }

    /** P0: absolute path to libjli.so or null (for log diagnostics). */
    fun findLibJli(javaPath: String): File? {
        return try {
            val bin = File(javaPath).parentFile ?: return null
            if (bin.name != "bin") return null
            val home = bin.parentFile ?: return null
            listOf(
                File(home, "lib/jli/libjli.so"),
                File(home, "lib/libjli.so"),
                File(home, "lib/server/libjli.so")
            ).firstOrNull { it.isFile }
        } catch (_: Exception) { null }
    }

    fun javaVersion(javaPath: String): String? {
        // Direct launch; on W^X exec ban - via linker.
        return probeJava(javaPath).version
    }

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /**
     * Working Bionic-JRE for Android (direct launch without proot).
     * Source: PojavLauncherTeam/android-openjdk-build-multiarch, release jre17-ec28559
     * (stable, ~37MB, hundreds of thousands of downloads).
     * Old PocketHost-JRE/jre-android URL is gone (404) - so the JRE never downloaded.
     *
     * Returns ONLY for its own ABI: a foreign-arch JRE will not launch anyway,
     * and on a slow network every extra attempt costs ~37MB and minutes of waiting.
     */
    fun jreDownloadUrls(): List<String> = listOf(jreDownloadUrl())

    /**
     * Single URL source for the JRE. Glibc builds work only inside
     * proot-ubuntu, so direct launch needs Bionic-JRE (Pojav).
     */
    fun jreDownloadUrl(): String = when (abi()) {
        "arm64-v8a" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
        "armeabi-v7a", "armeabi" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm-20210914-release.tar.xz"
        "x86_64" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-x86_64-20210825-release.tar.xz"
        "x86", "i686" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-x86-20220225-release.tar.xz"
        else -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
    }

    /**
     * Minecraft 1.20.5+ (including 1.21.x) requires Java 21.
     * Bundled JRE17 runs only <=1.20.4.
     */
    fun minJavaForMinecraft(version: String?): Int {
        if (version == null) return 17
        val v = version.trim().removePrefix("v")
        val parts = v.split(".", "-", "_")
        return try {
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return 17
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            if (major > 1) 21
            else if (minor > 20) 21
            else if (minor == 20 && patch >= 5) 21
            else 17
        } catch (_: Exception) { 17 }
    }

    /** Parses the major version from `java -version` ("openjdk version "17.0.10""). Via linker when needed. */
    fun javaMajorVersion(javaPath: String): Int? {
        val out = runAndFirstLine(listOf(javaPath, "-version"))
            ?: systemLinker()?.let { runAndFirstLine(listOf(it, javaPath, "-version")) }
            ?: return null
        return Regex("\"(\\d+)").find(out)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** Whether JRE auto-install is supported on this ABI. */
    fun isJreAutoInstallSupported(): Boolean = abi() in setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "i686")

    fun installHint(): String = buildString {
        appendLine("Java not found in the bundled runtime.")
        appendLine("PocketHost will now download the bundled JRE automatically (~40 MB, Pojav Bionic).")
        appendLine("Nothing to install separately (Termux) is needed.")
    }
}
