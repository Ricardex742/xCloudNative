package com.xcloud.tclnative.diagnostics

import org.json.JSONArray
import org.json.JSONObject

enum class PlaybackOrigin { CATALOG, SHORTCUT, REMOTE_PLAY }
enum class PlaybackStage {
    RENDERER_READY, PLAY_REQUEST, QUEUE_CONFIRMED, PROVISIONED, OFFER_POSTED, ANSWER_APPLIED,
    TRANSPORT_READY, FIRST_TRACK_FRAME, FIRST_EGL_SUBMISSION
}

/** One monotonic clock for each local playback attempt. Only the initial and latest transports are retained. */
class PlaybackTimeline internal constructor(
    private val generation: Int,
    private val origin: PlaybackOrigin,
    private val clockMs: () -> Long
) {
    enum class Codec {
        H264, H265, VP8, VP9, AV1;

        companion object {
            fun observed(label: String?): Codec? {
                val family = label?.substringBefore(' ')?.substringBefore('/') ?: return null
                return entries.firstOrNull { it.name.equals(family, ignoreCase = true) }
            }
        }
    }

    enum class IncidentKind { INTERRUPTION, FAILURE }
    enum class CleanupStage { TRACK, RENDERER, RTC, ALLOCATION }

    data class Incident(val kind: IncidentKind, val atMs: Long, val lastStage: PlaybackStage?, val failure: SessionFailure) {
        fun json() = JSONObject().put("kind", kind.name).put("at", atMs)
            .put("lastStage", lastStage?.name).put("failure", failure.json())
    }

    data class Cleanup(val stage: CleanupStage, val durationMs: Long, val failed: Boolean) {
        fun json() = JSONObject().put("stage", stage.name).put("duration", durationMs).put("failed", failed)
    }

    data class Transport(
        val epoch: Int,
        val startedAtMs: Long,
        val stages: Map<PlaybackStage, Long>,
        val codec: Codec?,
        val width: Int?,
        val height: Int?,
        val incidents: List<Incident> = emptyList(),
        val cleanup: List<Cleanup> = emptyList()
    )

    data class Snapshot(
        val generation: Int,
        val origin: PlaybackOrigin,
        val transports: List<Transport>,
        val cleanup: List<Cleanup> = emptyList()
    ) {
        fun json(): JSONObject = JSONObject()
            .put("schema", 1).put("clock", "elapsedRealtime").put("unit", "ms")
            .put("generation", generation).put("origin", origin.name)
            .put("cleanup", JSONArray(cleanup.map(Cleanup::json)))
            .put("transports", JSONArray().apply {
                transports.forEach { transport ->
                    put(JSONObject().put("epoch", transport.epoch).put("startedAt", transport.startedAtMs)
                        .put("stages", JSONObject().apply {
                            transport.stages.forEach { (stage, time) -> put(stage.name, time) }
                        }).put("codec", transport.codec?.name).put("width", transport.width).put("height", transport.height)
                        .put("incidents", JSONArray(transport.incidents.map(Incident::json)))
                        .put("cleanup", JSONArray(transport.cleanup.map(Cleanup::json))))
                }
            })

        fun text(): String = buildString {
            append("PLAYBACK/").append(origin.name).append(" #").append(generation).append(" • elapsedRealtime (ms)")
            transports.forEach { transport ->
                append("\nTRANSPORT/").append(transport.epoch).append(" +").append(transport.startedAtMs)
                append(" • ").append(transport.codec?.name ?: "n/d")
                if (transport.width != null && transport.height != null)
                    append(' ').append(transport.width).append('×').append(transport.height)
                transport.stages.entries.sortedBy { it.value }.forEach { (stage, time) ->
                    append("\n").append(stage.name).append(" +").append(time)
                }
                transport.incidents.forEach { entry ->
                    append("\n").append(entry.kind.name).append(" +").append(entry.atMs).append(' ')
                        .append(entry.failure.code)
                    entry.lastStage?.let { append(" after ").append(it.name) }
                }
                appendCleanup("TRANSPORT_CLEANUP", transport.cleanup)
            }
            appendCleanup("SESSION_CLEANUP", cleanup)
        }

        private fun StringBuilder.appendCleanup(label: String, steps: List<Cleanup>) {
            if (steps.isEmpty()) return
            append('\n').append(label)
            steps.forEach {
                append(' ').append(it.stage.name).append('=').append(it.durationMs).append("ms")
                if (it.failed) append("/FAILED")
            }
        }
    }

    private class Recording(val epoch: Int, val startedAtMs: Long) {
        val stages = LinkedHashMap<PlaybackStage, Long>()
        var codec: Codec? = null
        var width: Int? = null
        var height: Int? = null
        val incidents = linkedMapOf<IncidentKind, Incident>()
        var cleanup = emptyList<Cleanup>()
        fun snapshot() = Transport(epoch, startedAtMs, stages.toMap(), codec, width, height, incidents.values.toList(), cleanup)
    }

    private val start = clockMs()
    private val initial = Recording(0, 0)
    private val reconnects = ArrayDeque<Recording>(RECENT_TRANSPORTS)
    private var current = initial
    private var sessionCleanup = emptyList<Cleanup>()

    @Synchronized fun beginReconnect(epoch: Int) {
        if (epoch <= current.epoch) return
        val elapsed = (clockMs() - start).coerceAtLeast(current.startedAtMs)
        if (reconnects.size == RECENT_TRANSPORTS) reconnects.removeFirst()
        current = Recording(epoch, elapsed)
        reconnects.addLast(current)
    }

    @Synchronized fun mark(epoch: Int, stage: PlaybackStage, atMs: Long = clockMs()) {
        if (epoch != current.epoch) return
        val elapsed = atMs - start
        if (elapsed >= current.startedAtMs) current.stages.putIfAbsent(stage, elapsed)
    }

    @Synchronized fun observe(epoch: Int, codec: String?, width: Int?, height: Int?) {
        if (epoch != current.epoch) return
        Codec.observed(codec)?.let { current.codec = it }
        if (validDimension(width) != null && validDimension(height) != null) {
            current.width = width
            current.height = height
        }
    }

    @Synchronized
    internal fun recordIssue(epoch: Int?, kind: IncidentKind, failure: SessionFailure): Boolean {
        if (epoch != null && epoch != current.epoch) return false
        if (kind !in current.incidents) {
            val elapsed = (clockMs() - start).coerceAtLeast(current.startedAtMs)
            val lastAt = current.stages.values.filter { it <= elapsed }.maxOrNull()
            val lastStage = current.stages.entries.lastOrNull { it.value == lastAt }?.key
            current.incidents[kind] = Incident(kind, elapsed, lastStage, failure)
        }
        return true
    }

    @Synchronized
    internal fun recordCleanup(epoch: Int?, steps: List<Cleanup>) {
        val bounded = steps.distinctBy { it.stage }.take(CleanupStage.entries.size)
        if (epoch == null) {
            sessionCleanup = bounded
        } else {
            val transport = if (epoch == 0) initial else reconnects.firstOrNull { it.epoch == epoch }
            transport?.cleanup = bounded
        }
    }

    @Synchronized fun snapshot() = Snapshot(
        generation, origin, listOf(initial.snapshot()) + reconnects.map { it.snapshot() }, sessionCleanup
    )

    companion object {
        internal const val MAX_JSON_CHARS = 16_384
        private const val RECENT_TRANSPORTS = 3

        private fun readCleanup(array: JSONArray?): List<Cleanup> {
            if (array == null || array.length() > CleanupStage.entries.size) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val stage = CleanupStage.entries.firstOrNull { it.name == item.optString("stage") } ?: return@mapNotNull null
                val duration = item.optLong("duration", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                if (item.opt("failed") !is Boolean) return@mapNotNull null
                Cleanup(stage, duration, item.optBoolean("failed"))
            }.distinctBy { it.stage }
        }

        private fun readIncidents(array: JSONArray?, started: Long): List<Incident> {
            if (array == null || array.length() > IncidentKind.entries.size) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val kind = IncidentKind.entries.firstOrNull { it.name == item.optString("kind") } ?: return@mapNotNull null
                val at = item.optLong("at", -1).takeIf { it >= started } ?: return@mapNotNull null
                val stage = PlaybackStage.entries.firstOrNull { it.name == item.optString("lastStage") }
                val failure = SessionFailure.read(item.optJSONObject("failure")) ?: return@mapNotNull null
                Incident(kind, at, stage, failure)
            }.distinctBy { it.kind }
        }
        private fun validDimension(value: Int?) = value?.takeIf { it in 1..16_384 }

        fun read(json: JSONObject?): Snapshot? {
            if (json == null || json.optInt("schema") != 1) return null
            val generation = json.optInt("generation", -1).takeIf { it >= 0 } ?: return null
            val origin = PlaybackOrigin.entries.firstOrNull { it.name == json.optString("origin") } ?: return null
            val data = json.optJSONArray("transports") ?: return null
            if (data.length() !in 1..(RECENT_TRANSPORTS + 1)) return null
            val transports = (0 until data.length()).mapNotNull { index ->
                val item = data.optJSONObject(index) ?: return@mapNotNull null
                val epoch = item.optInt("epoch", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val started = item.optLong("startedAt", -1).takeIf { it >= 0 } ?: return@mapNotNull null
                val stages = item.optJSONObject("stages")
                val marks = PlaybackStage.entries.mapNotNull { stage ->
                    stages?.optLong(stage.name, -1)?.takeIf { it >= started }?.let { stage to it }
                }.toMap()
                Transport(epoch, started, marks, Codec.entries.firstOrNull { it.name == item.optString("codec") },
                    validDimension(item.optInt("width")), validDimension(item.optInt("height")),
                    readIncidents(item.optJSONArray("incidents"), started), readCleanup(item.optJSONArray("cleanup")))
            }
            return Snapshot(generation, origin, transports, readCleanup(json.optJSONArray("cleanup"))).takeIf { transports.firstOrNull()?.epoch == 0 }
        }
    }
}
