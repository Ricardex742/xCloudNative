package com.xcloud.tclnative.signaling

import org.json.JSONObject

internal class SessionTimeLimit(durationSeconds: Long, startedAtMs: Long) {
    private val deadlineMs = startedAtMs + durationSeconds * 1_000L
    private var reminded = false

    fun remainingSeconds(nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L).let { (it + 999L) / 1_000L }

    fun shouldWarn(remainingSeconds: Long): Boolean {
        if (reminded || remainingSeconds !in 1..300) return false
        reminded = true
        return true
    }

    companion object {
        fun durationSeconds(state: JSONObject): Long? =
            positiveNumber(state, "maxSessionDurationInSeconds", 86_400L)
                ?: positiveNumber(state, "sessionTimeoutInSeconds", 86_400L)
                ?: positiveNumber(state, "sessionLimitInMinutes", 1_440L)?.times(60)

        private fun positiveNumber(state: JSONObject, field: String, maximum: Long): Long? = when (val number = state.opt(field)) {
            is Int -> number.toLong()
            is Long -> number
            else -> null
        }?.takeIf { it in 1..maximum }
    }
}
