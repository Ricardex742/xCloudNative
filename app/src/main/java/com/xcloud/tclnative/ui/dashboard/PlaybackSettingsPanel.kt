package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.hardware.input.InputManager
import android.view.Surface
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import android.content.ClipData
import android.content.ClipboardManager
import com.xcloud.tclnative.diagnostics.AppMemoryDiagnostics
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.GamepadState
import com.xcloud.tclnative.webrtc.GyroController
import com.xcloud.tclnative.webrtc.StreamResolution
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsPage { ROOT, VIDEO, IMAGE, APPEARANCE, NETWORK, CONTROLS, PHYSICAL, MOUSE, GYRO, RUMBLE, AUDIO, DIAGNOSTICS, ACCOUNT, TREATMENT, UPSCALING, IMAGE_ADVANCED, VIDEO_ADVANCED }

/** Explicit single-choice menus: opening a row does not change the selected value. */
class PlaybackSettingsPanel(
    private val context: Context,
    private val store: PlaybackSettingsStore,
    private val changed: (PlaybackSettings) -> Unit,
    private val subscription: () -> Unit,
    private val refresh: () -> Unit,
    private val switchAccount: () -> Unit,
    private val testRumble: () -> Unit = {},
    private val homeShortcuts: () -> Unit = {},
    private val lastReport: () -> Unit = {},
    private val sessionOptions: () -> Unit = {},private val controllerProfiles: () -> Unit = {},
    private val onPageEntered: (SettingsPage) -> Unit = {},
    private val catalogInformation: () -> String? = { null },
    private val backgrounds: () -> List<SeriesWallpaper> = { emptyList() },
    private val refreshPlaytime: () -> Unit = {},
    private val playtimeInformation: () -> String = { com.xcloud.tclnative.catalog.CloudPlaytime().diagnostics() },
    private val friends: (() -> Unit)?=null,private val gameShortcut: (() -> Unit)?=null,
    private val sessionPanel: SessionOptionsPanel?=null,private val back: () -> Unit={},
    private val allowAccountChanges: Boolean=true,
    private val gameControls: (() -> Unit)?=null,private val recovery: (() -> Unit)?=null,
    private val currentDiagnostics: (() -> Unit)?=null,private val microphone: (() -> Unit)?=null,
    private val statisticsChanged: () -> Unit={},
    private val microphoneLabel: () -> String={ UiText.text("Ativar microfone") },
    private val liveVideoMode: VideoMode?=null,
    private val liveGyro: Boolean=false,
    private val videoMemoryInformation: () -> String? = { null },
    private val buyHours: (() -> Unit)? = null
) {
    private var currentPage=SettingsPage.ROOT
    private var upscalingBack: () -> Unit = { showImage() }
    private var advancedImageBack: () -> Unit={ showUpscaling() }
    private var gyroBack: () -> Unit = { showControls() }
    private var appearanceBack: (() -> Unit)?=null
    private fun compatibility(value: PlaybackSettings)=(liveVideoMode ?: value.videoMode)==VideoMode.COMPATIBILITY
    private fun resetImageLab(value: PlaybackSettings)=if(liveVideoMode==null) ImageLabSettings()
        else ImageLabSettings(frameGeneration=value.imageLab.frameGeneration)
    private fun enter(page: SettingsPage) { currentPage=page;onPageEntered(page) }
    fun open(page: SettingsPage): ConsoleDialog = when(page) {
        SettingsPage.ROOT -> show();SettingsPage.VIDEO -> showVideo();SettingsPage.IMAGE -> showImage();SettingsPage.APPEARANCE -> showAppearance()
        SettingsPage.NETWORK -> showNetwork();SettingsPage.CONTROLS -> showControls();SettingsPage.PHYSICAL -> showPhysical()
        SettingsPage.MOUSE -> showMouse();SettingsPage.GYRO -> showGyro();SettingsPage.RUMBLE -> showRumble()
        SettingsPage.AUDIO -> showAudio();SettingsPage.DIAGNOSTICS -> showDiagnostics();SettingsPage.ACCOUNT -> showAccount()
        SettingsPage.TREATMENT -> showTreatment()
        SettingsPage.VIDEO_ADVANCED -> showAdvancedVideo()
        SettingsPage.UPSCALING -> showUpscaling()
        SettingsPage.IMAGE_ADVANCED -> showAdvancedImage()
    }
    private fun settingsDialog(title: String) = ConsoleDialog(context,title,ConsolePanelPurpose.SETTINGS_LIST)
    private fun category(page: SettingsPage,title: String,rows: List<ConsoleAction>,parent: SettingsPage=SettingsPage.ROOT): ConsoleDialog {
        enter(page)
        return settingsDialog(title).apply {
            rows.forEach { addMenuItem(it.label,it.action) }
            addAction(UiText.text("Voltar")) { open(parent) }
            setOnCancelListener { open(parent) }
            show()
        }
    }

    fun show(): ConsoleDialog = showSettings(SettingsPage.ROOT)
    fun showVideo(): ConsoleDialog = showSettings(SettingsPage.VIDEO)
    fun showNetwork(): ConsoleDialog = showSettings(SettingsPage.NETWORK)
    fun showControls(): ConsoleDialog = showSettings(SettingsPage.CONTROLS)

    private fun TextView.settingsIcon(dialog: ConsoleDialog, glyph: ConsoleGlyph, above: Boolean = false) {
        val icon = ConsoleIconDrawable(glyph, dialog.px(if (above) 25 else 23), textColors)
        compoundDrawablePadding = dialog.px(if (above) 4 else 10)
        if (above) setCompoundDrawablesRelativeWithIntrinsicBounds(null, icon, null, null)
        else setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
    }

    private fun showSettings(initial: SettingsPage): ConsoleDialog {
        val dialog=settingsDialog(UiText.text("Configurações"))
        val tabs=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false }
        val pages=listOf(
            Triple(SettingsPage.ROOT, UiText.text("Geral"), ConsoleGlyph.SETTINGS),
            Triple(SettingsPage.VIDEO, UiText.text("Vídeo"), ConsoleGlyph.DISPLAY),
            Triple(SettingsPage.NETWORK, UiText.text("Rede"), ConsoleGlyph.NETWORK),
            Triple(SettingsPage.CONTROLS, UiText.text("Controles"), ConsoleGlyph.CONTROLLER),
            Triple(SettingsPage.AUDIO, UiText.text("Áudio"), ConsoleGlyph.AUDIO)
        )
        val buttons=linkedMapOf<SettingsPage,TextView>()
        fun select(page: SettingsPage) {
            enter(page)
            pages.forEach { (section,label) -> buttons[section]?.apply {
                isSelected=section==page
                text=UiText.join(if(isSelected) "✓ " else "",label)
            } }
            dialog.clearSettingsRows()
            when(page) {
                SettingsPage.VIDEO -> appendVideo(dialog)
                SettingsPage.NETWORK -> appendNetwork(dialog)
                SettingsPage.CONTROLS -> appendControls(dialog)
                SettingsPage.AUDIO -> audioRows { showSettings(SettingsPage.AUDIO) }.forEach { dialog.addMenuItem(it.label,it.action) }
                else -> with(dialog) {
                    addSetting(UiText.text("Aparência e temas"),UiText.text("Tema, cor e idioma do aplicativo.")) { showAppearance() }
                        .settingsIcon(this, ConsoleGlyph.PALETTE)
                    addSetting(UiText.text("Conta e amigos"),UiText.text("Contas Xbox, tempo de jogo e catálogo.")) { showAccount() }
                        .settingsIcon(this, ConsoleGlyph.ACCOUNT)
                    addSetting(UiText.text("Ajuda e diagnóstico"),UiText.text("Estatísticas e informações para resolver problemas.")) { showDiagnostics() }
                        .settingsIcon(this, ConsoleGlyph.DIAGNOSTICS)
                }
            }
        }
        val tabHeight = maxOf(dialog.touchSize(), dialog.px(62))
        pages.forEachIndexed { index,(page,label,glyph) ->
            val tab=dialog.keyButton(label) { select(page) }.apply {
                tag="settings-section-${page.name.lowercase(java.util.Locale.ROOT)}"
                minHeight=dialog.touchSize()
                setPadding(dialog.px(4),0,dialog.px(4),0)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dialog.px(19).toFloat())
                maxLines=2
                settingsIcon(dialog, glyph, above = true)
            }
            buttons[page]=tab
            tabs.addView(tab,LinearLayout.LayoutParams(0,tabHeight,1f).apply { if(index<pages.lastIndex) marginEnd=dialog.px(4) })
        }
        dialog.setSettingsNavigation(tabs)
        select(initial)
        dialog.addAction(UiText.text("Voltar"),back)
        dialog.setOnCancelListener { back() }
        dialog.show()
        return dialog
    }

    private fun appendVideo(dialog: ConsoleDialog) = with(dialog) {
        val value=store.read()
        addSetting(UiText.text("Resolução: {0}",UiText.nested(value.forSession().resolution.label)),
            UiText.text("Qualidade do vídeo recebido. Vale na próxima partida.")) { chooseResolution() }
            .settingsIcon(this, ConsoleGlyph.DISPLAY)
        addMenuItem(UiText.text("Modo de vídeo: {0}",UiText.nested(value.videoMode.label))) { chooseVideo() }
        addMenuItem(UiText.text("Proporção: {0}",UiText.nested(value.aspect.label))) { chooseAspect { showVideo() } }
        if(!compatibility(value)) {
            val profile=ImageProfiles.selected(value)
            addSetting(UiText.text("Perfil de imagem: {0}",UiText.nested(ImageProfiles.label(value))),
                UiText.text("Ajusta a imagem na sua tela. Inclui a ampliação com FSR.")) { chooseImageProfile { showVideo() } }
                .settingsIcon(this, ConsoleGlyph.IMAGE)
            if(profile==ImageProfile.LIGHT || profile==ImageProfile.FSR)
                addMenuItem(UiText.text("Intensidade: {0}%",UiText.nested(ImageProfiles.intensity(value)))) { chooseImageIntensity { showVideo() } }
            addMenuItem(UiText.text("Cores da imagem")) { showImageAdjustments { showVideo() } }
                .settingsIcon(this, ConsoleGlyph.PALETTE)
        }
        addMenuItem(UiText.text("Ajustes avançados")) { showAdvancedVideo() }
        Unit
    }

    private fun appendNetwork(dialog: ConsoleDialog) {
        sessionPanel?.appendTo(dialog) { showNetwork() }
        dialog.addSection(UiText.text("Rede avançada"))
        val value=store.read()
        dialog.addMenuItem(UiText.text("IPv4 / IPv6: {0}",UiText.nested(value.ipPreference.label))) { chooseNetwork() }
        dialog.addMenuItem(UiText.text("Bypass de região: {0}",UiText.nested(RegionBypassStore(context).read().label))) {
            RegionBypassPanel.show(context) { showNetwork() }
        }
    }

    private fun showAdvancedVideo(): ConsoleDialog {
        enter(SettingsPage.VIDEO_ADVANCED)
        val value=store.read()
        return settingsDialog(UiText.text("Ajustes avançados")).apply {
            addMenuItem(UiText.text("Codec: {0}",UiText.nested(value.videoCodec.label))) { chooseCodec() }
            addMenuItem(UiText.text("Fluidez: {0}",UiText.nested(value.presentationPacing.label))) { choosePacing() }
            addMenuItem(UiText.text("Ajuste automático da tela: {0}", UiText.nested(
                if(value.matchDisplayMode) UiText.text("Ativado") else UiText.text("Desativado")))) {
                save(value,value.copy(matchDisplayMode=!value.matchDisplayMode)) { showAdvancedVideo() }
            }
            addMenuItem(UiText.text("Avançado — métodos e filtros")) { showAdvancedImage { showAdvancedVideo() } }
            addMenuItem(UiText.text("Testes de desempenho")) { showPerformanceTrials() }
            addAction(UiText.text("Voltar")) { showVideo() }
            setOnCancelListener { showVideo() }
            show()
        }
    }
    private fun showPerformanceTrials(): ConsoleDialog {
        val value = store.read()
        return settingsDialog(UiText.text("Testes de desempenho")).apply {
            body.addView(text(UiText.text("Esses testes valem na próxima partida. O decoder alternativo exige H.264 no modo Adaptativo. A sincronização por fence exige geração de quadros. O controle neutro usa 60 Hz no modo sem toque. Sem análise de áudio, ganho acima de 100% exige iniciar outra partida."), 20, color = DashboardTheme.muted))
            fun toggle(label: String, enabled: Boolean, next: PlaybackSettings) {
                val state = if (enabled) UiText.text("Ativado") else UiText.text("Desativado")
                addMenuItem("$label • $state") { save(value, next) { showPerformanceTrials() } }
            }
            toggle(UiText.text("Decoder H.264 alternativo"), value.tryAlternateDecoder,
                value.copy(tryAlternateDecoder = !value.tryAlternateDecoder))
            toggle(UiText.text("Sincronização por fence"), value.tryMotionFence,
                value.copy(tryMotionFence = !value.tryMotionFence))
            toggle(UiText.text("Cadência reduzida no controle neutro"), value.tryIdleGamepadCadence,
                value.copy(tryIdleGamepadCadence = !value.tryIdleGamepadCadence))
            toggle(UiText.text("Pular análise de áudio com ganho até 100%"), value.trySkipAudioSamples,
                value.copy(trySkipAudioSamples = !value.trySkipAudioSamples))
            addAction(UiText.text("Voltar")) { showAdvancedVideo() }
            setOnCancelListener { showAdvancedVideo() }
            show()
        }
    }

    fun showAppearance(onBack: (() -> Unit)?=null): ConsoleDialog {
        if(onBack!=null) appearanceBack=onBack
        val appearance=AppearanceSettingsStore(context)
        val appearanceTheme=appearance.read()
        val rows=mutableListOf(
            ConsoleAction(UiText.text("Tema do aplicativo: {0}",UiText.nested(appearanceTheme.layoutLabel))) { chooseTheme() }
        )
        if(appearanceTheme.colors.size>1) rows+=ConsoleAction(UiText.text("Cores: {0}",UiText.nested(appearanceTheme.colorLabel))) { chooseColors() }
        if(appearanceTheme.scene!=ConsoleScene.NONE) rows+=ConsoleAction(UiText.text("Fundo dinâmico: {0}",UiText.nested(appearance.motion().label))) { chooseBackgroundMotion() }
        if(appearanceTheme.scene==ConsoleScene.XBOX_SERIES) rows+=ConsoleAction(UiText.text("Personalizar Series X|S")) {
            SeriesPersonalizationPanel(context,backgrounds) { showAppearance() }.show()
        }
        rows+=ConsoleAction(UiText.text("Idioma do aplicativo: {0}",UiText.nested(com.xcloud.tclnative.i18n.AppLanguageStore(context).read().nativeName))) {
            LanguageMenu.show(context) { showAppearance() }
        }
        enter(SettingsPage.APPEARANCE)
        return settingsDialog(UiText.text("Aparência e temas")).apply {
            rows.forEach { addMenuItem(it.label,it.action) }
            val returnToSettings: () -> Unit = { appearanceBack?.invoke() ?: open(SettingsPage.ROOT) }
            addAction(UiText.text("Voltar"),returnToSettings)
            setOnCancelListener { returnToSettings() }
            show()
        }
    }
    private fun chooseBackgroundMotion(): ConsoleDialog {
        enter(SettingsPage.APPEARANCE)
        val preferences=AppearanceSettingsStore(context)
        return settingsDialog(UiText.text("Fundo dinâmico")).apply {
            body.addView(text(UiText.text("Automático respeita a economia de energia e as animações do sistema. Ativado mantém um movimento leve, reduzido em aparelhos modestos ou em economia de energia. O fundo para durante a partida e quando o aplicativo não está visível."),20,color=DashboardTheme.muted))
            BackgroundMotion.entries.forEach { mode ->
                val row=addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(mode==preferences.motion()) "✓" else "○"),UiText.nested(mode.label))) {
                    preferences.saveMotion(mode);showAppearance()
                }
                if(mode==preferences.motion()) focusFirst(row)
            }
            addAction(UiText.text("Voltar")) { showAppearance() };setOnCancelListener { showAppearance() };show()
        }
    }
    fun chooseTheme(): ConsoleDialog {
        val preferences=AppearanceSettingsStore(context)
        return chooseAppearanceStyle(UiText.text("Tema do aplicativo"),UiTheme.layouts,preferences.read().layout,
            showColors=false,select=preferences::selectLayout)
    }
    private fun chooseColors(): ConsoleDialog {
        val preferences=AppearanceSettingsStore(context)
        val selected=preferences.read()
        return chooseAppearanceStyle(UiText.text("Cores"),selected.colors,selected,showColors=true) {
            it.also(preferences::save)
        }
    }
    private fun chooseAppearanceStyle(
        title: String, choices: List<UiTheme>, selected: UiTheme, showColors: Boolean, select: (UiTheme) -> UiTheme
    ): ConsoleDialog {
        enter(SettingsPage.APPEARANCE)
        val host=context
        return ConsoleDialog(host,title).apply {
            for(theme in choices) {
                val label=if(showColors) theme.colorLabel else theme.layoutLabel
                val selectedMark=UiText.nested(if(theme==selected) "✓" else "○")
                val text=if(showColors) UiText.text("{0}  ■  {1}",selectedMark,UiText.nested(label))
                    else UiText.text("{0}  {1}",selectedMark,UiText.nested(label))
                val row=addMenuItem(text) {
                    val applied=select(theme)
                    val root=(host as? android.app.Activity)?.findViewById<android.view.View>(android.R.id.content)
                    DashboardTheme.apply(host,applied,root)
                    showAppearance()
                }
                val swatch=row.text.indexOf('■')
                if(showColors && swatch>=0) row.text=android.text.SpannableString(row.text).apply {
                    setSpan(android.text.style.ForegroundColorSpan(theme.accent),swatch,swatch+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                row.isSelected=theme==selected
                row.contentDescription=UiText.text("{0}{1}",UiText.nested(label),UiText.nested(if(theme==selected) UiText.text(", selecionado") else ""))
                if(theme==selected) focusFirst(row)
            }
            addAction(UiText.text("Voltar")) { showAppearance() };setOnCancelListener { showAppearance() };show()
        }
    }
    fun showImage(): ConsoleDialog = showVideo()
    private fun methodName(mode: FsrMode): String = when(mode) {
        FsrMode.OFF -> UiText.text("Padrão do aparelho")
        FsrMode.FSR1 -> mode.label
    }
    private fun methodDescription(mode: FsrMode): String = when(mode) {
        FsrMode.OFF -> UiText.text("Usa a escala padrão do aparelho. A nitidez e os filtros adicionais abaixo são opcionais.")
        FsrMode.FSR1 -> UiText.text("O FSR melhora detalhes e amplia o vídeo para a tela. A qualidade automática ajusta o processamento à fluidez da partida.")
    }
    /** A few everyday chains; saved custom combinations stay available under Advanced. */
    fun showUpscaling(onBack: (() -> Unit)? = null): ConsoleDialog {
        if(onBack!=null) upscalingBack=onBack
        enter(SettingsPage.UPSCALING)
        val value=store.read();val profile=ImageProfiles.selected(value)
        val live=liveVideoMode!=null
        val compatible=compatibility(value)
        return settingsDialog(if(live) UiText.text("Imagem e tela") else UiText.text("Upscaling e tratamento")).apply {
            if(live) addMenuItem(UiText.text("Proporção: {0}",UiText.nested(value.aspect.label))) { chooseAspect { showUpscaling() } }
            if(!live || !compatible) {
                body.addView(text(UiText.text("Escolha um perfil e compare a mesma cena. Mais intensidade não garante mais detalhes. Os ajustes personalizados ficam em Avançado."),20,color=DashboardTheme.muted))
            }
            if(compatible) {
                if(!live) {
                    body.addView(text(UiText.text("Compatibilidade está ativa e desativa os métodos e filtros experimentais. Mude o modo em Vídeo e transmissão para usá-los."),20,color=DashboardTheme.muted))
                    addMenuItem(UiText.text("Modo de vídeo: {0}",UiText.nested(value.videoMode.label))) { chooseVideo() }
                }
            } else {
                addMenuItem(UiText.text("Perfil de imagem: {0}",UiText.nested(ImageProfiles.label(value)))) { chooseImageProfile() }
                if(profile==ImageProfile.LIGHT || profile==ImageProfile.FSR)
                    addMenuItem(UiText.text("Intensidade: {0}%",UiText.nested(ImageProfiles.intensity(value)))) { chooseImageIntensity() }
                addMenuItem(UiText.text("Comparação: {0}",UiText.nested(value.effectiveImageComparison().label))) { chooseGlobalComparison() }
                addMenuItem(UiText.text("Cores da imagem")) { showImageAdjustments { showUpscaling() } }
                addMenuItem(UiText.text("Avançado — métodos e filtros")) { showAdvancedImage { showUpscaling() } }
                addMenuItem(UiText.text("Restaurar imagem original")) {
                    save(value,ImageProfiles.apply(value,ImageProfile.ORIGINAL).copy(saturation=100,brightness=0,contrast=100,
                        imageLab=resetImageLab(value)))
                }
            }
            addAction(UiText.text("Voltar"),upscalingBack);setOnCancelListener { upscalingBack() };show()
        }
    }
    private fun chooseImageProfile(onReturn: () -> Unit={ showUpscaling() }): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read()
        val profiles=listOf(ImageProfile.ORIGINAL,ImageProfile.LIGHT,ImageProfile.FSR)
        return choices(UiText.text("Perfil de imagem"),UiText.text("Original mantém a imagem recebida e os ajustes de cor. Leve acrescenta nitidez. FSR combina ampliação, realce de detalhes e estabilidade entre quadros."),
            profiles.map {it.label to (it==ImageProfiles.selected(value))},onReturn) { save(value,ImageProfiles.apply(value,profiles[it]),onReturn) }
    }
    private fun chooseImageIntensity(onReturn: () -> Unit={ showUpscaling() }): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read();val levels=listOf(0,25,50,75,100)
        return choices(UiText.text("Intensidade"),UiText.text("Ajusta a nitidez e, no FSR, os detalhes e a estabilidade da imagem. Em 0%, o FSR ainda pode ampliar o vídeo."),
            levels.map {"$it%" to (it==ImageProfiles.intensity(value))},onReturn) {save(value,ImageProfiles.withIntensity(value,levels[it]),onReturn)}
    }
    private fun chooseGlobalComparison(onReturn: () -> Unit={ showUpscaling() }): ConsoleDialog {
        enter(SettingsPage.UPSCALING);val value=store.read()
        return choices(UiText.text("Comparação da imagem"),UiText.text("Compara o mesmo quadro com e sem tratamento, mantendo as cores e o enquadramento. Dividida mostra o original à esquerda. A comparação suspende a geração de quadros até a próxima partida. O custo de meia tela não representa o de tela inteira."),
            ImageComparison.entries.map {it.label to (it==value.effectiveImageComparison())},onReturn) {
            save(value,value.copy(imageComparison=ImageComparison.entries[it],
                imageLab=value.imageLab.copy(comparison=ImageComparison.PROCESSED)),onReturn)
        }
    }
    /** The previous method-specific controls remain available without rewriting stored choices. */
    fun showAdvancedImage(onBack: (() -> Unit)?=null): ConsoleDialog {
        if(onBack!=null) advancedImageBack=onBack
        enter(SettingsPage.IMAGE_ADVANCED)
        val value=store.read();val mode=value.fsr
        if(liveVideoMode!=null && compatibility(value)) return showUpscaling()
        fun back() { showAdvancedImage() }
        return settingsDialog(UiText.text("Avançado — métodos e filtros")).apply {
            if(compatibility(value)) {
                body.addView(text(UiText.text("Compatibilidade está ativa e desativa os métodos e filtros experimentais. Mude o modo em Vídeo e transmissão para usá-los."),20,color=DashboardTheme.muted))
                addMenuItem(UiText.text("Modo de vídeo: {0}",UiText.nested(value.videoMode.label))) { chooseVideo() }
            } else {
                body.addView(text(methodDescription(mode),20,color=DashboardTheme.muted))
                addMenuItem(UiText.text("Método: {0}",UiText.nested(methodName(mode)))) { chooseFsr() }
                if(mode==FsrMode.OFF && liveVideoMode==null) {
                    addMenuItem(UiText.text("Upscaling nativo: {0}",UiText.nested(if(value.nativeUpscaling) UiText.text("Ativado") else UiText.text("Desativado")))) { chooseUpscaling() }
                } else if(mode!=FsrMode.OFF) addMenuItem(UiText.text("Saída do escalador: {0}",UiText.nested(value.fsrOutput.label))) { chooseFsrOutput() }
                val amount=if(mode==FsrMode.OFF) value.sharpness else value.fsrSharpness
                addMenuItem(UiText.text("Nitidez: {0}%",UiText.nested(amount))) { chooseSharpeningStrength() }
                addMenuItem(UiText.text("Método de nitidez: {0}",UiText.nested(sharpeningName(value)))) { chooseSharpeningMethod() }
                if(mode==FsrMode.FSR1) {
                    addMenuItem(UiText.text("Qualidade do processamento: {0}",UiText.nested(value.reconstruction.quality.label))) { chooseReconstructionQuality() }
                    addMenuItem(UiText.text("Intensidade dos detalhes: {0}%",UiText.nested(value.reconstruction.strength))) { chooseReconstructionStrength() }
                }
                val filters=value.imageTreatment.spatial || value.imageTreatment.temporal>0 || value.imageTreatment.neural>0
                addMenuItem(UiText.text("Filtros adicionais: {0}",UiText.nested(if(filters) UiText.text("Ativados") else UiText.text("Desativados")))) { showTreatment() }
                addMenuItem(UiText.text("Comparação: {0}",UiText.nested(value.effectiveImageComparison().label))) { chooseGlobalComparison(::back) }
                addMenuItem(UiText.text("Cores da imagem")) { showImageAdjustments(onBack=::back) }
                addMenuItem(UiText.text("Proteção de desempenho: {0}",UiText.nested(if(value.imageTreatment.autoProtect) UiText.text("Ativada") else UiText.text("Desativada")))) { choosePerformanceProtection() }
                addMenuItem(UiText.text("Laboratório de imagem: {0}",UiText.nested(if(value.imageLab.enabled) UiText.text("Ativado") else UiText.text("Desativado")))) {
                    ImageLabPanel(context,store,changed,live=liveVideoMode!=null) { showAdvancedImage() }.show()
                }
                addMenuItem(UiText.text("Restaurar imagem original")) {
                    save(value,value.copy(nativeUpscaling=liveVideoMode==null || value.nativeUpscaling,
                        fsr=FsrMode.OFF,fsrOutput=FsrOutput.AUTO,fsrSharpness=50,
                        reconstruction=ReconstructionSettings(),imageLab=resetImageLab(value),imageTreatment=ImageTreatmentSettings(),imageComparison=ImageComparison.PROCESSED,sharpness=0,saturation=100,brightness=0,contrast=100),::back)
                }
            }
            addAction(UiText.text("Voltar"),advancedImageBack);setOnCancelListener { advancedImageBack() };show()
        }
    }
    private fun sharpeningName(value: PlaybackSettings): String = when(value.imageTreatment.sharpening) {
        ImageSharpening.LEGACY -> if(value.fsr==FsrMode.FSR1) UiText.text("RCAS original") else UiText.text("Nitidez simples")
        else -> value.imageTreatment.sharpening.label
    }
    private fun chooseSharpeningStrength(): ConsoleDialog {
        enter(SettingsPage.IMAGE_ADVANCED);val value=store.read();val levels=listOf(0,25,50,75,100)
        val amount=if(value.fsr==FsrMode.OFF) value.sharpness else value.fsrSharpness
        return choices(UiText.text("{0} — Nitidez",UiText.nested(methodName(value.fsr))),UiText.text("0% desativa somente a nitidez deste método. A ampliação e os filtros adicionais continuam independentes. Comece com 25%."),
            levels.map { "$it%" to (it==amount) }) {
            save(value,if(value.fsr==FsrMode.OFF) value.copy(sharpness=levels[it]) else value.copy(fsrSharpness=levels[it]))
        }
    }
    private fun chooseSharpeningMethod(): ConsoleDialog {
        enter(SettingsPage.IMAGE_ADVANCED);val value=store.read()
        return choices(UiText.text("Método de nitidez"),UiText.text("Este ajuste pertence ao método selecionado. RCAS adaptativo e USM trabalham na luminância para limitar ruído e halos."),
            ImageSharpening.entries.map { sharpeningName(value.copy(imageTreatment=value.imageTreatment.copy(sharpening=it))) to (it==value.imageTreatment.sharpening) }) {
            save(value,value.copy(imageTreatment=value.imageTreatment.copy(sharpening=ImageSharpening.entries[it])))
        }
    }
    private fun choosePerformanceProtection(): ConsoleDialog {
        enter(SettingsPage.IMAGE_ADVANCED);val value=store.read();val image=value.imageTreatment
        return choices(UiText.text("Proteção de desempenho"),UiText.text("Reduz temporal, saída, antialiasing, neural e filtros quando a cadência desenhada fica abaixo da recebida. As escolhas salvas são preservadas. Os cortes valem nesta partida e aparecem no diagnóstico."),
            listOf(UiText.text("Ativada") to image.autoProtect,UiText.text("Desativada") to !image.autoProtect)) { save(value,value.copy(imageTreatment=image.copy(autoProtect=it==0))) }
    }
    fun showDiagnostics(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.DIAGNOSTICS,UiText.text("Ajuda e diagnóstico"),listOfNotNull(
            recovery?.let { ConsoleAction(UiText.text("Recuperar / recarregar transmissão"),it) },
            currentDiagnostics?.let { ConsoleAction(UiText.text("Diagnóstico da partida"),it) },
            ConsoleAction(UiText.text("Memória e última saída do app")) { showMemoryDiagnostics() },
            ConsoleAction(UiText.text("Estatísticas durante a partida")) {
                StatisticsSettingsPanel(context,StatisticsSettingsStore(context),statisticsChanged,back={ showDiagnostics() }).show()
            },
            ConsoleAction(UiText.text("Diagnóstico pós-partida: {0}",UiText.nested(if(value.sessionReport) UiText.text("Ativado") else UiText.text("Desativado")))) {
                choices(UiText.text("Diagnóstico pós-partida"),UiText.text("Guarda o último resumo local. As estatísticas da partida continuam ocultas até você ativá-las."),
                    listOf(UiText.text("Ativado — abrir ao sair do jogo") to value.sessionReport,UiText.text("Desativado — consultar nas configurações") to !value.sessionReport)) {
                    save(value,value.copy(sessionReport=it==0))
                }
            },
            ConsoleAction(UiText.text("Último diagnóstico"),lastReport)
        ))
    }
    private fun showMemoryDiagnostics() {
        val title = UiText.text("Memória e última saída do app")
        val technical = videoMemoryInformation()
        val dialog = settingsDialog(title)
        val report = dialog.text(UiText.text("Consultando memória…"), 19)
        dialog.body.addView(report)
        val copy = dialog.addPersistentAction(UiText.text("Copiar detalhes")) {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(title, report.text))
        }.apply { isEnabled = false }
        dialog.addAction(UiText.text("Voltar")) { showDiagnostics() }
        dialog.setOnCancelListener { showDiagnostics() }
        dialog.show()
        val job = CoroutineScope(Dispatchers.Main.immediate).launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { AppMemoryDiagnostics.collect(context) }.getOrDefault("MEMORY/UNAVAILABLE")
            }
            report.text = listOfNotNull(data, technical).joinToString("\n")
            copy.isEnabled = true
        }
        dialog.addCleanup { job.cancel() }
    }
    fun showAccount(): ConsoleDialog = category(SettingsPage.ACCOUNT,UiText.text("Conta e amigos"),listOfNotNull(
        if(allowAccountChanges) ConsoleAction(UiText.text("Trocar conta"),switchAccount) else null,
        friends?.let { ConsoleAction(UiText.text("Amigos"),it) },
        gameShortcut?.let { ConsoleAction(UiText.text("Atalho do jogo selecionado"),it) },
        ConsoleAction(UiText.text("Atalhos dos favoritos"),homeShortcuts),ConsoleAction(UiText.text("Gerenciar assinatura Microsoft"),subscription),
        buyHours?.let { ConsoleAction(UiText.text("Comprar horas de jogo"), it) },
        ConsoleAction(UiText.text("Atualizar tempo restante")) { refreshPlaytime();showAccount() },
        ConsoleAction(UiText.text("Detalhes do tempo restante")) {
            ConsoleDialogs.message(context,UiText.text("Detalhes do tempo restante"),playtimeInformation(),
                listOf(ConsoleAction(UiText.text("Atualizar tempo restante")) { refreshPlaytime();showAccount() },
                    ConsoleAction(UiText.text("Voltar")) { showAccount() }),onCancel={ showAccount() })
        },
        ConsoleAction(UiText.text("Informações do catálogo")) {
            ConsoleDialogs.message(context,UiText.text("Informações do catálogo"),catalogInformation()?.takeIf { it.isNotBlank() }
                ?: UiText.text("Catálogos oficiais com disponibilidade sujeita à conta, à região e às condições do serviço."),
                listOf(ConsoleAction(UiText.text("Voltar")) { showAccount() }),onCancel={ showAccount() })
        },
        if(allowAccountChanges) ConsoleAction(UiText.text("Atualizar catálogo"),refresh) else null
    ))
    private fun save(base: PlaybackSettings, settings: PlaybackSettings, after: () -> Unit = { open(currentPage) }) {
        changed(store.update(base,settings))
        after()
    }
    private fun choices(title: String, description: String, entries: List<Pair<String,Boolean>>,
                        onBack: () -> Unit = { open(currentPage) }, select: (Int) -> Unit): ConsoleDialog {
        val dialog=settingsDialog(title)
        dialog.body.addView(dialog.text(description,20,color=DashboardTheme.muted),LinearLayout.LayoutParams(-1,-2)
            .apply { bottomMargin=dialog.px(16) })
        entries.forEachIndexed { i,(label,selected) ->
            val row=dialog.addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(selected) "✓" else "○"),UiText.nested(label))) { select(i) }
            row.isSelected=selected
            row.contentDescription=UiText.text("{0}{1}",UiText.nested(label),UiText.nested(if(selected) UiText.text(", selecionado") else ""))
            if (selected) dialog.focusFirst(row)
        }
        dialog.addAction(UiText.text("Voltar"),onBack)
        dialog.setOnCancelListener { onBack() }
        dialog.show()
        return dialog
    }
    fun chooseVideo(): ConsoleDialog {
        enter(SettingsPage.VIDEO)
        val current=store.read()
        return choices(UiText.text("Modo de vídeo"),UiText.text("Menor atraso reduz buffers. Adaptativo permite mais margem para a rede. Compatibilidade seleciona 720p, pede até 6 Mbps no Automático, prioriza H.264 Baseline e apresenta até 30 quadros/s. A decodificação ainda depende do fluxo recebido. Aplica-se à próxima partida."),
            VideoMode.entries.map { it.label to (it==current.videoMode) }) {
            val mode=VideoMode.entries[it]
            save(current,if(mode==VideoMode.COMPATIBILITY) current.copy(videoMode=mode,resolution=StreamResolution.HD_720,
                nativeUpscaling=true,sharpness=0,saturation=100,videoCodec=VideoCodecMode.H264,fsr=FsrMode.OFF,brightness=0,contrast=100) else current.copy(videoMode=mode))
        }
    }
    fun chooseCodec(): ConsoleDialog {
        enter(SettingsPage.VIDEO_ADVANCED)
        val current=store.read()
        return choices(UiText.text("Codec de vídeo"),UiText.join(UiText.text("Padrão mantém a negociação H.264 habitual. Leve oferece somente Baseline, solicita 720p e até 6 Mbps no Automático. O teto manual da partida pode substituir esse orçamento. É um perfil do H.264, sem garantia de ganho se o jogo já usava Baseline. A resolução e o bitrate recebidos dependem do serviço. Compatibilidade também usa Baseline. Aplica-se à próxima partida."),"\n\n",
            UiText.text("Experimentos: High, HEVC, VP9 e AV1 exigem decoder de hardware e suporte do serviço. Se indisponíveis, usamos H.264. Confira o codec recebido no diagnóstico."),"\n\n",UiText.text("Para comparar codecs, use Restaurar imagem original em Upscaling e tratamento. Mais compressão não garante imagem melhor.")),
            VideoCodecMode.entries.map { it.label to (it==current.videoCodec) }) {
            val selected=current.copy(videoCodec=VideoCodecMode.entries[it])
            save(current,if(selected.videoCodec==VideoCodecMode.H264_BASELINE) selected.copy(resolution=StreamResolution.HD_720) else selected)
        }
    }
    fun choosePacing(): ConsoleDialog {
        enter(SettingsPage.VIDEO_ADVANCED);val value=store.read()
        return choices(UiText.text("Fluidez da apresentação"),UiText.text("Suave agenda os quadros com uma pequena espera e pode aumentar o atraso do controle. Mantém apenas o quadro pendente mais recente; não gera novos quadros. Só atua em Adaptativo. Menor atraso e Compatibilidade mantêm a apresentação imediata. Vale na próxima partida."),
            PresentationPacing.entries.map { it.label to (it==value.presentationPacing) }) { save(value,value.copy(presentationPacing=PresentationPacing.entries[it])) }
    }
    fun chooseResolution(): ConsoleDialog {
        enter(SettingsPage.VIDEO)
        val current=store.read()
        return choices(UiText.text("Resolução da transmissão"),UiText.join(
            UiText.text("H.264 leve e Compatibilidade usam 720p mesmo com outra escolha salva. 720p solicita menos banda e trabalho de decodificação. A resolução recebida depende do Xbox, do jogo e da conexão. Aplica-se à próxima partida."),"\n\n",
            UiText.text("1440p solicita vídeo em até 2560×1440 pelo perfil de qualidade máxima do Xbox. A disponibilidade depende da assinatura, do jogo e do serviço. Exige mais rede e decodificação; confira a resolução realmente recebida no diagnóstico.")),
            StreamResolution.entries.map { it.label to (it==current.resolution) }) { save(current,current.copy(resolution=StreamResolution.entries[it])) }
    }
    fun chooseUpscaling(): ConsoleDialog {
        enter(SettingsPage.IMAGE_ADVANCED)
        val current=store.read()
        return choices(UiText.text("Upscaling nativo"),UiText.text("A imagem se ajusta à janela nos dois modos, respeitando a proporção escolhida. Ativado solicita a escala da Surface Android; desativado usa a escala do renderer. Quando o FSR está ativo, ele usa o renderer na resolução de saída. O processamento da TV é ajustado na própria TV. Aplica-se à próxima partida."),
            listOf(UiText.text("Ativado — escala da Surface") to current.nativeUpscaling,UiText.text("Desativado — escala do renderer") to !current.nativeUpscaling)) {
            save(current,current.copy(nativeUpscaling=it==0))
        }
    }
    fun chooseFsr(): ConsoleDialog {
        enter(SettingsPage.IMAGE_ADVANCED)
        val current=store.read()
        return choices(UiText.text("Escolher método de imagem"),UiText.text("Padrão usa a escala do aparelho. O FSR amplia o vídeo e melhora os detalhes na tela."),
            FsrMode.entries.map { methodName(it) to (it==current.fsr) }) {
            val mode=FsrMode.entries[it]
            val treatment=if(mode==FsrMode.FSR1 && current.fsr==FsrMode.OFF && current.imageTreatment.sharpening==ImageSharpening.LEGACY)
                current.imageTreatment.copy(sharpening=ImageSharpening.RCAS_LUMA) else current.imageTreatment
            save(current,current.copy(fsr=mode,imageTreatment=treatment))
        }
    }
    fun chooseFsrOutput(): ConsoleDialog {
        enter(SettingsPage.IMAGE_ADVANCED)
        val current=store.read()
        return choices(UiText.text("Saída do escalador"),UiText.text("A saída é local e não altera o stream recebido. Automático prioriza a fluidez e limita TVs a 1080p. Ative um escalador para usar esta opção. As escolhas preservam a proporção e respeitam a saída física. A proteção reduz a saída se os desenhos não acompanharem os quadros recebidos. 1440p e 4K não garantem 60 FPS."),
            FsrOutput.entries.map { it.label to (it==current.fsrOutput) }) { save(current,current.copy(fsrOutput=FsrOutput.entries[it])) }
    }
    fun chooseAspect(onBack: () -> Unit = { showImage() }): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val current=store.read()
        return choices(UiText.text("Proporção da imagem"),UiText.text("Original preserva o formato recebido e usa o maior tamanho que cabe na janela. Um vídeo 16:9 pode deixar barras laterais no celular; alguns jogos com toque nativo oferecem vídeo mais largo. Esticar preenche sem cortar, mas deforma a imagem. Zoom preenche sem deformar, cortando as bordas. Proporções fixas também podem deformar. Use ↑/↓ ou deslize para ver todas as opções. A mudança é imediata durante a partida e fica salva para os próximos jogos."),
            VideoAspect.entries.map { it.label to (it==current.aspect) },onBack=onBack) {
            save(current,current.copy(aspect=VideoAspect.entries[it]),onBack)
        }
    }
    fun showImageAdjustments(onBack: () -> Unit = { showImage() }): ConsoleDialog {
        enter(SettingsPage.IMAGE)
        val settings=store.read()
        fun backToImage() { showImageAdjustments(onBack) }
        return settingsDialog(UiText.text("Cores da imagem")).apply {
            body.addView(text(UiText.text("Brilho, contraste e saturação são ajustes de cor compartilhados. A nitidez fica em Upscaling e tratamento, junto ao método escolhido."),20,color=DashboardTheme.muted))
            addMenuItem(UiText.text("Brilho: {0}%",UiText.nested(settings.brightness))) {
                val levels=listOf(-50,-25,-10,0,10,25,50)
                choices(UiText.text("Brilho"),UiText.text("0% preserva a imagem original. Aplicado junto aos filtros existentes, inclusive no FSR."),
                    levels.map { "$it%" to (it==settings.brightness) },::backToImage) { save(settings,settings.copy(brightness=levels[it]),::backToImage) }
            }
            addMenuItem(UiText.text("Contraste: {0}%",UiText.nested(settings.contrast))) {
                val levels=listOf(50,75,90,100,110,125,150)
                choices(UiText.text("Contraste"),UiText.text("100% preserva o contraste original. Valores extremos podem esconder detalhes claros ou escuros."),
                    levels.map { "$it%" to (it==settings.contrast) },::backToImage) { save(settings,settings.copy(contrast=levels[it]),::backToImage) }
            }
            addMenuItem(UiText.text("Saturação: {0}%",UiText.nested(settings.saturation))) {
                val levels=listOf(0,75,100,110,125,150,200)
                choices(UiText.text("Saturação"),UiText.text("100% mantém as cores originais. Valores maiores intensificam as cores; 0% deixa a imagem em cinza."),
                    levels.map { UiText.text("{0}%{1}",UiText.nested(it),UiText.nested(if(it==100) UiText.text(" — original") else "")) to (it==settings.saturation) },::backToImage) {
                    save(settings,settings.copy(saturation=levels[it]),::backToImage)
                }
            }
            addAction(UiText.text("Voltar"),onBack); setOnCancelListener { onBack() }; show()
        }
    }
    private fun chooseReconstructionQuality(): ConsoleDialog {
        enter(SettingsPage.IMAGE_ADVANCED);val value=store.read();val options=value.reconstruction
        return choices(UiText.text("Qualidade do processamento"),UiText.text("Automático começa leve e aumenta a qualidade enquanto a partida estiver fluida. Em aparelhos com pouca memória, o FSR prioriza a ampliação."),
            ReconstructionQuality.entries.map { it.label to (it==options.quality) }) { save(value,value.copy(reconstruction=options.copy(quality=ReconstructionQuality.entries[it]))) }
    }
    private fun chooseReconstructionStrength(): ConsoleDialog {
        enter(SettingsPage.IMAGE_ADVANCED);val value=store.read();val options=value.reconstruction;val levels=listOf(0,25,50,75,100)
        return choices(UiText.text("Intensidade dos detalhes"),UiText.text("Controla o realce antes da ampliação. Em 0%, o FSR aplica apenas a ampliação e a nitidez escolhida."),
            levels.map { "$it%" to (it==options.strength) }) { save(value,value.copy(reconstruction=options.copy(strength=levels[it]))) }
    }
    fun showTreatment(): ConsoleDialog {
        if(compatibility(store.read())) return showAdvancedImage()
        enter(SettingsPage.TREATMENT)
        val value=store.read();val image=value.imageTreatment
        fun level(title: String,current: Int,max: Int=100,change: (ImageTreatmentSettings,Int)->ImageTreatmentSettings) {
            val levels=listOf(0,25,50,75,100).filter { it<=max }
            choices(title,UiText.text("Filtros opcionais. Use intensidades baixas e compare com a imagem original. Temporal pode deixar rastros. A rede neural exige renderização half-float e fonte de até 1080p; o diagnóstico informa quando não há suporte. Compatibilidade desativa o tratamento."),
                levels.map { "$it%" to (it==current) },{ showTreatment() }) { index ->
                val now=store.read();save(now,now.copy(imageTreatment=change(now.imageTreatment,levels[index])))
            }
        }
        return settingsDialog(UiText.text("Filtros adicionais — compartilhados")).apply {
            body.addView(text(UiText.text("Método: {0}",UiText.nested(methodName(value.fsr))),20,color=DashboardTheme.muted))
            body.addView(text(UiText.text("Filtros opcionais. Use intensidades baixas e compare com a imagem original. Temporal pode deixar rastros. A rede neural exige renderização half-float e fonte de até 1080p; o diagnóstico informa quando não há suporte. Compatibilidade desativa o tratamento."),20,color=DashboardTheme.muted))
            addMenuItem(UiText.text("Degradês: {0}%",UiText.nested(image.deband))) { level(UiText.text("Degradês"),image.deband) { s,n -> s.copy(deband=n) } }
            addMenuItem(UiText.text("Limpeza de compressão: {0}%",UiText.nested(image.cleanup))) { level(UiText.text("Limpeza de compressão"),image.cleanup) { s,n -> s.copy(cleanup=n) } }
            addMenuItem(UiText.text("Cor nas bordas: {0}%",UiText.nested(image.chroma))) { level(UiText.text("Cor nas bordas"),image.chroma) { s,n -> s.copy(chroma=n) } }
            addMenuItem(UiText.text("Antialiasing: {0}%",UiText.nested(image.antialias))) { level("Antialiasing",image.antialias) { s,n -> s.copy(antialias=n) } }
            addMenuItem(UiText.text("Estabilidade temporal: {0}%",UiText.nested(image.temporal))) { level(UiText.text("Estabilidade temporal"),image.temporal,50) { s,n -> s.copy(temporal=n) } }
            addMenuItem(UiText.text("Reconstrução neural: {0}%",UiText.nested(image.neural))) { level(UiText.text("Reconstrução neural"),image.neural) { s,n -> s.copy(neural=n) } }
            addAction(UiText.text("Voltar")) { showAdvancedImage() };setOnCancelListener { showAdvancedImage() };show()
        }
    }
    private fun appendControls(dialog: ConsoleDialog) = with(dialog) {
        val value=store.read()
        addSetting(UiText.text("Jogar com toque: {0}",UiText.nested(value.touchMode.label)),
            UiText.text("Escolhe como os jogos respondem ao toque no celular.")) { chooseTouch() }
        gameControls?.let { addMenuItem(UiText.text("HUD e controles deste jogo"),it) }
        addMenuItem(UiText.text("Controle físico e coop local")) { showPhysical() }
        addMenuItem(UiText.text("Giroscópio: {0}",UiText.nested(value.gyroMode.label))) { showGyro() }
            .settingsIcon(dialog, ConsoleGlyph.GYROSCOPE)
        addMenuItem(UiText.text("Vibração do controle e celular")) { showRumble() }
        addSection(UiText.text("Outras formas de jogar"))
        addMenuItem(UiText.text("Mouse e teclado")) { showMouse() }
        Unit
    }
    fun showPhysical(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.PHYSICAL,UiText.text("Controles › Controle físico"),listOf(
            ConsoleAction(UiText.text("Calibração e remapeamento por controle"),controllerProfiles),
            ConsoleAction(UiText.text("Coop local: {0}",UiText.nested(if(value.localCoOp) UiText.text("Ativado — até 4 jogadores") else UiText.text("Desativado")))) {
                choices(UiText.text("Coop local"),UiText.text("Cada controle ocupa um jogador. Toque/teclado reservam o jogador 1. O jogo e a sessão precisam aceitar multiplayer local. Vale na próxima partida."),
                    listOf(UiText.text("Desativado") to !value.localCoOp,UiText.text("Ativado — até 4 jogadores") to value.localCoOp)) { save(value,value.copy(localCoOp=it==1)) }
            }
        ),SettingsPage.CONTROLS)
    }
    fun chooseTouch(onBack: (() -> Unit)?=null): ConsoleDialog {
        enter(SettingsPage.CONTROLS);val value=store.read()
        return choices(UiText.text("Jogar com toque"),UiText.text("Na TV, o jogo usa controle físico. No celular, Automático prioriza toque nativo em jogos compatíveis e HUD nos demais. Exclusivo — controle virtual força o HUD. Vale na próxima partida."),
            TouchMode.entries.map { it.label to (it==value.touchMode) },onBack={ if(onBack!=null) onBack() else showControls() }) {
                save(value,value.copy(touchMode=TouchMode.entries[it])) { if(onBack!=null) onBack() else showControls() }
            }
    }
    fun showMouse(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.MOUSE,UiText.text("Controles › Mouse e teclado"),listOf(
            ConsoleAction(UiText.text("Modo: {0}",UiText.nested(value.desktopInput.label))) { chooseDesktopInput() },
            ConsoleAction(UiText.text("Sensibilidade do mouse emulado: {0}%",UiText.nested(value.mouseSensitivity))) {
                val levels=listOf(25,50,75,100,125,150,200,250,300)
                choices(UiText.text("Sensibilidade do mouse emulado"),UiText.text("Ajusta o analógico direito emulado. No modo nativo, use a sensibilidade do próprio jogo. Vale na próxima partida."),
                    levels.map { "$it%" to (it==value.mouseSensitivity) }) { save(value,value.copy(mouseSensitivity=levels[it])) }
            }
        ),SettingsPage.CONTROLS)
    }
    fun showGyro(onBack: () -> Unit = gyroBack): ConsoleDialog {
        gyroBack=onBack
        enter(SettingsPage.GYRO)
        val value=store.read()
        return ConsoleDialogs.menu(context,UiText.text("Configurações › {0}",UiText.nested(UiText.text("Controles › Giroscópio"))),listOf(
            ConsoleAction(UiText.text("Modo: {0}",UiText.nested(value.gyroMode.label))) { chooseGyro() },
            ConsoleAction(UiText.text("Fonte do giroscópio: {0}", UiText.nested(value.gyroSource.label))) {
                choices(UiText.text("Fonte do giroscópio"),UiText.text("O sensor do controle exige Android 12 ou superior e suporte do controle."),
                    GyroSource.entries.map { it.label to (it==value.gyroSource) },{ showGyro() }) {
                    save(value,value.copy(gyroSource=GyroSource.entries[it])) { showGyro() }
                }
            },
            ConsoleAction(UiText.text("Sensibilidade: {0}%",UiText.nested(value.gyroSensitivity))) {
                val levels=listOf(25,50,75,100,125,150,200,250,300)
                choices(UiText.text("Sensibilidade do giroscópio"),gyroApplyLabel(),levels.map { "$it%" to (it==value.gyroSensitivity) }) {
                    save(value,value.copy(gyroSensitivity=levels[it])) { showGyro() }
                }
            },
            ConsoleAction(UiText.text("Toque nativo Xbox: {0}",UiText.nested(if(value.gyroNativeTouch) UiText.text("Ativado — experimental") else UiText.text("Desativado")))) { chooseNativeGyro() },
            ConsoleAction(UiText.text("Testar giroscópio")) { showGyroTest(value.gyroSource,value.gyroSensitivity) }
        ),onBack=onBack)
    }

    private fun gyroApplyLabel() = if (liveGyro) UiText.text("Aplica ao retomar a partida.") else UiText.text("Vale na próxima partida.")

    private fun showGyroTest(source: GyroSource,sensitivity: Int) {
        val dialog = settingsDialog(UiText.text("Testar giroscópio"))
        dialog.body.addView(dialog.text(UiText.text("Mova o aparelho ou controle selecionado para testar o sensor e o analógico direito."),20))
        val sample = dialog.text("",20,color=DashboardTheme.muted)
        dialog.body.addView(sample)
        dialog.addAction(UiText.text("Voltar")) { showGyro() }
        dialog.setOnCancelListener { showGyro() }

        val preview = GyroController(context,{ dialog.window?.decorView?.display?.rotation ?: Surface.ROTATION_0 }) {}
        lateinit var poll: Runnable
        poll = Runnable {
            if (sample.hasWindowFocus()) {
                val stick = preview.apply(GamepadState())
                sample.text = preview.status + "\n" + UiText.text("Amostras: {0} • Analógico direito X: {1} / Y: {2}",
                    UiText.nested(preview.samplesReceived),UiText.nested(String.format(Locale.US,"%.2f",stick.rightX)),
                    UiText.nested(String.format(Locale.US,"%.2f",stick.rightY)))
                if (preview.listening) sample.postDelayed(poll,100)
            }
        }
        fun refreshSensor() {
            if (!sample.hasWindowFocus()) return
            sample.removeCallbacks(poll)
            preview.start(GyroMode.ALWAYS,sensitivity,nativeTouch=false,source=source)
            sample.post(poll)
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) refreshSensor() else { sample.removeCallbacks(poll); preview.stop() }
        }
        val inputs = if(source==GyroSource.CONTROLLER) context.getSystemService(InputManager::class.java) else null
        val devices = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = refreshSensor()
            override fun onInputDeviceRemoved(deviceId: Int) = refreshSensor()
            override fun onInputDeviceChanged(deviceId: Int) = refreshSensor()
        }
        dialog.show()
        val tree = sample.viewTreeObserver
        tree.addOnWindowFocusChangeListener(focus)
        inputs?.registerInputDeviceListener(devices, android.os.Handler(android.os.Looper.getMainLooper()))
        refreshSensor()
        dialog.addCleanup {
            inputs?.unregisterInputDeviceListener(devices)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(focus)
            sample.removeCallbacks(poll)
            preview.stop()
        }
    }

    fun chooseNativeGyro(): ConsoleDialog {
        enter(SettingsPage.GYRO);val value=store.read()
        return choices(UiText.text("Giroscópio com toque nativo Xbox"),UiText.text("Teste opcional: mantém os dedos no toque nativo e envia a mira pelo analógico direito. Alguns jogos podem alternar a interface de entrada. Se o modo estiver Desativado, ativar este teste escolhe Sempre. LT/LB exigem controle físico no toque nativo. {0}", UiText.nested(gyroApplyLabel())),
            listOf(UiText.text("Desativado") to !value.gyroNativeTouch,UiText.text("Ativado — experimental") to value.gyroNativeTouch)) {
            save(value,value.copy(gyroNativeTouch=it==1,gyroMode=if(it==1 && value.gyroMode==GyroMode.OFF) GyroMode.ALWAYS else value.gyroMode))
        }
    }
    fun showRumble(): ConsoleDialog {
        val value=store.read()
        return category(SettingsPage.RUMBLE,UiText.text("Controles › Vibração"),listOf(
            ConsoleAction(UiText.text("Vibração: {0}",UiText.nested(if(value.rumble) UiText.text("Ativada") else UiText.text("Desativada")))) {
                choices(UiText.text("Vibração"),UiText.text("Aciona o controle correspondente quando Android ou o teste USB oferecem suporte. Vale na próxima partida."),
                    listOf(UiText.text("Ativada") to value.rumble,UiText.text("Desativada") to !value.rumble)) { save(value,value.copy(rumble=it==0)) }
            },
            ConsoleAction(UiText.text("Vibração do celular no toque: {0}",UiText.nested(if(value.touchRumble) UiText.text("Ativada") else UiText.text("Desativada")))) {
                choices(UiText.text("Vibração do celular"),UiText.text("Usa rumble do jogador de toque quando não há controle físico associado. Requer Vibração ativada e vibrador no celular. Vale na próxima partida."),
                    listOf(UiText.text("Desativada") to !value.touchRumble,UiText.text("Ativada") to value.touchRumble)) { save(value,value.copy(touchRumble=it==1)) }
            },
            ConsoleAction(UiText.text("Testar vibração / verificar suporte"),testRumble)
        ),SettingsPage.CONTROLS)
    }
    fun chooseNetwork(): ConsoleDialog {
        enter(SettingsPage.NETWORK)
        val current=store.read()
        return choices(UiText.text("Preferência de rede"),UiText.text("Automático mantém IPv4/IPv6 disponíveis e verifica as rotas durante a partida. O WebRTC considera conectividade, recebimento e RTT, mantendo alternativas de reserva. As preferências IPv4/IPv6 dão até 750 ms iniciais à família escolhida e preservam a outra. A rota depende também do servidor; não há garantia do menor ping. Vale na próxima partida."),
            IpPreference.entries.map { it.label to (it==current.ipPreference) }) { save(current,current.copy(ipPreference=IpPreference.entries[it])) }
    }
    fun chooseDesktopInput(): ConsoleDialog {
        enter(SettingsPage.MOUSE)
        val current=store.read()
        return choices(UiText.text("Mouse e teclado"),UiText.text("Emulação: WASD move, mouse mira, clique esquerdo RT, direito LT; Espaço A, Ctrl B, R/E X, Q Y, Shift L3, Tab View e Esc Menu. Nativo envia teclas e mouse apenas em jogos que anunciam suporte. F7 abre o menu do app; F8 captura/solta entradas. Captura relativa requer Android 8+. Vale na próxima partida."),
            DesktopInputMode.entries.map { it.label to (it==current.desktopInput) },{ showMouse() }) { save(current,current.copy(desktopInput=DesktopInputMode.entries[it])) { showMouse() } }
    }
    fun chooseGyro(): ConsoleDialog {
        enter(SettingsPage.GYRO)
        val current=store.read()
        return choices(UiText.text("Mira por movimento"),UiText.text("Emula o analógico direito em controle/HUD. Para combinar com toque nativo Xbox, ative também o teste na categoria Giroscópio. LT/LB no toque nativo dependem de controle físico. {0}", UiText.nested(gyroApplyLabel())),
            GyroMode.entries.map { it.label to (it==current.gyroMode) },{ showGyro() }) { save(current,current.copy(gyroMode=GyroMode.entries[it])) { showGyro() } }
    }
    fun showAudio(onBack: (() -> Unit)?=null): ConsoleDialog {
        if(onBack==null && liveVideoMode==null) return showSettings(SettingsPage.AUDIO)
        enter(SettingsPage.AUDIO)
        val back: () -> Unit=onBack ?: { show() }
        return settingsDialog(UiText.text("Áudio e microfone")).apply {
            audioRows { showAudio(onBack) }.forEach { addMenuItem(it.label,it.action) }
            addAction(UiText.text("Voltar"),back)
            setOnCancelListener { back() }
            show()
        }
    }

    private fun audioRows(onReturn: () -> Unit): List<ConsoleAction> {
        val current=store.read()
        val audio=context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        return listOfNotNull(
            if(audio.isVolumeFixed) null else ConsoleAction(UiText.text("Volume de mídia: {0}/{1}",UiText.nested(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)),UiText.nested(audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)))) {
                val values=(0..audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)).toList()
                choices(UiText.text("Volume de mídia"),UiText.text("Áudio e microfone"),values.map { it.toString() to (it==audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) },{ onReturn() }) {
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,values[it],0);onReturn()
                }
            },
            microphone?.let { ConsoleAction(microphoneLabel(),it) },
            if(liveVideoMode!=null) null else ConsoleAction(UiText.text("Perfil de áudio: {0}", UiText.nested(current.audioMode.label))) {
                choices(UiText.text("Perfil de áudio"), UiText.text("Solicita saída de áudio de baixa latência no Android 8 ou superior. O sistema pode ignorar o pedido. Padrão mantém o comportamento atual. Vale na próxima partida; volte ao padrão se houver estalos ou cortes."),
                    AudioPlaybackMode.entries.map { it.label to (it==current.audioMode) }, { onReturn() }) {
                    save(current,current.copy(audioMode=AudioPlaybackMode.entries[it])) { onReturn() }
                }
            },
            ConsoleAction(UiText.text("Ganho de áudio: {0}%",UiText.nested(current.audioGain))) {
                val values=listOf(0,50,75,100,125,150,175,200,250,300)
                choices(UiText.text("Ganho de áudio"),UiText.text("100% preserva o volume. Acima disso, o app aumenta gradualmente e reduz o ganho ao detectar picos no áudio. Transientes ainda podem distorcer. Comece em 125%. O foco e o volume do Android continuam ativos."),
                    values.map { "$it%" to (it==current.audioGain) },{ onReturn() }) { save(current,current.copy(audioGain=values[it])) { onReturn() } }
            },
            if(liveVideoMode!=null) null else ConsoleAction(UiText.text("Microfone nas próximas partidas: {0}",UiText.nested(if(current.microphone) UiText.text("Disponível — inicia mudo") else UiText.text("Desativado")))) {
                choices(UiText.text("Microfone opcional"),UiText.text("A sessão sempre inicia muda. Com esta opção, o app consulta suporte a chat; ative a captura no menu da partida, após conceder a permissão do Android. Ir para segundo plano silencia. Alguns servidores podem não oferecer chat."),
                    listOf(UiText.text("Desativado") to !current.microphone,UiText.text("Disponível — iniciar mudo") to current.microphone),{ onReturn() }) { save(current,current.copy(microphone=it==1)) { onReturn() } }
            }
        )
    }

}
