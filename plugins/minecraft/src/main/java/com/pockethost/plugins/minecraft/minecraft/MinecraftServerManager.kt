package com.pockethost.plugins.minecraft

import android.util.Log
import com.pockethost.core.model.MinecraftLoader
import com.pockethost.core.model.Server
import com.pockethost.core.model.ServerConfig
import com.pockethost.core.model.ServerType
import com.pockethost.core.util.FileUtils
import com.pockethost.core.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MinecraftServerManager(private val baseDirectory: String) {

    suspend fun createMinecraftServer(
        name: String,
        version: String,
        software: MinecraftServerSoftware,
        port: Int = 25565,
        maxMemory: Int = 2048,
        minMemory: Int = 1024,
        onlineMode: Boolean = true,
        viewDistance: Int = 10,
        log: ((String) -> Unit)? = null
    ): MinecraftServer? = withContext(Dispatchers.IO) {
        try {
            val serverDir = "$baseDirectory/$name"
            FileUtils.createDirectory(serverDir)

            val availablePort = if (NetworkUtils.isPortAvailable(port)) {
                port
            } else {
                NetworkUtils.getAvailablePort(25565)
            }

            if (availablePort == -1) {
                log?.invoke("ERROR: No available port")
                return@withContext null
            }

            val jarName = getJarName(software)
            val jarFile = File("$serverDir/$jarName")
            if (!isValidServerJar(jarFile)) {
                log?.invoke("Downloading $software $version ...")
                val downloaded = downloadServerJar(serverDir, version, software, log)
                if (downloaded == null) {
                    log?.invoke("ERROR: Failed to download server jar for $software $version")
                    return@withContext null
                }
                log?.invoke("Downloaded $downloaded")
            } else {
                log?.invoke("Server jar already exists: $jarName (${jarFile.length() / 1024 / 1024} MB)")
            }

            createEula(serverDir)
            createServerProperties(serverDir, availablePort, onlineMode, viewDistance)
            createStartScript(serverDir, jarName, maxMemory, minMemory)

            val config = ServerConfig(
                startCommand = getStartCommand(jarName, maxMemory, minMemory),
                stopCommand = "stop",
                maxMemory = maxMemory,
                minMemory = minMemory,
                javaArgs = listOf("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled"),
                minecraftVersion = version,
                minecraftLoader = mapToLoader(software),
                onlineMode = onlineMode,
                viewDistance = viewDistance.coerceIn(2, 32)
            )

            val server = Server(
                name = name,
                type = ServerType.MINECRAFT,
                port = availablePort,
                workingDirectory = serverDir,
                config = config
            )

            MinecraftServer(
                server = server,
                minecraftVersion = version,
                serverSoftware = software
            )
        } catch (e: Exception) {
            Log.e(TAG, "createMinecraftServer failed", e)
            log?.invoke("EXCEPTION: ${e.message}")
            null
        }
    }

    suspend fun provisionIfNeeded(
        serverDir: String,
        version: String,
        loader: MinecraftLoader?,
        maxMemory: Int = 1024,
        minMemory: Int = 512,
        port: Int = 25565,
        log: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            FileUtils.createDirectory(serverDir)
            val software = mapFromLoader(loader)
            val jarName = getJarName(software)
            val jarFile = File("$serverDir/$jarName")
            // Also check generic server.jar for backward compat
            val genericJar = File("$serverDir/server.jar")
            // Broken/truncated jar (>1MB but not zip) would masquerade as ready and kill
            // start later with "no Main-Class". Verify integrity at once:
            // delete invalid so the next step downloads fresh.
            for (candidate in listOf(jarFile, genericJar)) {
                if (candidate.exists() && !isValidServerJar(candidate)) {
                    log("Found broken jar ${candidate.name} (${candidate.length() / 1024} KB) - deleting, downloading fresh...")
                    try { candidate.delete() } catch (_: Exception) {}
                }
            }
            val hasJar = isValidServerJar(jarFile) || isValidServerJar(genericJar)
            if (!hasJar) {
                log("Minecraft jar not found, downloading $software $version...")
                val downloaded = downloadServerJar(serverDir, version, software, log)
                if (downloaded == null) {
                    if (software == MinecraftServerSoftware.SPIGOT || software == MinecraftServerSoftware.FORGE || software == MinecraftServerSoftware.BUKKIT) {
                        log("ERROR: $software has no direct download. Drop the jar manually via Files (${getJarName(software)}) or pick PAPER/PURPUR/VANILLA/FABRIC.")
                    } else {
                        log("ERROR: Download failed for $software $version. See the cause above (HTTP code). With internet on - try VANILLA/PURPUR/FABRIC.")
                    }
                    return@withContext false
                }
            } else {
                val existingName = if (isValidServerJar(jarFile)) jarName else "server.jar"
                log("Found existing jar: $existingName")
            }
            // Ensure eula and properties
            val eulaFile = File("$serverDir/eula.txt")
            if (!eulaFile.exists()) {
                createEula(serverDir)
                log("Created eula.txt")
            }
            val propFile = File("$serverDir/server.properties")
            if (!propFile.exists()) {
                createServerProperties(serverDir, port)
                log("Created server.properties (port $port)")
            }
            // Ensure start script / correct jar in start command will be handled by ServerService
            val startSh = File("$serverDir/start.sh")
            if (!startSh.exists()) {
                val effectiveJar = if (jarFile.exists()) jarName else if (genericJar.exists()) "server.jar" else jarName
                createStartScript(serverDir, effectiveJar, maxMemory, minMemory)
                log("Created start.sh")
            }
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "provision failed", e)
            log("Provision error: ${e.message}")
            return@withContext false
        }
    }

    fun getJarName(software: MinecraftServerSoftware): String {
        return when (software) {
            MinecraftServerSoftware.VANILLA -> "server.jar"
            MinecraftServerSoftware.PAPER -> "paper.jar"
            MinecraftServerSoftware.SPIGOT -> "spigot.jar"
            MinecraftServerSoftware.FABRIC -> "fabric-server-launch.jar"
            MinecraftServerSoftware.FORGE -> "forge.jar"
            MinecraftServerSoftware.PURPUR -> "purpur.jar"
            MinecraftServerSoftware.BUKKIT -> "craftbukkit.jar"
        }
    }

    fun getStartCommand(jarName: String, maxMem: Int, minMem: Int): String {
        val javaPath = "java"
        return "$javaPath -Xmx${maxMem}M -Xms${minMem}M -XX:+UseSerialGC -Djava.awt.headless=true -Xss256k -jar $jarName nogui"
    }

    private fun mapToLoader(software: MinecraftServerSoftware): MinecraftLoader {
        return when (software) {
            MinecraftServerSoftware.VANILLA -> MinecraftLoader.VANILLA
            MinecraftServerSoftware.PAPER -> MinecraftLoader.PAPER
            MinecraftServerSoftware.SPIGOT -> MinecraftLoader.SPIGOT
            MinecraftServerSoftware.FABRIC -> MinecraftLoader.FABRIC
            MinecraftServerSoftware.FORGE -> MinecraftLoader.FORGE
            MinecraftServerSoftware.PURPUR -> MinecraftLoader.PURPUR
            MinecraftServerSoftware.BUKKIT -> MinecraftLoader.BUKKIT
        }
    }

    private fun mapFromLoader(loader: MinecraftLoader?): MinecraftServerSoftware {
        if (loader == null) return MinecraftServerSoftware.PAPER
        return when (loader) {
            MinecraftLoader.VANILLA -> MinecraftServerSoftware.VANILLA
            MinecraftLoader.PAPER -> MinecraftServerSoftware.PAPER
            MinecraftLoader.SPIGOT -> MinecraftServerSoftware.SPIGOT
            MinecraftLoader.FABRIC -> MinecraftServerSoftware.FABRIC
            MinecraftLoader.FORGE -> MinecraftServerSoftware.FORGE
            MinecraftLoader.PURPUR -> MinecraftServerSoftware.PURPUR
            MinecraftLoader.BUKKIT -> MinecraftServerSoftware.BUKKIT
            MinecraftLoader.NEOFORGE -> MinecraftServerSoftware.FORGE
        }
    }

    private suspend fun downloadServerJar(
        serverDir: String,
        version: String,
        software: MinecraftServerSoftware,
        log: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val jarName = getJarName(software)
            val jarPath = "$serverDir/$jarName"

            val downloadUrl = getDownloadUrl(version, software, log)
            if (downloadUrl == null) {
                log?.invoke("No download URL for $software $version. Try Paper or Vanilla. Spigot/Forge require manual install.")
                return@withContext null
            }

            log?.invoke("Fetching $downloadUrl")
            try {
                downloadFile(downloadUrl, File(jarPath), log)
            } catch (e: Exception) {
                log?.invoke("Download error: ${e.message}. Check internet, a VPN/provider may have blocked $downloadUrl")
                try { File(jarPath).takeIf { it.exists() && it.length() < 1024 * 1024 }?.delete() } catch (_: Exception) {}
                throw e
            }

            val len = File(jarPath).length()
            if (!isValidServerJar(File(jarPath))) {
                log?.invoke("Downloaded file invalid (${len / 1024} KB or broken zip). Possible connection cut - retry or use VANILLA/PURPUR.")
                try { File(jarPath).takeIf { it.exists() }?.delete() } catch (_: Exception) {}
                return@withContext null
            }

            jarName
        } catch (e: Exception) {
            Log.e(TAG, "downloadServerJar failed", e)
            log?.invoke("Download error: ${e.message}")
            null
        }
    }

    private suspend fun getDownloadUrl(version: String, software: MinecraftServerSoftware, log: ((String) -> Unit)?): String? = withContext(Dispatchers.IO) {
        return@withContext when (software) {
            MinecraftServerSoftware.VANILLA -> getVanillaUrl(version, log)
            MinecraftServerSoftware.PAPER -> getPaperUrl(version, log)
            MinecraftServerSoftware.PURPUR -> getPurpurUrl(version, log)
            MinecraftServerSoftware.FABRIC -> getFabricUrl(version, log)
            MinecraftServerSoftware.SPIGOT -> null
            MinecraftServerSoftware.FORGE -> null
            MinecraftServerSoftware.BUKKIT -> null
        }
    }

    private suspend fun getVanillaUrl(version: String, log: ((String) -> Unit)?): String? {
        return try {
            log?.invoke("Resolving Vanilla $version manifest...")
            var manifestJson: String? = null
            val manifestUrls = listOf(
                "https://piston-meta.mojang.com/mc/game/version_manifest.json",
                "https://launchermeta.mojang.com/mc/game/version_manifest.json",
                "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
            )
            var attempts = 0
            while (manifestJson == null && attempts < 6) {
                for (u in manifestUrls) {
                    try {
                        log?.invoke("Trying manifest: $u (attempt ${attempts+1})")
                        manifestJson = httpGet(u)
                        if (manifestJson != null) break
                    } catch (e: Exception) {
                        log?.invoke("Manifest $u failed: ${e.message}")
                    }
                }
                if (manifestJson == null) {
                    attempts++
                    if (attempts < 6) {
                        log?.invoke("Retrying manifest in ${attempts*2}s... ($attempts/6)")
                        delay((attempts*2000).toLong())
                    }
                }
            }
            if (manifestJson == null) {
                log?.invoke("All manifest URLs failed")
                return null
            }
            val manifest = JSONObject(manifestJson)
            val versions = manifest.getJSONArray("versions")
            var versionUrl: String? = null
            for (i in 0 until versions.length()) {
                val obj = versions.getJSONObject(i)
                if (obj.getString("id") == version) {
                    versionUrl = obj.getString("url")
                    break
                }
            }
            if (versionUrl == null) {
                log?.invoke("Version $version not found in manifest, trying latest release")
                // fallback to latest release
                val latest = manifest.getJSONObject("latest")
                val release = latest.getString("release")
                for (i in 0 until versions.length()) {
                    val obj = versions.getJSONObject(i)
                    if (obj.getString("id") == release) {
                        versionUrl = obj.getString("url")
                        break
                    }
                }
            }
            if (versionUrl == null) return null
            log?.invoke("Fetching version meta: $versionUrl")
            var versionMetaJson: String? = null
            repeat(3) { attempt ->
                try {
                    versionMetaJson = httpGet(versionUrl)
                    if (versionMetaJson != null) return@repeat
                } catch (e: Exception) {
                    log?.invoke("Version meta attempt ${attempt+1} failed: ${e.message}")
                }
                if (versionMetaJson == null && attempt < 2) delay(2000)
            }
            if (versionMetaJson == null) return null
            val versionMeta = JSONObject(versionMetaJson)
            val downloads = versionMeta.getJSONObject("downloads")
            val server = downloads.getJSONObject("server")
            val url = server.getString("url")
            log?.invoke("Vanilla URL: $url")
            url
        } catch (e: Exception) {
            Log.e(TAG, "getVanillaUrl failed", e)
            log?.invoke("Vanilla resolve failed: ${e.message}")
            null
        }
    }

    private fun getPaperUrl(version: String, log: ((String) -> Unit)?): String? {
        // PaperMC Fill v2 (api.papermc.io) returned 410 Gone on 01.07.2026.
        // Current API: Fill v3 -> fill.papermc.io
        // Docs: https://docs.papermc.io/misc/downloads-service/
        // GET /v3/projects/paper/versions/{version}/builds/latest
        //   -> downloads["server:default"].url (absolute URL on fill-data.papermc.io)
        return try {
            log?.invoke("Resolving Paper $version (Fill v3) ...")
            val latestJson = try {
                httpGet("https://fill.papermc.io/v3/projects/paper/versions/$version/builds/latest")
            } catch (e: Exception) {
                log?.invoke("Fill v3 latest failed: ${e.message}")
                null
            }
            if (latestJson != null) {
                try {
                    val obj = JSONObject(latestJson)
                    val channel = obj.optString("channel", "STABLE")
                    val downloads = obj.getJSONObject("downloads")
                    // Key changed in v3: application -> server:default
                    val downloadObj = when {
                        downloads.has("server:default") -> downloads.getJSONObject("server:default")
                        downloads.has("application") -> downloads.getJSONObject("application")
                        else -> null
                    }
                    val url = downloadObj?.optString("url", null)
                    if (!url.isNullOrEmpty()) {
                        // latest may be ALPHA/EXPERIMENTAL (e.g. 1.21.9-47):
                        // unstable is taken only when no STABLE exists below.
                        if (channel == "STABLE") {
                            log?.invoke("Paper URL (Fill v3): $url")
                            return url
                        } else {
                            log?.invoke("Fill v3 latest - $channel (not STABLE), looking for a stable build in the list...")
                        }
                    } else {
                        log?.invoke("Fill v3: no download url in response, trying builds list")
                    }
                } catch (e: Exception) {
                    log?.invoke("Fill v3 parse failed: ${e.message}, trying builds list")
                }
            }
            // Fallback: builds list (Fill v3 returns newest-first).
            // Pass 1 - STABLE only; pass 2 - anything (with warning).
            try {
                val buildsJson = httpGet("https://fill.papermc.io/v3/projects/paper/versions/$version/builds")
                if (buildsJson != null) {
                    val arr = org.json.JSONArray(buildsJson)
                    fun urlOf(buildObj: org.json.JSONObject): Pair<String?, String> {
                        val channel = buildObj.optString("channel", "STABLE")
                        val downloads = buildObj.optJSONObject("downloads") ?: return Pair(null, channel)
                        val downloadObj = when {
                            downloads.has("server:default") -> downloads.optJSONObject("server:default")
                            downloads.has("application") -> downloads.optJSONObject("application")
                            else -> null
                        }
                        return Pair(downloadObj?.optString("url", null)?.takeIf { it.isNotEmpty() }, channel)
                    }
                    for (i in 0 until arr.length()) {
                        val buildObj = arr.getJSONObject(i)
                        val (url, channel) = urlOf(buildObj)
                        if (channel != "STABLE" || url == null) continue
                        log?.invoke("Paper URL (Fill v3 list, build ${buildObj.optInt("id", -1)}, $channel): $url")
                        return url
                    }
                    for (i in 0 until arr.length()) {
                        val buildObj = arr.getJSONObject(i)
                        val (url, channel) = urlOf(buildObj)
                        if (url != null) {
                            log?.invoke("WARNING: no STABLE build, taking $channel build ${buildObj.optInt("id", -1)}: $url")
                            return url
                        }
                    }
                    log?.invoke("Fill v3 builds list: no suitable build for $version")
                }
            } catch (e: Exception) {
                log?.invoke("Fill v3 builds list failed: ${e.message}")
            }
            log?.invoke("No Paper build for $version. Check the version list: does Paper support $version? Try VANILLA/PURPUR/FABRIC.")
            null
        } catch (e: Exception) {
            Log.e(TAG, "getPaperUrl failed", e)
            log?.invoke("Paper resolve failed: ${e.message}")
            null
        }
    }

    private fun getPurpurUrl(version: String, log: ((String) -> Unit)?): String? {
        return try {
            log?.invoke("Resolving Purpur $version ...")
            val verJson = httpGet("https://api.purpurmc.org/v2/purpur/$version")
            if (verJson != null) {
                val obj = JSONObject(verJson)
                val builds = obj.getJSONObject("builds")
                val latest = builds.getString("latest")
                val url = "https://api.purpurmc.org/v2/purpur/$version/$latest/download"
                log?.invoke("Purpur URL: $url")
                return url
            }
            // fallback
            "https://api.purpurmc.org/v2/purpur/$version/latest/download"
        } catch (e: Exception) {
            Log.e(TAG, "getPurpurUrl failed", e)
            log?.invoke("Purpur resolve failed: ${e.message}")
            "https://api.purpurmc.org/v2/purpur/$version/latest/download"
        }
    }

    private fun getFabricUrl(version: String, log: ((String) -> Unit)?): String? {
        return try {
            log?.invoke("Resolving Fabric for $version ...")
            // Get loader versions
            val loadersJson = httpGet("https://meta.fabricmc.net/v2/versions/loader/$version")
            if (loadersJson != null) {
                val arr = org.json.JSONArray(loadersJson)
                if (arr.length() > 0) {
                    val latest = arr.getJSONObject(0)
                    val loader = latest.getJSONObject("loader")
                    val loaderVersion = loader.getString("version")
                    log?.invoke("Fabric loader $loaderVersion for $version")
                    val url = "https://meta.fabricmc.net/v2/versions/loader/$version/$loaderVersion/server/jar"
                    log?.invoke("Fabric URL: $url")
                    return url
                }
            }
            // fallback global loader
            val globalLoaders = httpGet("https://meta.fabricmc.net/v2/versions/loader")
            if (globalLoaders != null) {
                val arr = org.json.JSONArray(globalLoaders)
                if (arr.length() > 0) {
                    val loaderVersion = arr.getJSONObject(0).getString("version")
                    val url = "https://meta.fabricmc.net/v2/versions/loader/$version/$loaderVersion/server/jar"
                    return url
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "getFabricUrl failed", e)
            log?.invoke("Fabric resolve failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MinecraftServerManager"
        // Fill v3 requires an identifying User-Agent with contact, otherwise 403/rate-limit.
        // https://docs.papermc.io/misc/downloads-service/
        private const val USER_AGENT = "PocketHost/1.0 (Android; +https://github.com/PocketHost/pockethost)"

        /**
         * Minimal server-jar integrity: size + valid zip central directory.
         * An interrupted download (>1MB of garbage) fails ZipFile open - such a file
         * must be deleted and downloaded again, not taken to JVM start.
         */
        fun isValidServerJar(f: File): Boolean {
            return try {
                if (!f.isFile || f.length() < 1024 * 1024) return false
                java.util.zip.ZipFile(f).use { z -> z.size() > 0 }
                true
            } catch (_: Exception) { false }
        }
    }

    private fun httpGet(urlStr: String): String? {
        var current = urlStr
        var redirects = 0
        while (redirects < 6) {
            val url = URL(current)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("HTTP $code redirect without Location for $current")
                    // Location may be relative
                    current = try { URL(URL(current), loc).toString() } catch (_: Exception) { loc }
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    val errBody = try {
                        (conn.errorStream ?: conn.inputStream).bufferedReader().readText().take(300)
                    } catch (_: Exception) { "" }
                    throw IllegalStateException("HTTP $code for $current. $errBody".trim())
                }
                return conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects for $urlStr")
    }

    private fun downloadFile(urlStr: String, target: File, log: ((String) -> Unit)?) {
        var current = urlStr
        var redirects = 0
        while (true) {
            val url = URL(current)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 90000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.instanceFollowRedirects = false
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect without Location (HTTP $code) for $current")
                if (++redirects > 5) throw IllegalStateException("Too many redirects for $urlStr")
                current = try { URL(URL(current), loc).toString() } catch (_: Exception) { loc }
                log?.invoke("Redirect -> $current")
                conn.disconnect()
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                val errBody = try {
                    (conn.errorStream ?: conn.inputStream).bufferedReader().readText().take(300)
                } catch (_: Exception) { "" }
                conn.disconnect()
                throw IllegalStateException("HTTP $code for $current. $errBody".trim())
            }
            val total = conn.contentLengthLong
            log?.invoke("Downloading ${target.name} (${if (total > 0) "${total / 1024 / 1024} MB" else "unknown size"})...")
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    var lastLog = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0 && downloaded - lastLog > 2 * 1024 * 1024) {
                            val percent = (downloaded * 100 / total).toInt()
                            log?.invoke("Download $percent% (${downloaded / 1024 / 1024} MB)")
                            lastLog = downloaded
                        }
                    }
                }
            }
            conn.disconnect()
            log?.invoke("Download complete: ${target.length() / 1024 / 1024} MB")
            return
        }
    }

    private fun createEula(serverDir: String) {
        val eulaContent = """
            #By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).
            eula=true
        """.trimIndent()

        FileUtils.writeFile("$serverDir/eula.txt", eulaContent)
    }

    private fun createServerProperties(
        serverDir: String,
        port: Int,
        onlineMode: Boolean = true,
        viewDistance: Int = 10
    ) {
        val properties = """
            server-port=$port
            max-players=20
            gamemode=survival
            difficulty=normal
            pvp=true
            online-mode=$onlineMode
            motd=A Minecraft Server
            view-distance=${viewDistance.coerceIn(2, 32)}
            spawn-protection=16
            enable-command-block=false
            allow-flight=false
            white-list=false
            enforce-whitelist=false
            spawn-monsters=true
            spawn-animals=true
            generate-structures=true
            level-seed=
            level-type=minecraft\:normal
        """.trimIndent()

        FileUtils.writeFile("$serverDir/server.properties", properties)
    }

    private fun createStartScript(serverDir: String, jarFile: String, maxMem: Int, minMem: Int) {
        val javaPath = "java"
        val script = """
            #!/bin/sh
            $javaPath -Xms${minMem}M -Xmx${maxMem}M -XX:+UseSerialGC -Djava.awt.headless=true -Xss256k -jar $jarFile nogui
        """.trimIndent()

        FileUtils.writeFile("$serverDir/start.sh", script)

        val scriptFile = File("$serverDir/start.sh")
        scriptFile.setExecutable(true)
    }

    fun updateServerProperties(minecraftServer: MinecraftServer) {
        val serverDir = minecraftServer.server.workingDirectory
        val properties = """
            server-port=${minecraftServer.server.port}
            max-players=${minecraftServer.maxPlayers}
            gamemode=${minecraftServer.gameMode.name.lowercase()}
            difficulty=${minecraftServer.difficulty.name.lowercase()}
            pvp=${minecraftServer.pvp}
            online-mode=${minecraftServer.onlineMode}
            motd=${minecraftServer.motd}
            view-distance=${minecraftServer.viewDistance.coerceIn(2, 32)}
            spawn-protection=${minecraftServer.spawnProtection}
            enable-command-block=${minecraftServer.enableCommandBlock}
        """.trimIndent()

        FileUtils.writeFile("$serverDir/server.properties", properties)
    }

    fun installPlugin(serverDir: String, pluginUrl: String, pluginName: String): Boolean {
        return try {
            val pluginsDir = "$serverDir/plugins"
            FileUtils.createDirectory(pluginsDir)
            val file = File("$pluginsDir/$pluginName")
            downloadFile(pluginUrl, file, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "installPlugin failed", e)
            false
        }
    }

    fun installMod(serverDir: String, modUrl: String, modName: String): Boolean {
        return try {
            val modsDir = "$serverDir/mods"
            FileUtils.createDirectory(modsDir)
            val file = File("$modsDir/$modName")
            downloadFile(modUrl, file, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "installMod failed", e)
            false
        }
    }

    fun getOnlinePlayers(serverDir: String): List<MinecraftPlayer> {
        return emptyList()
    }

    fun executeCommand(serverDir: String, command: String): Boolean {
        return try {
            true
        } catch (e: Exception) {
            false
        }
    }
}






