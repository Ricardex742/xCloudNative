package com.xcloud.tclnative.touch

import org.json.JSONArray
import org.json.JSONObject

sealed interface OfficialTouchNotice {
    data object Hide : OfficialTouchNotice
    data object ShowDefault : OfficialTouchNotice
    data class Show(val name: String, val patch: String? = null) : OfficialTouchNotice
    data class Patch(val json: String) : OfficialTouchNotice
}

/** Layout and state belong to the Xbox session and survive a replacement WebRTC transport. */
internal class OfficialTouchSession(var enabled: Boolean) {
    private var bundle: OfficialTouchBundle? = null
    private var state = JSONObject()
    private var layoutName: String? = null
    private var hidden = false
    private val initialPatches = ArrayDeque<String>()
    private var failed = false

    fun load(bundle: OfficialTouchBundle?) {
        this.bundle = bundle
        failed = bundle == null
        if (bundle != null) {
            state = bundle.newState()
            initialPatches.forEach(::applyPatch)
        }
        initialPatches.clear()
    }

    fun receive(notice: OfficialTouchNotice) {
        when (notice) {
            OfficialTouchNotice.Hide -> hidden = true
            OfficialTouchNotice.ShowDefault -> {
                hidden = false
                layoutName = null
            }
            is OfficialTouchNotice.Show -> {
                hidden = false
                layoutName = notice.name
                notice.patch?.let(::patch)
            }
            is OfficialTouchNotice.Patch -> patch(notice.json)
        }
    }

    fun controls(): List<HudElement>? {
        if (!enabled) return null
        val current = bundle ?: return null
        if (hidden) return emptyList()
        return current.controls(layoutName,state)
    }

    fun diagnostics() = "TOUCH_LAYOUT/" + when {
        failed -> "UNAVAILABLE"
        bundle == null -> "LOADING"
        !enabled -> "AVAILABLE"
        hidden -> "HIDDEN"
        controls() == null -> "UNSUPPORTED_LAYOUT"
        else -> "ACTIVE"
    }

    private fun patch(json: String) {
        if (bundle != null) applyPatch(json)
        else if (!failed && initialPatches.size<16) initialPatches.addLast(json)
    }

    private fun applyPatch(json: String) {
        val ops = runCatching { JSONArray(json) }.getOrNull() ?: return
        for (index in 0 until minOf(32,ops.length())) {
            val op = ops.optJSONObject(index) ?: continue
            val path = OfficialTouchBundle.pointer(op.optString("path"))?.takeIf { it.size > 1 && it[0] == "state" } ?: continue
            val parent = path.subList(1,path.lastIndex).fold(state as Any?) { node,key -> OfficialTouchBundle.select(node,key) }
                as? JSONObject ?: continue
            val key = path.last()
            when(op.optString("op")) {
                "add", "replace" -> if(op.has("value")) parent.put(key,op.get("value"))
                "remove" -> parent.remove(key)
            }
        }
    }
}
