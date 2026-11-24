package com.xcloud.tclnative.touch

import org.json.JSONArray
import org.json.JSONObject

sealed interface OfficialTouchNotice {
    data object Hide : OfficialTouchNotice
    data object ShowDefault : OfficialTouchNotice
    data class Show(val name: String, val patch: String? = null) : OfficialTouchNotice
    data class Patch(val json: String) : OfficialTouchNotice
}

internal sealed interface OfficialTouchLoad {
    data class Ready(val bundle: OfficialTouchBundle) : OfficialTouchLoad
    data class Failed(val stage: Stage, val http: Int? = null) : OfficialTouchLoad
    enum class Stage { METADATA, NO_BUNDLE, REFERENCE, DISTRIBUTION, FORMAT, TIMEOUT }
}

internal data class OfficialTouchLayout(val id: String?, val controls: List<HudElement>)

/** Layout and state belong to the Xbox session and survive a replacement WebRTC transport. */
internal class OfficialTouchSession(private var enabled: Boolean, private val preserveManualLayoutOnHide: Boolean = false) {
    private var bundle: OfficialTouchBundle? = null
    private var state = JSONObject()
    private var layoutName: String? = null
    private var hidden = false
    private var manual = false
    private val initialPatches = ArrayDeque<String>()
    private var loading = false
    private var failure: OfficialTouchLoad.Failed? = null
    private var shows = 0
    private var hides = 0
    private var patches = 0
    val waitingForManualLoad get() = manual && bundle == null
    val loaded get() = bundle != null

    fun beginLoad(): Boolean {
        if (loading || loaded) return false
        loading = true
        failure = null
        return true
    }

    fun load(result: OfficialTouchLoad) {
        loading = false
        failure = result as? OfficialTouchLoad.Failed
        if (result is OfficialTouchLoad.Ready) {
            bundle = result.bundle
            state = result.bundle.newState()
            initialPatches.forEach(::applyPatch)
            initialPatches.clear()
        }
    }

    fun selectManually() {
        enabled = true
        manual = true
    }

    fun disable() {
        enabled = false
        manual = false
    }

    fun receive(notice: OfficialTouchNotice) {
        when (notice) {
            OfficialTouchNotice.Hide -> {
                hides++
                hidden = true
                if (loaded && !preserveManualLayoutOnHide) manual = false
            }
            OfficialTouchNotice.ShowDefault -> {
                shows++
                hidden = false
                layoutName = null
            }
            is OfficialTouchNotice.Show -> {
                shows++
                hidden = false
                layoutName = notice.name
                notice.patch?.let(::patch)
            }
            is OfficialTouchNotice.Patch -> {
                patches++
                patch(notice.json)
            }
        }
    }

    fun controls(): List<HudElement>? = layout()?.controls

    fun layout(): OfficialTouchLayout? {
        if (!enabled) return null
        val current = bundle ?: return null
        if (hidden && !manual) return OfficialTouchLayout(null, emptyList())
        val name = if (hidden && !preserveManualLayoutOnHide) null else layoutName
        if (name=="") return OfficialTouchLayout(null, GameHud.elements(HudPreset.GAMEPAD))
        fun selectedLayout(id: String?) = current.controls(id, state)?.let {
            OfficialTouchLayout(current.layoutId(id), it)
        }
        val selected = selectedLayout(name) ?: selectedLayout(null)
        return if (manual && selected?.controls?.isEmpty()==true)
            selectedLayout(null)?.takeUnless { it.controls.isEmpty() }
            else selected
    }

    fun diagnostics(): String {
        val status = failure?.let {
            "UNAVAILABLE_${it.stage.name}" + (it.http?.let { code -> "/HTTP_$code" } ?: "")
        } ?: when {
            !loaded -> "LOADING"
            !enabled -> "AVAILABLE"
            hidden && !manual -> "HIDDEN"
            controls() == null -> "UNSUPPORTED_LAYOUT"
            else -> "ACTIVE"
        }
        return "TOUCH_LAYOUT/$status"
    }

    fun noticeDiagnostics() = "TOUCH_LAYOUT_EVENTS/SHOW=$shows HIDE=$hides PATCH=$patches MANUAL=$manual"

    private fun patch(json: String) {
        if (bundle != null) applyPatch(json)
        else {
            if (initialPatches.size==16) initialPatches.removeFirst()
            initialPatches.addLast(json)
        }
    }

    private fun applyPatch(json: String) {
        val ops = runCatching { JSONArray(json) }.getOrNull() ?: return
        for (index in 0 until minOf(32,ops.length())) {
            val op = ops.optJSONObject(index) ?: continue
            val raw = op.optString("path").takeIf { it.isNotBlank() } ?: continue
            val tokens = OfficialTouchBundle.pointer("/"+raw.removePrefix("/")) ?: continue
            val path = if (tokens.first()=="state" && tokens.size>1 && !state.has("state")) tokens.drop(1) else tokens
            val parent = path.dropLast(1).fold(state as Any?) { node,key -> OfficialTouchBundle.select(node,key) }
                as? JSONObject ?: continue
            val key = path.last()
            when(op.optString("op")) {
                "add", "replace" -> if(op.has("value")) parent.put(key,op.get("value"))
                "remove" -> parent.remove(key)
            }
        }
    }
}
