package com.pockethost.plugins.minecraft

import com.pockethost.core.model.Server
import com.pockethost.core.model.ServerConfig
import com.pockethost.core.model.ServerType

data class MinecraftServer(
    val server: Server,
    val minecraftVersion: String,
    val serverSoftware: MinecraftServerSoftware,
    val maxPlayers: Int = 20,
    val gameMode: GameMode = GameMode.SURVIVAL,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val pvp: Boolean = true,
    val onlineMode: Boolean = true,
    val motd: String = "A Minecraft Server",
    val viewDistance: Int = 10,
    val spawnProtection: Int = 16,
    val enableCommandBlock: Boolean = false,
    val plugins: List<String> = emptyList(),
    val mods: List<String> = emptyList()
)

enum class MinecraftServerSoftware {
    VANILLA,
    PAPER,
    SPIGOT,
    FABRIC,
    FORGE,
    PURPUR,
    BUKKIT
}

enum class GameMode {
    SURVIVAL,
    CREATIVE,
    ADVENTURE,
    SPECTATOR
}

enum class Difficulty {
    PEACEFUL,
    EASY,
    NORMAL,
    HARD
}

data class MinecraftPlayer(
    val uuid: String,
    val name: String,
    val isOnline: Boolean,
    val lastSeen: Long
)
