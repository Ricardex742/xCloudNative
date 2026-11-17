package com.xcloud.tclnative.diagnostics

import android.content.Context

/** A cancelled pre-video attempt must not be presented as the previous game's report. */
class StartupDiagnosticsStore(context: Context) {
    data class Entry(val game: String,val text: String)
    private val prefs=context.getSharedPreferences("startup_diagnostics",Context.MODE_PRIVATE)
    fun save(game: String,text: String) {
        prefs.edit().putString("game",game.take(256)).putString("text",text.take(8192)).apply()
    }
    fun read(): Entry?=runCatching {
        val game=prefs.getString("game",null) ?: return null
        val text=prefs.getString("text",null)?.takeIf { it.isNotBlank() } ?: return null
        Entry(game.take(256),text.take(8192))
    }.getOrNull()
    fun clear() { prefs.edit().clear().apply() }
}
