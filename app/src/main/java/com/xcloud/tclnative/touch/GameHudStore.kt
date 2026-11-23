package com.xcloud.tclnative.touch

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import com.xcloud.tclnative.catalog.GameLibrary
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

/** Each title owns its layout on this device, independently of refreshable login grants. */
class GameHudStore(private val context: Context) {
    private fun action(data: JSONArray?): HudAction? {
        if(data==null) return null
        val keys=(0 until minOf(data.length(),HudBinding.entries.size)).mapNotNull { index ->
            HudBinding.entries.firstOrNull { it.name==data.optString(index) }
        }.toSet()
        return keys.takeIf { it.isNotEmpty() }?.let(::HudAction)
    }
    private fun encode(action: HudAction)=JSONArray(action.keys.map { it.name })
    private fun prefs(id: String)=context.getSharedPreferences("hud_"+GameLibrary.accountKey(id.uppercase(Locale.ROOT)),Context.MODE_PRIVATE)
    fun prefersNativeTouchWithController(id: String) = prefs(id).getBoolean("native_touch_with_controller", false)
    fun preferNativeTouchWithController(id: String, enabled: Boolean) {
        prefs(id).edit().putBoolean("native_touch_with_controller", enabled).apply()
    }
    fun read(id: String): HudSettings {
        val prefs = prefs(id)
        return readLayout(prefs.getString("layout", null)) { prefs.edit().remove("layout").apply() }
    }
    private fun readLayout(raw: String?, discard: (() -> Unit)? = null): HudSettings { return try {
        val text=raw ?: return HudSettings()
        if(text.length>65536) return HudSettings()
        val data=JSONObject(text)
        // Removed presets must not carry their combat/build bindings into another layout.
        if(data.optString("preset") in setOf("FORTNITE","FORTNITE_BUILD")) {
            discard?.invoke()
            return HudSettings()
        }
        val positions=data.optJSONObject("controls") ?: JSONObject()
        val overrides=positions.keys().asSequence().take(64).mapNotNull { key ->
            val item=positions.optJSONObject(key) ?: return@mapNotNull null
            val x=item.optDouble("x",Double.NaN).toFloat(); val y=item.optDouble("y",Double.NaN).toFloat()
            val size=item.optDouble("scale",1.0).toFloat()
            if(!x.isFinite() || !y.isFinite() || !size.isFinite()) return@mapNotNull null
            val gestures=item.optJSONObject("gestures")?.let { mapping ->
                HudSwipe.entries.mapNotNull gesture@ { direction ->
                    val saved=mapping.optJSONObject(direction.name) ?: return@gesture null
                    val input=action(saved.optJSONArray("keys")) ?: return@gesture null
                    direction to HudGesture(saved.optString("label",input.label).take(48),input)
                }.toMap()
            }
            key to HudPlacement(x.coerceIn(0f,1f),y.coerceIn(0f,1f),size.coerceIn(.55f,1.7f),
                HudBinding.entries.firstOrNull { it.name==item.optString("binding") },action(item.optJSONArray("keys")),gestures)
        }.toMap()
        HudSettings(HudPreset.entries.firstOrNull { it.name==data.optString("preset") },
            data.optInt("opacity",62).coerceIn(20,100),data.optInt("scale",100).coerceIn(75,150),
            data.optInt("sensitivity",100).coerceIn(40,1200),overrides,
            data.optInt("lookDeadzone",12).coerceIn(0,35),
            FootballScheme.entries.firstOrNull { it.name==data.optString("footballScheme") } ?: FootballScheme.CLASSIC,
            data.optBoolean("aimAndFire",false),data.optBoolean("sprintGesture",true),
            HudAutoHide.entries.firstOrNull { it.name==data.optString("autoHide") } ?: HudAutoHide.OFF,
            codDpad=data.optBoolean("codDpad",false))
    } catch(_: Exception) { HudSettings() } }
    private fun encodeLayout(settings: HudSettings): String {
        val controls=JSONObject()
        settings.placements.forEach { (key,value) ->
            val control=JSONObject().put("x",value.x).put("y",value.y).put("scale",value.scale).put("binding",value.binding?.name)
            value.action?.let { control.put("keys",encode(it)) }
            value.gestures?.let { mappings ->
                val data=JSONObject()
                mappings.forEach { (direction,gesture) -> data.put(direction.name,JSONObject().put("label",gesture.label).put("keys",encode(gesture.action))) }
                control.put("gestures",data)
            }
            controls.put(key,control)
        }
        val data=JSONObject().put("version",3).put("preset",settings.preset?.name).put("opacity",settings.opacity)
            .put("scale",settings.scale).put("sensitivity",settings.sensitivity).put("controls",controls)
            .put("lookDeadzone",settings.lookDeadzone).put("footballScheme",settings.footballScheme.name)
            .put("aimAndFire",settings.aimAndFire).put("sprintGesture",settings.sprintGesture)
            .put("autoHide",settings.autoHide.name).put("codDpad",settings.codDpad)
        return data.toString()
    }
    fun save(id: String, settings: HudSettings, resetPresets: Boolean = false) {
        val prefs = prefs(id)
        val editor = prefs.edit().putString("layout", encodeLayout(settings))
        if (resetPresets) prefs.all.keys.filter { it.startsWith("layout:") }.forEach(editor::remove)
        commit(editor)
    }
    fun selectPreset(id: String, automatic: HudPreset, preset: HudPreset?): HudSettings {
        val prefs = prefs(id)
        val current = read(id)
        val previous = (current.preset ?: automatic).family
        val next = (preset ?: automatic).family
        val edit = prefs.edit()
        val saved = if (previous == next) current else {
            edit.putString("layout:${previous.name}", encodeLayout(current))
            val archived = prefs.getString("layout:${next.name}", null)
            if (archived == null) current.copy(placements = emptyMap()) else readLayout(archived)
        }
        return saved.copy(preset = preset).also { commit(edit.putString("layout", encodeLayout(it))) }
    }
    private fun commit(editor: android.content.SharedPreferences.Editor) {
        if (!editor.commit()) throw java.io.IOException(UiText.text("Não foi possível salvar o HUD neste aparelho."))
    }
}
