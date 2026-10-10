package com.pockethost.core.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pockethost.core.model.Server
import com.pockethost.core.model.ServerStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore(name = "pockethost_servers")
private val SERVERS_KEY = stringPreferencesKey("servers_json")
private val gson = Gson()

class ServerRepository(private val context: Context) {
    private val servers = MutableStateFlow<Map<String, Server>>(emptyMap())
    // H6: SupervisorJob so one failed persist does not kill the whole scope + close() for cancellation.
    private val repoJob = SupervisorJob()
    private val scope = CoroutineScope(repoJob + Dispatchers.IO)

    /** Cancel the repository background tasks (call when the owner is destroyed). */
    fun close() {
        try { scope.cancel() } catch (_: Exception) {}
    }

    /** Alias for close() - scope cancellation. */
    fun cancel() = close()

    init {
        // Load persisted servers async with sanitization for Pure Termux
        scope.launch {
            try {
                val json = context.dataStore.data.map { it[SERVERS_KEY] }.first()
                if (!json.isNullOrEmpty()) {
                    val type = object : TypeToken<List<Server>>() {}.type
                    val list: List<Server> = gson.fromJson(json, type) ?: emptyList()
                    val sanitized = mutableMapOf<String, Server>()
                    var removedLegacy = 0
                    for (raw in list) {
                        try {
                            // Gson may set non-null fields to null via reflection (Samsung)
                            val rawWd: String? = try { raw.workDirUri } catch (_: Exception) { null }
                            val rawWorking: String? = try { raw.workingDirectory } catch (_: Exception) { null }
                            val rawDisplay: String? = try { raw.displayPath } catch (_: Exception) { null }
                            val rawTermux: String? = try { raw.termuxPath } catch (_: Exception) { null }
                            val rawName: String? = try { raw.name } catch (_: Exception) { null }
                            val rawPort: Int? = try { raw.port } catch (_: Exception) { null }
                            if (rawName.isNullOrEmpty()) { removedLegacy++; continue }
                            if (rawPort == null || rawPort !in 1..65535) { removedLegacy++; continue }
                            val wd = (rawWd?.takeIf { it.isNotEmpty() } ?: rawWorking ?: "").trim()
                            // Built-in terminal: accepts embedded://, content:// (legacy SAF) and
                            // internal /data/... paths. Empty ones are dropped.
                            if (wd.isEmpty()) {
                                removedLegacy++
                                continue
                            }
                            // Built-in terminal: canonical format embedded://<id>.
                            // Legacy content:// and absolute internal paths accepted as-is
                            // (launch still goes through EmbeddedPaths.resolveWorkDir),
                            // unknown schemes are migrated to embedded to avoid losing the server.
                            var normalizedWd = wd
                            val isEmbedded = wd.startsWith("embedded://")
                            val isSaf = wd.startsWith("content://")
                            val isFileScheme = wd.startsWith("file://")
                            val isAbsPath = wd.startsWith("/")
                            if (!isEmbedded && !isSaf && !isFileScheme && !isAbsPath) {
                                normalizedWd = "embedded://${raw.id}"
                            }
                            val safe = raw.copy(
                                workingDirectory = normalizedWd,
                                workDirUri = normalizedWd,
                                displayPath = rawDisplay ?: "",
                                termuxPath = rawTermux ?: ""
                            )
                            // If permission lost, keep but will be ERROR on start; don't filter now
                            sanitized[safe.id] = safe
                        } catch (_: Exception) {
                            removedLegacy++
                        }
                    }
                    if (removedLegacy > 0) {
                        android.util.Log.w("ServerRepository", "Removed $removedLegacy invalid servers")
                        // Persist cleaned list to avoid future crashes
                        try {
                            val cleanedJson = gson.toJson(sanitized.values.toList())
                            context.dataStore.edit { it[SERVERS_KEY] = cleanedJson }
                        } catch (_: Exception) {}
                        // Notify user on main thread
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                android.widget.Toast.makeText(context, "Removed $removedLegacy corrupted servers", android.widget.Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {}
                        }
                    }
                    servers.value = sanitized
                }
            } catch (e: Exception) {
                android.util.Log.e("ServerRepository", "Load failed, clearing", e)
                // Corrupted JSON -> clear
                try { context.dataStore.edit { it.remove(SERVERS_KEY) } } catch (_: Exception) {}
            }
        }
    }

    private suspend fun persist() {
        try {
            val json = gson.toJson(servers.value.values.toList())
            context.dataStore.edit { it[SERVERS_KEY] = json }
        } catch (_: Exception) {}
    }

    fun getAllServers(): Flow<List<Server>> = servers.map { it.values.toList() }

    fun getServer(id: String): Flow<Server?> = servers.map { it[id] }

    suspend fun createServer(server: Server) {
        servers.value = servers.value + (server.id to server)
        persist()
    }

    suspend fun updateServer(server: Server) {
        servers.value = servers.value + (server.id to server)
        persist()
    }

    suspend fun deleteServer(id: String) {
        servers.value = servers.value - id
        persist()
    }

    suspend fun updateServerStatus(id: String, status: ServerStatus) {
        servers.value[id]?.let { server ->
            val updated = server.copy(
                status = status,
                lastStarted = if (status == ServerStatus.RUNNING) System.currentTimeMillis() else server.lastStarted
            )
            servers.value = servers.value + (id to updated)
            persist()
        }
    }

    suspend fun updateTermuxExecutionId(id: String, executionId: Int?) {
        servers.value[id]?.let { server ->
            val updated = server.copy(termuxExecutionId = executionId)
            servers.value = servers.value + (id to updated)
            persist()
        }
    }

    fun getRunningServers(): Flow<List<Server>> = servers.map { map ->
        map.values.filter { it.status == ServerStatus.RUNNING }
    }

    suspend fun getByIdOnce(id: String): Server? = servers.value[id]

    suspend fun clearAll() {
        servers.value = emptyMap()
        try { context.dataStore.edit { it.remove(SERVERS_KEY) } } catch (_: Exception) {}
    }
}
