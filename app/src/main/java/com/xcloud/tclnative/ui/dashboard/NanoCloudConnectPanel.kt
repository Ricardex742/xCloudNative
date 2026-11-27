package com.xcloud.tclnative.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import com.xcloud.tclnative.diagnostics.NanoCloudConnectTest
import com.xcloud.tclnative.i18n.UiText

internal class NanoCloudConnectPanel(
    private val context: Context,
    private val openGuide: () -> Unit,
    private val arm: () -> Unit,
    private val reset: () -> Unit,
    private val catalog: () -> Unit,
    private val back: () -> Unit
) {
    fun show(test: NanoCloudConnectTest?): ConsoleDialog =
        settingsPanel(context, UiText.text("Teste do Xbox Nano"), ConsoleGlyph.CONTROLLER,
            ConsolePanelPurpose.SETTINGS_LIST).apply {
            when {
                test == null -> prepare()
                test.armed -> waiting()
                else -> result(test)
            }
            addAction(UiText.text("Voltar"), back)
            setOnCancelListener { back() }
            show()
        }

    private fun ConsoleDialog.prepare() {
        addSection(UiText.text("Prepare o Cloud Connect"))
        note(UiText.text("Configure o Nano no Xbox Beta ou no Xbox Accessories com a mesma conta deste app. Confirme o Cloud Connect numa partida oficial e encerre essa sessão."))
        addMenuItem(ConsoleGlyph.NETWORK, UiText.text("Guia oficial do Cloud Connect"), openGuide)
        note(UiText.text("Desconecte o Nano do Bluetooth e do USB deste aparelho. Mantenha o controle no Wi-Fi."))
        addAction(UiText.text("Testar na próxima partida"), arm)
    }

    private fun ConsoleDialog.waiting() {
        addSection(UiText.text("Testar na próxima partida"))
        note(UiText.text("Abra o mesmo jogo no catálogo. Quando o vídeo aparecer, use apenas o Nano e registre o resultado nos atalhos da partida ou volte a esta tela."))
        addMenuItem(ConsoleGlyph.EXIT, UiText.text("Cancelar"), reset)
        addAction(UiText.text("Voltar ao catálogo"), catalog)
    }

    private fun ConsoleDialog.result(test: NanoCloudConnectTest) {
        if (test.localControllerSeen) note(UiText.text("Controle local detectado. O teste fica inconclusivo."))
        if (test.videoPresented) {
            note(UiText.text("Registre o que aconteceu ao usar somente o Nano na partida."))
            test.responseObserved?.let {
                addSection(UiText.text("Resultado observado: {0}", UiText.nested(responseLabel(it))))
            }
            for (responded in listOf(true, false)) {
                addMenuItem(ConsoleGlyph.CONTROLLER, responseLabel(responded)) {
                    test.recordResponse(responded)
                    this@NanoCloudConnectPanel.show(test)
                }
            }
        } else note(UiText.text("O vídeo da partida ainda não foi apresentado."))

        if (test.ended) addMenuItem(ConsoleGlyph.REFRESH, UiText.text("Novo teste"), reset)
        if (test.responseObserved != null || test.ended) {
            addPersistentAction(UiText.text("Copiar detalhes")) {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(UiText.text("Teste do Xbox Nano"), test.report()))
                Toast.makeText(context, UiText.text("Detalhes copiados!"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun responseLabel(responded: Boolean) =
        if (responded) UiText.text("O jogo respondeu") else UiText.text("O jogo não respondeu")

    private fun ConsoleDialog.note(value: String) {
        body.addView(text(value, 20, color = DashboardTheme.muted), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = px(10)
        })
    }
}
