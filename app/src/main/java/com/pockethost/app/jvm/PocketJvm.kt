package com.pockethost.app.jvm

import android.content.Context
import android.os.Build
import android.util.Log
import com.pockethost.app.embedded.EmbeddedJava
import java.io.File
import java.util.jar.JarFile

/**
 * Kotlin-С€Р°СЂ Р·Р°РїСѓСЃРєСѓ JVM РІ РїСЂРѕС†РµСЃС– РґРѕРґР°С‚РєСѓ.
 *
 * РџРѕСЂС‚ MojoLauncher/PojavLauncher РїС–Рґ СЃРµСЂРІРµСЂРЅРёР№ РєРµР№СЃ:
 *   GameRunner.launchGame()  -> [PocketJvm.buildJvmArgs] + [InProcessServer.start]
 *   JavaRunner.startJvm()    -> [PocketJvm.startJvm]
 *   JavaRunner.getJavaArgs() -> [PocketJvm.baseJvmArgs]
 *   JavaRunner.preprocessUserArgs() -> [PocketJvm.sanitizeUserArgs]
 *   JavaRunner.findVmPath()  -> [PocketJvm.findVmPath]
 *   jre_launcher.c           -> cpp/jvm_launcher.c (nativeLoadJVM)
 *
 * Р’С–РґРјС–РЅРЅРѕСЃС‚С– РІС–Рґ Mojo (РєР»С–С”РЅС‚):
 *  - headless=true (СЃРµСЂРІРµСЂСѓ РЅРµ РїРѕС‚СЂС–Р±РµРЅ Caciocavallo/AWT);
 *  - Р¶РѕРґРЅРёС… GL/LWJGL РїСЂР°РїРѕСЂС†С–РІ (-Dorg.lwjgl.*) С– authlib-injector;
 *  - classpath = РѕРґРёРЅ fat-jar СЃРµСЂРІРµСЂР° + Main-Class Р· Р№РѕРіРѕ MANIFEST.MF;
 *  - appArgs = ["nogui"].
 *
 * Р’С–РґРјС–РЅРЅРѕСЃС‚С– РІС–Рґ СЃС‚Р°СЂРѕРіРѕ PocketHost (ProcessBuilder + /system/bin/linker):
 *  - Р¶РѕРґРЅРѕРіРѕ execve(), РѕС‚Р¶Рµ Р¶РѕРґРЅРѕРіРѕ error=13 (W^X Р·Р°Р±РѕСЂРѕРЅР° Android 10+);
 *  - СЃРµСЂРІРµСЂ Р¶РёРІРµ РІ С‚РѕРјСѓ Р¶ UID/РїСЂРѕС†РµСЃС–, LD_LIBRARY_PATH РІРёСЃС‚Р°РІР»СЏС”С‚СЊСЃСЏ
 *    С‡РµСЂРµР· android_update_LD_LIBRARY_PATH СЏРє Сѓ Mojo utils.c.
 */
object PocketJvm {

    private const val TAG = "PocketJvm"

    init {
        try {
            System.loadLibrary("pockethost_jvm")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "pockethost_jvm native lib missing (build with CMake first)", e)
        }
    }

    // ---- JNI (cpp/jvm_launcher.c) ----
    external fun nativeLoadJVM(
        vmPath: String,
        javaArgs: Array<String>,
        mainClass: String,
        appArgs: Array<String>,
        /** Р”РІРѕРєСЂР°РїРєРѕРІРёР№ classpath РґР»СЏ URLClassLoader-С„РѕР»Р±РµРєР° РІ native (РґРёРІ. jvm_launcher.c). */
        classpath: String
    ): Int

    external fun nativeChdir(path: String): Int
    external fun nativeSetLdLibraryPath(path: String)
    external fun nativeIsJvmCreated(): Boolean
    external fun nativeGetLastError(): String

    /**
     * Р‘СѓРґРёР»СЊРЅРёРє native-РїР°СЂРєСѓРІР°РЅРЅСЏ (jvm_launcher.c parking loop).
     * FAIL 17:44: Stop СЃС‚Р°РІРёРІ С‚С–Р»СЊРєРё Kotlin-С„Р»Р°Рі, Р° native РєСЂСѓС‚РёРІ
     * while(!g_exit_requested) РЅР° chunk-РІРѕСЂРєРµСЂР°С… РІС–С‡РЅРѕ вЂ” futureDone=false.
     * РџС–СЃР»СЏ С†СЊРѕРіРѕ РґР·РІС–РЅРєР° РїР°СЂРєСѓРІР°РЅРЅСЏ РґР°С” ~10СЃ grace РЅР° flush СЃРІС–С‚Сѓ С– РІРёС…РѕРґРёС‚СЊ.
     * Р‘РµР· РІРёРЅСЏС‚РєС–РІ (СЃС‚Р°СЂРёР№ .so Р±РµР· РјРµС‚РѕРґСѓ -> UnsatisfiedLinkError Р»РѕРІРёРјРѕ).
     */
    external fun nativeRequestStop()

    /** Р‘РµР·РїРµС‡РЅР° РѕР±РіРѕСЂС‚РєР° РЅР°Рґ nativeRequestStop (РЅРµ РєРёРґР°С”). */
    fun requestStop() {
        try {
            nativeRequestStop()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeRequestStop missing (old .so?)", e)
        } catch (_: Exception) {}
    }

    /** РўРµРєСЃС‚ РѕСЃС‚Р°РЅРЅСЊРѕС— РЅР°С‚РёРІРЅРѕС— РїРѕРјРёР»РєРё (РїРѕСЂРѕР¶РЅСЊРѕ СЏРєС‰Рѕ РЅРµ Р±СѓР»Рѕ). Р‘РµР· РІРёРЅСЏС‚РєС–РІ. */
    fun lastError(): String = try {
        nativeGetLastError()
    } catch (_: Exception) {
        ""
    }

    fun isNativeAvailable(): Boolean = try {
        nativeIsJvmCreated()
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Exception) {
        true // lib loaded, VM just not created yet
    }

    // ---- libjvm.so discovery (РїРѕСЂС‚ JavaRunner.findVmPath) ----

    private fun vmCandidate(home: File, arch: String?, flavor: String): File =
        if (arch != null) File(home, "lib/$arch/$flavor/libjvm.so")
        else File(home, "lib/$flavor/libjvm.so")

    private fun findForArch(home: File, arch: String?): File? {
        var c = vmCandidate(home, arch, "server")
        if (c.exists()) return c
        c = vmCandidate(home, arch, "client")
        if (c.exists()) return c
        return null
    }

    /**
     * РЁСѓРєР°С” libjvm.so РІСЃРµСЂРµРґРёРЅС– JRE. РџРѕСЂСЏРґРѕРє СЏРє Сѓ Mojo:
     * lib/server, lib/client, РїРѕС‚С–Рј arch-РїС–РґРїР°РїРєР° (i386/x86_64/aarch64...).
     */
    fun findVmPath(runtimeHome: File): File? {
        findForArch(runtimeHome, null)?.let { return it }
        val abi = EmbeddedJava.abi()
        val archAliases = when (abi) {
            "arm64-v8a" -> listOf("aarch64", "arm64", "arm")
            "armeabi-v7a", "armeabi" -> listOf("arm", "aarch64")
            "x86_64" -> listOf("amd64", "x86_64", "i386")
            "x86", "i686" -> listOf("i386", "i486", "i586", "x86")
            else -> listOf(abi)
        }
        for (a in archAliases) {
            findForArch(runtimeHome, a)?.let { return it }
        }
        // РѕСЃС‚Р°РЅРЅС–Р№ С€Р°РЅСЃ: РїРѕС€СѓРє РїРµСЂС€РѕРіРѕ libjvm.so СЂРµРєСѓСЂСЃРёРІРЅРѕ (РґРµС€РµРІРѕ, JRE РјР°Р»РµРЅСЊРєРёР№).
        // Р“Р»РёР±РёРЅСѓ РѕР±РјРµР¶СѓС”РјРѕ: СЃС‚Р°РЅРґР°СЂС‚РЅС– СЂРѕР·РєР»Р°РґРєРё С‚СЂРёРјР°СЋС‚СЊ libjvm.so РЅР° РіР»РёР±РёРЅС– <=3
        // (lib/server/, lib/aarch64/server/), Р° Р±РµР· Р»С–РјС–С‚Сѓ С‡СѓР¶РёР№ runtimeHome
        // (РЅР°РїСЂ. /system РІС–Рґ СЃРёСЃС‚РµРјРЅРѕРіРѕ java) РїРѕРІС–СЃРёРІ Р±Рё СЃС‚Р°СЂС‚ РЅР° С…РІРёР»РёРЅРё РѕР±С…РѕРґСѓ.
        return try {
            runtimeHome.walkTopDown().maxDepth(4).firstOrNull { it.name == "libjvm.so" && it.isFile }
        } catch (_: Exception) { null }
    }

    // ---- Main-Class Р· MANIFEST.MF (РґР»СЏ -jar РµРјСѓР»СЏС†С–С— С‡РµСЂРµР· classpath+main) ----

    /**
     * Р§РёС‚Р°С” Main-Class Р· MANIFEST.MF СЃРµСЂРІРµСЂРЅРѕРіРѕ jar.
     * Paper: io.papermc.paperclip.Paperclip, Vanilla: net.minecraft.server.Main, etc.
     */
    fun readMainClass(jar: File): String? {
        return try {
            JarFile(jar).use { jf ->
                jf.manifest?.mainAttributes?.getValue("Main-Class")?.trim()?.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readMainClass failed for ${jar.name}", e)
            null
        }
    }

    /**
     * Р§РёС‚Р°С” Class-Path Р· MANIFEST.MF (РµРјСѓР»СЏС†С–СЏ `java -jar`: С‚РѕР№ РїС–РґС…РѕРїР»СЋС”
     * РІС–РґРЅРѕСЃРЅС– jar Р· Class-Path Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ, Р° classpath+main вЂ” РЅС–).
     * РџРѕС‚СЂС–Р±РЅРѕ Fabric/Forge-Р»Р°СѓРЅС‡РµСЂР°Рј Р· Р·РѕРІРЅС–С€РЅСЊРѕСЋ РїР°РїРєРѕСЋ libraries/.
     * РџРѕРІРµСЂС‚Р°С” С–СЃРЅСѓСЋС‡С– С„Р°Р№Р»Рё/РїР°РїРєРё, РІС–РґРЅРѕСЃРЅС– С€Р»СЏС…Рё СЂРµР·РѕР»РІРёС‚СЊ РІС–Рґ РїР°РїРєРё jar.
     */
    fun readClassPathEntries(jar: File): List<File> {
        return try {
            JarFile(jar).use { jf ->
                val cp = jf.manifest?.mainAttributes?.getValue("Class-Path")
                    ?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
                cp.split(Regex("\\s+")).mapNotNull { entry ->
                    try {
                        val f = File(entry)
                        val resolved = if (f.isAbsolute) f else File(jar.parentFile, entry)
                        if (resolved.exists()) resolved else {
                            Log.i(TAG, "Class-Path entry missing: $entry")
                            null
                        }
                    } catch (_: Exception) { null }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readClassPathEntries failed for ${jar.name}", e)
            emptyList()
        }
    }

    /**
     * Major-РІРµСЂСЃС–СЏ JRE Р· С„Р°Р№Р»Сѓ `release` (JAVA_VERSION="17.0.10").
     * Р”РµС€РµРІРѕ С– Р±РµР· Р·Р°РїСѓСЃРєСѓ РїСЂРѕС†РµСЃСѓ вЂ” РЅР° РІС–РґРјС–РЅСѓ РІС–Рґ `java -version`.
     */
    fun readJreMajor(runtimeHome: File): Int? {
        return try {
            val release = File(runtimeHome, "release")
            if (!release.isFile) return null
            val line = release.readLines().firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?: return null
            val ver = line.substringAfter('=').trim().trim('"')
            // "17.0.10" -> 17, "21" -> 21, "1.8.0_392" -> 8
            if (ver.startsWith("1.")) ver.split(".").getOrNull(1)?.toIntOrNull()
            else ver.split(".").firstOrNull()?.toIntOrNull()
        } catch (_: Exception) { null }
    }

    /** Р”РµС„С–РЅС–С‚РёРІРЅР° РїРѕРјРёР»РєР° РїС–РґРіРѕС‚РѕРІРєРё: legacy exec С—С— С‚РµР¶ РЅРµ РІРёРїСЂР°РІРёС‚СЊ, fallback Р±РµР·РіР»СѓР·РґРёР№. */
    class DefinitiveFailureException(message: String) : Exception(message)

    // ---- JVM args (РїРѕСЂС‚ JavaRunner.getJavaArgs + preprocessUserArgs) ----

    /**
     * Р’РёРґР°Р»СЏС” Р°СЂРіСѓРјРµРЅС‚Рё СЏРєС– Р»Р°СѓРЅС‡РµСЂ СЃС‚Р°РІРёС‚СЊ СЃР°Рј Р°Р±Рѕ СЏРєС– Р»Р°РјР°СЋС‚СЊ Android:
     * -cp/-classpath (classpath РїРµСЂРµРґР°С”РјРѕ РѕРєСЂРµРјРѕ), -Xms/-Xmx (СЃС‚Р°РІРёРјРѕ Р· РєРѕРЅС„С–РіСѓ),
     * -d32/-d64, -Xint, large-pages (РЅРµРјР° РЅР° РјРѕР±С–Р»С†С–),
     * РєРѕРЅРєСѓСЂСѓСЋС‡С– GC (Р»Р°СѓРЅС‡РµСЂ РїСЂРёРјСѓСЃРѕРІРѕ СЃС‚Р°РІРёС‚СЊ UseSerialGC РґР»СЏ РјРѕР±С–Р»РєРё вЂ”
     * РґРІР° РєРѕР»РµРєС‚РѕСЂРё РІ РєРѕРјР°РЅРґРЅРѕРјСѓ СЂСЏРґРєСѓ РІР°Р»СЏС‚СЊ СЃС‚РІРѕСЂРµРЅРЅСЏ JVM),
     * -Djava.class.path (prepare РґРѕРґР°С” СЃРІС–Р№ РїРѕРІРЅРёР№).
     * --module-path С„РѕСЂРјРё Р· РїСЂРѕР±С–Р»РѕРј РєР»РµС—С‚СЊ Сѓ --module-path=... (СЏРє Mojo).
     */
    fun sanitizeUserArgs(args: List<String>): MutableList<String> {
        val out = args.toMutableList()
        val it = out.listIterator()
        while (it.hasNext()) {
            val arg = it.next()
            val glued = when (arg) {
                "-p" -> "--module-path"
                "--add-reads", "--add-exports", "--add-opens", "--add-modules",
                "--limit-modules", "--module-path", "--patch-module", "--upgrade-module-path" -> arg
                else -> null
            }
            if (glued != null) {
                val realArg = if (arg == "-p") "--module-path" else arg
                it.remove()
                if (it.hasNext()) {
                    val value = it.next()
                    it.remove()
                    it.add("$realArg=$value")
                }
                continue
            }
            when (arg) {
                "-d32", "-d64", "-Xint",
                "-XX:+UseTransparentHugePages", "-XX:+UseLargePagesInMetaspace",
                "-XX:+UseLargePages",
                // РљРѕРЅРєСѓСЂРµРЅС‚Рё РїСЂРёРјСѓСЃРѕРІРѕРіРѕ UseSerialGC (РґРёРІ. buildJvmArgs):
                // СЃС‚Р°СЂС– РєРѕРЅС„С–РіРё СЃРµСЂРІРµСЂС–РІ Р·Р±РµСЂС–РіР°Р»Рё G1GC вЂ” РґРІР° РєРѕР»РµРєС‚РѕСЂРё РІР°Р»СЏС‚СЊ СЃС‚Р°СЂС‚.
                "-XX:+UseG1GC", "-XX:+UseParallelGC", "-XX:+UseConcMarkSweepGC",
                "-XX:+UseZGC", "-XX:+UseShenandoahGC", "-XX:-UseSerialGC",
                "-XX:+ParallelRefProcEnabled" -> it.remove()
                "-cp", "-classpath" -> {
                    it.remove()
                    if (it.hasNext()) { it.next(); it.remove() }
                }
                else -> {
                    if (arg.startsWith("-Xms") || arg.startsWith("-Xmx") ||
                        arg.startsWith("-XX:ActiveProcessorCount") ||
                        arg.startsWith("-Djava.class.path=")
                    ) it.remove()
                }
            }
        }
        return out
    }

    /**
     * Р‘Р°Р·РѕРІС– VM-Р°СЂРіСѓРјРµРЅС‚Рё. РџРѕСЂСЏРґРѕРє СЏРє Сѓ Mojo: -Xmx/-Xms РїРµСЂС€РёРјРё,
     * РїРѕС‚С–Рј СЋР·РµСЂСЃСЊРєС–, РїРѕС‚С–Рј overridable (СЋР·РµСЂСЃСЊРєС– РїРµСЂРµРјР°РіР°СЋС‚СЊ).
     */
    fun buildJvmArgs(
        context: Context,
        runtimeHome: File,
        workDir: File,
        maxMemoryMB: Int,
        minMemoryMB: Int,
        userArgs: List<String>,
        extraArgs: List<String> = emptyList()
    ): List<String> {
        val vmArgs = sanitizeUserArgs(userArgs + extraArgs).toMutableList()

        // RAM РїРµСЂС€РёРјРё (СЏРє Mojo: add(0, -Xms), add(0, -Xmx) -> Xmx РѕРїРёРЅСЏС”С‚СЊСЃСЏ РїРµСЂС€РёРј)
        vmArgs.add(0, "-Xms${minMemoryMB}M")
        vmArgs.add(0, "-Xmx${maxMemoryMB}M")

        val overridable = arrayListOf(
            "-Djava.home=" + runtimeHome.absolutePath,
            "-Djava.io.tmpdir=" + context.cacheDir.absolutePath,
            "-Duser.home=" + workDir.absolutePath,
            "-Duser.language=en",
            "-Duser.country=US",
            "-Dfile.encoding=UTF-8",
            "-Djava.awt.headless=true",
            "-Dlog4j2.formatMsgNoLookups=true", // Log4Shell РјС–С‚С–РіР°С†С–СЏ, СЏРє Сѓ Mojo
            "-Djdk.lang.Process.launchMechanism=FORK", // POSIX_SPAWN РїРѕС‚СЂРµР±СѓС” jspawnhelper, СЏРєРѕРіРѕ РЅРµРјР° РЅР° Android
            // Р’Р±СѓРґРѕРІР°РЅРёР№ JRE вЂ” adhoc-Р·Р±С–СЂРєР° Pojav, Р·РІС–С‚СѓС” "17-internal":
            // Paper gate (contains("-") -> pre-release -> return) РІР±РёРІР°С” СЃС‚Р°СЂС‚.
            // РћС„С–С†С–Р№РЅРёР№ РїСЂР°РїРѕСЂРµС†СЊ РѕР±С…РѕРґСѓ (docs.papermc.io: Paper.IgnoreJavaVersion).
            // NOTE: -Djava.version РќР• РїСЂР°С†СЋС” (HotSpot РїРµСЂРµР·Р°РїРёСЃСѓС” РІР±СѓРґРѕРІР°РЅРёРј),
            // С‚РѕРјСѓ Р»РёС€Рµ IgnoreJavaVersion.
            "-DPaper.IgnoreJavaVersion=true",
            // РњРѕР±С–Р»СЊРЅРёР№ С‚СЋРЅС–РЅРі СЃС‚Р°СЂС‚Сѓ: РјРµРЅС€Рµ РїР°СЂР°Р»РµР»СЊРЅРёС… РєРѕРјРїС–Р»СЏС‚РѕСЂС–РІ С– РІРёС‰С–
            // РїРѕСЂРѕРіРё JIT вЂ” bulk classloading РїРµСЂС€РёС… СЃРµРєСѓРЅРґ РЅРµ С‚РѕРЅРµ РІ IC-СЃС‚РѕСЂРјС–
            // (ICBufferFull safepoint + tag-abort РІ libjvm 2021 РЅР° РЅРѕРІРёС… Android).
            // Р“Р°СЂСЏС‡С– РјРµС‚РѕРґРё РІСЃРµ РѕРґРЅРѕ СЃРєРѕРјРїС–Р»СЋСЋС‚СЊСЃСЏ Р·Р° С…РІРёР»РёРЅРё СЂРѕР±РѕС‚Рё СЃРµСЂРІРµСЂР°.
            "-XX:CICompilerCount=2",
            "-XX:Tier3CompileThreshold=5000",
            "-XX:Tier4CompileThreshold=50000",
            "-XX:+UseSerialGC",
            "-Xss256k",
            "-XX:-UseCompressedOops", // MTE-С„С–РєСЃ Android 14+ (СЏРє Р±СѓР»Рѕ РІ СЃС‚Р°СЂРѕРјСѓ Р»Р°СѓРЅС‡РµСЂС–)
            "-XX:ActiveProcessorCount=" + Runtime.getRuntime().availableProcessors()
        )
        // РјР°СЂС€РјРµР»РѕСѓ-x86 РєРѕСЃС‚РёР»СЊ Р· Mojo (signal/sigchain Р±Р°Рі): РґРµС€РµРІРѕ Р»РёС€РёС‚Рё
        if (Build.VERSION.SDK_INT == 23 && EmbeddedJava.abi() == "x86") {
            overridable.add("-Xrs")
        }
        for (arg in overridable) {
            val key = arg.substringBefore('=')
            if (vmArgs.none { it == arg || it.startsWith("$key=") || it == key }) {
                vmArgs.add(arg)
            } else {
                Log.i(TAG, "user overrides $arg")
            }
        }
        return vmArgs
    }

    /**
     * LD_LIBRARY_PATH РґР»СЏ libjvm: <home>/lib/server : <home>/lib : <home>/lib/jli.
     * РЇРє Mojo relocateLdLibPath(), Р°Р»Рµ Р±РµР· NATIVE_LIB_DIR (РіСЂР°С„С–РєРё РЅРµРјР°).
     */
    fun ldLibraryPathFor(vmFile: File): String {
        val serverDir = vmFile.parentFile // .../lib/server
        val libDir = serverDir?.parentFile // .../lib
        val parts = arrayListOf<String>()
        libDir?.let { parts.add(it.absolutePath) }
        serverDir?.let { parts.add(it.absolutePath) }
        libDir?.let { parts.add(File(it, "jli").absolutePath) }
        return parts.joinToString(":")
    }

    data class StartParams(
        val vmPath: File,
        val runtimeHome: File,
        val jvmArgs: List<String>,
        val mainClass: String,
        val appArgs: List<String>,
        /** РџРѕРІРЅРёР№ classpath (jar + Class-Path Р· РјР°РЅС–С„РµСЃС‚Сѓ). Р¤С–РєСЃСѓС”С‚СЊСЃСЏ РїСЂРё СЃС‚РІРѕСЂРµРЅРЅС– JVM. */
        val classpath: String
    )

    /**
     * Р“РѕС‚СѓС” РІСЃРµ РґР»СЏ nativeLoadJVM: Р·РЅР°С…РѕРґРёС‚СЊ libjvm.so, РїРµСЂРµРІС–СЂСЏС” РІРµСЂСЃС–СЋ Java,
     * С‡РёС‚Р°С” Main-Class (+ Class-Path Р· РјР°РЅС–С„РµСЃС‚Сѓ), Р±СѓРґСѓС” jvmArgs.
     *
     * @param minecraftVersion РІРµСЂСЃС–СЏ РіСЂРё ("1.21.4") РґР»СЏ РїРµСЂРµРІС–СЂРєРё Java 17 vs 21.
     * @throws DefinitiveFailureException СЏРєС‰Рѕ РїСЂРѕР±Р»РµРјР° РЅРµ Р»С–РєСѓС”С‚СЊСЃСЏ fallback'РѕРј
     *         (Р±РёС‚РёР№ jar, РЅРµРјР° Main-Class, СЃС‚Р°СЂР° Java) вЂ” Р· РїСЂРёС‡РёРЅРѕСЋ РІР¶Рµ РІ log().
     * @return null Р»РёС€Рµ РґР»СЏ retryable-РїРѕРјРёР»РѕРє (РЅР°РїСЂ. JRE С‰Рµ РґРѕРєР°С‡СѓС”С‚СЊСЃСЏ).
     */
    fun prepare(
        context: Context,
        runtimeHome: File,
        serverJar: File,
        workDir: File,
        maxMemoryMB: Int,
        minMemoryMB: Int,
        userArgs: List<String>,
        minecraftVersion: String? = null,
        log: (String) -> Unit = {}
    ): StartParams? {
        val vmFile = findVmPath(runtimeHome)
        if (vmFile == null || !vmFile.exists()) {
            log("ERROR: libjvm.so РЅРµ Р·РЅР°Р№РґРµРЅРѕ РІ ${runtimeHome.absolutePath}. JRE Р±РёС‚РёР№ вЂ” РІРёРґР°Р»Рё tools/jre С– РЅР°С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·.")
            throw DefinitiveFailureException("no libjvm")
        }
        // Р’РµСЂСЃС–СЏ Java Р”Рћ СЃС‚Р°СЂС‚Сѓ: С–РЅР°РєС€Рµ РѕС‚СЂРёРјР°С”РјРѕ РЅРµР·СЂРѕР·СѓРјС–Р»РёР№ -8
        // (UnsupportedClassVersionError РІСЃРµСЂРµРґРёРЅС– FindClass).
        if (minecraftVersion != null) {
            val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
            val have = readJreMajor(runtimeHome)
            if (have != null && have < need) {
                log("ERROR: Minecraft $minecraftVersion РїРѕС‚СЂРµР±СѓС” Java $need+, Р° РІР±СѓРґРѕРІР°РЅРёР№ JRE вЂ” Java $have.")
                log("Р’Р°СЂС–Р°РЅС‚Рё: РѕР±РµСЂРё СЃРµСЂРІРµСЂ 1.20.4 Р°Р±Рѕ РЅРёР¶С‡Рµ, Р°Р±Рѕ РїРѕРєР»Р°РґРё JRE $need РІСЂСѓС‡РЅСѓ РІ tools/jre21/ С‡РµСЂРµР· Files.")
                throw DefinitiveFailureException("java $have < $need")
            }
        }
        if (!serverJar.isFile || serverJar.length() < 1024) {
            log("ERROR: jar ${serverJar.name} Р±РёС‚РёР№ Р°Р±Рѕ РїРѕСЂРѕР¶РЅС–Р№ (${serverJar.length()} Р±Р°Р№С‚). Р’РёРґР°Р»Рё Р№РѕРіРѕ С‡РµСЂРµР· Files С– РЅР°С‚РёСЃРЅРё Start вЂ” Р·Р°РІР°РЅС‚Р°Р¶РёС‚СЊСЃСЏ Р·Р°РЅРѕРІРѕ.")
            throw DefinitiveFailureException("broken jar")
        }
        val mainClass = readMainClass(serverJar)
        if (mainClass == null) {
            log("ERROR: ${serverJar.name} Р±РµР· Main-Class РІ MANIFEST.MF. РџРѕРєР»Р°РґРё РїРѕРІРЅРёР№ СЃРµСЂРІРµСЂРЅРёР№ jar (Paper/Vanilla/Purpur/Fabric).")
            throw DefinitiveFailureException("no Main-Class")
        }
        val jvmArgs = buildJvmArgs(
            context, runtimeHome, workDir, maxMemoryMB, minMemoryMB, userArgs
        ).toMutableList()
        // classpath СЏРє РѕРєСЂРµРјРёР№ -D (СЏРє Mojo: РѕРґРёРЅ -Djava.class.path=a:b:c, РЅРµ -cp),
        // РїР»СЋСЃ Class-Path Р· РјР°РЅС–С„РµСЃС‚Сѓ (РµРјСѓР»СЏС†С–СЏ `java -jar` РґР»СЏ Fabric/Forge).
        val cpEntries = arrayListOf(serverJar.absolutePath)
        readClassPathEntries(serverJar).forEach { cpEntries.add(it.absolutePath) }
        val classpath = cpEntries.joinToString(":")
        jvmArgs.add("-Djava.class.path=$classpath")
        // --- Р°РіСЂРµСЃРёРІРЅР° РґС–Р°РіРЅРѕСЃС‚РёРєР° -8: РІСЃРµ С‰Рѕ С‚СЂРµР±Р° С‰РѕР± Р·СЂРѕР·СѓРјС–С‚Рё С‡РѕРјСѓ РєР»Р°СЃ РЅРµ РІР°РЅС‚Р°Р¶РёС‚СЊСЃСЏ ---
        Log.i(TAG, "prepare: runtimeHome=${runtimeHome.absolutePath} exists=${runtimeHome.exists()}")
        Log.i(TAG, "prepare: vmFile=${vmFile.absolutePath} exists=${vmFile.exists()} size=${vmFile.length()}")
        Log.i(TAG, "prepare: serverJar=${serverJar.absolutePath} isFile=${serverJar.isFile} size=${serverJar.length()}")
        Log.i(TAG, "prepare: classpath=$classpath")
        Log.i(TAG, "prepare: jvmArgs(${jvmArgs.size})=${jvmArgs.joinToString(" | ")}")
        val alreadyCreated = try { nativeIsJvmCreated() } catch (_: Exception) { false }
        Log.i(TAG, "prepare: jvmAlreadyCreated=$alreadyCreated")
        log("DBG prepare: jar exists=${serverJar.isFile} size=${serverJar.length()}")
        log("DBG prepare: classpath=$classpath")
        log("DBG prepare: jvmArgs(${jvmArgs.size}): ${jvmArgs.joinToString(" ")}")
        log("DBG prepare: JVM already created=$alreadyCreated")
        log("JVM: ${vmFile.absolutePath}")
        log("Main: $mainClass")
        if (cpEntries.size > 1) log("Class-Path: +${cpEntries.size - 1} entries Р· РјР°РЅС–С„РµСЃС‚Сѓ")
        return StartParams(vmFile, runtimeHome, jvmArgs, mainClass, listOf("nogui"), classpath)
    }
}
