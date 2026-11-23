package com.xcloud.tclnative.touch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.xcloud.tclnative.webrtc.TouchKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.cos
import kotlin.math.sin

/** Reads the Xbox Touch Adaptation Bundle without extracting any files to disk. */
internal class OfficialTouchBundle private constructor(
    private val layouts: Map<String, JSONObject>,
    private val defaultLayout: String,
    private val context: JSONObject,
    private val iconsFromBundle: Map<String, Bitmap>
) {
    fun controls(name: String?, state: JSONObject): List<HudElement>? {
        val layout = layouts[name?.lowercase(Locale.ROOT) ?: defaultLayout] ?: return null
        val content = layout.optJSONObject("content") ?: return null
        val result = mutableListOf<HudElement>()
        var unsupported = content.has("layers")
        fun resolve(value: Any?, depth: Int = 0, inContext: Boolean = false): Any? {
            val ref = (value as? JSONObject)?.optString("\$ref")?.takeIf { it.isNotBlank() } ?: return value
            if (depth >= 8 || '#' !in ref || ref.startsWith("http", ignoreCase = true)) return null
            val path = pointer(ref.substringAfter('#')) ?: return null
            val useContext = inContext || ref.substringBefore('#').isNotEmpty() || path.first() == "state"
            val start = when(path.first()) {
                "state" -> state
                "definitions" -> (if (useContext) context else layout).optJSONObject("definitions")
                else -> return null
            }
            val selected = path.drop(1).fold(start as Any?) { node, key -> select(node, key) }
            return resolve(selected, depth + 1, useContext)
        }
        fun actions(value: Any?, depth: Int = 0): Set<HudBinding> {
            if (depth >= 5) return emptySet()
            return when (val resolved = resolve(value)) {
                is String -> setOfNotNull(bindings[resolved.lowercase(Locale.ROOT)])
                is JSONArray -> (0 until minOf(8,resolved.length())).flatMap { actions(resolved.opt(it),depth + 1) }.toSet()
                else -> emptySet()
            }
        }
        fun add(value: Any?, id: String, x: Float, y: Float, radius: Float) {
            val item = resolve(value) as? JSONObject ?: return
            if (resolve(item.opt("visible")) == false) return
            val type = item.optString("type")
            val keys = actions(item.opt("action"))
            val mapping = resolve(item.opt("axis")) as? JSONObject
            val axis = mapping?.optString("output")?.lowercase(Locale.ROOT).orEmpty()
            val kind = when (type) {
                "button" -> TouchKind.BUTTON.takeIf { keys.isNotEmpty() }
                "directionalPad" -> TouchKind.DPAD
                "joystick", "touchpad" -> when {
                    axis.startsWith("leftjoystick") -> TouchKind.LEFT_STICK
                    axis.startsWith("rightjoystick") -> TouchKind.RIGHT_STICK
                    else -> null
                }
                "blank" -> return
                else -> null
            } ?: run { unsupported = true; return }
            val styles = resolve(item.opt("styles")) as? JSONObject
            val enabled = resolve(item.opt("enabled")) != false
            val defaultStyle = resolve(styles?.opt("default")) as? JSONObject
            fun style(vararg states: String): OfficialTouchStyle {
                val layers = states.mapNotNull { resolve(styles?.opt(it)) as? JSONObject } + listOfNotNull(defaultStyle)
                return readStyle(layers, type == "joystick") { resolve(it) }
            }
            val action = keys.takeIf { it.isNotEmpty() }?.let(::HudAction)
            val axisActionThreshold = if (action != null && (type == "joystick" || type == "touchpad")) {
                (resolve(item.opt("actionThreshold")) as? Number)?.toFloat()?.takeIf { it in 0f..1f } ?: 0f
            } else null
            val label = action?.label ?: when (kind) {
                TouchKind.LEFT_STICK -> "L"
                TouchKind.RIGHT_STICK -> "R"
                else -> "+"
            }
            result += HudElement(id,label,x,y,radius,kind,keys.singleOrNull(),action=action,
                stickAxis=when {
                    axis.endsWith('x') -> HudStickAxis.HORIZONTAL
                    axis.endsWith('y') -> HudStickAxis.VERTICAL
                    else -> HudStickAxis.BOTH
                },
                officialAppearance=OfficialTouchAppearance(
                    if (enabled) style("idle") else style("disabled"),
                    if (type == "joystick") style("moving", "activated") else style("activated"),
                    enabled,
                    touchpad = type == "touchpad"
                ),
                axisActionThreshold = axisActionThreshold)
        }
        listOf("left" to .205f, "right" to .795f).forEach { (side, x) ->
            val wheel = resolve(content.opt(side)) as? JSONObject ?: return@forEach
            val inner = wheel.optJSONArray("inner")
            val size = minOf(inner?.length() ?: 0, 4)
            for (index in 0 until size) {
                val offset = if(size == 1) 0.0 else 1.0
                val angle = Math.toRadians(-90.0 + index * 360.0 / size)
                add(inner?.opt(index),"$side-inner-$index", x + (cos(angle)*.056*offset).toFloat(),
                    .67f + (sin(angle)*.091*offset).toFloat(), if(size == 1) .085f else .046f)
            }
            val outer = wheel.optJSONArray("outer")
            for (index in 0 until minOf(8, outer?.length() ?: 0)) {
                val item = resolve(outer?.opt(index))
                val group = if (item is JSONArray) (0 until minOf(4,item.length())).map(item::opt) else listOf(item)
                group.forEachIndexed { part, control ->
                    val offset = (part - (group.size - 1) / 2f) * 16f
                    val angle = Math.toRadians(-60.0 + index * 45.0 + offset)
                    add(control,"$side-outer-$index-$part", x + (cos(angle)*.118).toFloat(),
                        .67f + (sin(angle)*.232).toFloat(), if(group.size > 1) .032f else .05f)
                }
            }
        }
        for ((side,sign) in listOf("left" to 1, "right" to -1)) {
            val upper = (resolve(content.opt("upper")) as? JSONObject)?.optJSONArray(side) ?: continue
            val x = if(side == "left") .15f else .89f
            for (index in 0 until minOf(4,upper.length())) {
                add(upper.opt(index),"upper-$side-$index",x + sign * index * .063f,.14f,.043f)
            }
        }
        return result.takeUnless { it.isEmpty() && unsupported }
    }

    fun newState(): JSONObject = JSONObject(context.optJSONObject("state")?.toString() ?: "{}")

    private fun readStyle(
        layers: List<JSONObject>,
        joystick: Boolean,
        resolve: (Any?) -> Any?
    ): OfficialTouchStyle {
        fun field(objects: List<JSONObject>, key: String): Any? =
            objects.firstNotNullOfOrNull { resolve(it.opt(key)).takeUnless { value -> value == JSONObject.NULL } }
        fun opacity(value: Any?) = (value as? Number)?.toFloat()?.takeIf { it in 0f..1f } ?: 1f
        fun color(value: Any?): Int? {
            val hex = (value as? String)?.takeIf { it.startsWith('#') }?.drop(1) ?: return null
            if (hex.length !in setOf(3, 4, 6, 8) || hex.any { it.digitToIntOrNull(16) == null }) return null
            val rgba = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
            return Color.parseColor("#" + if (rgba.length == 8) rgba.takeLast(2) + rgba.take(6) else rgba)
        }
        fun image(value: Any?) = (value as? String)?.lowercase(Locale.ROOT)?.removeSuffix(".png")?.let(iconsFromBundle::get)
        fun background(raw: Any?): OfficialTouchBackground? {
            val value = raw as? JSONObject ?: return null
            val alpha = opacity(resolve(value.opt("opacity")))
            val content = resolve(value.opt("value"))
            return when (value.optString("type")) {
                "color" -> color(content)?.let { OfficialTouchBackground(color=it, opacity=alpha) }
                "asset" -> image(content)?.let { OfficialTouchBackground(image=it, opacity=alpha) }
                else -> null
            }
        }
        fun face(raw: Any?): OfficialTouchFace? {
            val value = raw as? JSONObject ?: return null
            val content = resolve(value.opt("value"))
            return when (value.optString("type")) {
                "asset" -> image(content)?.let { OfficialTouchFace(image=it) }
                "icon" -> (content as? String)?.lowercase(Locale.ROOT)?.let(icons::get)?.let { OfficialTouchFace(icon=it) }
                else -> null
            }
        }
        fun stroke(raw: Any?): OfficialTouchStroke? {
            val value = raw as? JSONObject ?: return null
            if (value.optString("type") != "solid") return null
            return OfficialTouchStroke(color(resolve(value.opt("color"))) ?: Color.WHITE, opacity(resolve(value.opt("opacity"))))
        }
        val knobs = layers.mapNotNull { resolve(it.opt("knob")) as? JSONObject }
        val outlines = layers.mapNotNull { resolve(it.opt("outline")) as? JSONObject }
        return OfficialTouchStyle(
            opacity = opacity(field(layers, "opacity")),
            background = background(field(layers, "background")),
            face = face(field(if (joystick) knobs else layers, "faceImage")),
            fill = color(field(layers, "fill")),
            stroke = stroke(field(layers, "stroke")),
            knobOpacity = opacity(field(knobs, "opacity")),
            knobBackground = background(field(knobs, "background")),
            knobStroke = stroke(field(knobs, "stroke")),
            outlineOpacity = opacity(field(outlines, "opacity")),
            outlineStroke = stroke(field(outlines, "stroke"))
        )
    }

    companion object {
        private val bindings = mapOf(
            "gamepada" to HudBinding.A, "gamepadb" to HudBinding.B, "gamepadx" to HudBinding.X, "gamepady" to HudBinding.Y,
            "leftbumper" to HudBinding.LB, "rightbumper" to HudBinding.RB, "lefttrigger" to HudBinding.LT,
            "righttrigger" to HudBinding.RT, "leftthumb" to HudBinding.L3, "rightthumb" to HudBinding.R3,
            "menu" to HudBinding.MENU, "view" to HudBinding.VIEW, "guide" to HudBinding.GUIDE,
            "dpadup" to HudBinding.UP, "dpaddown" to HudBinding.DOWN, "dpadleft" to HudBinding.LEFT, "dpadright" to HudBinding.RIGHT
        )
        private val icons = mapOf(
            "interact" to HudIcon.USE, "aim" to HudIcon.AIM, "shoot" to HudIcon.FIRE, "jump" to HudIcon.JUMP,
            "crouch" to HudIcon.CROUCH, "sprint" to HudIcon.SPRINT, "reload" to HudIcon.RELOAD,
            "melee" to HudIcon.MELEE, "map" to HudIcon.MAP, "pause" to HudIcon.PAUSE, "attack" to HudIcon.ATTACK,
            "brakepedal" to HudIcon.BRAKE, "gaspedal" to HudIcon.ACCELERATE
        )
        private const val MAX_ARCHIVE_ENTRIES = 20_000
        private const val MAX_MEMBER_BYTES = 192 * 1024

        fun parse(file: File, locale: String): OfficialTouchBundle = ZipFile(file).use { zip ->
            require(zip.size() <= MAX_ARCHIVE_ENTRIES)
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.mapNotNull { entry ->
                normalizedPath(entry.name)?.let { name -> name to entry }
            }.toMap()
            val manifest = json(zip,entries["takxmanifest.json"]) ?: error("TOUCH_BUNDLE: no manifest")
            val path = normalizedPath(manifest.optString("layouts", "layouts")) ?: error("TOUCH_BUNDLE: layouts")
            val folders = listOf(path, "$path/en", "$path/en-us", "$path/en/en-us", "$path/neutral") +
                localeFolders(path,locale)
            val layouts = linkedMapOf<String, JSONObject>()
            for (directory in folders) {
                for ((name,entry) in entries) {
                    if (name.substringBeforeLast('/',"")!=directory || !name.endsWith(".json")) continue
                    val layout = json(zip,entry)?.takeIf { it.optJSONObject("content")!=null } ?: continue
                    layouts[name.substringAfterLast('/').removeSuffix(".json")] = layout
                    require(layouts.size<=128) { "TOUCH_BUNDLE: too many layouts" }
                }
            }
            require(layouts.isNotEmpty()) { "TOUCH_BUNDLE: layouts unavailable" }
            val declared = manifest.optString("defaultLayout").lowercase(Locale.ROOT).removeSuffix(".json")
            val default = declared.takeIf(layouts::containsKey) ?: layouts.keys.sorted().first()
            val contextPath = normalizedPath(manifest.optString("contextFile","context.json"))
            val context = json(zip,entries[contextPath]) ?: JSONObject()
            val assets = normalizedPath(manifest.optString("assets"))
            OfficialTouchBundle(layouts,default,context,
                if (assets==null) emptyMap() else decodeImages(zip,entries,assets,locale))
        }

        private fun localeFolders(root: String, locale: String): List<String> {
            val parts = locale.lowercase(Locale.ROOT).split('-')
            val tags = parts.runningReduce { a,b -> "$a-$b" }
            return tags.flatMapIndexed { index,tag ->
                listOf("$root/$tag", "$root/${tags.take(index+1).joinToString("/")}")
            }.distinct()
        }

        private fun normalizedPath(path: String): String? {
            val normalized = path.trim('/').lowercase(Locale.ROOT)
            return normalized.takeIf { it.isNotBlank() && it.length<=512 && '\\' !in it &&
                it.split('/').none { part -> part in listOf("",".","..") } }
        }

        private fun json(zip: ZipFile, entry: ZipEntry?): JSONObject? =
            entry?.let { member -> runCatching {
                JSONObject(readMember(zip,member).toString(Charsets.UTF_8).trimStart('\uFEFF').trim())
            }.getOrNull() }

        private fun readMember(zip: ZipFile, entry: ZipEntry): ByteArray {
            require(entry.size<=MAX_MEMBER_BYTES)
            return zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count<0) break
                    require(output.size()+count<=MAX_MEMBER_BYTES)
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
        }

        private fun decodeImages(zip: ZipFile, entries: Map<String, ZipEntry>, root: String, locale: String): Map<String, Bitmap> {
            val selected = linkedMapOf<String, ZipEntry>()
            val folders = listOf("$root/en", "$root/en-us", "$root/en/en-us", "$root/neutral") + localeFolders(root,locale)
            for (directory in folders) {
                val choices = entries.entries.filter { it.key.startsWith("$directory/@") && it.key.endsWith(".png") &&
                    it.value.size<=MAX_MEMBER_BYTES }.sortedBy {
                    it.key.removePrefix("$directory/").substringBefore('/').removePrefix("@").removeSuffix("x").toFloatOrNull()
                        ?: 1f
                }
                for ((key, variants) in choices.groupBy { it.key.substringAfterLast('/').removeSuffix(".png") })
                    selected[key] = variants.first().value
            }
            return selected.entries.take(48).mapNotNull { (name, entry) ->
                val bytes = runCatching { readMember(zip,entry) }.getOrNull() ?: return@mapNotNull null
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                val size = maxOf(options.outWidth,options.outHeight)
                if(size !in 1..2048 || options.outMimeType!="image/png") return@mapNotNull null
                options.inJustDecodeBounds = false
                options.inSampleSize = if (size<=128) 1 else Integer.highestOneBit((size+127)/128 - 1)*2
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.let { name to it }
            }.toMap()
        }

        internal fun pointer(path: String): List<String>? = path.takeIf { it.startsWith('/') && it.length <= 256 }
            ?.drop(1)?.split('/')?.takeIf { it.size <= 12 && it.none(String::isEmpty) }
            ?.map { it.replace("~1","/").replace("~0","~") }

        internal fun select(node: Any?, key: String): Any? = when(node) {
            is JSONObject -> node.opt(key)
            is JSONArray -> key.toIntOrNull()?.let(node::opt)
            else -> null
        }
    }
}
