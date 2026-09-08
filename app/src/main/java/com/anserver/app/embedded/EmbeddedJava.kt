package com.anserver.app.embedded

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Пошук Java всередині додатку. Жодних зовнішніх залежностей.
 *
 * Порядок:
 * 1. filesDir/tools/jre/bin/java (вбудований JRE, завантажується автоматично)
 * 2. filesDir/jre/bin/java (legacy)
 * 3. системний java в PATH (деякі прошивки мають)
 * 4. "java" з PATH (спроба запустити, якщо раптом є)
 */
object EmbeddedJava {

    private const val TAG = "EmbeddedJava"

    fun bundledJreDir(context: Context): File = File(EmbeddedPaths.toolsDir(context), "jre")

    /**
     * Дім JRE 21 (для Minecraft 1.20.5+). Автозавантаження стабільного JRE21
     * нема (Pojav-реліз архівовано, JRE21 лише CI-артефакти), тому сюди
     * користувач кладе JRE21 вручну через Files: tools/jre21/bin/java.
     * In-process і prepare вибирають його автоматично за версією гри.
     */
    fun bundledJre21Dir(context: Context): File = File(EmbeddedPaths.toolsDir(context), "jre21")

    fun bundledJavaBinary(context: Context): File = File(bundledJreDir(context), "bin/java")

    fun bundledJava21Binary(context: Context): File = File(bundledJre21Dir(context), "bin/java")

    /** Дім JRE за шляхом до bin/java (…/jre21/bin/java -> …/jre21). null якщо не схоже на JRE. */
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
            // Файл є але exec заборонено W^X: для in-process все одно придатний
            // (libjvm читається через dlopen, exec не потрібен).
            if (f.isFile) return f.absolutePath
            null
        } catch (_: Exception) { null }
    }

    fun findJava(context: Context): String? {
        // 1. вбудований JRE (спочатку 21 щоб новий рантайм мав пріоритет,
        // якщо користувач поклав обидва вручну)
        try {
            bundledCandidate(bundledJava21Binary(context))?.let { return it }
            val bundled = bundledJavaBinary(context)
            if (bundled.exists() && bundled.canExecute()) return bundled.absolutePath
            // може бути не виставлений +x після розпакування
            if (bundled.exists()) {
                try { bundled.setExecutable(true) } catch (_: Exception) {}
                if (bundled.canExecute()) return bundled.absolutePath
                // W^X: exec заборонено, але in-process (dlopen) все одно спрацює
                return bundled.absolutePath
            }
            // legacy місце
            val legacy = File(context.filesDir, "jre/bin/java")
            if (legacy.exists()) {
                try { legacy.setExecutable(true) } catch (_: Exception) {}
                if (legacy.canExecute()) return legacy.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "bundled check failed", e)
        }

        // 2. системний java через which
        try {
            val proc = ProcessBuilder("sh", "-c", "command -v java").start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isNotEmpty() && out.contains("java")) {
                // перевіряємо що запускається
                return out.lineSequence().first().trim()
            }
        } catch (_: Exception) {}

        // 3. відомі системні шляхи (рідко, але безкоштовно перевірити)
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
     * Java під конкретну версію Minecraft.
     * 1.20.5+ (need 21): спочатку tools/jre21, потім звичайний пошук
     * (JRE17 повернеться як fallback — prepare чесно відмовить з Definitive
     * і підказкою про ручний імпорт JRE21, замість мовчазного -8).
     * <=1.20.4: tools/jre21 теж підійде (Java 21 запускає старі версії),
     * але пріоритет у JRE17 щоб не ламати перевірене.
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
     * Спосіб запуску java.
     *
     * Передісторія: на Android 10+ з targetSdk 29+ ядро забороняє execve()
     * файлів з папки додатку (W^X, b/112357170) — прямий запуск дає
     * "error=13, Permission denied", і жоден chmod цього не лікує.
     * Обхід (патерн termux-exec): запускати через системний лінкер —
     * ядро бачить exec системного файла (дозволено), а mmap бінарника
     * дозволений так само як dlopen().
     */
    data class JavaLaunch(
        val javaPath: String,
        /** Префікс команди, наприклад ["/system/bin/linker64"]. Порожній = прямий запуск. */
        val prefix: List<String> = emptyList(),
        val version: String? = null,
        /** Додаткове оточення (LD_LIBRARY_PATH/JAVA_HOME для режиму лінкера). */
        val env: Map<String, String> = emptyMap()
    ) {
        val useLinker: Boolean get() = prefix.isNotEmpty()
        val works: Boolean get() = version != null
    }

    /** Схоже на справжній вивід `java -version`, а не на помилку лінкера. */
    private val VERSION_RE = Regex("(?i)\\b(openjdk|java)(\\s+version|\\s+runtime)?\\b[^\\n]*\"?\\d")

    private fun looksLikeJavaVersion(line: String?): Boolean =
        line != null && VERSION_RE.containsMatchIn(line)

    /** Системний лінкер під ABI пристрою (для обходу exec-заборони). */
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

    /** Перший непорожній рядок виводу команди або null (не стартувала / timeout / порожньо). */
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
     * Перевіряє як реально запускається java і підбирає робочу форму команди.
     * P1: напряму. P2: лінкер + env + прапорець купи (форма як у сервера).
     * P3: те саме без env (якщо env ламає лінкер — побачимо в лозі).
     * У лог пише кожен крок: пристрій сам покаже що йому підходить.
     */
    fun probeJava(javaPath: String, log: (String) -> Unit = {}): JavaLaunch {
        val directOut = runAndFirstLine(listOf(javaPath, "-version"))
        val directVer = directOut?.takeIf { looksLikeJavaVersion(it) }
        if (directVer != null) {
            log("Java exec: прямий запуск ОК")
            return JavaLaunch(javaPath, emptyList(), directVer)
        }
        if (directOut != null) log("Java exec: пряма спроба: $directOut")
        else log("Java exec: прямий запуск неможливий (error=13 — W^X exec-заборона)")
        val linker = systemLinker()
        if (linker == null) {
            log("Java exec: системного лінкера не знайдено")
            return JavaLaunch(javaPath)
        }
        val env = linkerEnv(javaPath)
        // P2: повна форма серверної команди (з прапорцем купи) + env.
        val shape = listOf(linker, javaPath, "-Xmx64M", "-version")
        val vEnvRaw = runAndFirstLine(shape, env)
        val vEnv = vEnvRaw?.takeIf { looksLikeJavaVersion(it) }
        if (vEnv != null) {
            log("Java exec: лінкер + env ОК")
            return JavaLaunch(javaPath, listOf(linker), vEnv, env)
        }
        log("Java exec: лінкер + env: ${vEnvRaw ?: "нема виводу"}")
        // P3: та сама форма без нашого env.
        val vPlainRaw = runAndFirstLine(shape)
        val vPlain = vPlainRaw?.takeIf { looksLikeJavaVersion(it) }
        if (vPlain != null) {
            log("Java exec: лінкер ОК, але лише БЕЗ нашого env — запускаю без нього")
            return JavaLaunch(javaPath, listOf(linker), vPlain)
        }
        log("Java exec: лінкер без env: ${vPlainRaw ?: "нема виводу"}")
        return JavaLaunch(javaPath)
    }

    /**
     * Оточення для запуску через лінкер: щоб libjli/libjvm знаходились
     * за LD_LIBRARY_PATH незалежно від того, що бачить лаунчер в argv[0].
     */
    fun linkerEnv(javaPath: String): Map<String, String> {
        return try {
            val bin = File(javaPath).parentFile ?: return emptyMap()
            if (bin.name != "bin") return emptyMap()
            val home = bin.parentFile ?: return emptyMap()
            val dirs = listOf(File(home, "lib"), File(home, "lib/server"))
                .filter { it.isDirectory }
                .joinToString(":") { it.absolutePath }
            val env = mutableMapOf("JAVA_HOME" to home.absolutePath)
            if (dirs.isNotEmpty()) env["LD_LIBRARY_PATH"] = dirs
            env
        } catch (_: Exception) { emptyMap() }
    }

    fun javaVersion(javaPath: String): String? {
        // Прямий запуск; якщо впираємось у W^X exec-заборону — через лінкер.
        return probeJava(javaPath).version
    }

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /**
     * Робочий Bionic-JRE під Android (прямий запуск без proot).
     * Джерело: PojavLauncherTeam/android-openjdk-build-multiarch, реліз jre17-ec28559
     * (стабільний, ~37MB, сотні тисяч завантажень).
     * Старий URL PocketHost-JRE/jre-android не існує (404) — тому JRE ніколи не качався.
     *
     * Повертає ТІЛЬКИ під своє ABI: чужий архітектурний JRE все одно не запуститься,
     * а на повільній мережі кожна зайва спроба це ~37MB і хвилини очікування.
     */
    fun jreDownloadUrls(): List<String> = listOf(jreDownloadUrl())

    /**
     * Єдине джерело URL для JRE. Glibc-збірки працюють лише всередині
     * proot-ubuntu, тому для прямого запуску потрібен Bionic-JRE (Pojav).
     */
    fun jreDownloadUrl(): String = when (abi()) {
        "arm64-v8a" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
        "armeabi-v7a", "armeabi" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm-20210914-release.tar.xz"
        "x86_64" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-x86_64-20210825-release.tar.xz"
        "x86", "i686" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-x86-20220225-release.tar.xz"
        else -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
    }

    /**
     * Minecraft 1.20.5+ (включно 1.21.x) вимагає Java 21.
     * Вбудований JRE17 запустить лише <=1.20.4.
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

    /** Парсить major-версію з `java -version` ("openjdk version \"17.0.10\""). Через лінкер за потреби. */
    fun javaMajorVersion(javaPath: String): Int? {
        val out = runAndFirstLine(listOf(javaPath, "-version"))
            ?: systemLinker()?.let { runAndFirstLine(listOf(it, javaPath, "-version")) }
            ?: return null
        return Regex("\"(\\d+)").find(out)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** Чи підтримується автозавантаження JRE на цьому ABI. */
    fun isJreAutoInstallSupported(): Boolean = abi() in setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "i686")

    fun installHint(): String = buildString {
        appendLine("Java не знайдена у вбудованому середовищі.")
        appendLine("PocketHost зараз завантажить вбудований JRE автоматично (~40 MB, Pojav Bionic).")
        appendLine("Нічого встановлювати окремо (Termux) не потрібно.")
    }
}
