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
    // H6: SupervisorJob С‰РѕР± РѕРґРЅР° РІРїР°РІС€Р° persist РЅРµ РІР±РёРІР°Р»Р° РІРµСЃСЊ scope + close() РґР»СЏ СЃРєР°СЃСѓРІР°РЅРЅСЏ.
    private val repoJob = SupervisorJob()
    private val scope = CoroutineScope(repoJob + Dispatchers.IO)

    /** РЎРєР°СЃСѓРІР°С‚Рё С„РѕРЅРѕРІС– Р·Р°РґР°С‡С– СЂРµРїРѕР·РёС‚РѕСЂС–СЋ (РІРёРєР»РёРєР°С‚Рё РїСЂРё Р·РЅРёС‰РµРЅРЅС– РІР»Р°СЃРЅРёРєР°). */
    fun close() {
        try { scope.cancel() } catch (_: Exception) {}
    }

    /** РђР»С–Р°СЃ РґР»СЏ close() вЂ” СЃРєР°СЃСѓРІР°РЅРЅСЏ scope. */
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
                            // Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»: РїСЂРёР№РјР°С”РјРѕ embedded://, content:// (legacy SAF) С‚Р°
                            // РІРЅСѓС‚СЂС–С€РЅС– С€Р»СЏС…Рё /data/... . РџРѕСЂРѕР¶РЅС– РІС–РґРєРёРґР°С”РјРѕ.
                            if (wd.isEmpty()) {
                                removedLegacy++
                                continue
                            }
                            // Р’Р±СѓРґРѕРІР°РЅРёР№ С‚РµСЂРјС–РЅР°Р»: РєР°РЅРѕРЅС–С‡РЅРёР№ С„РѕСЂРјР°С‚ embedded://<id>.
                            // Legacy content:// С‚Р° Р°Р±СЃРѕР»СЋС‚РЅС– РІРЅСѓС‚СЂС–С€РЅС– С€Р»СЏС…Рё РїСЂРёР№РјР°С”РјРѕ СЏРє С”
                            // (Р·Р°РїСѓСЃРє РІСЃРµ РѕРґРЅРѕ Р№РґРµ С‡РµСЂРµР· EmbeddedPaths.resolveWorkDir),
                            // РЅРµРІС–РґРѕРјС– СЃС…РµРјРё РјС–РіСЂСѓС”РјРѕ Сѓ embedded С‰РѕР± РЅРµ РІС‚СЂР°С‚РёС‚Рё СЃРµСЂРІРµСЂ.
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
                                android.widget.Toast.makeText(context, "Р’РёРґР°Р»РµРЅРѕ $removedLegacy РїРѕС€РєРѕРґР¶РµРЅРёС… СЃРµСЂРІРµСЂС–РІ", android.widget.Toast.LENGTH_LONG).show()
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
