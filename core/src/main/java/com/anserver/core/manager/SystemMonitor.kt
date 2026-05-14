package com.anserver.core.manager

import com.anserver.core.model.SystemStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class SystemMonitor {
    private val systemStats = MutableStateFlow(
        SystemStats(
            cpuUsagePercent = 0f,
            totalMemory = 0L,
            usedMemory = 0L,
            freeMemory = 0L,
            networkSent = 0L,
            networkReceived = 0L,
            activePorts = emptyList()
        )
    )

    fun getSystemStats(): Flow<SystemStats> = systemStats

    fun updateStats(stats: SystemStats) {
        systemStats.value = stats
    }

    fun updateCpuUsage(usage: Float) {
        systemStats.value = systemStats.value.copy(cpuUsagePercent = usage)
    }

    fun updateMemoryUsage(total: Long, used: Long, free: Long) {
        systemStats.value = systemStats.value.copy(
            totalMemory = total,
            usedMemory = used,
            freeMemory = free
        )
    }

    fun updateNetworkStats(sent: Long, received: Long) {
        systemStats.value = systemStats.value.copy(
            networkSent = sent,
            networkReceived = received
        )
    }

    fun updateActivePorts(ports: List<Int>) {
        systemStats.value = systemStats.value.copy(activePorts = ports)
    }
}
