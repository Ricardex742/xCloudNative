package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.i18n.UiText

import android.view.Window
import android.view.Display
import java.util.Locale

/** Holds a window request only while playing. Android still controls the actual mode. */
class PlaybackDisplayPreference(private val window: Window) {
    private data class Original(val modeId: Int, val refreshRate: Float, val displayId: Int?)
    private var original: Original? = null
    private var requested: OutputMode? = null
    private var result = "SYSTEM"

    private fun mode(value: Display.Mode) =
        OutputMode(value.modeId, value.physicalWidth, value.physicalHeight, value.refreshRate)

    internal fun apply(display: Display?, fps: Int, tv4k: Boolean): Boolean {
        val next = runCatching {
            if (display == null || !display.isValid) null
            else {
                val modes = display.supportedModes.map(::mode)
                val id = DisplayModePolicy.playbackMode(mode(display.mode), modes, fps, tv4k)
                modes.firstOrNull { it.id == id }
            }
        }.getOrNull()
        validate(display)
        if (next == null) {
            restore()
            result = "NO_COMPATIBLE_MODE"
        } else request(next)
        return requested != null
    }

    internal fun request(mode: OutputMode) {
        request(mode.id)
        if (result == "REQUESTED") requested = mode
    }

    /** A mode ID belongs to a particular display and its currently advertised geometry. */
    internal fun validate(display: Display?): Boolean {
        val active = requested ?: return false
        val saved = original ?: return false
        val stillOffered = runCatching {
            display != null && display.isValid && saved.displayId == display.displayId &&
                display.supportedModes.map(::mode).filter { it.id == active.id }.distinct() == listOf(active)
        }.getOrDefault(false)
        if (!stillOffered) {
            restore()
            result = "MODE_RETIRED"
        }
        return stillOffered
    }

    fun request(modeId: Int?) {
        if (modeId == null) { restore(); return }
        val attrs = window.attributes
        if (original == null) original = Original(attrs.preferredDisplayModeId, attrs.preferredRefreshRate, window.decorView.display?.displayId)
        result = try {
            if (attrs.preferredDisplayModeId != modeId || attrs.preferredRefreshRate != 0f) {
                window.attributes = attrs.apply {
                    preferredDisplayModeId = modeId
                    preferredRefreshRate = 0f
                }
            }
            "REQUESTED"
        } catch (_: RuntimeException) {
            restore()
            "REQUEST_REJECTED"
        }
    }

    fun restore() {
        val saved = original ?: return
        requested = null
        result = try {
            window.attributes = window.attributes.apply {
                val sameOutput = saved.displayId == window.decorView.display?.displayId
                preferredDisplayModeId = if (sameOutput) saved.modeId else 0
                preferredRefreshRate = if (sameOutput) saved.refreshRate else 0f
            }
            original = null
            "RESTORED"
        } catch (_: RuntimeException) {
            "RESTORE_REJECTED"
        }
    }

    /** A successful request is distinct from the active physical mode and the app's rate override. */
    fun diagnostics(display: Display?): String = runCatching {
        if(display==null) return@runCatching UiText.text("Modo de tela: n/d")
        fun rate(hz: Float)=if(hz.isFinite()) String.format(Locale.US,"%.2f",hz) else "n/d"
        fun describe(mode: Display.Mode)="#${mode.modeId} ${mode.physicalWidth}x${mode.physicalHeight}@${rate(mode.refreshRate)}Hz"
        val modes=display.supportedModes.toList()
        val modeId=window.attributes.preferredDisplayModeId
        val request=modes.firstOrNull { it.modeId==modeId }?.let(::describe)
            ?: if(modeId==0) UiText.text("padrão do sistema (#0)") else UiText.text("#{0} não anunciado",UiText.nested(modeId))
        val hdr = runCatching { hdrTypes(display.hdrCapabilities?.supportedHdrTypes) }.getOrDefault("n/d")
        UiText.join(UiText.text("Modo de tela pedido: {0} | físico atual: {1} | taxa informada ao app: {2}Hz\n",
            UiText.nested(request), UiText.nested(describe(display.mode)), UiText.nested(rate(display.refreshRate))),
            "Modos anunciados: ${modes.take(12).joinToString("; ",transform=::describe)}\nDISPLAY_HDR_ADVERTISED/$hdr\n" +
                "DISPLAY_MODE/$result display=${display.displayId} matched=${requested?.let { it == mode(display.mode) } ?: "n/d"}")
    }.getOrDefault(UiText.text("Modo de tela: não informado pelo Android"))

    companion object {
        internal fun hdrTypes(types: IntArray?): String {
            if (types == null) return "n/d"
            return types.distinct().joinToString(",") { type ->
                when {
                    type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DOLBY_VISION"
                    type == Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                    type == Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                    android.os.Build.VERSION.SDK_INT >= 29 && type == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10_PLUS"
                    else -> "OTHER($type)"
                }
            }.ifEmpty { "NONE" }
        }
    }
}
