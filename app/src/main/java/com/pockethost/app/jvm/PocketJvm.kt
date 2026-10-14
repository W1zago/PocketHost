package com.pockethost.app.jvm

import android.content.Context
import android.os.Build
import android.util.Log
import com.pockethost.app.embedded.EmbeddedJava
import java.io.File
import java.util.jar.JarFile

/**
 * Kotlin layer for launching the JVM in the app process.
 *
 * Port of MojoLauncher/PojavLauncher for the server use-case:
 *   GameRunner.launchGame()  -> [PocketJvm.buildJvmArgs] + [InProcessServer.start]
 *   JavaRunner.startJvm()    -> [PocketJvm.startJvm]
 *   JavaRunner.getJavaArgs() -> [PocketJvm.baseJvmArgs]
 *   JavaRunner.preprocessUserArgs() -> [PocketJvm.sanitizeUserArgs]
 *   JavaRunner.findVmPath()  -> [PocketJvm.findVmPath]
 *   jre_launcher.c           -> cpp/jvm_launcher.c (nativeLoadJVM)
 *
 * Differences from Mojo (client):
 *  - headless=true (the server needs no Caciocavallo/AWT);
 *  - no GL/LWJGL props (-Dorg.lwjgl.*) and no authlib-injector;
 *  - classpath = one fat server jar + Main-Class from its MANIFEST.MF;
 *  - appArgs = ["nogui"].
 *
 * Differences from old PocketHost (ProcessBuilder + /system/bin/linker):
 *  - no execve(), hence no error=13 (W^X ban on Android 10+);
 *  - the server lives in the same UID/process, LD_LIBRARY_PATH is set
 *    via android_update_LD_LIBRARY_PATH as in Mojo utils.c.
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
        /** Double classpath for the URLClassLoader fallback in native (see jvm_launcher.c). */
        classpath: String
    ): Int

    external fun nativeChdir(path: String): Int
    external fun nativeSetLdLibraryPath(path: String)
    external fun nativeIsJvmCreated(): Boolean
    external fun nativeGetLastError(): String

    /**
     * Native parking watchdog (jvm_launcher.c parking loop).
     * FAIL 17:44: Stop set only the Kotlin flag, while native spun
     * while(!g_exit_requested) on chunk workers forever - futureDone=false.
     * Since then the parking bell gives ~10s grace to flush the world and exits.
     * No exceptions (old .so without the method -> catch UnsatisfiedLinkError).
     */
    external fun nativeRequestStop()

    /** Safe wrapper over nativeRequestStop (never throws).
     * @return true when the native stop hook exists (current .so).
     * False = old .so without the parking watchdog: native parking has no
     * grace and a "stop" then relies solely on the server reading stdin.
     * Callers must surface this (reinstall APK), not swallow it. */
    fun requestStop(): Boolean {
        return try {
            nativeRequestStop()
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeRequestStop missing (old .so - reinstall APK)", e)
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Text of the last native error (empty when none). No exceptions. */
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

    // ---- libjvm.so discovery (port of JavaRunner.findVmPath) ----

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
     * Finds libjvm.so inside the JRE. Order as in Mojo:
     * lib/server, lib/client, then arch subfolder (i386/x86_64/aarch64...).
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
        // last chance: recursive search for the first libjvm.so (cheap, the JRE is small).
        // Bound the depth: standard layouts keep libjvm.so at depth <=3
        // (lib/server/, lib/aarch64/server/), while no limit on a foreign runtimeHome
        // (e.g. /system from system java) would stall start for minutes of walking.
        return try {
            runtimeHome.walkTopDown().maxDepth(4).firstOrNull { it.name == "libjvm.so" && it.isFile }
        } catch (_: Exception) { null }
    }

    // ---- Main-Class from MANIFEST.MF (for -jar emulation via classpath+main) ----

    /**
     * Reads Main-Class from MANIFEST.MF of the server jar.
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
     * Reads Class-Path from MANIFEST.MF (`java -jar` emulation: it auto-attaches
     * relative jars from Class-Path, while classpath+main does not).
     * Needed by Fabric/Forge launchers with an external libraries/ folder.
     * Returns existing files/dirs, relative entries resolve against the jar folder.
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
     * Major JRE version from the `release` file (JAVA_VERSION="17.0.10").
     * Cheap and process-free - unlike `java -version`.
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

    /** Definitive prepare failure: legacy exec cannot fix it either, fallback is pointless. */
    class DefinitiveFailureException(message: String) : Exception(message)

    // ---- JVM args (port of JavaRunner.getJavaArgs + preprocessUserArgs) ----

    /**
     * Drops args the launcher sets itself or that break Android:
     * -cp/-classpath (we pass classpath separately), -Xms/-Xmx (set from config),
     * -d32/-d64, -Xint, large-pages (absent on mobile),
     * competing GCs (launcher forces UseSerialGC for mobile -
     * two collectors in one command line kill JVM creation),
     * -Djava.class.path (prepare adds its own full one).
     * --module-path forms with a space are glued into --module-path=... (like Mojo).
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
                // Competing forced UseSerialGC (see buildJvmArgs):
                // old server configs kept G1GC - two collectors kill the start.
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
     * Base VM args. Order as in Mojo: -Xmx/-Xms first,
     * then user args, then overridable (user args win).
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

        // RAM first (like Mojo: add(0, -Xms), add(0, -Xmx) -> Xmx ends up first)
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
            "-Dlog4j2.formatMsgNoLookups=true", // Log4Shell mitigation, like Mojo
            "-Djdk.lang.Process.launchMechanism=FORK", // POSIX_SPAWN needs jspawnhelper, absent on Android
            // Headless Android console: no JLine/Jansi native terminal.
            // Without this Paper loads jansi-*.so built for glibc
            // (dlopen fails with "libc.so.6 not found" on bionic) and logs
            // "Advanced terminal features are not available". --nojline app
            // arg (see prepare) disables JLine; these props are the second
            // gate for JLine3/Log4j2/Jansi probing.
            "-Djline.terminal=jline.UnsupportedTerminal",
            "-Dterminal.jline=false",
            "-Dterminal.ansi=false",
            "-Dlog4j.skipJansi=true",
            // JNA (used by OSHI hardware report in CrashReport preload):
            // prefer bundled dispatch, extract per-server (not shared cache).
            "-Djna.nosys=true",
            "-Djna.tmpdir=" + File(workDir, "tmp").absolutePath,
            // Bundled JRE is an adhoc Pojav build, reports "17-internal":
            // Paper gate (contains("-") -> pre-release -> return) kills the start.
            // Official bypass (docs.papermc.io: Paper.IgnoreJavaVersion).
            // NOTE: -Djava.version does NOT work (HotSpot overwrites the built-in),
            // so only IgnoreJavaVersion.
            "-DPaper.IgnoreJavaVersion=true",
            // Mobile start tuning: fewer parallel compilers and higher
            // JIT thresholds - bulk classloading of the first seconds does not drown in IC storms
            // (ICBufferFull safepoint + tag-abort in 2021 libjvm on new Android).
            // Hot methods still compile within minutes of server uptime.
            "-XX:CICompilerCount=2",
            "-XX:Tier3CompileThreshold=5000",
            "-XX:Tier4CompileThreshold=50000",
            "-XX:+UseSerialGC",
            "-Xss256k",
            "-XX:-UseCompressedOops", // MTE fix for Android 14+ (as in the old launcher)
            "-XX:ActiveProcessorCount=" + Runtime.getRuntime().availableProcessors()
        )
        // marshmallow-x86 crutch from Mojo (signal/sigchain bug): cheap to keep
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
     * LD_LIBRARY_PATH for libjvm: <home>/lib/server : <home>/lib : <home>/lib/jli.
     * Like Mojo relocateLdLibPath(), but without NATIVE_LIB_DIR (no graphics).
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
        /** Full classpath (jar + Class-Path from manifest). Fixed at JVM creation. */
        val classpath: String
    )

    /**
     * Prepares everything for nativeLoadJVM: finds libjvm.so, checks the Java version,
     * reads Main-Class (+ Class-Path from manifest), builds jvmArgs.
     *
     * @param minecraftVersion game version ("1.21.4") for the Java 17 vs 21 check.
     * @throws DefinitiveFailureException when the problem is not fixable by fallback
     *         (broken jar, no Main-Class, old Java) - reason already in log().
     * @return null only for retryable failures (e.g. JRE still downloading).
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
            log("ERROR: libjvm.so not found in ${runtimeHome.absolutePath}. JRE is broken - delete tools/jre and press Start again.")
            throw DefinitiveFailureException("no libjvm")
        }
        // Java version BEFORE start: otherwise we get a cryptic -8
        // (UnsupportedClassVersionError inside FindClass).
        if (minecraftVersion != null) {
            val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
            val have = readJreMajor(runtimeHome)
            if (have != null && have < need) {
                log("ERROR: Minecraft $minecraftVersion needs Java $need+, but bundled JRE is Java $have.")
                log("Options: pick server 1.20.4 or lower, or drop JRE $need manually into tools/jre21/ via Files.")
                throw DefinitiveFailureException("java $have < $need")
            }
        }
        if (!serverJar.isFile || serverJar.length() < 1024) {
            log("ERROR: jar ${serverJar.name} is broken or empty (${serverJar.length()} bytes). Delete it via Files and press Start - it will download again.")
            throw DefinitiveFailureException("broken jar")
        }
        val mainClass = readMainClass(serverJar)
        if (mainClass == null) {
            log("ERROR: ${serverJar.name} has no Main-Class in MANIFEST.MF. Drop a full server jar (Paper/Vanilla/Purpur/Fabric).")
            throw DefinitiveFailureException("no Main-Class")
        }
        val jvmArgs = buildJvmArgs(
            context, runtimeHome, workDir, maxMemoryMB, minMemoryMB, userArgs
        ).toMutableList()
        // classpath as a separate -D (like Mojo: one -Djava.class.path=a:b:c, not -cp),
        // plus Class-Path from the manifest (`java -jar` emulation for Fabric/Forge).
        val cpEntries = arrayListOf(serverJar.absolutePath)
        readClassPathEntries(serverJar).forEach { cpEntries.add(it.absolutePath) }
        val classpath = cpEntries.joinToString(":")
        jvmArgs.add("-Djava.class.path=$classpath")
        // --- aggressive -8 diagnostics: everything needed to understand why the class did not load ---
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
        if (cpEntries.size > 1) log("Class-Path: +${cpEntries.size - 1} entries from manifest")
        // OSHI/JNA/udev/MacAddress warnings below are expected on Android
        // (no udev, no stable MAC) and do not break the server.
        log("NOTE: OSHI/JNA/udev warnings below are expected on Android, ignore them.")
        // Headless server args: nogui (no Swing) + --nojline (no JLine/Jansi
        // native terminal - the jansi-*.so in Paper is glibc-built and fails
        // on bionic with "libc.so.6 not found"). Paperclip forwards unknown
        // args to the patched server main; Vanilla/Paper/CraftBukkit accept it.
        return StartParams(vmFile, runtimeHome, jvmArgs, mainClass, listOf("nogui", "--nojline"), classpath)
    }
}
