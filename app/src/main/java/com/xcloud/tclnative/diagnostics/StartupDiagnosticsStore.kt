package com.xcloud.tclnative.diagnostics

import android.content.Context
import org.json.JSONObject

/** A cancelled pre-video attempt must not be presented as the previous game's report. */
class StartupDiagnosticsStore(context: Context) {
    data class Entry(val game: String,val text: String,val timeline: PlaybackTimeline.Snapshot? = null)
    private val prefs=context.getSharedPreferences("startup_diagnostics",Context.MODE_PRIVATE)
    fun save(game: String,text: String,timeline: PlaybackTimeline.Snapshot? = null) {
        prefs.edit().putString("game",game.take(256)).putString("text",text.take(8192))
            .putString("timeline",timeline?.json()?.toString()).apply()
    }
    fun read(): Entry?=runCatching {
        val game=prefs.getString("game",null) ?: return null
        val text=prefs.getString("text",null)?.takeIf { it.isNotBlank() } ?: return null
        val timeline = prefs.getString("timeline",null)?.takeIf { it.length <= 4096 }?.let {
            runCatching { PlaybackTimeline.read(JSONObject(it)) }.getOrNull()
        }
        Entry(game.take(256),text.take(8192),timeline)
    }.getOrNull()
    fun clear() { prefs.edit().clear().apply() }
}
