package com.xcloud.tclnative.touch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xcloud.tclnative.webrtc.TouchKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
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
        val content = layouts[name?.lowercase(Locale.ROOT) ?: defaultLayout]?.optJSONObject("content") ?: return null
        val result = mutableListOf<HudElement>()
        var unsupported = content.has("layers")
        fun resolve(value: Any?, depth: Int = 0): Any? {
            val ref = (value as? JSONObject)?.optString("\$ref")?.takeIf { it.isNotBlank() } ?: return value
            if (depth >= 8 || '#' !in ref || ref.startsWith("http", ignoreCase = true)) return null
            val path = pointer(ref.substringAfter('#')) ?: return null
            val start = when(path.first()) {
                "state" -> state
                "definitions" -> context.optJSONObject("definitions")
                else -> return null
            }
            val selected = path.drop(1).fold(start as Any?) { node, key -> select(node, key) }
            return resolve(selected, depth + 1)
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
            if (resolve(item.opt("visible")) == false || resolve(item.opt("enabled")) == false) return
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
            val style = item.optJSONObject("styles")?.optJSONObject("default")
            val face = resolve(style?.opt("faceImage") ?: style?.optJSONObject("knob")?.opt("faceImage")) as? JSONObject
            val icon = if (face?.optString("type") == "icon") icons[face.optString("value").lowercase(Locale.ROOT)] else null
            val image = if (face?.optString("type") == "asset") {
                val key = resolve(face.opt("value")) as? String
                key?.lowercase(Locale.ROOT)?.removeSuffix(".png")?.let(iconsFromBundle::get)
            } else null
            val action = keys.takeIf { it.isNotEmpty() }?.let(::HudAction)
            val label = action?.label ?: when (kind) {
                TouchKind.LEFT_STICK -> "L"
                TouchKind.RIGHT_STICK -> "R"
                else -> "+"
            }
            result += HudElement(id,label,x,y,radius,kind,keys.singleOrNull(),icon ?: HudIcon.TEXT,action=action,
                stickAxis=when {
                    axis.endsWith('x') -> HudStickAxis.HORIZONTAL
                    axis.endsWith('y') -> HudStickAxis.VERTICAL
                    else -> HudStickAxis.BOTH
                },image=image)
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
            val upper = content.optJSONObject("upper")?.optJSONArray(side) ?: continue
            val x = if(side == "left") .15f else .89f
            for (index in 0 until minOf(4,upper.length())) {
                add(upper.opt(index),"upper-$side-$index",x + sign * index * .063f,.14f,.043f)
            }
        }
        return result.takeUnless { it.isEmpty() && unsupported }
    }

    fun newState(): JSONObject = JSONObject(context.optJSONObject("state")?.toString() ?: "{}")

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
        private const val MAX_ARCHIVE_ENTRIES = 512
        private const val MAX_JSON_BYTES = 192 * 1024
        private const val MAX_UNPACKED_BYTES = 16 * 1024 * 1024
        private data class Entries(val json: Map<String, JSONObject>, val images: Map<String, ByteArray>)

        fun parse(bytes: ByteArray, locale: String): OfficialTouchBundle {
            val entries = readEntries(bytes)
            val files = entries.json
            val manifest = files["takxmanifest.json"] ?: error("TOUCH_BUNDLE: no manifest")
            val path = cleanPath(manifest.optString("layouts", "layouts"))
            val default = manifest.optString("defaultLayout").lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
                ?: error("TOUCH_BUNDLE: no default layout")
            val choices = locale.lowercase(Locale.ROOT).split('-').runningReduce { a,b -> "$a-$b" }
            val language = listOf("neutral") + choices
            val layouts = linkedMapOf<String, JSONObject>()
            for (directory in language) {
                val prefix = "$path/$directory/"
                for ((file, value) in files) {
                    if (file.startsWith(prefix) && value.optJSONObject("content")!=null) {
                        layouts[file.removePrefix(prefix).removeSuffix(".json")] = value
                    }
                }
            }
            require(layouts.containsKey(default)) { "TOUCH_BUNDLE: default layout unavailable" }
            val context = manifest.optString("contextFile").takeIf { it.isNotBlank() }
                ?.let { files[cleanPath(it)] } ?: JSONObject()
            val assets = manifest.optString("assets").takeIf { it.isNotBlank() }?.let(::cleanPath)
            return OfficialTouchBundle(layouts,default,context,
                if(assets==null) emptyMap() else decodeImages(entries.images,assets,language))
        }

        private fun cleanPath(path: String): String {
            val normalized = path.trim('/').lowercase(Locale.ROOT)
            require(normalized.isNotBlank() && '\\' !in normalized && normalized.split('/').none { it in listOf("",".","..") })
            return normalized
        }

        private fun readEntries(bytes: ByteArray): Entries {
            val files = mutableMapOf<String, JSONObject>()
            val images = linkedMapOf<String, ByteArray>()
            val buffer = ByteArray(8192)
            var total = 0
            var entries = 0
            var imageBytes = 0
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_ARCHIVE_ENTRIES)
                    val name = cleanPath(entry.name)
                    val jsonEntry = !entry.isDirectory && name.endsWith(".json")
                    val imageEntry = !entry.isDirectory && name.endsWith(".png") && imageBytes < 3*1024*1024
                    var output = if (jsonEntry || imageEntry) ByteArrayOutputStream() else null
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_UNPACKED_BYTES)
                        output?.let { retained ->
                            if (retained.size() + count <= MAX_JSON_BYTES) retained.write(buffer,0,count)
                            else if (jsonEntry) error("TOUCH_BUNDLE: oversized layout")
                            else output = null
                        }
                    }
                    output?.let { retained ->
                        if (jsonEntry) {
                            val json = JSONObject(retained.toString("UTF-8").trimStart('\uFEFF').trim())
                            require(files.put(name,json)==null)
                        } else {
                            imageBytes += retained.size()
                            images[name] = retained.toByteArray()
                        }
                    }
                    zip.closeEntry()
                }
            }
            return Entries(files,images)
        }

        private fun decodeImages(images: Map<String, ByteArray>, root: String, language: List<String>): Map<String, Bitmap> {
            val selected = linkedMapOf<String, ByteArray>()
            for (directory in language) {
                val prefix = "$root/$directory/"
                // Prefer the lowest available density. A HUD glyph uses at most 128 px here.
                val choices = images.entries.filter { it.key.startsWith(prefix) }.sortedBy {
                    it.key.removePrefix(prefix).substringBefore('/').removePrefix("@").removeSuffix("x").toFloatOrNull()
                        ?: 1f
                }
                val byName = choices.groupBy { it.key.removePrefix(prefix).substringAfter('/').removeSuffix(".png") }
                for ((key, candidates) in byName) selected[key] = candidates.first().value
            }
            return selected.entries.take(48).mapNotNull { (name, bytes) ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                val size = maxOf(options.outWidth,options.outHeight)
                if(size !in 1..2048 || options.outMimeType!="image/png") return@mapNotNull null
                options.inJustDecodeBounds = false
                options.inSampleSize = Integer.highestOneBit((size+127)/128 - 1).coerceAtLeast(1)*2
                if(size<=128) options.inSampleSize = 1
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
