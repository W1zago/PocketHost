package com.pockethost.core.model

data class ProcessInfo(
    val pid: Int,
    val serverId: String,
    val startTime: Long,
    val cpuUsage: Float = 0f,
    val memoryUsage: Long = 0L,
    val threadCount: Int = 0,
    val isAlive: Boolean = true
)

data class SystemStats(
    val cpuUsagePercent: Float,
    val totalMemory: Long,
    val usedMemory: Long,
    val freeMemory: Long,
    val networkSent: Long,
    val networkReceived: Long,
    val activePorts: List<Int>,
    val timestamp: Long = System.currentTimeMillis()
)

data class ServerMetrics(
    val serverId: String,
    val cpuUsage: Float,
    val memoryUsage: Long,
    val networkIn: Long,
    val networkOut: Long,
    val uptime: Long,
    val requestCount: Long = 0,
    val errorCount: Long = 0
)
