package com.anserver.app.jvm

import android.content.Context
import android.os.Build
import android.util.Log
import com.anserver.app.embedded.EmbeddedJava
import java.io.File
import java.util.jar.JarFile

/**
 * Kotlin-шар запуску JVM в процесі додатку.
 *
 * Порт MojoLauncher/PojavLauncher під серверний кейс:
 *   GameRunner.launchGame()  -> [PocketJvm.buildJvmArgs] + [InProcessServer.start]
 *   JavaRunner.startJvm()    -> [PocketJvm.startJvm]
 *   JavaRunner.getJavaArgs() -> [PocketJvm.baseJvmArgs]
 *   JavaRunner.preprocessUserArgs() -> [PocketJvm.sanitizeUserArgs]
 *   JavaRunner.findVmPath()  -> [PocketJvm.findVmPath]
 *   jre_launcher.c           -> cpp/jvm_launcher.c (nativeLoadJVM)
 *
 * Відмінності від Mojo (клієнт):
 *  - headless=true (серверу не потрібен Caciocavallo/AWT);
 *  - жодних GL/LWJGL прапорців (-Dorg.lwjgl.*) і authlib-injector;
 *  - classpath = один fat-jar сервера + Main-Class з його MANIFEST.MF;
 *  - appArgs = ["nogui"].
 *
 * Відмінності від старого PocketHost (ProcessBuilder + /system/bin/linker):
 *  - жодного execve(), отже жодного error=13 (W^X заборона Android 10+);
 *  - сервер живе в тому ж UID/процесі, LD_LIBRARY_PATH виставляється
 *    через android_update_LD_LIBRARY_PATH як у Mojo utils.c.
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
        /** Двокрапковий classpath для URLClassLoader-фолбека в native (див. jvm_launcher.c). */
        classpath: String
    ): Int

    external fun nativeChdir(path: String): Int
    external fun nativeSetLdLibraryPath(path: String)
    external fun nativeIsJvmCreated(): Boolean
    external fun nativeGetLastError(): String

    /**
     * Будильник native-паркування (jvm_launcher.c parking loop).
     * FAIL 17:44: Stop ставив тільки Kotlin-флаг, а native крутив
     * while(!g_exit_requested) на chunk-воркерах вічно — futureDone=false.
     * Після цього дзвінка паркування дає ~10с grace на flush світу і виходить.
     * Без винятків (старий .so без методу -> UnsatisfiedLinkError ловимо).
     */
    external fun nativeRequestStop()

    /** Безпечна обгортка над nativeRequestStop (не кидає). */
    fun requestStop() {
        try {
            nativeRequestStop()
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeRequestStop missing (old .so?)", e)
        } catch (_: Exception) {}
    }

    /** Текст останньої нативної помилки (порожньо якщо не було). Без винятків. */
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

    // ---- libjvm.so discovery (порт JavaRunner.findVmPath) ----

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
     * Шукає libjvm.so всередині JRE. Порядок як у Mojo:
     * lib/server, lib/client, потім arch-підпапка (i386/x86_64/aarch64...).
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
        // останній шанс: пошук першого libjvm.so рекурсивно (дешево, JRE маленький).
        // Глибину обмежуємо: стандартні розкладки тримають libjvm.so на глибині <=3
        // (lib/server/, lib/aarch64/server/), а без ліміту чужий runtimeHome
        // (напр. /system від системного java) повісив би старт на хвилини обходу.
        return try {
            runtimeHome.walkTopDown().maxDepth(4).firstOrNull { it.name == "libjvm.so" && it.isFile }
        } catch (_: Exception) { null }
    }

    // ---- Main-Class з MANIFEST.MF (для -jar емуляції через classpath+main) ----

    /**
     * Читає Main-Class з MANIFEST.MF серверного jar.
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
     * Читає Class-Path з MANIFEST.MF (емуляція `java -jar`: той підхоплює
     * відносні jar з Class-Path автоматично, а classpath+main — ні).
     * Потрібно Fabric/Forge-лаунчерам з зовнішньою папкою libraries/.
     * Повертає існуючі файли/папки, відносні шляхи резолвить від папки jar.
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
     * Major-версія JRE з файлу `release` (JAVA_VERSION="17.0.10").
     * Дешево і без запуску процесу — на відміну від `java -version`.
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

    /** Дефінітивна помилка підготовки: legacy exec її теж не виправить, fallback безглуздий. */
    class DefinitiveFailureException(message: String) : Exception(message)

    // ---- JVM args (порт JavaRunner.getJavaArgs + preprocessUserArgs) ----

    /**
     * Видаляє аргументи які лаунчер ставить сам або які ламають Android:
     * -cp/-classpath (classpath передаємо окремо), -Xms/-Xmx (ставимо з конфігу),
     * -d32/-d64, -Xint, large-pages (нема на мобілці),
     * конкуруючі GC (лаунчер примусово ставить UseSerialGC для мобілки —
     * два колектори в командному рядку валять створення JVM),
     * -Djava.class.path (prepare додає свій повний).
     * --module-path форми з пробілом клеїть у --module-path=... (як Mojo).
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
                // Конкуренти примусового UseSerialGC (див. buildJvmArgs):
                // старі конфіги серверів зберігали G1GC — два колектори валять старт.
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
     * Базові VM-аргументи. Порядок як у Mojo: -Xmx/-Xms першими,
     * потім юзерські, потім overridable (юзерські перемагають).
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

        // RAM першими (як Mojo: add(0, -Xms), add(0, -Xmx) -> Xmx опиняється першим)
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
            "-Dlog4j2.formatMsgNoLookups=true", // Log4Shell мітігація, як у Mojo
            "-Djdk.lang.Process.launchMechanism=FORK", // POSIX_SPAWN потребує jspawnhelper, якого нема на Android
            // Вбудований JRE — adhoc-збірка Pojav, звітує "17-internal":
            // Paper gate (contains("-") -> pre-release -> return) вбиває старт.
            // Офіційний прапорець обходу (docs.papermc.io: Paper.IgnoreJavaVersion).
            // NOTE: -Djava.version НЕ працює (HotSpot перезаписує вбудованим),
            // тому лише IgnoreJavaVersion.
            "-DPaper.IgnoreJavaVersion=true",
            // Мобільний тюнінг старту: менше паралельних компіляторів і вищі
            // пороги JIT — bulk classloading перших секунд не тоне в IC-стормі
            // (ICBufferFull safepoint + tag-abort в libjvm 2021 на нових Android).
            // Гарячі методи все одно скомпілюються за хвилини роботи сервера.
            "-XX:CICompilerCount=2",
            "-XX:Tier3CompileThreshold=5000",
            "-XX:Tier4CompileThreshold=50000",
            "-XX:+UseSerialGC",
            "-Xss256k",
            "-XX:-UseCompressedOops", // MTE-фікс Android 14+ (як було в старому лаунчері)
            "-XX:ActiveProcessorCount=" + Runtime.getRuntime().availableProcessors()
        )
        // маршмелоу-x86 костиль з Mojo (signal/sigchain баг): дешево лишити
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
     * LD_LIBRARY_PATH для libjvm: <home>/lib/server : <home>/lib : <home>/lib/jli.
     * Як Mojo relocateLdLibPath(), але без NATIVE_LIB_DIR (графіки нема).
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
        /** Повний classpath (jar + Class-Path з маніфесту). Фіксується при створенні JVM. */
        val classpath: String
    )

    /**
     * Готує все для nativeLoadJVM: знаходить libjvm.so, перевіряє версію Java,
     * читає Main-Class (+ Class-Path з маніфесту), будує jvmArgs.
     *
     * @param minecraftVersion версія гри ("1.21.4") для перевірки Java 17 vs 21.
     * @throws DefinitiveFailureException якщо проблема не лікується fallback'ом
     *         (битий jar, нема Main-Class, стара Java) — з причиною вже в log().
     * @return null лише для retryable-помилок (напр. JRE ще докачується).
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
            log("ERROR: libjvm.so не знайдено в ${runtimeHome.absolutePath}. JRE битий — видали tools/jre і натисни Start ще раз.")
            throw DefinitiveFailureException("no libjvm")
        }
        // Версія Java ДО старту: інакше отримаємо незрозумілий -8
        // (UnsupportedClassVersionError всередині FindClass).
        if (minecraftVersion != null) {
            val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
            val have = readJreMajor(runtimeHome)
            if (have != null && have < need) {
                log("ERROR: Minecraft $minecraftVersion потребує Java $need+, а вбудований JRE — Java $have.")
                log("Варіанти: обери сервер 1.20.4 або нижче, або поклади JRE $need вручну в tools/jre21/ через Files.")
                throw DefinitiveFailureException("java $have < $need")
            }
        }
        if (!serverJar.isFile || serverJar.length() < 1024) {
            log("ERROR: jar ${serverJar.name} битий або порожній (${serverJar.length()} байт). Видали його через Files і натисни Start — завантажиться заново.")
            throw DefinitiveFailureException("broken jar")
        }
        val mainClass = readMainClass(serverJar)
        if (mainClass == null) {
            log("ERROR: ${serverJar.name} без Main-Class в MANIFEST.MF. Поклади повний серверний jar (Paper/Vanilla/Purpur/Fabric).")
            throw DefinitiveFailureException("no Main-Class")
        }
        val jvmArgs = buildJvmArgs(
            context, runtimeHome, workDir, maxMemoryMB, minMemoryMB, userArgs
        ).toMutableList()
        // classpath як окремий -D (як Mojo: один -Djava.class.path=a:b:c, не -cp),
        // плюс Class-Path з маніфесту (емуляція `java -jar` для Fabric/Forge).
        val cpEntries = arrayListOf(serverJar.absolutePath)
        readClassPathEntries(serverJar).forEach { cpEntries.add(it.absolutePath) }
        val classpath = cpEntries.joinToString(":")
        jvmArgs.add("-Djava.class.path=$classpath")
        // --- агресивна діагностика -8: все що треба щоб зрозуміти чому клас не вантажиться ---
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
        if (cpEntries.size > 1) log("Class-Path: +${cpEntries.size - 1} entries з маніфесту")
        return StartParams(vmFile, runtimeHome, jvmArgs, mainClass, listOf("nogui"), classpath)
    }
}
