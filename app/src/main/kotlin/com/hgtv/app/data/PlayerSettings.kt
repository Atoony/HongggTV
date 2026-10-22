package com.hgtv.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerSettingsDataStore by preferencesDataStore(name = "player_settings")

/** 播放器设置（SPEC §3.4 态2'）：自动连播开关持久化，跨会话生效。 */
class PlayerSettingsStore(private val context: Context) {

    companion object {
        private val AUTO_NEXT = booleanPreferencesKey("auto_next")
        const val AUTO_NEXT_DEFAULT = true
    }

    val autoNext: Flow<Boolean> = context.playerSettingsDataStore.data.map { prefs ->
        prefs[AUTO_NEXT] ?: AUTO_NEXT_DEFAULT
    }

    suspend fun setAutoNext(value: Boolean) {
        context.playerSettingsDataStore.edit { it[AUTO_NEXT] = value }
    }
}
