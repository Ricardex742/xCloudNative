package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.i18n.UiText
import java.util.Locale

enum class CatalogGenre(private val sourceLabel: String, private vararg val storeNames: String) {
    ACTION("Ação e aventura", "action & adventure", "action and adventure"),
    SHOOTER("Tiro", "shooter"),
    ROLE_PLAYING("RPG", "role playing", "role-playing", "role-playing games", "rpg"),
    RACING("Corrida", "racing & flying", "racing and flying", "racing"),
    SPORTS("Esportes", "sports"),
    SIMULATION("Simulação", "simulation"),
    STRATEGY("Estratégia", "strategy"),
    FIGHTING("Luta", "fighting"),
    PLATFORMER("Plataforma", "platformer"),
    PUZZLE("Quebra-cabeça", "puzzle & trivia", "puzzle and trivia", "puzzle");

    val label: String get() = UiText.text(sourceLabel)

    companion object {
        private val byStoreName = entries.flatMap { genre ->
            genre.storeNames.map { it to genre } + (genre.name.lowercase(Locale.ROOT) to genre)
        }.toMap()

        internal fun fromStore(value: String) = byStoreName[value.trim().lowercase(Locale.ROOT)]
    }
}
