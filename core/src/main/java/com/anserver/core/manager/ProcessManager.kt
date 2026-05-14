package com.anserver.core.manager

import com.anserver.core.model.ProcessInfo
import com.anserver.core.model.SystemStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class ProcessManager {
    private val processes = MutableStateFlow<Map<Int, ProcessInfo>>(emptyMap())

    fun registerProcess(pid: Int, serverId: String) {
        val processInfo = ProcessInfo(
            pid = pid,
            serverId = serverId,
            startTime = System.currentTimeMillis()
        )
        processes.value = processes.value + (pid to processInfo)
    }

    fun unregisterProcess(pid: Int) {
        processes.value = processes.value - pid
    }

    fun getProcess(pid: Int): Flow<ProcessInfo?> = processes.map { it[pid] }

    fun getAllProcesses(): Flow<List<ProcessInfo>> = processes.map { it.values.toList() }

    fun updateProcessStats(pid: Int, cpuUsage: Float, memoryUsage: Long, threadCount: Int) {
        processes.value[pid]?.let { process ->
            val updated = process.copy(
                cpuUsage = cpuUsage,
                memoryUsage = memoryUsage,
                threadCount = threadCount
            )
            processes.value = processes.value + (pid to updated)
        }
    }

    fun markProcessDead(pid: Int) {
        processes.value[pid]?.let { process ->
            val updated = process.copy(isAlive = false)
            processes.value = processes.value + (pid to updated)
        }
    }
}
