package com.anserver.plugins.minecraft

import com.anserver.core.model.Server
import com.anserver.core.model.ServerConfig
import com.anserver.core.model.ServerType
import com.anserver.core.util.FileUtils
import com.anserver.core.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

class MinecraftServerManager(private val baseDirectory: String) {

    suspend fun createMinecraftServer(
        name: String,
        version: String,
        software: MinecraftServerSoftware,
        port: Int = 25565
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
                return@withContext null
            }

            val jarFile = downloadServerJar(serverDir, version, software)
            if (jarFile == null) {
                return@withContext null
            }

            createEula(serverDir)
            createServerProperties(serverDir, availablePort)
            createStartScript(serverDir, jarFile)

            val config = ServerConfig(
                startCommand = "sh start.sh",
                stopCommand = "stop",
                maxMemory = 2048,
                minMemory = 1024,
                javaArgs = listOf("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled")
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
            e.printStackTrace()
            null
        }
    }

    private suspend fun downloadServerJar(
        serverDir: String,
        version: String,
        software: MinecraftServerSoftware
    ): String? = withContext(Dispatchers.IO) {
        try {
            val jarName = "server.jar"
            val jarPath = "$serverDir/$jarName"

            val downloadUrl = getDownloadUrl(version, software)
            if (downloadUrl == null) {
                return@withContext null
            }

            val url = URL(downloadUrl)
            val connection = url.openConnection()
            connection.connect()

            val input = connection.getInputStream()
            val output = File(jarPath).outputStream()

            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }

            jarName
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getDownloadUrl(version: String, software: MinecraftServerSoftware): String? {
        return when (software) {
            MinecraftServerSoftware.VANILLA -> {
                "https://launcher.mojang.com/v1/objects/server.jar"
            }
            MinecraftServerSoftware.PAPER -> {
                "https://api.papermc.io/v2/projects/paper/versions/$version/builds/latest/downloads/paper-$version.jar"
            }
            MinecraftServerSoftware.SPIGOT -> {
                null
            }
            MinecraftServerSoftware.FABRIC -> {
                "https://meta.fabricmc.net/v2/versions/loader/$version/latest/server/jar"
            }
            MinecraftServerSoftware.FORGE -> {
                null
            }
            MinecraftServerSoftware.PURPUR -> {
                "https://api.purpurmc.org/v2/purpur/$version/latest/download"
            }
            MinecraftServerSoftware.BUKKIT -> {
                null
            }
        }
    }

    private fun createEula(serverDir: String) {
        val eulaContent = """
            #By changing the setting below to TRUE you are indicating your agreement to our EULA (https://account.mojang.com/documents/minecraft_eula).
            eula=true
        """.trimIndent()

        FileUtils.writeFile("$serverDir/eula.txt", eulaContent)
    }

    private fun createServerProperties(serverDir: String, port: Int) {
        val properties = """
            server-port=$port
            max-players=20
            gamemode=survival
            difficulty=normal
            pvp=true
            online-mode=true
            motd=A Minecraft Server
            view-distance=10
            spawn-protection=16
            enable-command-block=false
        """.trimIndent()

        FileUtils.writeFile("$serverDir/server.properties", properties)
    }

    private fun createStartScript(serverDir: String, jarFile: String) {
        // Find available Java executable
        val javaPath = com.anserver.core.util.JavaUtils.findJavaExecutable() ?: "java"

        val script = """
            #!/bin/sh
            $javaPath -Xms1024M -Xmx2048M -XX:+UseG1GC -XX:+ParallelRefProcEnabled -jar $jarFile nogui
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
            view-distance=${minecraftServer.viewDistance}
            spawn-protection=${minecraftServer.spawnProtection}
            enable-command-block=${minecraftServer.enableCommandBlock}
        """.trimIndent()

        FileUtils.writeFile("$serverDir/server.properties", properties)
    }

    fun installPlugin(serverDir: String, pluginUrl: String, pluginName: String): Boolean {
        return try {
            val pluginsDir = "$serverDir/plugins"
            FileUtils.createDirectory(pluginsDir)

            val url = URL(pluginUrl)
            val connection = url.openConnection()
            connection.connect()

            val input = connection.getInputStream()
            val output = File("$pluginsDir/$pluginName").outputStream()

            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun installMod(serverDir: String, modUrl: String, modName: String): Boolean {
        return try {
            val modsDir = "$serverDir/mods"
            FileUtils.createDirectory(modsDir)

            val url = URL(modUrl)
            val connection = url.openConnection()
            connection.connect()

            val input = connection.getInputStream()
            val output = File("$modsDir/$modName").outputStream()

            input.use { i ->
                output.use { o ->
                    i.copyTo(o)
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
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
