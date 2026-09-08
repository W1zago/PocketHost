package com.anserver.app.embedded

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Вбудований bootstrap: JRE + Ubuntu rootfs завантажуються
 * у приватну папку додатку. Жодного зовнішнього Termux не треба.
 *
 * Все виконується через звичайний INTERNET (вже є в Manifest),
 * без RUN_COMMAND / SAF / storage дозволів.
 */
object EmbeddedBootstrap {

    private const val TAG = "EmbeddedBootstrap"

    // URL беремо з єдиного джерела EmbeddedJava.jreDownloadUrl() щоб не розходились.
    // Легкий Android-Bionic JRE (aarch64). Shell-сервери працюють і без Java.

    data class BootstrapState(
        val javaPath: String?,
        val javaOk: Boolean,
        val ubuntuInstalled: Boolean,
        val toolsReady: Boolean
    )

    fun getState(context: Context): BootstrapState {
        val java = EmbeddedJava.findJava(context)
        val ubuntuMarker = File(EmbeddedPaths.ubuntuRootfsDir(context), ".installed")
        return BootstrapState(
            javaPath = java,
            javaOk = java != null,
            ubuntuInstalled = ubuntuMarker.exists(),
            toolsReady = true
        )
    }

    suspend fun ensureServerDirReady(workDir: File): File =
        withContext(Dispatchers.IO) {
            workDir.mkdirs()
            val log = File(workDir, "server.log")
            if (!log.exists()) {
                try { log.createNewFile() } catch (_: Exception) {}
            }
            // input.txt більше не потрібен (stdin-труба), але лишаємо для сумісності старих логів
            workDir
        }

    /**
     * Гарантує Java для Minecraft. Повертає шлях до java або null.
     * Якщо JRE нема - пробує завантажити в фоні і пише прогрес у log().
     * Не кидає exception, не вимагає жодних дозволів.
     * @param minecraftVersion для попередження: 1.21+ потребує Java 21, вбудований JRE17 її не запустить.
     */
    suspend fun ensureJava(
        context: Context,
        minecraftVersion: String? = null,
        log: (String) -> Unit = {}
    ): String? =
        withContext(Dispatchers.IO) {
            val existing = EmbeddedJava.findJavaForVersion(context, minecraftVersion)
            if (existing != null) {
                val ver = EmbeddedJava.javaVersion(existing) ?: "?"
                log("Java: $existing ($ver)")
                // Для 1.20.5+ перевіряємо що це справді 21+: JRE17 тут не запуститься.
                // findJavaForVersion вже віддав jre21 якщо він є; якщо нема —
                // чесно кажемо де його взяти, а prepare нижче відмовить з Definitive.
                if (minecraftVersion != null) {
                    try {
                        val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
                        if (need > 17) {
                            val home = EmbeddedJava.runtimeHomeFor(existing)
                            val have = if (home != null) com.anserver.app.jvm.PocketJvm.readJreMajor(home) else null
                            if (have != null && have < need) {
                                log("Java 21 не знайдена (є лише Java $have). Поклади JRE21 вручну через Files у tools/jre21/ (щоб з'явився tools/jre21/bin/java) і натисни Start ще раз. Стабільного автозавантаження JRE21 нема — Pojav-реліз архівовано.")
                            } else {
                                warnIfJavaTooOld(existing, minecraftVersion, log)
                            }
                        } else {
                            warnIfJavaTooOld(existing, minecraftVersion, log)
                        }
                    } catch (_: Exception) {
                        warnIfJavaTooOld(existing, minecraftVersion, log)
                    }
                }
                return@withContext existing
            }
            log(EmbeddedJava.installHint())
            val ok = tryDownloadAndExtractJre(context, log)
            if (ok) {
                val now = EmbeddedJava.findJava(context)
                if (now != null) {
                    log("Вбудований JRE встановлено: $now (${EmbeddedJava.javaVersion(now) ?: "?"})")
                    warnIfJavaTooOld(now, minecraftVersion, log)
                    return@withContext now
                }
            }
            log("Java поки недоступна. Shell-сервери працюють, Minecraft потребує JRE.")
            log("Обхід: поклади свій JRE вручну через Files у tools/jre/ (щоб з'явився tools/jre/bin/java) і натисни Start ще раз.")
            null
        }

    private fun warnIfJavaTooOld(javaPath: String, minecraftVersion: String?, log: (String) -> Unit) {
        if (minecraftVersion == null) return
        val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
        if (need <= 17) return
        val have = try { EmbeddedJava.javaMajorVersion(javaPath) } catch (_: Exception) { null }
        if (have != null && have < need) {
            log("УВАГА: Minecraft $minecraftVersion потребує Java $need+, а знайдено Java $have.")
            log("Вбудований JRE17 запустить лише Minecraft <=1.20.4.")
            log("Варіанти: обери версію 1.20.4 (VANILLA/PAPER), або імпортуй JRE21 вручну в tools/jre21/ через Files.")
        }
    }

    private suspend fun tryDownloadAndExtractJre(context: Context, log: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!EmbeddedJava.isJreAutoInstallSupported()) {
                    log("Архітектура ${EmbeddedJava.abi()}: автозавантаження JRE не підтримується. Спроба системного java...")
                    return@withContext false
                }
                val toolsDir = EmbeddedPaths.toolsDir(context)
                val tmpFile = File(toolsDir, "jre-download.tar.xz")
                // Битий/чужий файл замість архіву — не докачуємо сміття, а качаємо заново.
                // Цілий архів лишаємо: докачка/повторне розпакування підуть без скачування.
                if (tmpFile.exists() && !TarExtractor.looksLikeXz(tmpFile)) {
                    log("Збережений архів пошкоджений — качаю заново...")
                    try { tmpFile.delete() } catch (_: Exception) {}
                }
                val urls = EmbeddedJava.jreDownloadUrls()
                var downloaded = false
                var lastError = ""
                for (url in urls) {
                    if (tmpFile.exists() && tmpFile.length() > 0) {
                        log("Продовжую завантаження JRE з ${tmpFile.length() / 1024 / 1024} MB (~40MB всього)...")
                    } else {
                        log("Завантаження вбудованого JRE (~40MB)...")
                    }
                    log("URL: $url")
                    // Мережа рве з'єднання (stall-и, timeout-и) — повторюємо з докачкою,
                    // щоб користувач не тицяв Start заради останнього мегабайта.
                    var err: String? = null
                    var attempt = 0
                    while (attempt < MAX_JRE_ATTEMPTS) {
                        attempt++
                        err = downloadFileDetailed(url, tmpFile) { pct ->
                            if (pct % 10 == 0) log("Завантаження JRE: $pct%")
                        }
                        if (err == null) break
                        if (!isRetryableDownloadError(err)) break
                        if (attempt < MAX_JRE_ATTEMPTS) {
                            val keptMb = try { tmpFile.length() / 1024 / 1024 } catch (_: Exception) { 0L }
                            log("Спроба $attempt/$MAX_JRE_ATTEMPTS перервана ($err). Збережено $keptMb MB — повторюю докачку...")
                            try { Thread.sleep(3000) } catch (_: Exception) {}
                        }
                    }
                    if (err == null) {
                        downloaded = true
                        break
                    } else {
                        lastError = err
                        log("Спроба не вдалася: $err")
                        // Частковий файл НЕ видаляємо — наступний Start докачає через Range.
                    }
                }
                if (!downloaded) {
                    val keptMb = try { tmpFile.length() / 1024 / 1024 } catch (_: Exception) { 0L }
                    log("Не вдалося завантажити JRE ($lastError).")
                    if (keptMb > 0) log("Збережено частковий файл ($keptMb MB) — натисни Start ще раз, завантаження продовжиться.")
                    log("Перевір Console вище: 404 = бите посилання, інший код = блокування мережі/VPN.")
                    return@withContext false
                }
                log("Розпакування JRE...")
                val dest = EmbeddedJava.bundledJreDir(context)
                // Прибираємо сміття невдалих спроб: частковий bin/java міг би
                // прикинутися робочим при наступному пошуку Java.
                try {
                    if (dest.exists() && (dest.listFiles()?.isNotEmpty() == true)) {
                        log("Чищу незавершену попередню установку JRE...")
                        dest.deleteRecursively()
                    }
                    dest.mkdirs()
                } catch (_: Exception) {}
                val extracted = extractTarXz(tmpFile, dest, log)
                // Архів видаляємо лише після УСПІШНОГО розпакування,
                // щоб повторна спроба не качала 37MB заново.
                if (extracted) {
                    try { tmpFile.delete() } catch (_: Exception) {}
                } else {
                    log("Архів збережено — повторна спроба піде без скачування.")
                }
                if (!extracted) {
                    log("Не вдалося розпакувати JRE. Деталі вище.")
                    return@withContext false
                }
                // +x на бінарники
                try {
                    File(dest, "bin/java").setExecutable(true)
                    File(dest, "bin").listFiles()?.forEach { it.setExecutable(true) }
                    File(dest, "lib").walkTopDown().filter { it.isFile && (it.name.endsWith(".so") || !it.extension.isNotEmpty()) }
                        .forEach { try { it.setExecutable(true) } catch (_: Exception) {} }
                } catch (_: Exception) {}
                EmbeddedJava.findJava(context) != null
            } catch (e: Exception) {
                Log.e(TAG, "JRE install failed", e)
                log("Помилка встановлення JRE: ${e.message}")
                false
            }
        }

    /**
     * Ubuntu rootfs для UBUNTU_SERVER. Повертає true якщо готовий.
     * На MVP: якщо rootfs нема - сервер все одно стартує як вбудований shell (sh -i),
     * а повний Ubuntu довантажується за потребою окремою кнопкою.
     */
    suspend fun ensureUbuntu(context: Context, log: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val rootfs = EmbeddedPaths.ubuntuRootfsDir(context)
            val marker = File(rootfs, ".installed")
            if (marker.exists()) return@withContext true
            // Поки не тягнемо 200MB+ rootfs автоматично - лише інформуємо.
            // Повне завантаження реалізується в installUbuntuFull().
            log("Вбудований shell готовий. Повний Ubuntu rootfs можна довантажити в налаштуваннях сервера.")
            false
        }

    suspend fun installUbuntuFull(context: Context, log: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            // Заглушка з чесним прогресом: завантаження Ubuntu 22.04 rootfs (~120MB)
            // TODO: підключити реальний URL rootfs + proot-бінарник під abi пристрою
            log("Завантаження Ubuntu rootfs... (функція в розробці, поки доступний вбудований shell)")
            false
        }

    // ---------- io helpers ----------

    /** Скільки разів повторювати обірване завантаження JRE (з докачкою) за один Start. */
    private const val MAX_JRE_ATTEMPTS = 3

    /**
     * Чи має сенс повторювати помилку завантаження.
     * 404/403/помилки навігації — фатальні (повтор не допоможе),
     * все інше (timeout, обрив, 5xx, 429) — транзитне.
     */
    private fun isRetryableDownloadError(err: String): Boolean {
        if (err.contains("HTTP 404") || err.contains("HTTP 403") || err.contains("HTTP 401")) return false
        if (err.contains("без Location") || err.contains("забагато редиректів")) return false
        return true
    }

    /**
     * Качає файл. Повертає null якщо успіх, або текст помилки з HTTP-кодом.
     * GitHub-релізи віддають 302 на objects.githubusercontent.com — редиректи обробляємо вручну.
     * Підтримує докачку (Range): обірваний файл продовжується, а не качається з нуля.
     */
    private fun downloadFileDetailed(url: String, dest: File, onProgress: (Int) -> Unit = {}): String? {
        try {
            dest.parentFile?.mkdirs()
            var current = url
            var redirects = 0
            var rangeRestarted = false
            while (true) {
                val startAt = if (dest.exists()) dest.length() else 0L
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    // Недовгий readTimeout: завислі stall-и виявляємо швидко,
                    // бо обірване автоматично повторюється з докачкою (див. вище).
                    readTimeout = 60000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "PocketHost/1.0 (Android; +https://github.com/anserver/pockethost)")
                    setRequestProperty("Accept", "*/*")
                    // Без keep-alive: повторне використання протухлих OkHttp-з'єднань дає
                    // "unexpected end of stream" на повільних мережах.
                    setRequestProperty("Connection", "close")
                    if (startAt > 0) setRequestProperty("Range", "bytes=$startAt-")
                }
                val code = try {
                    conn.connect()
                    conn.responseCode
                } catch (e: Exception) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    return "мережева помилка: ${e.message} (для $current)"
                }
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty()) return "HTTP $code redirect без Location (для $current)"
                    if (++redirects > 5) return "забагато редиректів (для $url)"
                    current = try { URL(URL(current), loc).toString() } catch (_: Exception) { loc }
                    continue
                }
                if (code == 416) {
                    // Діапазон незадовільний: локальний файл >= віддаленого. Приймаємо якщо не крихта.
                    conn.disconnect()
                    if (dest.exists() && dest.length() >= 1024 * 1024) return null
                    try { dest.delete() } catch (_: Exception) {}
                    if (rangeRestarted) return "HTTP 416, локальний файл битий — видалено, спробуй ще раз"
                    rangeRestarted = true
                    current = url
                    redirects = 0
                    continue
                }
                if (code !in 200..299) {
                    val body = try {
                        (conn.errorStream ?: conn.inputStream).bufferedReader().readText().take(200)
                    } catch (_: Exception) { "" }
                    conn.disconnect()
                    return "HTTP $code для $current. $body".trim()
                }
                // 206 = сервер продовжив з startAt (дописуємо), 200 = повний файл (перезаписуємо).
                val append = (code == HttpURLConnection.HTTP_PARTIAL && startAt > 0)
                val bodyTotal = conn.contentLengthLong
                val expectedTotal = if (append && bodyTotal > 0) startAt + bodyTotal else bodyTotal
                var done = if (append) startAt else 0L
                var lastPct = -1
                if (append && startAt > 0 && expectedTotal > 0) {
                    lastPct = ((done * 100) / expectedTotal).toInt().coerceIn(0, 100)
                }
                if (!append) {
                    try { if (dest.exists()) dest.delete() } catch (_: Exception) {}
                }
                try {
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(dest, append).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                if (n == 0) continue
                                output.write(buf, 0, n)
                                done += n
                                if (expectedTotal > 0) {
                                    val pct = ((done * 100) / expectedTotal).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        try { onProgress(pct) } catch (_: Exception) {}
                                    }
                                }
                            }
                            output.flush()
                            try { output.fd.sync() } catch (_: Exception) {}
                        }
                    }
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
                val finalLen = dest.length()
                if (expectedTotal > 0 && finalLen < expectedTotal) {
                    // Тихий обрив потоку (без винятку): файл НЕ чіпаємо — наступний Start докачає.
                    return "завантаження обірвалось (${finalLen / 1024 / 1024} з ${expectedTotal / 1024 / 1024} MB) — натисни Start ще раз, докачка продовжить"
                }
                if (!dest.exists() || finalLen < 1024 * 1024) {
                    val kb = finalLen / 1024
                    try { dest.delete() } catch (_: Exception) {}
                    return "файл обірваний ($kb KB)"
                }
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "download failed $url", e)
            return "виняток: ${e.message}"
        }
    }

    private fun extractTarXz(archive: File, dest: File, log: (String) -> Unit): Boolean {
        return try {
            // Аргументи списком щоб уникнути shell-quoting проблем з пробілами в шляхах.
            // Частина toybox-tar не має -J (xz), тому пробуємо -xJ, потім -xjf, потім чистий tar.
            val attempts = listOf(
                listOf("tar", "-xJf", archive.absolutePath, "-C", dest.absolutePath),
                listOf("tar", "-xjf", archive.absolutePath, "-C", dest.absolutePath),
                listOf("tar", "-xf", archive.absolutePath, "-C", dest.absolutePath)
            )
            for (args in attempts) {
                try {
                    val proc = ProcessBuilder(args)
                        .directory(dest)
                        .redirectErrorStream(true)
                        .start()
                    val out = proc.inputStream.bufferedReader().readText()
                    val code = proc.waitFor()
                    if (out.isNotBlank()) log(out.take(500))
                    if (code == 0) {
                        normalizeJreTopLevel(dest, log)
                        return true
                    }
                    log("tar ${args[1]} не вдався (код $code), пробую наступний...")
                } catch (e: Exception) {
                    log("tar запуск не вдався: ${e.message}")
                }
            }
            // Системного tar з xz-підтримкою нема — пробуємо вбудований
            // Java-розпаковувач (Tukaani XZ + свій tar-парсер, без зовнішніх бінарників).
            log("Системний tar без xz-підтримки. Пробую вбудований розпаковувач...")
            try {
                if (TarExtractor.extractXzTar(archive, dest, log)) {
                    normalizeJreTopLevel(dest, log)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "embedded extractor failed", e)
                log("Вбудований розпаковувач: ${e.message}")
            }
            log("Розпакувати JRE не вдалося. Спробуй системний java або ручну установку в tools/jre/ через Files.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
            log("Помилка розпакування: ${e.message}")
            false
        }
    }

    /** Якщо архів містить top-level папку (jre17/.../bin/java), піднімаємо вміст у dest. */
    private fun normalizeJreTopLevel(dest: File, log: (String) -> Unit) {
        try {
            if (File(dest, "bin/java").exists()) return
            val children = dest.listFiles()?.toList() ?: return
            if (children.size == 1 && children[0].isDirectory) {
                val top = children[0]
                if (File(top, "bin/java").exists()) {
                    log("Нормалізую структуру JRE (прибираю ${top.name}/)...")
                    top.listFiles()?.forEach { child ->
                        val target = File(dest, child.name)
                        if (!child.renameTo(target)) {
                            // fallback копіюванням для різних mount-ів
                            if (child.isDirectory) child.copyRecursively(target, overwrite = true)
                            else child.copyTo(target, overwrite = true)
                        }
                    }
                    try { top.deleteRecursively() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "normalize JRE failed", e)
        }
    }
}
