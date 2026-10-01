package com.pockethost.app.embedded

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * РџРѕС€СѓРє Java РІСЃРµСЂРµРґРёРЅС– РґРѕРґР°С‚РєСѓ. Р–РѕРґРЅРёС… Р·РѕРІРЅС–С€РЅС–С… Р·Р°Р»РµР¶РЅРѕСЃС‚РµР№.
 *
 * РџРѕСЂСЏРґРѕРє:
 * 1. filesDir/tools/jre/bin/java (РІР±СѓРґРѕРІР°РЅРёР№ JRE, Р·Р°РІР°РЅС‚Р°Р¶СѓС”С‚СЊСЃСЏ Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ)
 * 2. filesDir/jre/bin/java (legacy)
 * 3. СЃРёСЃС‚РµРјРЅРёР№ java РІ PATH (РґРµСЏРєС– РїСЂРѕС€РёРІРєРё РјР°СЋС‚СЊ)
 * 4. "java" Р· PATH (СЃРїСЂРѕР±Р° Р·Р°РїСѓСЃС‚РёС‚Рё, СЏРєС‰Рѕ СЂР°РїС‚РѕРј С”)
 */
object EmbeddedJava {

    private const val TAG = "EmbeddedJava"

    fun bundledJreDir(context: Context): File = File(EmbeddedPaths.toolsDir(context), "jre")

    /**
     * Р”С–Рј JRE 21 (РґР»СЏ Minecraft 1.20.5+). РђРІС‚РѕР·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ СЃС‚Р°Р±С–Р»СЊРЅРѕРіРѕ JRE21
     * РЅРµРјР° (Pojav-СЂРµР»С–Р· Р°СЂС…С–РІРѕРІР°РЅРѕ, JRE21 Р»РёС€Рµ CI-Р°СЂС‚РµС„Р°РєС‚Рё), С‚РѕРјСѓ СЃСЋРґРё
     * РєРѕСЂРёСЃС‚СѓРІР°С‡ РєР»Р°РґРµ JRE21 РІСЂСѓС‡РЅСѓ С‡РµСЂРµР· Files: tools/jre21/bin/java.
     * In-process С– prepare РІРёР±РёСЂР°СЋС‚СЊ Р№РѕРіРѕ Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ Р·Р° РІРµСЂСЃС–С”СЋ РіСЂРё.
     */
    fun bundledJre21Dir(context: Context): File = File(EmbeddedPaths.toolsDir(context), "jre21")

    fun bundledJavaBinary(context: Context): File = File(bundledJreDir(context), "bin/java")

    fun bundledJava21Binary(context: Context): File = File(bundledJre21Dir(context), "bin/java")

    /** Р”С–Рј JRE Р·Р° С€Р»СЏС…РѕРј РґРѕ bin/java (вЂ¦/jre21/bin/java -> вЂ¦/jre21). null СЏРєС‰Рѕ РЅРµ СЃС…РѕР¶Рµ РЅР° JRE. */
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
            // Р¤Р°Р№Р» С” Р°Р»Рµ exec Р·Р°Р±РѕСЂРѕРЅРµРЅРѕ W^X: РґР»СЏ in-process РІСЃРµ РѕРґРЅРѕ РїСЂРёРґР°С‚РЅРёР№
            // (libjvm С‡РёС‚Р°С”С‚СЊСЃСЏ С‡РµСЂРµР· dlopen, exec РЅРµ РїРѕС‚СЂС–Р±РµРЅ).
            if (f.isFile) return f.absolutePath
            null
        } catch (_: Exception) { null }
    }

    fun findJava(context: Context): String? {
        // 1. РІР±СѓРґРѕРІР°РЅРёР№ JRE (СЃРїРѕС‡Р°С‚РєСѓ 21 С‰РѕР± РЅРѕРІРёР№ СЂР°РЅС‚Р°Р№Рј РјР°РІ РїСЂС–РѕСЂРёС‚РµС‚,
        // СЏРєС‰Рѕ РєРѕСЂРёСЃС‚СѓРІР°С‡ РїРѕРєР»Р°РІ РѕР±РёРґРІР° РІСЂСѓС‡РЅСѓ)
        try {
            bundledCandidate(bundledJava21Binary(context))?.let { return it }
            val bundled = bundledJavaBinary(context)
            if (bundled.exists() && bundled.canExecute()) return bundled.absolutePath
            // РјРѕР¶Рµ Р±СѓС‚Рё РЅРµ РІРёСЃС‚Р°РІР»РµРЅРёР№ +x РїС–СЃР»СЏ СЂРѕР·РїР°РєСѓРІР°РЅРЅСЏ
            if (bundled.exists()) {
                try { bundled.setExecutable(true) } catch (_: Exception) {}
                if (bundled.canExecute()) return bundled.absolutePath
                // W^X: exec Р·Р°Р±РѕСЂРѕРЅРµРЅРѕ, Р°Р»Рµ in-process (dlopen) РІСЃРµ РѕРґРЅРѕ СЃРїСЂР°С†СЋС”
                return bundled.absolutePath
            }
            // legacy РјС–СЃС†Рµ
            val legacy = File(context.filesDir, "jre/bin/java")
            if (legacy.exists()) {
                try { legacy.setExecutable(true) } catch (_: Exception) {}
                if (legacy.canExecute()) return legacy.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "bundled check failed", e)
        }

        // 2. СЃРёСЃС‚РµРјРЅРёР№ java С‡РµСЂРµР· which
        try {
            val proc = ProcessBuilder("sh", "-c", "command -v java").start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isNotEmpty() && out.contains("java")) {
                // РїРµСЂРµРІС–СЂСЏС”РјРѕ С‰Рѕ Р·Р°РїСѓСЃРєР°С”С‚СЊСЃСЏ
                return out.lineSequence().first().trim()
            }
        } catch (_: Exception) {}

        // 3. РІС–РґРѕРјС– СЃРёСЃС‚РµРјРЅС– С€Р»СЏС…Рё (СЂС–РґРєРѕ, Р°Р»Рµ Р±РµР·РєРѕС€С‚РѕРІРЅРѕ РїРµСЂРµРІС–СЂРёС‚Рё)
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
     * Java РїС–Рґ РєРѕРЅРєСЂРµС‚РЅСѓ РІРµСЂСЃС–СЋ Minecraft.
     * 1.20.5+ (need 21): СЃРїРѕС‡Р°С‚РєСѓ tools/jre21, РїРѕС‚С–Рј Р·РІРёС‡Р°Р№РЅРёР№ РїРѕС€СѓРє
     * (JRE17 РїРѕРІРµСЂРЅРµС‚СЊСЃСЏ СЏРє fallback вЂ” prepare С‡РµСЃРЅРѕ РІС–РґРјРѕРІРёС‚СЊ Р· Definitive
     * С– РїС–РґРєР°Р·РєРѕСЋ РїСЂРѕ СЂСѓС‡РЅРёР№ С–РјРїРѕСЂС‚ JRE21, Р·Р°РјС–СЃС‚СЊ РјРѕРІС‡Р°Р·РЅРѕРіРѕ -8).
     * <=1.20.4: tools/jre21 С‚РµР¶ РїС–РґС–Р№РґРµ (Java 21 Р·Р°РїСѓСЃРєР°С” СЃС‚Р°СЂС– РІРµСЂСЃС–С—),
     * Р°Р»Рµ РїСЂС–РѕСЂРёС‚РµС‚ Сѓ JRE17 С‰РѕР± РЅРµ Р»Р°РјР°С‚Рё РїРµСЂРµРІС–СЂРµРЅРµ.
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
     * РЎРїРѕСЃС–Р± Р·Р°РїСѓСЃРєСѓ java.
     *
     * РџРµСЂРµРґС–СЃС‚РѕСЂС–СЏ: РЅР° Android 10+ Р· targetSdk 29+ СЏРґСЂРѕ Р·Р°Р±РѕСЂРѕРЅСЏС” execve()
     * С„Р°Р№Р»С–РІ Р· РїР°РїРєРё РґРѕРґР°С‚РєСѓ (W^X, b/112357170) вЂ” РїСЂСЏРјРёР№ Р·Р°РїСѓСЃРє РґР°С”
     * "error=13, Permission denied", С– Р¶РѕРґРµРЅ chmod С†СЊРѕРіРѕ РЅРµ Р»С–РєСѓС”.
     * РћР±С…С–Рґ (РїР°С‚РµСЂРЅ termux-exec): Р·Р°РїСѓСЃРєР°С‚Рё С‡РµСЂРµР· СЃРёСЃС‚РµРјРЅРёР№ Р»С–РЅРєРµСЂ вЂ”
     * СЏРґСЂРѕ Р±Р°С‡РёС‚СЊ exec СЃРёСЃС‚РµРјРЅРѕРіРѕ С„Р°Р№Р»Р° (РґРѕР·РІРѕР»РµРЅРѕ), Р° mmap Р±С–РЅР°СЂРЅРёРєР°
     * РґРѕР·РІРѕР»РµРЅРёР№ С‚Р°Рє СЃР°РјРѕ СЏРє dlopen().
     */
    data class JavaLaunch(
        val javaPath: String,
        /** РџСЂРµС„С–РєСЃ РєРѕРјР°РЅРґРё, РЅР°РїСЂРёРєР»Р°Рґ ["/system/bin/linker64"]. РџРѕСЂРѕР¶РЅС–Р№ = РїСЂСЏРјРёР№ Р·Р°РїСѓСЃРє. */
        val prefix: List<String> = emptyList(),
        val version: String? = null,
        /** Р”РѕРґР°С‚РєРѕРІРµ РѕС‚РѕС‡РµРЅРЅСЏ (LD_LIBRARY_PATH/JAVA_HOME РґР»СЏ СЂРµР¶РёРјСѓ Р»С–РЅРєРµСЂР°). */
        val env: Map<String, String> = emptyMap()
    ) {
        val useLinker: Boolean get() = prefix.isNotEmpty()
        val works: Boolean get() = version != null
    }

    /** РЎС…РѕР¶Рµ РЅР° СЃРїСЂР°РІР¶РЅС–Р№ РІРёРІС–Рґ `java -version`, Р° РЅРµ РЅР° РїРѕРјРёР»РєСѓ Р»С–РЅРєРµСЂР°. */
    private val VERSION_RE = Regex("(?i)\\b(openjdk|java)(\\s+version|\\s+runtime)?\\b[^\\n]*\"?\\d")

    private fun looksLikeJavaVersion(line: String?): Boolean =
        line != null && VERSION_RE.containsMatchIn(line)

    /** РЎРёСЃС‚РµРјРЅРёР№ Р»С–РЅРєРµСЂ РїС–Рґ ABI РїСЂРёСЃС‚СЂРѕСЋ (РґР»СЏ РѕР±С…РѕРґСѓ exec-Р·Р°Р±РѕСЂРѕРЅРё). */
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

    /** РџРµСЂС€РёР№ РЅРµРїРѕСЂРѕР¶РЅС–Р№ СЂСЏРґРѕРє РІРёРІРѕРґСѓ РєРѕРјР°РЅРґРё Р°Р±Рѕ null (РЅРµ СЃС‚Р°СЂС‚СѓРІР°Р»Р° / timeout / РїРѕСЂРѕР¶РЅСЊРѕ). */
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
     * РџРµСЂРµРІС–СЂСЏС” СЏРє СЂРµР°Р»СЊРЅРѕ Р·Р°РїСѓСЃРєР°С”С‚СЊСЃСЏ java С– РїС–РґР±РёСЂР°С” СЂРѕР±РѕС‡Сѓ С„РѕСЂРјСѓ РєРѕРјР°РЅРґРё.
     * P1: РЅР°РїСЂСЏРјСѓ. P2: Р»С–РЅРєРµСЂ + env (С„РѕСЂРјР° СЏРє Сѓ СЃРµСЂРІРµСЂР°, Р°Р»Рµ Р‘Р•Р— -Xmx: РїСЂР°РїРѕСЂРµС†СЊ РєСѓРїРё
     * РЅС–РєРѕР»Рё РЅРµ РјР°С” СЃС‚РѕСЏС‚Рё РЅР° РјС–СЃС†С– executable вЂ” С–РЅР°РєС€Рµ linker РїР°РґР°С” Р·
     * `expected absolute path: "-Xmx64M"`). P3: С‚Рµ СЃР°РјРµ Р±РµР· env.
     * РЈ Р»РѕРі РїРёС€Рµ РєРѕР¶РµРЅ РєСЂРѕРє: РїСЂРёСЃС‚СЂС–Р№ СЃР°Рј РїРѕРєР°Р¶Рµ С‰Рѕ Р№РѕРјСѓ РїС–РґС…РѕРґРёС‚СЊ.
     *
     * P0 FORK-fix: argv[0] РјР°С” Р±СѓС‚Рё Р°Р±СЃРѕР»СЋС‚РЅРёРј С€Р»СЏС…РѕРј РґРѕ java (РІС–РґРЅРѕСЃРЅРёР№ "java"
     * Р· PATH РґР°С” `expected absolute path: "-Xmx64M"` вЂ” linker РїСЂРѕРїСѓСЃРєР°С” РЅРµР°Р±СЃРѕР»СЋС‚РЅРёР№
     * argv[1] С– С‡С–РїР»СЏС”С‚СЊСЃСЏ РґРѕ РЅР°СЃС‚СѓРїРЅРѕРіРѕ). РўРѕРјСѓ РЅРµР°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС… РѕРґСЂР°Р·Сѓ РІС–РґС…РёР»СЏС”РјРѕ.
     * РўР°РєРѕР¶ РїРµСЂРµРІС–СЂСЏС”РјРѕ libjli.so РїРµСЂРµРґ exec (С–РЅР°РєС€Рµ `CANNOT LINK EXECUTABLE ...
     * library "libjli.so" not found`).
     */
    fun probeJava(javaPath: String, log: (String) -> Unit = {}): JavaLaunch {
        // argv[0] РјСѓСЃРёС‚СЊ Р±СѓС‚Рё Р°Р±СЃРѕР»СЋС‚РЅРёРј вЂ” С–РЅР°РєС€Рµ linker РІС–Р·СЊРјРµ -Xmx Р·Р° executable.
        if (!File(javaPath).isAbsolute) {
            log("Java exec: С€Р»СЏС… РЅРµ Р°Р±СЃРѕР»СЋС‚РЅРёР№ ($javaPath) вЂ” exec С‡РµСЂРµР· Р»С–РЅРєРµСЂ РЅРµРјРѕР¶Р»РёРІРёР№, РїРѕС‚СЂС–Р±РµРЅ Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС…")
            return JavaLaunch(javaPath)
        }
        if (!hasLibJli(javaPath)) {
            log("Java exec: WARN libjli.so РЅРµ Р·РЅР°Р№РґРµРЅРѕ РїРѕСЂСѓС‡ Р· $javaPath вЂ” linker РґР°СЃС‚СЊ CANNOT LINK EXECUTABLE")
            // РќРµ РІС–РґРјРѕРІР»СЏС”РјРѕ РѕРґСЂР°Р·Сѓ: in-process (dlopen) РјРѕР¶Рµ СЃРїСЂР°С†СЋРІР°С‚Рё, Р° probe РЅРёР¶С‡Рµ РїРѕРєР°Р¶Рµ.
        }
        val directOut = runAndFirstLine(listOf(javaPath, "-version"))
        val directVer = directOut?.takeIf { looksLikeJavaVersion(it) }
        if (directVer != null) {
            log("Java exec: РїСЂСЏРјРёР№ Р·Р°РїСѓСЃРє РћРљ")
            return JavaLaunch(javaPath, emptyList(), directVer)
        }
        if (directOut != null) log("Java exec: РїСЂСЏРјР° СЃРїСЂРѕР±Р°: $directOut")
        else log("Java exec: РїСЂСЏРјРёР№ Р·Р°РїСѓСЃРє РЅРµРјРѕР¶Р»РёРІРёР№ (error=13 вЂ” W^X exec-Р·Р°Р±РѕСЂРѕРЅР°)")
        val linker = systemLinker()
        if (linker == null) {
            log("Java exec: СЃРёСЃС‚РµРјРЅРѕРіРѕ Р»С–РЅРєРµСЂР° РЅРµ Р·РЅР°Р№РґРµРЅРѕ")
            return JavaLaunch(javaPath)
        }
        val env = linkerEnv(javaPath)
        // P2: С„РѕСЂРјР° `linker <abs java> -version` + env. Р‘Р•Р— -Xmx: РІС–РЅ РЅС–РєРѕР»Рё РЅРµ РјР°С”
        // СЃС‚РѕСЏС‚Рё С‚Р°Рј РґРµ linker С‡РµРєР°С” executable (С–РЅР°РєС€Рµ `expected absolute path: "-Xmx64M"`).
        // ProcessBuilder РЅРµ РІРјС–С” РїС–РґРјС–РЅСЏС‚Рё argv[0] РѕРєСЂРµРјРѕ РІС–Рґ executable (СЏРє execve),
        // С‚РѕРјСѓ С”РґРёРЅРёР№ РєРѕСЂРµРєС‚РЅРёР№ РІРёРєР»РёРє вЂ” linker РїРµСЂС€РёРј, java Р°Р±СЃРѕР»СЋС‚РЅРёРј РґСЂСѓРіРёРј.
        val shape = listOf(linker, javaPath, "-version")
        val vEnvRaw = runAndFirstLine(shape, env)
        val vEnv = vEnvRaw?.takeIf { looksLikeJavaVersion(it) }
        if (vEnv != null) {
            log("Java exec: Р»С–РЅРєРµСЂ + env РћРљ")
            return JavaLaunch(javaPath, listOf(linker), vEnv, env)
        }
        log("Java exec: Р»С–РЅРєРµСЂ + env: ${vEnvRaw ?: "РЅРµРјР° РІРёРІРѕРґСѓ"}")
        // P3: С‚Р° СЃР°РјР° С„РѕСЂРјР° Р±РµР· РЅР°С€РѕРіРѕ env.
        val vPlainRaw = runAndFirstLine(shape)
        val vPlain = vPlainRaw?.takeIf { looksLikeJavaVersion(it) }
        if (vPlain != null) {
            log("Java exec: Р»С–РЅРєРµСЂ РћРљ, Р°Р»Рµ Р»РёС€Рµ Р‘Р•Р— РЅР°С€РѕРіРѕ env вЂ” Р·Р°РїСѓСЃРєР°СЋ Р±РµР· РЅСЊРѕРіРѕ")
            return JavaLaunch(javaPath, listOf(linker), vPlain)
        }
        log("Java exec: Р»С–РЅРєРµСЂ Р±РµР· env: ${vPlainRaw ?: "РЅРµРјР° РІРёРІРѕРґСѓ"}")
        return JavaLaunch(javaPath)
    }

    /**
     * РћС‚РѕС‡РµРЅРЅСЏ РґР»СЏ Р·Р°РїСѓСЃРєСѓ С‡РµСЂРµР· Р»С–РЅРєРµСЂ: С‰РѕР± libjli/libjvm Р·РЅР°С…РѕРґРёР»РёСЃСЊ
     * Р·Р° LD_LIBRARY_PATH РЅРµР·Р°Р»РµР¶РЅРѕ РІС–Рґ С‚РѕРіРѕ, С‰Рѕ Р±Р°С‡РёС‚СЊ Р»Р°СѓРЅС‡РµСЂ РІ argv[0].
     * P0: РјР°С” РІРєР»СЋС‡Р°С‚Рё jre/lib/server + jre/lib (+ jre/lib/jli РґРµ Р»РµР¶РёС‚СЊ libjli.so),
     * С–РЅР°РєС€Рµ linker РїР°РґР°С” `library "libjli.so" not found`. JAVA_HOME вЂ” РѕР±РѕРІ'СЏР·РєРѕРІРѕ.
     */
    fun linkerEnv(javaPath: String): Map<String, String> {
        return try {
            val bin = File(javaPath).parentFile ?: return emptyMap()
            if (bin.name != "bin") return emptyMap()
            val home = bin.parentFile ?: return emptyMap()
            // РџРѕСЂСЏРґРѕРє СЏРє Сѓ PocketJvm.ldLibraryPathFor, Р°Р»Рµ Р· lib/server РїРµСЂС€РёРј
            // (С‚Р°Рј РґРµ libjvm.so) + lib + lib/jli (С‚Р°Рј РґРµ libjli.so РЅР° Pojav-JRE).
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
     * P0: С‡Рё С” libjli.so РїРѕСЂСѓС‡ Р· java (РїРµСЂРµРІС–СЂРєР° РїРµСЂРµРґ exec С‡РµСЂРµР· Р»С–РЅРєРµСЂ).
     * РЁСѓРєР°С”РјРѕ РІ lib/jli/libjli.so, lib/libjli.so, lib/server/libjli.so.
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

    /** P0: Р°Р±СЃРѕР»СЋС‚РЅРёР№ С€Р»СЏС… РґРѕ libjli.so Р°Р±Рѕ null (РґР»СЏ РґС–Р°РіРЅРѕСЃС‚РёРєРё РІ Р»РѕР·С–). */
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
        // РџСЂСЏРјРёР№ Р·Р°РїСѓСЃРє; СЏРєС‰Рѕ РІРїРёСЂР°С”РјРѕСЃСЊ Сѓ W^X exec-Р·Р°Р±РѕСЂРѕРЅСѓ вЂ” С‡РµСЂРµР· Р»С–РЅРєРµСЂ.
        return probeJava(javaPath).version
    }

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /**
     * Р РѕР±РѕС‡РёР№ Bionic-JRE РїС–Рґ Android (РїСЂСЏРјРёР№ Р·Р°РїСѓСЃРє Р±РµР· proot).
     * Р”Р¶РµСЂРµР»Рѕ: PojavLauncherTeam/android-openjdk-build-multiarch, СЂРµР»С–Р· jre17-ec28559
     * (СЃС‚Р°Р±С–Р»СЊРЅРёР№, ~37MB, СЃРѕС‚РЅС– С‚РёСЃСЏС‡ Р·Р°РІР°РЅС‚Р°Р¶РµРЅСЊ).
     * РЎС‚Р°СЂРёР№ URL PocketHost-JRE/jre-android РЅРµ С–СЃРЅСѓС” (404) вЂ” С‚РѕРјСѓ JRE РЅС–РєРѕР»Рё РЅРµ РєР°С‡Р°РІСЃСЏ.
     *
     * РџРѕРІРµСЂС‚Р°С” РўР†Р›Р¬РљР РїС–Рґ СЃРІРѕС” ABI: С‡СѓР¶РёР№ Р°СЂС…С–С‚РµРєС‚СѓСЂРЅРёР№ JRE РІСЃРµ РѕРґРЅРѕ РЅРµ Р·Р°РїСѓСЃС‚РёС‚СЊСЃСЏ,
     * Р° РЅР° РїРѕРІС–Р»СЊРЅС–Р№ РјРµСЂРµР¶С– РєРѕР¶РЅР° Р·Р°Р№РІР° СЃРїСЂРѕР±Р° С†Рµ ~37MB С– С…РІРёР»РёРЅРё РѕС‡С–РєСѓРІР°РЅРЅСЏ.
     */
    fun jreDownloadUrls(): List<String> = listOf(jreDownloadUrl())

    /**
     * Р„РґРёРЅРµ РґР¶РµСЂРµР»Рѕ URL РґР»СЏ JRE. Glibc-Р·Р±С–СЂРєРё РїСЂР°С†СЋСЋС‚СЊ Р»РёС€Рµ РІСЃРµСЂРµРґРёРЅС–
     * proot-ubuntu, С‚РѕРјСѓ РґР»СЏ РїСЂСЏРјРѕРіРѕ Р·Р°РїСѓСЃРєСѓ РїРѕС‚СЂС–Р±РµРЅ Bionic-JRE (Pojav).
     */
    fun jreDownloadUrl(): String = when (abi()) {
        "arm64-v8a" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
        "armeabi-v7a", "armeabi" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm-20210914-release.tar.xz"
        "x86_64" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-x86_64-20210825-release.tar.xz"
        "x86", "i686" -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-x86-20220225-release.tar.xz"
        else -> "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559/jre17-arm64-20210825-release.tar.xz"
    }

    /**
     * Minecraft 1.20.5+ (РІРєР»СЋС‡РЅРѕ 1.21.x) РІРёРјР°РіР°С” Java 21.
     * Р’Р±СѓРґРѕРІР°РЅРёР№ JRE17 Р·Р°РїСѓСЃС‚РёС‚СЊ Р»РёС€Рµ <=1.20.4.
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

    /** РџР°СЂСЃРёС‚СЊ major-РІРµСЂСЃС–СЋ Р· `java -version` ("openjdk version \"17.0.10\""). Р§РµСЂРµР· Р»С–РЅРєРµСЂ Р·Р° РїРѕС‚СЂРµР±Рё. */
    fun javaMajorVersion(javaPath: String): Int? {
        val out = runAndFirstLine(listOf(javaPath, "-version"))
            ?: systemLinker()?.let { runAndFirstLine(listOf(it, javaPath, "-version")) }
            ?: return null
        return Regex("\"(\\d+)").find(out)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** Р§Рё РїС–РґС‚СЂРёРјСѓС”С‚СЊСЃСЏ Р°РІС‚РѕР·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ JRE РЅР° С†СЊРѕРјСѓ ABI. */
    fun isJreAutoInstallSupported(): Boolean = abi() in setOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "i686")

    fun installHint(): String = buildString {
        appendLine("Java РЅРµ Р·РЅР°Р№РґРµРЅР° Сѓ РІР±СѓРґРѕРІР°РЅРѕРјСѓ СЃРµСЂРµРґРѕРІРёС‰С–.")
        appendLine("PocketHost Р·Р°СЂР°Р· Р·Р°РІР°РЅС‚Р°Р¶РёС‚СЊ РІР±СѓРґРѕРІР°РЅРёР№ JRE Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ (~40 MB, Pojav Bionic).")
        appendLine("РќС–С‡РѕРіРѕ РІСЃС‚Р°РЅРѕРІР»СЋРІР°С‚Рё РѕРєСЂРµРјРѕ (Termux) РЅРµ РїРѕС‚СЂС–Р±РЅРѕ.")
    }
}
