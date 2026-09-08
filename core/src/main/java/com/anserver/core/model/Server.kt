package com.anserver.core.model

import java.util.UUID

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ServerType,
    val port: Int,
    val workingDirectory: String = "", // legacy alias, use workDirUri
    val workDirUri: String = "",
    val displayPath: String = "",
    val termuxPath: String = "",
    val status: ServerStatus = ServerStatus.STOPPED,
    val autoStart: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastStarted: Long? = null,
    val config: ServerConfig = ServerConfig(),
    val termuxExecutionId: Int? = null
) {
    // Compatibility: if workDirUri empty, fallback to workingDirectory; handle Gson null via reflection
    val effectiveWorkDirUri: String get() = try {
        val wd = try { workDirUri } catch (_: Exception) { null }
        val legacy = try { workingDirectory } catch (_: Exception) { null }
        (wd?.takeIf { it.isNotEmpty() } ?: legacy ?: "")
    } catch (_: Exception) { "" }
}

enum class ServerType {
    MINECRAFT,
    UBUNTU_SERVER
}

enum class ServerStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

data class ServerConfig(
    val environment: Map<String, String> = emptyMap(),
    val startCommand: String = "",
    val stopCommand: String = "",
    val maxMemory: Int = 1024,
    val minMemory: Int = 512,
    val javaArgs: List<String> = emptyList(),
    val customArgs: Map<String, String> = emptyMap(),
    // Minecraft specific
    val minecraftVersion: String? = null,
    val minecraftLoader: MinecraftLoader? = null,
    val loaderVersion: String? = null,
    // Nullable для Gson-сумісності зі старими збереженнями (відсутнє поле -> null -> дефолт).
    // onlineMode: null -> true (перевірка ліцензії Mojang увімкнена).
    // false = "піратка": заходити можуть гравці без ліцензії.
    val onlineMode: Boolean? = null,
    // viewDistance: null -> 10 (дефолт сервера). Діапазон 2..32.
    val viewDistance: Int? = null
) {
    val effectiveOnlineMode: Boolean get() = onlineMode ?: true
    val effectiveViewDistance: Int get() = (viewDistance ?: 10).coerceIn(2, 32)
}

enum class MinecraftLoader {
    VANILLA,
    FORGE,
    FABRIC,
    NEOFORGE,
    PAPER,
    SPIGOT,
    BUKKIT,
    PURPUR
}
