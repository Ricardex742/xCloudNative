package com.xcloud.tclnative.settings

import android.content.Context
import com.xcloud.tclnative.i18n.UiText

internal enum class GameShortcutId(private val source: String) {
    IMAGE("Imagem"), AUDIO("Áudio"), VIRTUAL_CONTROL("Controle virtual"), NETWORK("Avisos de rede"),
    NETWORK_DETAILS("Estatísticas da rede"),
    HUD("Controles"), BITRATE("Bitrate máximo"), NATIVE_TOUCH("Toque nativo"),
    REMAPPING("Perfis de controle"), MOUSE_CAPTURE("Mouse e teclado"), STATISTICS("Estatísticas"),
    STATISTICS_LAYOUT("Escolher indicadores"), DIAGNOSTICS("Diagnóstico da partida"),
    RECOVERY("Recuperar imagem"), XBOX("Botão Xbox — experimental"), SAVE_SESSION("Salvar sessão");

    val label get() = UiText.text(source)
}

internal class GameShortcutSettings(context: Context) {
    private val prefs = context.getSharedPreferences("game_shortcuts", Context.MODE_PRIVATE)

    fun slots(): List<GameShortcutId?> {
        val saved = prefs.getString("slots", null)?.split(',') ?: return DEFAULT_SLOTS
        if (saved.size != DEFAULT_SLOTS.size) return DEFAULT_SLOTS
        val seen = mutableSetOf<GameShortcutId>()
        return saved.map { name -> GameShortcutId.entries.firstOrNull { it.name == name }?.takeIf(seen::add) }
    }

    fun assign(index: Int, id: GameShortcutId?) {
        val slots = slots().toMutableList()
        val previous = slots.indexOf(id)
        // Selecting a shortcut already in the bar swaps the two positions.
        if (id != null && previous >= 0) slots[previous] = slots[index]
        slots[index] = id
        prefs.edit().putString("slots", slots.joinToString(",") { it?.name.orEmpty() }).apply()
    }

    fun restoreSlots() { prefs.edit().remove("slots").apply() }

    var networkWarnings: Boolean
        get() = prefs.getBoolean("network_warnings", false)
        set(enabled) { prefs.edit().putBoolean("network_warnings", enabled).apply() }

    companion object {
        val DEFAULT_SLOTS = listOf(GameShortcutId.IMAGE, GameShortcutId.AUDIO,
            GameShortcutId.VIRTUAL_CONTROL, null)
    }
}
