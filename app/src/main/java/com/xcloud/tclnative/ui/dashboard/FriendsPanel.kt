package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.signaling.GameTitle
import kotlinx.coroutines.*

/** A foreground-only snapshot. Closing a panel cancels its request and late account results are ignored. */
class FriendsPanel(private val context: Context,private val scope: CoroutineScope,
                   private val load: suspend () -> List<XboxFriend>,private val games: () -> List<GameTitle>,
                   private val valid: () -> Boolean,private val play: (GameTitle) -> Unit,private val back: () -> Unit) {
    private var dialog: ConsoleDialog?=null
    private var request: Job?=null
    fun close() { request?.cancel();request=null;dialog?.dismiss();dialog=null }
    fun show() {
        close()
        if(!valid()) return
        val panel=ConsoleDialog(context,UiText.text("Amigos"))
        dialog=panel
        panel.addPersistentAction(UiText.text("Atualizar")) { refresh(panel) }
        panel.addAction(UiText.text("Voltar"),back)
        panel.setOnCancelListener { back() }
        panel.addCleanup { request?.cancel() }
        panel.show();refresh(panel)
    }
    private fun refresh(panel: ConsoleDialog) {
        request?.cancel()
        panel.body.removeAllViews()
        panel.body.addView(panel.text(UiText.text("Consultando amigos…")))
        request=scope.launch {
            try {
                val friends=load()
                if(dialog!==panel || !panel.isShowing || !valid()) return@launch
                render(panel,friends,0)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) {
                if(dialog!==panel || !panel.isShowing || !valid()) return@launch
                panel.body.removeAllViews()
                panel.body.addView(panel.text(UiText.text("Não foi possível consultar os amigos. Verifique a conexão e as permissões de privacidade da conta.")))
                (error as? CloudHttpException)?.let { panel.body.addView(panel.text("FRIENDS/HTTP_${it.status}",18,color=DashboardTheme.muted)) }
            }
        }
    }
    private fun render(panel: ConsoleDialog,friends: List<XboxFriend>,page: Int) {
        panel.body.removeAllViews()
        panel.body.addView(panel.text(UiText.text("Presença informada pelo Xbox. Atualize para consultar novamente."),18,color=DashboardTheme.muted))
        if(friends.isEmpty()) panel.body.addView(panel.text(UiText.text("Nenhum amigo disponível nesta consulta.")))
        friends.drop(page*50).take(50).forEachIndexed { index,friend ->
            val row=panel.addMenuItem("${friend.gamertag} • ${status(friend.presence)}"+
                friend.activity?.let { "\n$it" }.orEmpty()) { detail(friend) }
            if(index==0) { panel.focusFirst(row);row.requestFocus() }
        }
        if(page>0) panel.body.addView(panel.keyButton(UiText.text("Página anterior")) { render(panel,friends,page-1) })
        if((page+1)*50<friends.size) panel.body.addView(panel.keyButton(UiText.text("Próxima página")) { render(panel,friends,page+1) })
    }
    private fun detail(friend: XboxFriend) {
        if(!valid()) return
        val panel=ConsoleDialog(context,friend.gamertag)
        dialog=panel
        panel.body.addView(panel.text(status(friend.presence)))
        friend.activity?.let { panel.body.addView(panel.text(it,20,color=DashboardTheme.muted)) }
        val game=friend.cloudGame(games())
        if(game!=null) {
            panel.body.addView(panel.text(game.name,25,true))
            panel.body.addView(panel.text(UiText.text("Abre sua própria sessão na nuvem. O ingresso na mesma partida do amigo ainda não está disponível."),18,color=DashboardTheme.muted))
            panel.addMenuItem(UiText.text("Abrir jogo na nuvem")) { if(valid()) play(game) }
        }
        panel.addAction(UiText.text("Voltar")) { show() }
        panel.setOnCancelListener { show() };panel.show()
    }
    companion object {
        fun status(presence: FriendPresence)=when(presence) {
            FriendPresence.ONLINE -> UiText.text("Online")
            FriendPresence.AWAY -> UiText.text("Ausente")
            FriendPresence.OFFLINE -> UiText.text("Offline")
            FriendPresence.UNKNOWN -> UiText.text("Presença indisponível")
        }
    }
}
