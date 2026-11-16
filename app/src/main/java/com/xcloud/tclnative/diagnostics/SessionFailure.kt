package com.xcloud.tclnative.diagnostics

import com.xcloud.tclnative.i18n.UiText

import com.xcloud.tclnative.webrtc.XboxServerDisconnectException
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.CloudRequestException
import java.io.EOFException
import java.io.InterruptedIOException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.json.JSONObject

enum class FailureStage(private val sourceLabel: String) {
    KEEPALIVE("Manutenção da sessão Xbox"), TRANSPORT("Conexão WebRTC"), CHANNELS("Canal de controles"),
    VIDEO_CODEC("Decodificador de vídeo"), RENDERER("Apresentação do vídeo"), VIDEO("Recebimento do vídeo"),
    SESSION_STATE("Estado da sessão Xbox"), SIGNALING("Negociação da sessão"), NETWORK("Requisição de rede"),
    UNKNOWN("Origem não identificada");
    val label: String get()=UiText.text(sourceLabel)
}
enum class FailureKind(private val sourceLabel: String) {
    SESSION_ENDED("Sessão Xbox encerrada"), HTTP("resposta HTTP de erro"), TIMEOUT("tempo limite excedido"), DNS("falha ao localizar o servidor"),
    TLS("falha na conexão segura"), CONNECTION("falha na conexão de rede"),
    ICE_FAILED("a conexão ICE falhou"), ICE_CLOSED("a conexão ICE foi fechada"),
    CHANNEL_CLOSED("o canal de controles foi fechado"), CONTROL_SEND("falha no envio de controles/configuração"),
    CODEC("erro informado pelo decoder"), RENDERER("erro na apresentação local"),
    NO_VIDEO("nenhum quadro foi apresentado no prazo"), IO("falha de comunicação"),
    UNCLASSIFIED("erro sem classificação técnica");
    val label: String get()=UiText.text(sourceLabel)
}

/** A bounded description, never an arbitrary exception message, URL, SDP or HTTP body. */
data class SessionFailure(val stage: FailureStage,val kind: FailureKind,val httpStatus: Int?=null,
                          val serviceCode: String?=null,val exceptionType: String?=null,val channel: String?=null) {
    private val safeStatus get()=httpStatus?.takeIf { it in 100..599 }
    private val safeService get()=serviceCode?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
    private val safeType get()=exceptionType?.takeIf { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")) }
    private val safeChannel get()=channel?.takeIf { it in setOf("input","control","message") }
    val code get()="${stage.name}/${kind.name}"+(if(kind==FailureKind.HTTP) safeStatus?.let { "_$it" }.orEmpty() else "")
    val description: String get()=UiText.join(UiText.text("{0}: {1}",UiText.nested(stage.label),UiText.nested(kind.label)),(if(kind==FailureKind.HTTP) safeStatus?.let { " $it" }.orEmpty() else ""),safeService?.let { UiText.text(" • código do serviço: {0}",UiText.nested(it)) }.orEmpty(),safeChannel?.let { " • $it" }.orEmpty(),UiText.text("\nCódigo técnico: {0}",UiText.nested(code)),safeType?.let { " • $it" }.orEmpty())
    fun json()=JSONObject().put("stage",stage.name).put("kind",kind.name).put("status",safeStatus)
        .put("serviceCode",safeService).put("type",safeType).put("channel",safeChannel)
    companion object {
        fun read(json: JSONObject?): SessionFailure? {
            if(json==null) return null
            val stage=FailureStage.entries.firstOrNull { it.name==json.optString("stage") } ?: return null
            val kind=FailureKind.entries.firstOrNull { it.name==json.optString("kind") } ?: return null
            val status=json.optInt("status").takeIf { it in 100..599 }
            val raw=SessionFailure(stage,kind,status,json.optString("serviceCode"),json.optString("type"),json.optString("channel"))
            return raw.copy(serviceCode=raw.safeService,exceptionType=raw.safeType,channel=raw.safeChannel)
        }
        fun from(error: Throwable): SessionFailure {
            val chain=generateSequence(error) { it.cause }.take(8).toList()
            val server=chain.filterIsInstance<XboxServerDisconnectException>().firstOrNull()
            val http=chain.filterIsInstance<CloudHttpException>().firstOrNull()
            val request=chain.filterIsInstance<CloudRequestException>().firstOrNull()
            val rendering=chain.any { it is RenderFailureException }
            val message=error.message.orEmpty()
            val prefix=when {
                server!=null -> "SESSION_STATE"
                rendering -> "RENDERER"
                http!=null -> http.stage
                request!=null -> request.stage
                else -> message.substringBefore(':').take(32)
            }
            val stage=when(prefix) {
                "KEEPALIVE","RECONNECT_KEEPALIVE" -> FailureStage.KEEPALIVE
                "TRANSPORT","ICE","RECONNECT_TIMEOUT" -> FailureStage.TRANSPORT
                "CHANNELS" -> FailureStage.CHANNELS
                "VIDEO_CODEC" -> FailureStage.VIDEO_CODEC
                "RENDERER" -> FailureStage.RENDERER
                "VIDEO","RECONNECT_FRAME_TIMEOUT" -> FailureStage.VIDEO
                "SESSION_STATE" -> FailureStage.SESSION_STATE
                "SDP","SDP_LOCAL","SDP_OFFER","SDP_ANSWER","ICE_LOCAL","ICE_REMOTE","PLAY","CONSOLE_CONNECT" -> FailureStage.SIGNALING
                else -> if(http!=null || request!=null) FailureStage.NETWORK else FailureStage.UNKNOWN
            }
            val kind=when {
                server!=null -> FailureKind.SESSION_ENDED
                http!=null -> FailureKind.HTTP
                prefix=="RECONNECT_TIMEOUT" -> FailureKind.TIMEOUT
                rendering -> FailureKind.RENDERER
                stage==FailureStage.TRANSPORT && message in setOf("ICE: FAILED","TRANSPORT: ICE FAILED") -> FailureKind.ICE_FAILED
                stage==FailureStage.TRANSPORT && message in setOf("ICE: CLOSED","TRANSPORT: ICE CLOSED") -> FailureKind.ICE_CLOSED
                stage==FailureStage.CHANNELS && message in setOf("CHANNELS: input fechado","CHANNELS: control fechado","CHANNELS: message fechado") -> FailureKind.CHANNEL_CLOSED
                stage==FailureStage.CHANNELS -> FailureKind.CONTROL_SEND
                stage==FailureStage.VIDEO_CODEC -> FailureKind.CODEC
                stage==FailureStage.RENDERER -> FailureKind.RENDERER
                stage==FailureStage.VIDEO -> FailureKind.NO_VIDEO
                chain.any { it is SSLException } -> FailureKind.TLS
                chain.any { it is UnknownHostException } -> FailureKind.DNS
                chain.any { it is InterruptedIOException } -> FailureKind.TIMEOUT
                chain.any { it is ConnectException || it is SocketException || it is EOFException } -> FailureKind.CONNECTION
                chain.any { it is IOException } -> FailureKind.IO
                else -> FailureKind.UNCLASSIFIED
            }
            return SessionFailure(stage,kind,http?.status,server?.notice?.reason?.wire ?: http?.serviceCode,
                chain.last().javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")) },
                setOf("input","control","message").firstOrNull { message=="CHANNELS: $it fechado" })
        }
    }
}

/** Preserve the rendering origin without retaining its platform error message in a report. */
class RenderFailureException(cause: Exception): IOException("RENDERER: falha na apresentação local",cause)
