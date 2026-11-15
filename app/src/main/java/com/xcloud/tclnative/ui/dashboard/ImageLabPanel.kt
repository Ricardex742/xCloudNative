package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.*

/** All experimental image controls have one home and an immediate original-image comparison. */
class ImageLabPanel(private val context: Context,private val store: PlaybackSettingsStore,
    private val changed: (PlaybackSettings)->Unit,private val back: ()->Unit) {
    private fun state(v: Boolean)=if(v)UiText.text("Ativado")else UiText.text("Desativado")
    private fun protection(v: HudProtection)=when(v){
        HudProtection.OFF->UiText.text("Desativado")
        HudProtection.AUTO->UiText.text("Automático — textos e bordas")
        HudProtection.CORNERS->UiText.text("Quatro cantos — HUDs")
    }
    private fun generation(v: ExperimentalFrameGeneration)=if(v==ExperimentalFrameGeneration.OFF)UiText.text("Desativado")else UiText.text("30 → até 60 FPS — experimental")
    private fun scale(v: ImageSupersampling)=if(v==ImageSupersampling.OFF)UiText.text("Desativado")else String.format(UiText.locale,"%.2f×",v.scale)
    private fun save(lab: ImageLabSettings){val value=store.read().copy(imageLab=lab.bounded());store.save(value);changed(value);show()}
    fun show(): ConsoleDialog {
        val lab=store.read().imageLab
        return ConsoleDialog(context,UiText.text("Laboratório de imagem — experimental")).apply {
            body.addView(text(UiText.text("Começa desativado. Ative uma opção por vez. Reconstrução e superamostragem mudam a imagem; geração de quadros pode aumentar o atraso. A proteção reduz efeitos pesados sem mudar suas escolhas salvas."),18))
            if(DevicePerformancePolicy.read(context).constrainedMemory)
                body.addView(text(UiText.text("Pouca memória: usa reconstrução leve; neural, superamostragem e geração ficam suspensas neste aparelho."),18))
            addMenuItem(UiText.text("Laboratório: {0}",UiText.nested(state(lab.enabled)))) {save(lab.copy(enabled=!lab.enabled))}
            if(lab.enabled){
                addMenuItem(UiText.text("Restauração residual: {0}%",UiText.nested(lab.restoration))){
                    val levels=listOf(0,25,35,50,75,100)
                    choose(UiText.text("Restauração residual"),UiText.text("Realça detalhes com limites de luminância e cor. 0% desliga esta reconstrução. Neural usa o pequeno modelo do app quando suportado; sem suporte, aplica reconstrução espacial."),
                        levels.map {"$it%" to (it==lab.restoration)}){save(lab.copy(restoration=levels[it]))}
                }
                addMenuItem(UiText.text("Reconstrução neural: {0}",UiText.nested(state(lab.neural)))){save(lab.copy(neural=!lab.neural))}
                addMenuItem(UiText.text("Proteção de HUDs e textos: {0}",UiText.nested(protection(lab.protection)))){
                    choose(UiText.text("Proteção de HUDs e textos"),UiText.text("Automático procura traços finos de alto contraste; não é reconhecimento de texto. Quatro cantos preserva regiões fixas. Os controles virtuais do app ficam fora do vídeo."),
                        HudProtection.entries.map {protection(it) to (it==lab.protection)}){save(lab.copy(protection=HudProtection.entries[it]))}
                }
                addMenuItem(UiText.text("Superamostragem: {0}",UiText.nested(scale(lab.supersampling)))){
                    choose(UiText.text("Superamostragem"),UiText.text("Processa em tamanho intermediário maior e reduz à saída atual. Não muda a resolução pedida ao Xbox. Pode suavizar contornos; exige mais GPU e memória. O tamanho efetivo aparece no diagnóstico."),
                        ImageSupersampling.entries.map {scale(it) to (it==lab.supersampling)}){save(lab.copy(supersampling=ImageSupersampling.entries[it]))}
                }
                addMenuItem(UiText.text("Geração de quadros: {0}",UiText.nested(generation(lab.frameGeneration)))){
                    choose(UiText.text("Geração de quadros"),UiText.text("Aplica na próxima partida. Interpola conteúdo estável a 30 FPS para até 60 submissões/s; também procura duplicatas alternadas em streams a 60. Requer GLES 3 e memória disponível, até 2,5 MP. Acrescenta espera e pode gerar artefatos. Cortes, dúvidas, sobrecarga e comparação preservam os quadros originais. Não altera o FPS do jogo nem garante 60 Hz físicos."),
                        ExperimentalFrameGeneration.entries.map {generation(it) to (it==lab.frameGeneration)}){save(lab.copy(frameGeneration=ExperimentalFrameGeneration.entries[it]))}
                }
                addMenuItem(UiText.text("Comparação: {0}",UiText.nested(lab.comparison.label))){
                    choose(UiText.text("Comparação"),UiText.text("Compara o mesmo quadro, mantendo os ajustes de cor. Original e Dividida suspendem a geração; reative-a na próxima partida."),
                        ImageComparison.entries.map {it.label to (it==lab.comparison)}){save(lab.copy(comparison=ImageComparison.entries[it]))}
                }
                addMenuItem(UiText.text("Desativar laboratório")){save(lab.copy(enabled=false))}
            }
            addAction(UiText.text("Voltar"),back);setOnCancelListener{back()};show()
        }
    }
    private fun choose(title: String,description: String,rows: List<Pair<String,Boolean>>,select: (Int)->Unit){
        ConsoleDialog(context,title).apply {
            body.addView(text(description,18))
            rows.forEachIndexed {i,(label,selected)->val row=addMenuItem((if(selected) "✓  " else "○  ")+label){select(i)};if(selected)focusFirst(row)}
            addAction(UiText.text("Voltar")){this@ImageLabPanel.show()};setOnCancelListener{this@ImageLabPanel.show()};show()
        }
    }
}
