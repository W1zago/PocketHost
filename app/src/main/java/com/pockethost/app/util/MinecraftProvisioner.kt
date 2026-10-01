package com.pockethost.app.util

import com.pockethost.core.model.MinecraftLoader
import com.pockethost.core.model.Server
import com.pockethost.core.model.ServerType
import com.pockethost.plugins.minecraft.MinecraftServerManager
import java.io.File

object MinecraftProvisioner {

    suspend fun provision(
        server: Server,
        workingDir: String,
        log: (String) -> Unit
    ): Boolean {
        if (server.type != ServerType.MINECRAFT) return true
        // Default 1.20.4: РІР±СѓРґРѕРІР°РЅРёР№ JRE17 Р·Р°РїСѓСЃРєР°С” Р»РёС€Рµ Minecraft <=1.20.4.
        val version = server.config.minecraftVersion ?: "1.20.4"
        val loader = server.config.minecraftLoader
        val maxMem = server.config.maxMemory
        val minMem = server.config.minMemory
        val port = server.port

        val wdFile = File(workingDir)
        return try {
            wdFile.mkdirs()
            ensureDirectoryReady(workingDir, version, loader, maxMem, minMem, port, log)
        } catch (e: Exception) {
            log("Provision exception: ${e.message}")
            false
        }
    }

    private suspend fun ensureDirectoryReady(
        serverDir: String,
        version: String,
        loader: MinecraftLoader?,
        maxMemory: Int,
        minMemory: Int,
        port: Int,
        log: (String) -> Unit
    ): Boolean {
        val wdFile = File(serverDir)
        val parent = wdFile.parentFile?.absolutePath ?: serverDir
        val manager = MinecraftServerManager(parent)
        return manager.provisionIfNeeded(serverDir, version, loader, maxMemory, minMemory, port, log)
    }

    fun getExpectedJarName(loader: MinecraftLoader?): String {
        return when (loader) {
            MinecraftLoader.VANILLA -> "server.jar"
            MinecraftLoader.PAPER -> "paper.jar"
            MinecraftLoader.SPIGOT -> "spigot.jar"
            MinecraftLoader.FABRIC -> "fabric-server-launch.jar"
            MinecraftLoader.FORGE -> "forge.jar"
            MinecraftLoader.PURPUR -> "purpur.jar"
            MinecraftLoader.BUKKIT -> "craftbukkit.jar"
            MinecraftLoader.NEOFORGE -> "forge.jar"
            null -> "paper.jar"
        }
    }

    fun resolveStartCommand(server: Server, workingDir: String, javaPath: String): String {
        val loader = server.config.minecraftLoader
        val jarName = getExpectedJarName(loader)
        val candidates = listOf(jarName, "server.jar", "paper.jar", "purpur.jar")
        var effectiveJar: String? = null
        for (c in candidates) {
            if (File(workingDir, c).exists()) {
                effectiveJar = c
                break
            }
        }
        if (effectiveJar == null) effectiveJar = jarName
        val maxMem = server.config.maxMemory
        val minMem = server.config.minMemory
        // Android 14+ MTE: disable compressed oops to avoid "Pointer tag was truncated" hang
        return "$javaPath -Xmx${maxMem}M -Xms${minMem}M -XX:+UseSerialGC -Djava.awt.headless=true -Xss256k -jar $effectiveJar nogui"
    }
}



