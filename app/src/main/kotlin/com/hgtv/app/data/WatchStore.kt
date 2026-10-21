package com.hgtv.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hgtv.app.net.JVal
import com.hgtv.app.net.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.watchDataStore by preferencesDataStore(name = "watch_history")

data class WatchEntry(
    val seriesId: String,
    val title: String,
    val cover: String,
    val episode: Int,
    val episodeCount: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** 观看历史（DataStore，SPEC §7）：播放每 5s + 退出写入；历史页/继续观看行读取。 */
class WatchStore(private val context: Context) {

    companion object {
        private val KEY = stringPreferencesKey("entries")
        private const val MAX_ENTRIES = 200

        fun serialize(entries: List<WatchEntry>): String =
            JVal.obj(
                "v" to JVal.num(1),
                "list" to JVal.JArr(entries.map { e ->
                    JVal.obj(
                        "seriesId" to JVal.str(e.seriesId),
                        "title" to JVal.str(e.title),
                        "cover" to JVal.str(e.cover),
                        "episode" to JVal.num(e.episode.toLong()),
                        "episodeCount" to JVal.num(e.episodeCount.toLong()),
                        "positionMs" to JVal.num(e.positionMs),
                        "durationMs" to JVal.num(e.durationMs),
                        "updatedAt" to JVal.num(e.updatedAt),
                    )
                }),
            ).serialize()

        fun deserialize(text: String): List<WatchEntry> {
            if (text.isBlank()) return emptyList()
            val root = runCatching { JsonParser.parse(text) }.getOrNull() as? Map<String, Any?> ?: return emptyList()
            val list = JsonParser.arr(root["list"]) ?: return emptyList()
            return list.mapNotNull { row ->
                val m = JsonParser.obj(row) ?: return@mapNotNull null
                WatchEntry(
                    seriesId = JsonParser.str(m, "seriesId"),
                    title = JsonParser.str(m, "title"),
                    cover = JsonParser.str(m, "cover"),
                    episode = JsonParser.num(m["episode"]).toInt(),
                    episodeCount = JsonParser.num(m["episodeCount"]).toInt(),
                    positionMs = JsonParser.num(m["positionMs"]),
                    durationMs = JsonParser.num(m["durationMs"]),
                    updatedAt = JsonParser.num(m["updatedAt"]),
                )
            }
        }
    }

    val entries: Flow<List<WatchEntry>> = context.watchDataStore.data.map { prefs ->
        (prefs[KEY] ?: "").let(::deserialize).sortedByDescending { it.updatedAt }
    }

    suspend fun get(seriesId: String): WatchEntry? {
        val prefs = context.watchDataStore.data.first()
        return deserialize(prefs[KEY] ?: "").firstOrNull { it.seriesId == seriesId }
    }

    suspend fun upsert(entry: WatchEntry) {
        context.watchDataStore.edit { prefs ->
            val cur = deserialize(prefs[KEY] ?: "").filter { it.seriesId != entry.seriesId }
            (listOf(entry) + cur).take(MAX_ENTRIES).let(::serialize).also { prefs[KEY] = it }
        }
    }

    suspend fun clear() {
        context.watchDataStore.edit { it[KEY] = "" }
    }
}
