package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.i18n.UiText
import java.util.Locale

enum class FrameGenerationPhase(private val source: String) {
    OFF("Desativada"), WAITING("Aguardando conteúdo estável a 30 FPS"), ACTIVE("Ativa"),
    UNSUPPORTED("Indisponível neste aparelho ou modo"), NEXT_SESSION("Aplica na próxima partida"),
    PROTECTED("Suspensa pela proteção de desempenho"), LATE("Quadros gerados fora do prazo"),
    ERROR("Falha no processamento"), COMPARISON("Suspensa durante a comparação");
    val label get()=UiText.text(source)
}

/** Rates describe our renderer pipeline, never the game's or the physical display's FPS. */
data class FrameGenerationSnapshot(val phase: FrameGenerationPhase,val generated: Long=0,
    val submittedPerSecond: Double?=null,val drawsPerSecond: Double?=null,val generatedPerSecond: Double?=null) {
    fun text()=UiText.text("Geração: {0} | gerados {1} | enviados/s {2} | desenhos/s {3}",
        UiText.nested(phase.label),UiText.nested(generated),UiText.nested(rate(submittedPerSecond)),UiText.nested(rate(drawsPerSecond)))
    private fun rate(value: Double?)=value?.let { String.format(Locale.US,"%.1f",it) } ?: "n/d"
}
