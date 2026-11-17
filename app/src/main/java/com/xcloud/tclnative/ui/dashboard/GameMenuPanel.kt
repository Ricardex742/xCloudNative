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
             settings: (() -> Unit)?=null,xbox: (() -> Unit)?=null): ConsoleDialog {
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
