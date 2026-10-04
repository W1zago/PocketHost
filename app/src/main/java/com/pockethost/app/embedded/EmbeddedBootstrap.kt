package com.pockethost.app.embedded

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Р’Р±СѓРґРѕРІР°РЅРёР№ bootstrap: JRE + Ubuntu rootfs Р·Р°РІР°РЅС‚Р°Р¶СѓСЋС‚СЊСЃСЏ
 * Сѓ РїСЂРёРІР°С‚РЅСѓ РїР°РїРєСѓ РґРѕРґР°С‚РєСѓ. Р–РѕРґРЅРѕРіРѕ Р·РѕРІРЅС–С€РЅСЊРѕРіРѕ Termux РЅРµ С‚СЂРµР±Р°.
 *
 * Р’СЃРµ РІРёРєРѕРЅСѓС”С‚СЊСЃСЏ С‡РµСЂРµР· Р·РІРёС‡Р°Р№РЅРёР№ INTERNET (РІР¶Рµ С” РІ Manifest),
 * Р±РµР· RUN_COMMAND / SAF / storage РґРѕР·РІРѕР»С–РІ.
 */
object EmbeddedBootstrap {

    private const val TAG = "EmbeddedBootstrap"

    // URL Р±РµСЂРµРјРѕ Р· С”РґРёРЅРѕРіРѕ РґР¶РµСЂРµР»Р° EmbeddedJava.jreDownloadUrl() С‰РѕР± РЅРµ СЂРѕР·С…РѕРґРёР»РёСЃСЊ.
    // Р›РµРіРєРёР№ Android-Bionic JRE (aarch64). Shell-СЃРµСЂРІРµСЂРё РїСЂР°С†СЋСЋС‚СЊ С– Р±РµР· Java.

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
            // input.txt Р±С–Р»СЊС€Рµ РЅРµ РїРѕС‚СЂС–Р±РµРЅ (stdin-С‚СЂСѓР±Р°), Р°Р»Рµ Р»РёС€Р°С”РјРѕ РґР»СЏ СЃСѓРјС–СЃРЅРѕСЃС‚С– СЃС‚Р°СЂРёС… Р»РѕРіС–РІ
            workDir
        }

    /**
     * Р“Р°СЂР°РЅС‚СѓС” Java РґР»СЏ Minecraft. РџРѕРІРµСЂС‚Р°С” С€Р»СЏС… РґРѕ java Р°Р±Рѕ null.
     * РЇРєС‰Рѕ JRE РЅРµРјР° - РїСЂРѕР±СѓС” Р·Р°РІР°РЅС‚Р°Р¶РёС‚Рё РІ С„РѕРЅС– С– РїРёС€Рµ РїСЂРѕРіСЂРµСЃ Сѓ log().
     * РќРµ РєРёРґР°С” exception, РЅРµ РІРёРјР°РіР°С” Р¶РѕРґРЅРёС… РґРѕР·РІРѕР»С–РІ.
     * @param minecraftVersion РґР»СЏ РїРѕРїРµСЂРµРґР¶РµРЅРЅСЏ: 1.21+ РїРѕС‚СЂРµР±СѓС” Java 21, РІР±СѓРґРѕРІР°РЅРёР№ JRE17 С—С— РЅРµ Р·Р°РїСѓСЃС‚РёС‚СЊ.
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
                // Р”Р»СЏ 1.20.5+ РїРµСЂРµРІС–СЂСЏС”РјРѕ С‰Рѕ С†Рµ СЃРїСЂР°РІРґС– 21+: JRE17 С‚СѓС‚ РЅРµ Р·Р°РїСѓСЃС‚РёС‚СЊСЃСЏ.
                // findJavaForVersion РІР¶Рµ РІС–РґРґР°РІ jre21 СЏРєС‰Рѕ РІС–РЅ С”; СЏРєС‰Рѕ РЅРµРјР° вЂ”
                // С‡РµСЃРЅРѕ РєР°Р¶РµРјРѕ РґРµ Р№РѕРіРѕ РІР·СЏС‚Рё, Р° prepare РЅРёР¶С‡Рµ РІС–РґРјРѕРІРёС‚СЊ Р· Definitive.
                if (minecraftVersion != null) {
                    try {
                        val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
                        if (need > 17) {
                            val home = EmbeddedJava.runtimeHomeFor(existing)
                            val have = if (home != null) com.pockethost.app.jvm.PocketJvm.readJreMajor(home) else null
                            if (have != null && have < need) {
                                log("Java 21 РЅРµ Р·РЅР°Р№РґРµРЅР° (С” Р»РёС€Рµ Java $have). РџРѕРєР»Р°РґРё JRE21 РІСЂСѓС‡РЅСѓ С‡РµСЂРµР· Files Сѓ tools/jre21/ (С‰РѕР± Р·'СЏРІРёРІСЃСЏ tools/jre21/bin/java) С– РЅР°С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·. РЎС‚Р°Р±С–Р»СЊРЅРѕРіРѕ Р°РІС‚РѕР·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ JRE21 РЅРµРјР° вЂ” Pojav-СЂРµР»С–Р· Р°СЂС…С–РІРѕРІР°РЅРѕ.")
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
                    log("Р’Р±СѓРґРѕРІР°РЅРёР№ JRE РІСЃС‚Р°РЅРѕРІР»РµРЅРѕ: $now (${EmbeddedJava.javaVersion(now) ?: "?"})")
                    warnIfJavaTooOld(now, minecraftVersion, log)
                    return@withContext now
                }
            }
            log("Java РїРѕРєРё РЅРµРґРѕСЃС‚СѓРїРЅР°. Shell-СЃРµСЂРІРµСЂРё РїСЂР°С†СЋСЋС‚СЊ, Minecraft РїРѕС‚СЂРµР±СѓС” JRE.")
            log("РћР±С…С–Рґ: РїРѕРєР»Р°РґРё СЃРІС–Р№ JRE РІСЂСѓС‡РЅСѓ С‡РµСЂРµР· Files Сѓ tools/jre/ (С‰РѕР± Р·'СЏРІРёРІСЃСЏ tools/jre/bin/java) С– РЅР°С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·.")
            null
        }

    private fun warnIfJavaTooOld(javaPath: String, minecraftVersion: String?, log: (String) -> Unit) {
        if (minecraftVersion == null) return
        val need = EmbeddedJava.minJavaForMinecraft(minecraftVersion)
        if (need <= 17) return
        val have = try { EmbeddedJava.javaMajorVersion(javaPath) } catch (_: Exception) { null }
        if (have != null && have < need) {
            log("РЈР’РђР“Рђ: Minecraft $minecraftVersion РїРѕС‚СЂРµР±СѓС” Java $need+, Р° Р·РЅР°Р№РґРµРЅРѕ Java $have.")
            log("Р’Р±СѓРґРѕРІР°РЅРёР№ JRE17 Р·Р°РїСѓСЃС‚РёС‚СЊ Р»РёС€Рµ Minecraft <=1.20.4.")
            log("Р’Р°СЂС–Р°РЅС‚Рё: РѕР±РµСЂРё РІРµСЂСЃС–СЋ 1.20.4 (VANILLA/PAPER), Р°Р±Рѕ С–РјРїРѕСЂС‚СѓР№ JRE21 РІСЂСѓС‡РЅСѓ РІ tools/jre21/ С‡РµСЂРµР· Files.")
        }
    }

    private suspend fun tryDownloadAndExtractJre(context: Context, log: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (!EmbeddedJava.isJreAutoInstallSupported()) {
                    log("РђСЂС…С–С‚РµРєС‚СѓСЂР° ${EmbeddedJava.abi()}: Р°РІС‚РѕР·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ JRE РЅРµ РїС–РґС‚СЂРёРјСѓС”С‚СЊСЃСЏ. РЎРїСЂРѕР±Р° СЃРёСЃС‚РµРјРЅРѕРіРѕ java...")
                    return@withContext false
                }
                val toolsDir = EmbeddedPaths.toolsDir(context)
                val tmpFile = File(toolsDir, "jre-download.tar.xz")
                // Р‘РёС‚РёР№/С‡СѓР¶РёР№ С„Р°Р№Р» Р·Р°РјС–СЃС‚СЊ Р°СЂС…С–РІСѓ вЂ” РЅРµ РґРѕРєР°С‡СѓС”РјРѕ СЃРјС–С‚С‚СЏ, Р° РєР°С‡Р°С”РјРѕ Р·Р°РЅРѕРІРѕ.
                // Р¦С–Р»РёР№ Р°СЂС…С–РІ Р»РёС€Р°С”РјРѕ: РґРѕРєР°С‡РєР°/РїРѕРІС‚РѕСЂРЅРµ СЂРѕР·РїР°РєСѓРІР°РЅРЅСЏ РїС–РґСѓС‚СЊ Р±РµР· СЃРєР°С‡СѓРІР°РЅРЅСЏ.
                if (tmpFile.exists() && !TarExtractor.looksLikeXz(tmpFile)) {
                    log("Р—Р±РµСЂРµР¶РµРЅРёР№ Р°СЂС…С–РІ РїРѕС€РєРѕРґР¶РµРЅРёР№ вЂ” РєР°С‡Р°СЋ Р·Р°РЅРѕРІРѕ...")
                    try { tmpFile.delete() } catch (_: Exception) {}
                }
                val urls = EmbeddedJava.jreDownloadUrls()
                var downloaded = false
                var lastError = ""
                for (url in urls) {
                    if (tmpFile.exists() && tmpFile.length() > 0) {
                        log("РџСЂРѕРґРѕРІР¶СѓСЋ Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ JRE Р· ${tmpFile.length() / 1024 / 1024} MB (~40MB РІСЃСЊРѕРіРѕ)...")
                    } else {
                        log("Р—Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ РІР±СѓРґРѕРІР°РЅРѕРіРѕ JRE (~40MB)...")
                    }
                    log("URL: $url")
                    // РњРµСЂРµР¶Р° СЂРІРµ Р·'С”РґРЅР°РЅРЅСЏ (stall-Рё, timeout-Рё) вЂ” РїРѕРІС‚РѕСЂСЋС”РјРѕ Р· РґРѕРєР°С‡РєРѕСЋ,
                    // С‰РѕР± РєРѕСЂРёСЃС‚СѓРІР°С‡ РЅРµ С‚РёС†СЏРІ Start Р·Р°СЂР°РґРё РѕСЃС‚Р°РЅРЅСЊРѕРіРѕ РјРµРіР°Р±Р°Р№С‚Р°.
                    var err: String? = null
                    var attempt = 0
                    while (attempt < MAX_JRE_ATTEMPTS) {
                        attempt++
                        err = downloadFileDetailed(url, tmpFile) { pct ->
                            if (pct % 10 == 0) log("Р—Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ JRE: $pct%")
                        }
                        if (err == null) break
                        if (!isRetryableDownloadError(err)) break
                        if (attempt < MAX_JRE_ATTEMPTS) {
                            val keptMb = try { tmpFile.length() / 1024 / 1024 } catch (_: Exception) { 0L }
                            log("РЎРїСЂРѕР±Р° $attempt/$MAX_JRE_ATTEMPTS РїРµСЂРµСЂРІР°РЅР° ($err). Р—Р±РµСЂРµР¶РµРЅРѕ $keptMb MB вЂ” РїРѕРІС‚РѕСЂСЋСЋ РґРѕРєР°С‡РєСѓ...")
                            try { delay(3000) } catch (_: Exception) {}
                        }
                    }
                    if (err == null) {
                        downloaded = true
                        break
                    } else {
                        lastError = err
                        log("РЎРїСЂРѕР±Р° РЅРµ РІРґР°Р»Р°СЃСЏ: $err")
                        // Р§Р°СЃС‚РєРѕРІРёР№ С„Р°Р№Р» РќР• РІРёРґР°Р»СЏС”РјРѕ вЂ” РЅР°СЃС‚СѓРїРЅРёР№ Start РґРѕРєР°С‡Р°С” С‡РµСЂРµР· Range.
                    }
                }
                if (!downloaded) {
                    val keptMb = try { tmpFile.length() / 1024 / 1024 } catch (_: Exception) { 0L }
                    log("РќРµ РІРґР°Р»РѕСЃСЏ Р·Р°РІР°РЅС‚Р°Р¶РёС‚Рё JRE ($lastError).")
                    if (keptMb > 0) log("Р—Р±РµСЂРµР¶РµРЅРѕ С‡Р°СЃС‚РєРѕРІРёР№ С„Р°Р№Р» ($keptMb MB) вЂ” РЅР°С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·, Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ РїСЂРѕРґРѕРІР¶РёС‚СЊСЃСЏ.")
                    log("РџРµСЂРµРІС–СЂ Console РІРёС‰Рµ: 404 = Р±РёС‚Рµ РїРѕСЃРёР»Р°РЅРЅСЏ, С–РЅС€РёР№ РєРѕРґ = Р±Р»РѕРєСѓРІР°РЅРЅСЏ РјРµСЂРµР¶С–/VPN.")
                    return@withContext false
                }
                log("Р РѕР·РїР°РєСѓРІР°РЅРЅСЏ JRE...")
                val dest = EmbeddedJava.bundledJreDir(context)
                // РџСЂРёР±РёСЂР°С”РјРѕ СЃРјС–С‚С‚СЏ РЅРµРІРґР°Р»РёС… СЃРїСЂРѕР±: С‡Р°СЃС‚РєРѕРІРёР№ bin/java РјС–Рі Р±Рё
                // РїСЂРёРєРёРЅСѓС‚РёСЃСЏ СЂРѕР±РѕС‡РёРј РїСЂРё РЅР°СЃС‚СѓРїРЅРѕРјСѓ РїРѕС€СѓРєСѓ Java.
                try {
                    if (dest.exists() && (dest.listFiles()?.isNotEmpty() == true)) {
                        log("Р§РёС‰Сѓ РЅРµР·Р°РІРµСЂС€РµРЅСѓ РїРѕРїРµСЂРµРґРЅСЋ СѓСЃС‚Р°РЅРѕРІРєСѓ JRE...")
                        dest.deleteRecursively()
                    }
                    dest.mkdirs()
                } catch (_: Exception) {}
                val extracted = extractTarXz(tmpFile, dest, log)
                // РђСЂС…С–РІ РІРёРґР°Р»СЏС”РјРѕ Р»РёС€Рµ РїС–СЃР»СЏ РЈРЎРџР†РЁРќРћР“Рћ СЂРѕР·РїР°РєСѓРІР°РЅРЅСЏ,
                // С‰РѕР± РїРѕРІС‚РѕСЂРЅР° СЃРїСЂРѕР±Р° РЅРµ РєР°С‡Р°Р»Р° 37MB Р·Р°РЅРѕРІРѕ.
                if (extracted) {
                    try { tmpFile.delete() } catch (_: Exception) {}
                } else {
                    log("РђСЂС…С–РІ Р·Р±РµСЂРµР¶РµРЅРѕ вЂ” РїРѕРІС‚РѕСЂРЅР° СЃРїСЂРѕР±Р° РїС–РґРµ Р±РµР· СЃРєР°С‡СѓРІР°РЅРЅСЏ.")
                }
                if (!extracted) {
                    log("РќРµ РІРґР°Р»РѕСЃСЏ СЂРѕР·РїР°РєСѓРІР°С‚Рё JRE. Р”РµС‚Р°Р»С– РІРёС‰Рµ.")
                    return@withContext false
                }
                // +x РЅР° Р±С–РЅР°СЂРЅРёРєРё
                try {
                    File(dest, "bin/java").setExecutable(true)
                    File(dest, "bin").listFiles()?.forEach { it.setExecutable(true) }
                    File(dest, "lib").walkTopDown().filter { it.isFile && (it.name.endsWith(".so") || !it.extension.isNotEmpty()) }
                        .forEach { try { it.setExecutable(true) } catch (_: Exception) {} }
                } catch (_: Exception) {}
                EmbeddedJava.findJava(context) != null
            } catch (e: Exception) {
                Log.e(TAG, "JRE install failed", e)
                log("РџРѕРјРёР»РєР° РІСЃС‚Р°РЅРѕРІР»РµРЅРЅСЏ JRE: ${e.message}")
                false
            }
        }

    /**
     * Ubuntu rootfs РґР»СЏ UBUNTU_SERVER. РџРѕРІРµСЂС‚Р°С” true СЏРєС‰Рѕ РіРѕС‚РѕРІРёР№.
     * РќР° MVP: СЏРєС‰Рѕ rootfs РЅРµРјР° - СЃРµСЂРІРµСЂ РІСЃРµ РѕРґРЅРѕ СЃС‚Р°СЂС‚СѓС” СЏРє РІР±СѓРґРѕРІР°РЅРёР№ shell (sh -i),
     * Р° РїРѕРІРЅРёР№ Ubuntu РґРѕРІР°РЅС‚Р°Р¶СѓС”С‚СЊСЃСЏ Р·Р° РїРѕС‚СЂРµР±РѕСЋ РѕРєСЂРµРјРѕСЋ РєРЅРѕРїРєРѕСЋ.
     */
    /**
     * Ubuntu rootfs for UBUNTU_SERVER. Returns true when the full Ubuntu
     * (proot + 22.04 rootfs) is ready. Otherwise false: the server still
     * starts as the built-in shell (sh -i), and the full Ubuntu is fetched
     * on demand via installUbuntuFull() (auto on Start or by UI button).
     */
    suspend fun ensureUbuntu(context: Context, log: (String) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (EmbeddedUbuntu.isInstalled(context)) return@withContext true
            log("Full Ubuntu is not installed. Built-in shell works; Ubuntu 22.04 (~30MB) can be fetched on Start or from server settings.")
            false
        }

    /**
     * Downloads the full Ubuntu 22.04: 3 Termux .debs (proot + libtalloc +
     * libandroid-shmem) plus the official ubuntu-base rootfs tarball.
     * Resume via Range; archives are deleted only after SUCCESSFUL
     * extraction, so a retry continues without re-downloading.
     * Returns true when EmbeddedUbuntu.isInstalled() afterwards.
     */
    suspend fun installUbuntuFull(context: Context, log: (String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            try {
                if (EmbeddedUbuntu.isInstalled(context)) {
                    log("Ubuntu ${EmbeddedUbuntu.UBUNTU_VERSION} is already installed.")
                    return@withContext true
                }
                if (!EmbeddedUbuntu.isSupported()) {
                    log(EmbeddedUbuntu.supportHint())
                    return@withContext false
                }
                // Space: unpacked rootfs ~150MB + 27MB archive + headroom.
                try {
                    val free = android.os.StatFs(context.filesDir.absolutePath).availableBytes
                    if (free < 300L * 1024 * 1024) {
                        log("ERROR: low space (${free / 1024 / 1024}MB free, ~300MB needed for Ubuntu).")
                        return@withContext false
                    }
                } catch (_: Exception) {
                }

                val toolsDir = EmbeddedPaths.toolsDir(context)
                val debs = EmbeddedUbuntu.prootDebs()
                    ?: return@withContext false.also { log(EmbeddedUbuntu.supportHint()) }
                var idx = 0
                for ((fileName, url) in debs) {
                    idx++
                    val tmp = File(toolsDir, fileName)
                    if (tmp.exists() && !looksLikeDeb(tmp)) {
                        log("Cached $fileName is damaged, re-downloading...")
                        try {
                            tmp.delete()
                        } catch (_: Exception) {
                        }
                    }
                    if (!(tmp.exists() && looksLikeDeb(tmp) && tmp.length() > 4 * 1024)) {
                        log("Downloading proot ($idx/${debs.size}): $fileName...")
                        log("URL: $url")
                        var err: String? = null
                        var attempt = 0
                        while (attempt < MAX_UBUNTU_ATTEMPTS) {
                            attempt++
                            err = downloadFileDetailed(url, tmp, minBytes = 4 * 1024) { pct ->
                                if (pct % 25 == 0) log("proot $idx/${debs.size}: $pct%")
                            }
                            if (err == null) break
                            if (!isRetryableDownloadError(err)) break
                            if (attempt < MAX_UBUNTU_ATTEMPTS) {
                                log("Attempt $attempt/$MAX_UBUNTU_ATTEMPTS interrupted ($err). Retrying...")
                                try {
                                    delay(3000)
                                } catch (_: Exception) {
                                }
                            }
                        }
                        if (err != null) {
                            log("Failed to download $fileName ($err). Press Start again, download will resume.")
                            return@withContext false
                        }
                    } else {
                        log("proot ($idx/${debs.size}): $fileName already downloaded, extracting...")
                    }
                    val prefix = EmbeddedUbuntu.prootPrefix(context)
                    if (!DebExtractor.extractDataTar(tmp, prefix, 6, log)) {
                        log("Failed to extract $fileName. See details above.")
                        return@withContext false
                    }
                    try {
                        tmp.delete()
                    } catch (_: Exception) {
                    }
                }
                try {
                    EmbeddedUbuntu.prootBin(context).setExecutable(true)
                } catch (_: Exception) {
                }

                val rootfsUrl = EmbeddedUbuntu.rootfsUrl()
                    ?: return@withContext false.also { log(EmbeddedUbuntu.supportHint()) }
                val rootfsTmp = File(toolsDir, "ubuntu-rootfs.tar.gz")
                if (rootfsTmp.exists() && !TarExtractor.looksLikeGz(rootfsTmp)) {
                    log("Cached rootfs archive is damaged, re-downloading...")
                    try {
                        rootfsTmp.delete()
                    } catch (_: Exception) {
                    }
                }
                if (!(rootfsTmp.exists() && rootfsTmp.length() > 10L * 1024 * 1024)) {
                    log("Downloading Ubuntu ${EmbeddedUbuntu.UBUNTU_VERSION} rootfs (~27MB)...")
                    log("URL: $rootfsUrl")
                    var err: String? = null
                    var attempt = 0
                    while (attempt < MAX_UBUNTU_ATTEMPTS) {
                        attempt++
                        err = downloadFileDetailed(rootfsUrl, rootfsTmp) { pct ->
                            if (pct % 10 == 0) log("Downloading Ubuntu: $pct%")
                        }
                        if (err == null) break
                        if (!isRetryableDownloadError(err)) break
                        if (attempt < MAX_UBUNTU_ATTEMPTS) {
                            val keptMb = try {
                                rootfsTmp.length() / 1024 / 1024
                            } catch (_: Exception) {
                                0L
                            }
                            log("Attempt $attempt/$MAX_UBUNTU_ATTEMPTS interrupted ($err). Kept $keptMb MB, retrying...")
                            try {
                                delay(3000)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (err != null) {
                        log("Failed to download rootfs ($err). Press Start again, download will resume.")
                        return@withContext false
                    }
                } else {
                    log("Rootfs archive already downloaded, extracting...")
                }
                log("Extracting Ubuntu rootfs (~150MB, a few minutes)...")
                val ubuntuRoot = EmbeddedUbuntu.rootfsDir(context)
                try {
                    if (ubuntuRoot.exists() && !EmbeddedUbuntu.isInstalled(context)) {
                        ubuntuRoot.deleteRecursively()
                    }
                    ubuntuRoot.mkdirs()
                } catch (_: Exception) {
                }
                if (!TarExtractor.extractGzTar(rootfsTmp, ubuntuRoot, log)) {
                    log("Failed to extract rootfs. Archive kept, retry will skip downloading.")
                    return@withContext false
                }
                try {
                    rootfsTmp.delete()
                } catch (_: Exception) {
                }
                EmbeddedUbuntu.applyGuestFixups(context, log)
                try {
                    File(ubuntuRoot, "usr/bin/bash").setExecutable(true)
                    EmbeddedUbuntu.prootBin(context).setExecutable(true)
                } catch (_: Exception) {
                }
                if (EmbeddedUbuntu.isInstalled(context)) {
                    log("Ubuntu ${EmbeddedUbuntu.UBUNTU_VERSION} installed.")
                    true
                } else {
                    log("Install finished but verification failed. See details above.")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ubuntu install failed", e)
                log("Ubuntu install error: ${e.message}")
                false
            }
        }

    /** Quick ar-magic check (`!<arch>`) for .deb files. */
    private fun looksLikeDeb(file: File): Boolean {
        return try {
            if (!file.isFile || file.length() < 1024) return false
            java.io.FileInputStream(file).use { fis ->
                val magic = ByteArray(8)
                var off = 0
                while (off < magic.size) {
                    val n = fis.read(magic, off, magic.size - off)
                    if (n == -1) return false
                    if (n == 0) continue
                    off += n
                }
                String(magic, Charsets.US_ASCII).startsWith("!<arch>")
            }
        } catch (_: Exception) {
            false
        }
    }

    // ---------- io helpers ----------

    /** РЎРєС–Р»СЊРєРё СЂР°Р·С–РІ РїРѕРІС‚РѕСЂСЋРІР°С‚Рё РѕР±С–СЂРІР°РЅРµ Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ JRE (Р· РґРѕРєР°С‡РєРѕСЋ) Р·Р° РѕРґРёРЅ Start. */
    private const val MAX_JRE_ATTEMPTS = 3

    /** How many times to retry an interrupted Ubuntu download (with resume) per Start. */
    private const val MAX_UBUNTU_ATTEMPTS = 3

    /**
     * Р§Рё РјР°С” СЃРµРЅСЃ РїРѕРІС‚РѕСЂСЋРІР°С‚Рё РїРѕРјРёР»РєСѓ Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ.
     * 404/403/РїРѕРјРёР»РєРё РЅР°РІС–РіР°С†С–С— вЂ” С„Р°С‚Р°Р»СЊРЅС– (РїРѕРІС‚РѕСЂ РЅРµ РґРѕРїРѕРјРѕР¶Рµ),
     * РІСЃРµ С–РЅС€Рµ (timeout, РѕР±СЂРёРІ, 5xx, 429) вЂ” С‚СЂР°РЅР·РёС‚РЅРµ.
     */
    private fun isRetryableDownloadError(err: String): Boolean {
        if (err.contains("HTTP 404") || err.contains("HTTP 403") || err.contains("HTTP 401")) return false
        if (err.contains("Р±РµР· Location") || err.contains("Р·Р°Р±Р°РіР°С‚Рѕ СЂРµРґРёСЂРµРєС‚С–РІ")) return false
        return true
    }

    /**
     * РљР°С‡Р°С” С„Р°Р№Р». РџРѕРІРµСЂС‚Р°С” null СЏРєС‰Рѕ СѓСЃРїС–С…, Р°Р±Рѕ С‚РµРєСЃС‚ РїРѕРјРёР»РєРё Р· HTTP-РєРѕРґРѕРј.
     * GitHub-СЂРµР»С–Р·Рё РІС–РґРґР°СЋС‚СЊ 302 РЅР° objects.githubusercontent.com вЂ” СЂРµРґРёСЂРµРєС‚Рё РѕР±СЂРѕР±Р»СЏС”РјРѕ РІСЂСѓС‡РЅСѓ.
     * РџС–РґС‚СЂРёРјСѓС” РґРѕРєР°С‡РєСѓ (Range): РѕР±С–СЂРІР°РЅРёР№ С„Р°Р№Р» РїСЂРѕРґРѕРІР¶СѓС”С‚СЊСЃСЏ, Р° РЅРµ РєР°С‡Р°С”С‚СЊСЃСЏ Р· РЅСѓР»СЏ.
     */
    private fun downloadFileDetailed(
        url: String,
        dest: File,
        minBytes: Long = 1024 * 1024,
        onProgress: (Int) -> Unit = {}
    ): String? {
        try {
            dest.parentFile?.mkdirs()
            var current = url
            var redirects = 0
            var rangeRestarted = false
            while (true) {
                val startAt = if (dest.exists()) dest.length() else 0L
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    // РќРµРґРѕРІРіРёР№ readTimeout: Р·Р°РІРёСЃР»С– stall-Рё РІРёСЏРІР»СЏС”РјРѕ С€РІРёРґРєРѕ,
                    // Р±Рѕ РѕР±С–СЂРІР°РЅРµ Р°РІС‚РѕРјР°С‚РёС‡РЅРѕ РїРѕРІС‚РѕСЂСЋС”С‚СЊСЃСЏ Р· РґРѕРєР°С‡РєРѕСЋ (РґРёРІ. РІРёС‰Рµ).
                    readTimeout = 60000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "PocketHost/1.0 (Android; +https://github.com/PocketHost/pockethost)")
                    setRequestProperty("Accept", "*/*")
                    // Р‘РµР· keep-alive: РїРѕРІС‚РѕСЂРЅРµ РІРёРєРѕСЂРёСЃС‚Р°РЅРЅСЏ РїСЂРѕС‚СѓС…Р»РёС… OkHttp-Р·'С”РґРЅР°РЅСЊ РґР°С”
                    // "unexpected end of stream" РЅР° РїРѕРІС–Р»СЊРЅРёС… РјРµСЂРµР¶Р°С….
                    setRequestProperty("Connection", "close")
                    if (startAt > 0) setRequestProperty("Range", "bytes=$startAt-")
                }
                val code = try {
                    conn.connect()
                    conn.responseCode
                } catch (e: Exception) {
                    try { conn.disconnect() } catch (_: Exception) {}
                    return "РјРµСЂРµР¶РµРІР° РїРѕРјРёР»РєР°: ${e.message} (РґР»СЏ $current)"
                }
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrEmpty()) return "HTTP $code redirect Р±РµР· Location (РґР»СЏ $current)"
                    if (++redirects > 5) return "Р·Р°Р±Р°РіР°С‚Рѕ СЂРµРґРёСЂРµРєС‚С–РІ (РґР»СЏ $url)"
                    current = try { URL(URL(current), loc).toString() } catch (_: Exception) { loc }
                    continue
                }
                if (code == 416) {
                    // Р”С–Р°РїР°Р·РѕРЅ РЅРµР·Р°РґРѕРІС–Р»СЊРЅРёР№: Р»РѕРєР°Р»СЊРЅРёР№ С„Р°Р№Р» >= РІС–РґРґР°Р»РµРЅРѕРіРѕ. РџСЂРёР№РјР°С”РјРѕ СЏРєС‰Рѕ РЅРµ РєСЂРёС…С‚Р°.
                    conn.disconnect()
                    if (dest.exists() && dest.length() >= minBytes) return null
                    try { dest.delete() } catch (_: Exception) {}
                    if (rangeRestarted) return "HTTP 416, Р»РѕРєР°Р»СЊРЅРёР№ С„Р°Р№Р» Р±РёС‚РёР№ вЂ” РІРёРґР°Р»РµРЅРѕ, СЃРїСЂРѕР±СѓР№ С‰Рµ СЂР°Р·"
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
                    return "HTTP $code РґР»СЏ $current. $body".trim()
                }
                // 206 = СЃРµСЂРІРµСЂ РїСЂРѕРґРѕРІР¶РёРІ Р· startAt (РґРѕРїРёСЃСѓС”РјРѕ), 200 = РїРѕРІРЅРёР№ С„Р°Р№Р» (РїРµСЂРµР·Р°РїРёСЃСѓС”РјРѕ).
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
                    // РўРёС…РёР№ РѕР±СЂРёРІ РїРѕС‚РѕРєСѓ (Р±РµР· РІРёРЅСЏС‚РєСѓ): С„Р°Р№Р» РќР• С‡С–РїР°С”РјРѕ вЂ” РЅР°СЃС‚СѓРїРЅРёР№ Start РґРѕРєР°С‡Р°С”.
                    return "Р·Р°РІР°РЅС‚Р°Р¶РµРЅРЅСЏ РѕР±С–СЂРІР°Р»РѕСЃСЊ (${finalLen / 1024 / 1024} Р· ${expectedTotal / 1024 / 1024} MB) вЂ” РЅР°С‚РёСЃРЅРё Start С‰Рµ СЂР°Р·, РґРѕРєР°С‡РєР° РїСЂРѕРґРѕРІР¶РёС‚СЊ"
                }
                if (!dest.exists() || finalLen < minBytes) {
                    val kb = finalLen / 1024
                    try { dest.delete() } catch (_: Exception) {}
                    return "С„Р°Р№Р» РѕР±С–СЂРІР°РЅРёР№ ($kb KB)"
                }
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "download failed $url", e)
            return "РІРёРЅСЏС‚РѕРє: ${e.message}"
        }
    }

    private fun extractTarXz(archive: File, dest: File, log: (String) -> Unit): Boolean {
        return try {
            // РђСЂРіСѓРјРµРЅС‚Рё СЃРїРёСЃРєРѕРј С‰РѕР± СѓРЅРёРєРЅСѓС‚Рё shell-quoting РїСЂРѕР±Р»РµРј Р· РїСЂРѕР±С–Р»Р°РјРё РІ С€Р»СЏС…Р°С….
            // Р§Р°СЃС‚РёРЅР° toybox-tar РЅРµ РјР°С” -J (xz), С‚РѕРјСѓ РїСЂРѕР±СѓС”РјРѕ -xJ, РїРѕС‚С–Рј -xjf, РїРѕС‚С–Рј С‡РёСЃС‚РёР№ tar.
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
                    log("tar ${args[1]} РЅРµ РІРґР°РІСЃСЏ (РєРѕРґ $code), РїСЂРѕР±СѓСЋ РЅР°СЃС‚СѓРїРЅРёР№...")
                } catch (e: Exception) {
                    log("tar Р·Р°РїСѓСЃРє РЅРµ РІРґР°РІСЃСЏ: ${e.message}")
                }
            }
            // РЎРёСЃС‚РµРјРЅРѕРіРѕ tar Р· xz-РїС–РґС‚СЂРёРјРєРѕСЋ РЅРµРјР° вЂ” РїСЂРѕР±СѓС”РјРѕ РІР±СѓРґРѕРІР°РЅРёР№
            // Java-СЂРѕР·РїР°РєРѕРІСѓРІР°С‡ (Tukaani XZ + СЃРІС–Р№ tar-РїР°СЂСЃРµСЂ, Р±РµР· Р·РѕРІРЅС–С€РЅС–С… Р±С–РЅР°СЂРЅРёРєС–РІ).
            log("РЎРёСЃС‚РµРјРЅРёР№ tar Р±РµР· xz-РїС–РґС‚СЂРёРјРєРё. РџСЂРѕР±СѓСЋ РІР±СѓРґРѕРІР°РЅРёР№ СЂРѕР·РїР°РєРѕРІСѓРІР°С‡...")
            try {
                if (TarExtractor.extractXzTar(archive, dest, log)) {
                    normalizeJreTopLevel(dest, log)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "embedded extractor failed", e)
                log("Р’Р±СѓРґРѕРІР°РЅРёР№ СЂРѕР·РїР°РєРѕРІСѓРІР°С‡: ${e.message}")
            }
            log("Р РѕР·РїР°РєСѓРІР°С‚Рё JRE РЅРµ РІРґР°Р»РѕСЃСЏ. РЎРїСЂРѕР±СѓР№ СЃРёСЃС‚РµРјРЅРёР№ java Р°Р±Рѕ СЂСѓС‡РЅСѓ СѓСЃС‚Р°РЅРѕРІРєСѓ РІ tools/jre/ С‡РµСЂРµР· Files.")
            false
        } catch (e: Exception) {
            Log.e(TAG, "extract failed", e)
            log("РџРѕРјРёР»РєР° СЂРѕР·РїР°РєСѓРІР°РЅРЅСЏ: ${e.message}")
            false
        }
    }

    /** РЇРєС‰Рѕ Р°СЂС…С–РІ РјС–СЃС‚РёС‚СЊ top-level РїР°РїРєСѓ (jre17/.../bin/java), РїС–РґРЅС–РјР°С”РјРѕ РІРјС–СЃС‚ Сѓ dest. */
    private fun normalizeJreTopLevel(dest: File, log: (String) -> Unit) {
        try {
            if (File(dest, "bin/java").exists()) return
            val children = dest.listFiles()?.toList() ?: return
            if (children.size == 1 && children[0].isDirectory) {
                val top = children[0]
                if (File(top, "bin/java").exists()) {
                    log("РќРѕСЂРјР°Р»С–Р·СѓСЋ СЃС‚СЂСѓРєС‚СѓСЂСѓ JRE (РїСЂРёР±РёСЂР°СЋ ${top.name}/)...")
                    top.listFiles()?.forEach { child ->
                        val target = File(dest, child.name)
                        if (!child.renameTo(target)) {
                            // fallback РєРѕРїС–СЋРІР°РЅРЅСЏРј РґР»СЏ СЂС–Р·РЅРёС… mount-С–РІ
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
