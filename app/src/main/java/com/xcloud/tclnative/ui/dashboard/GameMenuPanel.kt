package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context

/** The same in-game panel is used by controller and touch shortcuts. */
object GameMenuPanel {
    fun show(context: Context,image: () -> Unit,statistics: () -> Unit,diagnostics: () -> Unit,
             disconnect: () -> Unit,virtualTouch: (() -> Unit)? = null,hud: (() -> Unit)? = null,sessionOptions: (() -> Unit)? = null,audio: (() -> Unit)? = null,
             microphone: (() -> Unit)? = null,microphoneLabel: String=UiText.text("Ativar microfone"),mouse: (() -> Unit)? = null,controllerProfiles: (() -> Unit)?=null,
             aspect: (() -> Unit)? = null,language: (() -> Unit)? = null,appearance: (() -> Unit)? = null,
             profile: com.xcloud.tclnative.catalog.XboxProfile? = null,statisticsOptions: (() -> Unit)?=null,
             recovery: (() -> Unit)?=null,hudToggle: (() -> Unit)?=null,hudVisible: Boolean=false,
             nativeTouch: (() -> Unit)?=null,touchSelection: (() -> Unit)?=null,
             friends: (() -> Unit)?=null,shortcut: (() -> Unit)?=null,video: (() -> Unit)?=null,
             settings: (() -> Unit)?=null,xbox: (() -> Unit)?=null,
             themeGame: com.xcloud.tclnative.signaling.GameTitle?=null,
             themeQuota: com.xcloud.tclnative.catalog.CloudPlaytime=com.xcloud.tclnative.catalog.CloudPlaytime()): ConsoleDialog {
        val htmlTheme=com.xcloud.tclnative.settings.AppearanceSettingsStore(context).read()
        if(htmlTheme.htmlAsset!=null) {
            val dialog=ConsoleDialog(context,UiText.text("Atalhos da partida"))
            val callbacks=mapOf("resume" to ({}),"end" to disconnect,"hud" to (hudToggle ?: hud ?: settings),
                "statistics" to statistics,"audio" to audio,"video" to (video ?: image),"settings" to settings,
                "friends" to friends,"touch" to touchSelection,"microphone" to microphone,"xbox" to xbox)
            val cards=listOfNotNull(themeGame?.let { com.xcloud.tclnative.catalog.CatalogCard(game=it) })
            val data=HtmlConsoleData.snapshot(cards,cards.firstOrNull()?.key,com.xcloud.tclnative.catalog.LibraryTab.ALL,
                profile ?: com.xcloud.tclnative.catalog.XboxProfile(),themeQuota,emptySet(),com.xcloud.tclnative.settings.PlaybackSettingsStore(context).read(),true)
                .put("actions",org.json.JSONArray(callbacks.filterValues { it!=null }.keys)).put("hudVisible",hudVisible)
            lateinit var web: HtmlConsoleTheme
            web=HtmlConsoleTheme(context,htmlTheme) { action,value ->
                when(action) {
                    "select" -> Unit
                    "night" -> { com.xcloud.tclnative.settings.AppearanceSettingsStore(context).saveHtmlNight(htmlTheme,value=="true");web.update(data) }
                    "motion" -> { com.xcloud.tclnative.settings.AppearanceSettingsStore(context).saveMotion(if(value=="true") com.xcloud.tclnative.settings.BackgroundMotion.ON else com.xcloud.tclnative.settings.BackgroundMotion.OFF);web.update(data) }
                    "settings" -> { dialog.dismiss();when(value) { "VIDEO","IMAGE" -> (video ?: image)();"AUDIO" -> audio?.invoke();"CONTROLS" -> (hud ?: controllerProfiles ?: settings)?.invoke();"APPEARANCE" -> appearance?.invoke();else -> settings?.invoke() } }
                    else -> callbacks[action]?.let { callback -> dialog.dismiss();callback() }
                }
            }
            dialog.htmlContent(web);dialog.addCleanup { web.close() };dialog.shortcut={ web.controller(it) }
            web.update(data);dialog.show();return dialog
        }
        fun again() { show(context,image,statistics,diagnostics,disconnect,virtualTouch,hud,sessionOptions,audio,
            microphone,microphoneLabel,mouse,controllerProfiles,aspect,language,appearance,profile,statisticsOptions,
            recovery,hudToggle,hudVisible,nativeTouch,touchSelection,friends,shortcut,video,settings,xbox) }
        fun controls() {
            ConsoleDialogs.menu(context,UiText.text("Controles e toque"),listOfNotNull(
                hud?.let { ConsoleAction(UiText.text("HUD e controles deste jogo"),it) },
                virtualTouch?.let { ConsoleAction(UiText.text("Usar controle virtual nesta partida"),it) },
                nativeTouch?.let { ConsoleAction(UiText.text("Usar toque nativo nesta partida"),it) },
                touchSelection?.let { ConsoleAction(UiText.text("Modo de toque da próxima partida"),it) },
                controllerProfiles?.let { ConsoleAction(UiText.text("Calibração e remapeamento do controle"),it) },
                mouse?.let { ConsoleAction(UiText.text("Capturar / soltar mouse e teclado — F8"),it) }
            ),onBack=::again)
        }
        fun more() {
            ConsoleDialogs.menu(context,UiText.text("Configurações"),listOfNotNull(
                ConsoleAction(UiText.text("Vídeo e conexão"),video ?: image),
                aspect?.let { ConsoleAction(UiText.text("Proporção da imagem"),it) },
                sessionOptions?.let { ConsoleAction(UiText.text("Bitrate, região e idioma deste jogo"),it) },
                ConsoleAction(UiText.text("Controles e toque"),::controls),
                audio?.let { ConsoleAction(UiText.text("Áudio e microfone"),it) },
                appearance?.let { ConsoleAction(UiText.text("Aparência e temas"),it) },
                language?.let { ConsoleAction(UiText.text("Idioma do aplicativo"),it) },
                microphone?.let { ConsoleAction(microphoneLabel,it) },
                statisticsOptions?.let { ConsoleAction(UiText.text("Personalizar estatísticas"),it) },
                recovery?.let { ConsoleAction(UiText.text("Recuperar / recarregar transmissão"),it) },
                ConsoleAction(UiText.text("Diagnóstico da partida"),diagnostics),
                shortcut?.let { ConsoleAction(UiText.text("Atalho na tela inicial"),it) }
            ),onBack=::again)
        }
        val shortcuts=mutableListOf<Pair<ConsoleGlyph,ConsoleAction>>()
        shortcuts+=ConsoleGlyph.CONTROLLER to ConsoleAction(
            if(hudToggle==null) UiText.text("Controles e toque") else if(hudVisible) UiText.text("Ocultar controle virtual") else UiText.text("Mostrar controle virtual"),hudToggle ?: ::controls)
        shortcuts+=ConsoleGlyph.DIAGNOSTICS to ConsoleAction(UiText.text("Mostrar / ocultar estatísticas"),statistics).apply { longAction=statisticsOptions }
        audio?.let { shortcuts+=ConsoleGlyph.AUDIO to ConsoleAction(UiText.text("Áudio e microfone"),it) }
        shortcuts+=ConsoleGlyph.IMAGE to ConsoleAction(UiText.text("Vídeo e conexão"),video ?: image)
        friends?.let { shortcuts+=ConsoleGlyph.ACCOUNT to ConsoleAction(UiText.text("Amigos"),it) }
        shortcuts+=ConsoleGlyph.SETTINGS to ConsoleAction(UiText.text("Configurações"),settings ?: ::more)
        nativeTouch?.let { shortcuts+=ConsoleGlyph.CONTROLLER to ConsoleAction(UiText.text("Usar toque nativo nesta partida"),it) }
        touchSelection?.let { shortcuts+=ConsoleGlyph.CONTROLLER to ConsoleAction(UiText.text("Modo de toque da próxima partida"),it) }
        xbox?.let { shortcuts+=ConsoleGlyph.XBOX to ConsoleAction(UiText.text("Botão Xbox — experimental"),it) }
        val device=com.xcloud.tclnative.settings.InputDeviceProfile.read(context)
        val window=(context as? android.app.Activity)?.window?.decorView
        val width=window?.width?.takeIf { it>0 } ?: context.resources.displayMetrics.widthPixels
        val height=window?.height?.takeIf { it>0 } ?: context.resources.displayMetrics.heightPixels
        val roomy=width>=height && height/context.resources.displayMetrics.density>=480
        val purpose=if(SeriesGuidePanel.enabled(context) && !device.touchDevice && (device.television || roomy))
            ConsolePanelPurpose.GUIDE else ConsolePanelPurpose.GENERAL
        return ConsoleDialog(context,UiText.text("Atalhos da partida"),purpose).apply {
            addShortcuts(shortcuts)
            mouse?.let { addMenuItem(UiText.text("Capturar / soltar mouse e teclado — F8"),it) }
            addAction(UiText.text("Retomar partida")) {}
            addAction(UiText.text("Encerrar partida")) {
                ConsoleDialogs.message(context,UiText.text("Encerrar partida"),UiText.text("Encerrar a partida e voltar ao catálogo?"),
                    listOf(ConsoleAction(UiText.text("Encerrar partida"),disconnect),ConsoleAction(UiText.text("Retomar partida")) {}))
            }
            show()
        }
    }
}
