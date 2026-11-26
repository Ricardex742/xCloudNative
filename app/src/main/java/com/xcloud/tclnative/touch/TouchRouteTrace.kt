package com.xcloud.tclnative.touch

/** Short, local trace for correlating HUD hits and channel acceptance on the same clock. */
internal class TouchRouteTrace {
    private val events = ArrayDeque<String>()

    @Synchronized fun add(event: String) {
        if (events.size == 8) events.removeFirst()
        events.addLast("${System.nanoTime() / 1_000_000}:$event")
    }

    @Synchronized fun clear() = events.clear()

    @Synchronized fun diagnostics() = events.joinToString(">").ifEmpty { "NONE" }

    companion object {
        fun label(value: String?) = value?.take(48)?.map { char ->
            if (char.isLetterOrDigit() || char in "_-.") char else '_'
        }?.joinToString("") ?: "NONE"
    }
}
