package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText

import android.content.Context

enum class ConsoleScene {
    NONE, XBOX_360, XBOX_ONE, XBOX_SERIES, XBOX_ORIGINAL, XBOX_360_NXE, XBOX_360_METRO, PS4, PS5;
    val playStation: Boolean get()=this==PS4 || this==PS5
}
enum class BackgroundMotion(private val sourceLabel: String) {
    AUTO("Automático"), ON("Ativado"), OFF("Desativado");
    val label get()=UiText.text(sourceLabel)
}
enum class UiTheme(private val sourceLabel: String,val accent: Int,val hue: Float,val saturation: Float=1f,
                   val scene: ConsoleScene=ConsoleScene.NONE) {
    GREEN("Verde — padrão",0xff54de69.toInt(),128f),
    CYBER_BLUE("Azul Cibernético",0xff54caff.toInt(),199f),
    VOLCANIC_RED("Vermelho Vulcânico",0xffff6973.toInt(),356f),
    PRESTIGE_GOLD("Dourado Prestígio",0xfff2cd68.toInt(),45f,.65f),
    INDUSTRIAL_ORANGE("Laranja Industrial",0xffffa052.toInt(),27f,.25f),
    GALAXY_PURPLE("Roxo Galáxia",0xffc58aff.toInt(),275f,1.1f),
    PLATINUM_SILVER("Prata Platina",0xffd9e2ef.toInt(),216f,.08f),
    XBOX_360("Xbox 360 — Blades (2005)",0xffa8df55.toInt(),95f,.40f,ConsoleScene.XBOX_360),
    XBOX_360_NXE("Xbox 360 — NXE (2008)",0xffb0df74.toInt(),91f,.35f,ConsoleScene.XBOX_360_NXE),
    XBOX_360_METRO("Xbox 360 — Metro (2011)",0xff9fdb64.toInt(),95f,.45f,ConsoleScene.XBOX_360_METRO),
    XBOX_ONE("Xbox One",0xff7ddd78.toInt(),118f,.70f,ConsoleScene.XBOX_ONE),
    XBOX_SERIES("Xbox Series X|S",0xffa0efbe.toInt(),154f,.58f,ConsoleScene.XBOX_SERIES),
    XBOX_ORIGINAL("Xbox Original",0xffb6ef4b.toInt(),88f,1.10f,ConsoleScene.XBOX_ORIGINAL),
    PS4("PlayStation 4",0xffdcefff.toInt(),216f,.8f,ConsoleScene.PS4),
    PS5("PlayStation 5",0xffeeeeee.toInt(),220f,.12f,ConsoleScene.PS5),
    PS4_NIGHT("PlayStation 4 — Noturno",0xffd5e8ff.toInt(),216f,.45f,ConsoleScene.PS4),
    PS5_NIGHT("PlayStation 5 — Noturno",0xffe3e8f0.toInt(),220f,.12f,ConsoleScene.PS5),
    SERIES_BLUE("Azul Cibernético",0xff62b9ff.toInt(),208f,.95f,ConsoleScene.XBOX_SERIES),
    SERIES_RED("Vermelho Vulcânico",0xffff7883.toInt(),355f,.85f,ConsoleScene.XBOX_SERIES),
    SERIES_PURPLE("Roxo Galáxia",0xffc4a2ff.toInt(),266f,.82f,ConsoleScene.XBOX_SERIES),
    SERIES_ORANGE("Laranja Industrial",0xffffb26e.toInt(),29f,.85f,ConsoleScene.XBOX_SERIES),
    SERIES_SILVER("Prata Platina",0xffd1dce4.toInt(),207f,.16f,ConsoleScene.XBOX_SERIES);
    val label: String get()=UiText.text(sourceLabel)
    val night: Boolean get()=this==PS4_NIGHT || this==PS5_NIGHT
    val layout: UiTheme get()=when(scene) {
        ConsoleScene.NONE -> GREEN
        ConsoleScene.PS4 -> PS4
        ConsoleScene.PS5 -> PS5
        ConsoleScene.XBOX_SERIES -> XBOX_SERIES
        else -> this
    }
    val layoutLabel: String get()=if(scene==ConsoleScene.NONE) "xCLOUD" else layout.label
    val colorLabel: String get()=when {
        night -> UiText.text("Noturno")
        scene.playStation || this==XBOX_SERIES -> UiText.text("Padrão")
        else -> label
    }
    val colors: List<UiTheme> get()=entries.filter { it.layout==layout }

    companion object {
        val layouts get()=entries.filter { it.layout==it }
    }
}

/** Interface preference only: independent of account, stream profile and video filters. */
class AppearanceSettingsStore(context: Context) {
    private val prefs=context.getSharedPreferences("app_appearance",Context.MODE_PRIVATE)
    fun read(): UiTheme = UiTheme.entries.firstOrNull { it.name==prefs.getString("theme",null) } ?: UiTheme.GREEN
    fun save(theme: UiTheme) {
        val previous=read()
        prefs.edit()
            .putString("color_${previous.layout.name}",previous.name)
            .putString("color_${theme.layout.name}",theme.name)
            .putString("theme",theme.name).apply()
    }
    fun selectLayout(theme: UiTheme): UiTheme {
        val base=theme.layout
        val current=read()
        if(current.layout==base) return current
        val saved=prefs.getString("color_${base.name}",null)
        return base.colors.firstOrNull { it.name==saved }?.also(::save) ?: base.also(::save)
    }
    fun motion(): BackgroundMotion = BackgroundMotion.entries.firstOrNull { it.name==prefs.getString("background_motion",null) } ?: BackgroundMotion.AUTO
    fun saveMotion(mode: BackgroundMotion) { prefs.edit().putString("background_motion",mode.name).apply() }
}
