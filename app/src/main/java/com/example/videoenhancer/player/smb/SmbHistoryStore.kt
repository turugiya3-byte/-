package com.example.videoenhancer.player.smb

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.smbDataStore: DataStore<Preferences> by preferencesDataStore(name = "smb_server_history")

@Serializable
data class SavedSmbServer(
    val id: String,
    val host: String,
    val shareName: String,
    val username: String,
    val password: String = ""
) {
    fun toServerConfig() = SmbServerConfig(host, shareName, username, password)
}

class SmbHistoryStore(private val context: Context) {
    companion object {
        private val HISTORY_KEY = stringPreferencesKey("saved_servers_json")
        private const val MAX_HISTORY_COUNT = 10
        private val json = Json { ignoreUnknownKeys = true }
    }

    val savedServersFlow: Flow<List<SavedSmbServer>> = context.smbDataStore.data.map { prefs ->
        val raw = prefs[HISTORY_KEY] ?: return@map emptyList()
        try {
            json.decodeFromString<List<SavedSmbServer>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun saveServer(config: SmbServerConfig, rememberPassword: Boolean) {
        val serverId = "${config.username}@${config.host}/${config.shareName}"
        val entry = SavedSmbServer(
            id = serverId,
            host = config.host,
            shareName = config.shareName,
            username = config.username,
            password = if (rememberPassword) config.password else ""
        )
        context.smbDataStore.edit { prefs ->
            val current = prefs[HISTORY_KEY]?.let {
                try {
                    json.decodeFromString<List<SavedSmbServer>>(it)
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: emptyList()

            val updated = buildList {
                add(entry)
                addAll(current.filterNot { it.id == serverId })
            }.take(MAX_HISTORY_COUNT)

            prefs[HISTORY_KEY] = json.encodeToString(updated)
        }
    }

    suspend fun removeServer(serverId: String) {
        context.smbDataStore.edit { prefs ->
            val current = prefs[HISTORY_KEY]?.let {
                try {
                    json.decodeFromString<List<SavedSmbServer>>(it)
                } catch (_: Exception) {
                    emptyList()
                }
            } ?: return@edit
            prefs[HISTORY_KEY] = json.encodeToString(current.filterNot { it.id == serverId })
        }
    }
}
