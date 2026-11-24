package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import com.xcloud.tclnative.auth.CloudRegion
import com.xcloud.tclnative.settings.SessionOptions
import com.xcloud.tclnative.settings.SessionDnsPreference
import com.xcloud.tclnative.settings.SessionOptionsStore
import com.xcloud.tclnative.settings.SessionOptionField
import android.widget.LinearLayout

class SessionOptionsPanel(private val context: Context,private val store: SessionOptionsStore,
    private val regions: List<CloudRegion>,private val gameId: String?=null,private val gameName: String?=null,
    private val back: () -> Unit) {
    private var selectedGame=gameId
    private var returnTo: () -> Unit={ show() }
    companion object {
        private val bitrateOptions = listOf(0,1000,2000,3000,5000,6000,8000,10000,12000,15000,20000,30000,50000)
        private fun bitrateLabel(kbps: Int) = if(kbps==0) UiText.text("Automático") else "${kbps/1000.0} Mbps"

        internal fun liveBitrate(context: Context,current: Int,back: () -> Unit,reload: (Int) -> Unit): ConsoleDialog {
            val notice=UiText.text("Alterar o bitrate recarrega a transmissão da partida atual. O Xbox decide o bitrate recebido.")
            return ConsoleDialog(context,UiText.text("Bitrate máximo")).apply {
                body.addView(text(notice,20,color=DashboardTheme.muted))
                bitrateOptions.forEach { value ->
                    val row=addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(value==current) "✓" else "○"),UiText.nested(bitrateLabel(value)))) {
                        if(value==current) back() else ConsoleDialogs.message(context,UiText.text("Bitrate máximo: {0}",UiText.nested(bitrateLabel(value))),notice,
                            listOf(ConsoleAction(UiText.text("Voltar")) { liveBitrate(context,current,back,reload) },
                                ConsoleAction(UiText.text("Recarregar transmissão — experimental")) { reload(value) }),
                            onCancel={ liveBitrate(context,current,back,reload) })
                    }
                    if(value==current) focusFirst(row)
                }
                addAction(UiText.text("Voltar"),back);setOnCancelListener { back() };show()
            }
        }
    }
    fun show(): ConsoleDialog = ConsoleDialog(context,UiText.text("Opções das partidas")).apply {
        appendTo(this) { this@SessionOptionsPanel.show() }
        addAction(UiText.text("Voltar"),back);setOnCancelListener { back() };show()
    }
    /** The same scoped controls are embedded in Video; no duplicate scope-selection screen. */
    fun appendTo(dialog: ConsoleDialog,onReturn: () -> Unit) {
        returnTo=onReturn
        val game=selectedGame
        val value=store.read(game)
        val scope=if(game==null) UiText.text("Padrão das partidas") else UiText.text("Este jogo: {0}",UiText.nested(gameName ?: game))
        dialog.addSection(scope)
        dialog.body.addView(dialog.text(UiText.text("Bitrate, região e idioma do jogo valem na próxima partida."),18,color=DashboardTheme.muted))
        if(gameId!=null) {
            val scopes=LinearLayout(dialog.context).apply { orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false }
            listOf(gameId to UiText.text("Este jogo"),null to UiText.text("Padrão das partidas")).forEach { (id,label) ->
                val button=dialog.keyButton(UiText.join(if(id==game) "✓ " else "",label)) {
                    selectedGame=id;dialog.dismiss();onReturn()
                }.apply { isSelected=id==game;minimumHeight=dialog.touchSize() }
                scopes.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=dialog.px(6) })
            }
            dialog.body.addView(scopes)
        }
        fun source(field: SessionOptionField)=if(game==null) "" else UiText.join(" • ",
            if(store.hasOverride(game,field)) UiText.text("Personalizado") else UiText.text("Usando o padrão"))
        dialog.addMenuItem(UiText.join(UiText.text("Bitrate máximo: {0}",UiText.nested(if(value.maxBitrateKbps==0) UiText.text("Automático") else "${value.maxBitrateKbps/1000.0} Mbps")),source(SessionOptionField.BITRATE))) { bitrate() }
        dialog.addMenuItem(UiText.join(UiText.text("Região: {0}",UiText.nested(value.region.ifBlank { UiText.text("Automático") })),source(SessionOptionField.REGION))) { region() }
        dialog.addMenuItem(UiText.join(UiText.text("Idioma do jogo: {0}",UiText.nested(SessionOptions.LOCALES[value.locale])),source(SessionOptionField.LOCALE))) { locale() }
        dialog.addMenuItem(UiText.join(UiText.text("DNS: {0}", UiText.nested(value.dns.label)), source(SessionOptionField.DNS))) { dns() }
        if(game!=null && store.hasOverride(game)) dialog.addMenuItem(UiText.text("Usar o padrão das partidas")) { store.reset(game);onReturn() }
    }
    private fun choices(title: String,description: String,rows: List<Pair<String,Boolean>>,field: SessionOptionField,select: (Int)->Unit): ConsoleDialog =
        ConsoleDialog(context,title).apply {
            body.addView(text(description,20,color=DashboardTheme.muted))
            selectedGame?.let { game ->
                addMenuItem(UiText.text("Usar o padrão das partidas")) { store.inherit(game,field);returnTo() }
            }
            rows.forEachIndexed { index,(label,selected) ->
                val row=addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(selected) "✓" else "○"),UiText.nested(label))) { select(index);returnTo() }
                if(selected) focusFirst(row)
            }
            addAction(UiText.text("Voltar")) { returnTo() };setOnCancelListener { returnTo() };show()
        }
    fun bitrate(): ConsoleDialog {
        val current=store.read(selectedGame)
        return choices(UiText.text("Bitrate máximo"),UiText.text("Automático mantém o orçamento do perfil de vídeo. Um teto manual é independente da resolução. Valores maiores exigem mais rede e podem piorar a estabilidade. O Xbox decide o bitrate recebido. Vale na próxima partida."),
            bitrateOptions.map { bitrateLabel(it) to (it==current.maxBitrateKbps) },SessionOptionField.BITRATE) {
            store.set(selectedGame,SessionOptionField.BITRATE,bitrateOptions[it])
        }
    }
    fun region(): ConsoleDialog {
        val current=store.read(selectedGame)
        val values=listOf("" to UiText.text("Automático — região padrão da conta"))+regions.map { it.name to it.name }
        return choices(UiText.text("Região do servidor"),UiText.text("Somente as regiões oferecidas pelo Xbox a esta conta. A escolha solicita a sessão nessa região; não prevê o ping ou a fila do jogo. Se uma região salva deixar de ser oferecida, o app usa a padrão e informa no diagnóstico. Vale na próxima partida."),
            values.map { it.second to (it.first==current.region) },SessionOptionField.REGION) { store.set(selectedGame,SessionOptionField.REGION,values[it].first) }
    }
    fun locale(): ConsoleDialog {
        val current=store.read(selectedGame);val values=SessionOptions.LOCALES.entries.toList()
        return choices(UiText.text("Idioma da sessão"),UiText.text("Esta opção é independente do idioma dos menus do app. O idioma é solicitado ao Xbox; alguns jogos usam suas próprias configurações ou podem ignorá-lo. Vale na próxima partida."),
            values.map { it.value to (it.key==current.locale) },SessionOptionField.LOCALE) { store.set(selectedGame,SessionOptionField.LOCALE,values[it].key) }
    }

    private fun dns(): ConsoleDialog {
        val current = store.read(selectedGame).dns
        val values = SessionDnsPreference.entries
        return choices(UiText.text("DNS da sessão"),
            UiText.text("Compara o DNS do sistema e cinco provedores ao iniciar. Escolhe o mais rápido a responder aos dois testes de resolução do servidor."),
            values.map { it.label to (it == current) }, SessionOptionField.DNS) {
            store.set(selectedGame, SessionOptionField.DNS, values[it])
        }
    }
}
