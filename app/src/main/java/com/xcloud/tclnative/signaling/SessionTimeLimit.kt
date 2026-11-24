package com.xcloud.tclnative.signaling

import org.json.JSONObject

internal class SessionTimeLimit {
    private var deadlineMs: Long? = null
    private var receivedTimer = false
    private var lastWarningMinutes = Int.MAX_VALUE

    fun startFromSessionState(durationSeconds: Long?, startedAtMs: Long) {
        if (!receivedTimer && durationSeconds != null) setDeadline(durationSeconds,startedAtMs)
    }

    fun updateFromXbox(secondsLeft: Long?, nowMs: Long) {
        receivedTimer = true
        setDeadline(secondsLeft,nowMs)
    }

    private fun setDeadline(seconds: Long?, nowMs: Long) {
        deadlineMs = seconds?.let { nowMs + it * 1_000L }
    }

    fun remainingSeconds(nowMs: Long): Long? =
        deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }?.let { (it + 999L) / 1_000L }

    fun diagnostics(nowMs: Long): String {
        val seconds = remainingSeconds(nowMs)
        val source = when {
            receivedTimer && seconds == null -> "XBOX_TIMER_INACTIVE"
            receivedTimer -> "XBOX_TIMER"
            seconds != null -> "SESSION_STATE"
            else -> "UNAVAILABLE"
        }
        return "SESSION_TIME/$source; remaining_s=${seconds ?: "n/d"}"
    }

    fun takeWarningMinutes(remainingSeconds: Long): Int? {
        val minutes = when (remainingSeconds) {
            in 1L..60L -> 1
            in 61L..300L -> 5
            else -> return null
        }
        if (minutes >= lastWarningMinutes) return null
        lastWarningMinutes = minutes
        return minutes
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
