package com.anserver.core.repository

import com.anserver.core.model.Server
import com.anserver.core.model.ServerStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class ServerRepository {
    private val servers = MutableStateFlow<Map<String, Server>>(emptyMap())

    fun getAllServers(): Flow<List<Server>> = servers.map { it.values.toList() }

    fun getServer(id: String): Flow<Server?> = servers.map { it[id] }

    suspend fun createServer(server: Server) {
        servers.value = servers.value + (server.id to server)
    }

    suspend fun updateServer(server: Server) {
        servers.value = servers.value + (server.id to server)
    }

    suspend fun deleteServer(id: String) {
        servers.value = servers.value - id
    }

    suspend fun updateServerStatus(id: String, status: ServerStatus) {
        servers.value[id]?.let { server ->
            val updated = server.copy(
                status = status,
                lastStarted = if (status == ServerStatus.RUNNING) System.currentTimeMillis() else server.lastStarted
            )
            servers.value = servers.value + (id to updated)
        }
    }

    suspend fun updateServerPid(id: String, pid: Int) {
        servers.value[id]?.let { server ->
            val updated = server.copy(pid = pid)
            servers.value = servers.value + (id to updated)
        }
    }

    fun getRunningServers(): Flow<List<Server>> = servers.map { map ->
        map.values.filter { it.status == ServerStatus.RUNNING }
    }
}
